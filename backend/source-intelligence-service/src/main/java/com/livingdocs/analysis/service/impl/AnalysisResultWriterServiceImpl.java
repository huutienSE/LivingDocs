package com.livingdocs.analysis.service.impl;

import com.livingdocs.analysis.ast.JavaStructuralRepresentation;
import com.livingdocs.analysis.entity.AnalysisFindingEntity;
import com.livingdocs.analysis.entity.AnalysisJobEntity;
import com.livingdocs.analysis.entity.DriftEvidenceEntity;
import com.livingdocs.analysis.entity.DriftIssueEntity;
import com.livingdocs.analysis.entity.TargetAnalysisFenceEntity;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.Coverage;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.DocumentEvidenceRef;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceReason;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceResult;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.FindingEvidence;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.SourceLocator;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.WitnessEvidence;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.WitnessStatus;
import com.livingdocs.analysis.impact.ImpactClassifier.AssessmentContext;
import com.livingdocs.analysis.impact.ImpactClassifier.ClaimVerdict;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactOutcome;
import com.livingdocs.analysis.impact.ImpactClassifier.IssueAdmission;
import com.livingdocs.analysis.impact.ImpactClassifier.ScopeCompleteness;
import com.livingdocs.analysis.impact.ImpactClassifier.ScopeManifestRef;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticStatus;
import com.livingdocs.analysis.model.AnalysisPersistenceModels;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.ApplyResultStatus;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.AuthorizedJobWriteContext;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.BindingConflictException;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.CoverageJsonPayload;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.CurrentnessProof;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.CurrentnessReceiptState;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.DiagnosticJsonPayload;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.FindingEvidenceJsonPayload;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.PersistAnalysisCommand;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.PersistAnalysisOutcome;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.ScopeUnauthorizedException;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.StaleAttemptException;
import com.livingdocs.analysis.port.SourceAuthorityPort.ValidationReceipt;
import com.livingdocs.analysis.repository.AnalysisFindingRepository;
import com.livingdocs.analysis.repository.AnalysisJobRepository;
import com.livingdocs.analysis.repository.DriftEvidenceRepository;
import com.livingdocs.analysis.repository.DriftIssueRepository;
import com.livingdocs.analysis.repository.TargetAnalysisFenceRepository;
import com.livingdocs.analysis.security.S03TenantContext;
import com.livingdocs.analysis.service.AnalysisResultWriterService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AnalysisResultWriterServiceImpl implements AnalysisResultWriterService {

	private static final String OBS_MISMATCH_DETECTED = "MISMATCH_DETECTED";
	private static final String OBS_MISMATCH_ANALYZED = "MISMATCH_ANALYZED";
	private static final String OBS_CONSISTENCY_REVERTED = "CONSISTENCY_REVERTED";
	private static final String UNINITIALIZED_FENCE_MARKER = "UNINITIALIZED";

	private final S03TenantContext tenantContext;
	private final AnalysisJobRepository jobRepository;
	private final TargetAnalysisFenceRepository fenceRepository;
	private final DriftIssueRepository issueRepository;
	private final AnalysisFindingRepository findingRepository;
	private final DriftEvidenceRepository evidenceRepository;
	private final JdbcTemplate jdbcTemplate;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	@Autowired
	public AnalysisResultWriterServiceImpl(
			S03TenantContext tenantContext,
			AnalysisJobRepository jobRepository,
			TargetAnalysisFenceRepository fenceRepository,
			DriftIssueRepository issueRepository,
			AnalysisFindingRepository findingRepository,
			DriftEvidenceRepository evidenceRepository,
			JdbcTemplate jdbcTemplate,
			JsonMapper jsonMapper) {
		this(tenantContext, jobRepository, fenceRepository, issueRepository,
				findingRepository, evidenceRepository, jdbcTemplate, jsonMapper, Clock.systemUTC());
	}

	public AnalysisResultWriterServiceImpl(
			S03TenantContext tenantContext,
			AnalysisJobRepository jobRepository,
			TargetAnalysisFenceRepository fenceRepository,
			DriftIssueRepository issueRepository,
			AnalysisFindingRepository findingRepository,
			DriftEvidenceRepository evidenceRepository,
			JdbcTemplate jdbcTemplate,
			JsonMapper jsonMapper,
			Clock clock) {
		this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
		this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
		this.fenceRepository = Objects.requireNonNull(fenceRepository, "fenceRepository");
		this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
		this.findingRepository = Objects.requireNonNull(findingRepository, "findingRepository");
		this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository");
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
		this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public PersistAnalysisOutcome applyResult(PersistAnalysisCommand command) {
		Objects.requireNonNull(command, "command");
		AuthorizedJobWriteContext jobCtx = command.jobContext();
		AssessmentContext ctx = jobCtx.assessmentContext();
		EvidenceResult result = command.evidenceResult();
		CurrentnessProof proof = command.currentnessProof();

		if (!jobCtx.authorized()
				|| ctx.declaredScopeCompleteness() == ScopeCompleteness.DENIED
				|| proof.state() == CurrentnessReceiptState.DENIED
				|| isScopeDeniedResult(result)) {
			throw new ScopeUnauthorizedException("Analysis scope is not authorized for persistence");
		}

		UUID tenantId = parseRequiredUuid(ctx.tenantId(), "tenantId");
		UUID workspaceId = parseRequiredUuid(ctx.workspaceId(), "workspaceId");
		UUID repoId = parseRequiredUuid(ctx.repository(), "repository");

		if (proof.state() == CurrentnessReceiptState.CURRENT) {
			ValidationReceipt receipt = proof.receipt()
					.orElseThrow(() -> new IllegalArgumentException("CURRENT state requires ValidationReceipt"));
			validateReceiptScope(receipt, tenantId, repoId, ctx);
		}

		// Pre-serialize deterministic JSON payloads outside DB lock.
		String coverageJson = toJson(CoverageJsonPayload.from(result.coverage()));
		String diagnosticsJson = toJson(result.diagnostics().stream().map(DiagnosticJsonPayload::from).toList());
		Map<String, String> findingPayloadJsonByKey = new LinkedHashMap<>();
		for (FindingEvidence fe : result.findings()) {
			findingPayloadJsonByKey.put(fe.findingId(), toJson(FindingEvidenceJsonPayload.from(fe)));
		}

		return tenantContext.writeInTenant(tenantId, () -> executeTransactionalWrite(
				command, tenantId, workspaceId, repoId, coverageJson, diagnosticsJson, findingPayloadJsonByKey));
	}

	private PersistAnalysisOutcome executeTransactionalWrite(
			PersistAnalysisCommand command,
			UUID tenantId,
			UUID workspaceId,
			UUID repoId,
			String coverageJson,
			String diagnosticsJson,
			Map<String, String> findingPayloadJsonByKey) {
		Instant now = clock.instant();
		AuthorizedJobWriteContext jobCtx = command.jobContext();
		AssessmentContext ctx = jobCtx.assessmentContext();
		EvidenceResult result = command.evidenceResult();
		CurrentnessProof proof = command.currentnessProof();

		AnalysisJobEntity job = acquireAndValidateJob(
				jobCtx, ctx, tenantId, workspaceId, repoId, now);

		if (job.isFinalized()) {
			List<AnalysisFindingEntity> existing = findingRepository.findScopedByJobId(tenantId, job.getJobId());
			return new PersistAnalysisOutcome(
					ApplyResultStatus.IDEMPOTENT_REPLAY,
					tenantId,
					job.getJobId(),
					job.getProcessingStatus(),
					job.getOverallOutcome(),
					job.getEvidenceStatus(),
					existing.size(),
					0,
					0,
					0,
					0,
					"APPLIED_CURRENT".equals(job.getResultDisposition())
							&& result.coverage().map(Coverage::completeCoverage).orElse(false));
		}

		// Upsert findings once with versioned JSONB before evaluating fence/issue effects.
		Map<String, AnalysisFindingEntity> persistedFindings = upsertFindings(
				tenantId, job.getJobId(), result.findings(), findingPayloadJsonByKey, now);

		String procStatus = AnalysisPersistenceModels.nullableProcessingName(result.processingStatus());
		String overallOutcome = AnalysisPersistenceModels.nullableOutcomeName(result.overallOutcome());
		String evidenceStatus = result.status().name();

		if (proof.state() == CurrentnessReceiptState.UNKNOWN) {
			job.markDeferredUnknownCurrentness(procStatus, overallOutcome, evidenceStatus, coverageJson, diagnosticsJson, now);
			jobRepository.saveAndFlush(job);
			return new PersistAnalysisOutcome(
					ApplyResultStatus.DEFERRED_UNKNOWN_CURRENTNESS,
					tenantId, job.getJobId(), procStatus, overallOutcome, evidenceStatus,
					persistedFindings.size(), 0, 0, 0, 0, false);
		}

		if (proof.state() == CurrentnessReceiptState.STALE) {
			long observedRev = proof.receipt().map(ValidationReceipt::sourceRevision).orElse(-1L);
			job.markSuppressedStale(ctx.attemptId(), observedRev, procStatus, overallOutcome, evidenceStatus,
					coverageJson, diagnosticsJson, now);
			jobRepository.saveAndFlush(job);
			return new PersistAnalysisOutcome(
					ApplyResultStatus.SUPPRESSED_STALE,
					tenantId, job.getJobId(), procStatus, overallOutcome, evidenceStatus,
					persistedFindings.size(), 0, 0, 0, 0, false);
		}

		ValidationReceipt receipt = proof.receipt().orElseThrow();
		if (!receipt.expiresAt().isAfter(now)) {
			job.markDeferredUnknownCurrentness(procStatus, overallOutcome, evidenceStatus, coverageJson, diagnosticsJson, now);
			jobRepository.saveAndFlush(job);
			return new PersistAnalysisOutcome(
					ApplyResultStatus.DEFERRED_UNKNOWN_CURRENTNESS,
					tenantId, job.getJobId(), procStatus, overallOutcome, evidenceStatus,
					persistedFindings.size(), 0, 0, 0, 0, false);
		}

		// Serialize current-effect writes per target (tenant, repo, targetKind, targetRef) via neutral lock anchor.
		ensureFenceLockAnchor(tenantId, repoId, ctx, now);
		TargetAnalysisFenceEntity fence = fenceRepository
				.findForUpdateByTarget(tenantId, repoId, ctx.targetKind().name(), ctx.targetRef())
				.orElseThrow(() -> new IllegalStateException("Expected locked target fence row"));

		Instant underLockNow = clock.instant();
		if (!receipt.expiresAt().isAfter(underLockNow)) {
			// Receipt expired while waiting on target lock: defer safely without initializing/advancing fence!
			job.markDeferredUnknownCurrentness(procStatus, overallOutcome, evidenceStatus, coverageJson, diagnosticsJson, underLockNow);
			jobRepository.saveAndFlush(job);
			return new PersistAnalysisOutcome(
					ApplyResultStatus.DEFERRED_UNKNOWN_CURRENTNESS,
					tenantId, job.getJobId(), procStatus, overallOutcome, evidenceStatus,
					persistedFindings.size(), 0, 0, 0, 0, false);
		}

		if (!fence.acceptsRevision(receipt.sourceRevision())) {
			job.markSuppressedStale(ctx.attemptId(), receipt.sourceRevision(), procStatus, overallOutcome, evidenceStatus,
					coverageJson, diagnosticsJson, underLockNow);
			jobRepository.saveAndFlush(job);
			return new PersistAnalysisOutcome(
					ApplyResultStatus.SUPPRESSED_STALE,
					tenantId, job.getJobId(), procStatus, overallOutcome, evidenceStatus,
					persistedFindings.size(), 0, 0, 0, 0, false);
		}

		fence.advance(receipt.sourceRevision(), receipt.validatedHeadSha(), receipt.configRevision(),
				job.getJobId(), receipt.checkedAt(), underLockNow);
		fenceRepository.saveAndFlush(fence);

		EffectCounters counters = applyDriftEffects(
				tenantId, workspaceId, repoId, ctx, result.findings(), persistedFindings,
				findingPayloadJsonByKey, command.observedAt(), underLockNow);

		job.markAppliedCurrent(ctx.attemptId(), receipt.sourceRevision(), procStatus, overallOutcome, evidenceStatus,
				coverageJson, diagnosticsJson, underLockNow);
		jobRepository.saveAndFlush(job);

		boolean checkpointEligible = result.coverage().map(Coverage::completeCoverage).orElse(false);
		return new PersistAnalysisOutcome(
				ApplyResultStatus.APPLIED_CURRENT,
				tenantId,
				job.getJobId(),
				procStatus,
				overallOutcome,
				evidenceStatus,
				persistedFindings.size(),
				counters.issuesOpened,
				counters.issuesUpdated,
				counters.revertsRecorded,
				counters.observationsRecorded,
				checkpointEligible);
	}

	private AnalysisJobEntity acquireAndValidateJob(
			AuthorizedJobWriteContext jobCtx,
			AssessmentContext ctx,
			UUID tenantId,
			UUID workspaceId,
			UUID repoId,
			Instant now) {
		Optional<AnalysisJobEntity> existingOpt = jobRepository.findForUpdateByExternalKey(tenantId, ctx.jobId());
		if (existingOpt.isEmpty()) {
			if (!jobCtx.leaseExpiresAt().isAfter(now)) {
				throw new StaleAttemptException("Initial attempt lease is already expired for job " + ctx.jobId());
			}
			// Atomic insert-or-ignore prevents 23505 race when two concurrent threads deliver the same brand-new job.
			jdbcTemplate.update("""
					INSERT INTO intelligence.analysis_jobs (
						job_id, tenant_id, workspace_id, repository_connection_id, external_job_key,
						target_kind, target_ref, comparison_intent, comparison_before_sha,
						authoritative_target_sha, scope_manifest_id, config_revision,
						declared_scope_completeness, whitespace_no_impact_enabled,
						status, current_attempt_id, lease_expires_at, result_disposition,
						coverage_summary, safe_diagnostics, created_at, updated_at, row_version
					) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLAIMED', ?, ?, 'PENDING', '{}'::jsonb, '[]'::jsonb, ?, ?, 0)
					ON CONFLICT (tenant_id, external_job_key) DO NOTHING
					""",
					jobCtx.jobId(),
					tenantId,
					workspaceId,
					repoId,
					ctx.jobId(),
					ctx.targetKind().name(),
					ctx.targetRef(),
					ctx.intent().name(),
					ctx.comparisonBeforeSha().orElse(null),
					ctx.authoritativeTargetSha(),
					ctx.scopeManifest().map(ScopeManifestRef::manifestId).orElse(null),
					ctx.configVersion().orElse(null),
					ctx.declaredScopeCompleteness().name(),
					ctx.whitespaceOnlyNoImpactPolicyEnabled(),
					ctx.attemptId(),
					Timestamp.from(jobCtx.leaseExpiresAt()),
					Timestamp.from(now),
					Timestamp.from(now));

			existingOpt = jobRepository.findForUpdateByExternalKey(tenantId, ctx.jobId());
		}

		AnalysisJobEntity existing = existingOpt
				.orElseThrow(() -> new IllegalStateException("Expected locked analysis job row for " + ctx.jobId()));
		if (!bindingMatches(existing, workspaceId, repoId, ctx)) {
			throw new BindingConflictException("Immutable job binding mismatch for job " + ctx.jobId());
		}

		if (existing.isFinalized()) {
			return existing;
		}

		Instant lockNow = clock.instant();
		if (Objects.equals(existing.getCurrentAttemptId(), ctx.attemptId())) {
			if (!existing.hasActiveLeaseFor(ctx.attemptId(), lockNow)) {
				throw new StaleAttemptException("Lease expired for attempt " + ctx.attemptId());
			}
			return existing;
		}

		// Allow superseding only if current job is awaiting retry or its previous attempt lease has expired.
		boolean canReclaim = "RETRY_PENDING".equals(existing.getStatus())
				|| !existing.getLeaseExpiresAt().isAfter(lockNow);
		if (!canReclaim || !jobCtx.leaseExpiresAt().isAfter(lockNow)) {
			throw new StaleAttemptException("Cannot supersede active attempt " + existing.getCurrentAttemptId()
					+ " with " + ctx.attemptId());
		}
		existing.claimRetryAttempt(ctx.attemptId(), jobCtx.leaseExpiresAt(), lockNow);
		return jobRepository.saveAndFlush(existing);
	}

	private boolean bindingMatches(
			AnalysisJobEntity job,
			UUID workspaceId,
			UUID repoId,
			AssessmentContext ctx) {
		return Objects.equals(job.getWorkspaceId(), workspaceId)
				&& Objects.equals(job.getRepositoryConnectionId(), repoId)
				&& Objects.equals(job.getTargetKind(), ctx.targetKind().name())
				&& Objects.equals(job.getTargetRef(), ctx.targetRef())
				&& Objects.equals(job.getComparisonIntent(), ctx.intent().name())
				&& Objects.equals(job.getComparisonBeforeSha(), ctx.comparisonBeforeSha().orElse(null))
				&& Objects.equals(job.getAuthoritativeTargetSha(), ctx.authoritativeTargetSha())
				&& Objects.equals(job.getScopeManifestId(), ctx.scopeManifest().map(ScopeManifestRef::manifestId).orElse(null))
				&& Objects.equals(job.getConfigRevision(), ctx.configVersion().orElse(null))
				&& Objects.equals(job.getDeclaredScopeCompleteness(), ctx.declaredScopeCompleteness().name())
				&& job.isWhitespaceNoImpactEnabled() == ctx.whitespaceOnlyNoImpactPolicyEnabled();
	}

	private Map<String, AnalysisFindingEntity> upsertFindings(
			UUID tenantId,
			UUID jobId,
			List<FindingEvidence> findings,
			Map<String, String> findingPayloadJsonByKey,
			Instant now) {
		Map<String, AnalysisFindingEntity> map = new LinkedHashMap<>();
		for (FindingEvidence fe : findings) {
			String payloadJson = findingPayloadJsonByKey.get(fe.findingId());
			Optional<AnalysisFindingEntity> existingOpt = findingRepository
					.findScopedByJobAndKey(tenantId, jobId, fe.findingId());
			AnalysisFindingEntity entity;
			if (existingOpt.isPresent()) {
				entity = existingOpt.get();
				entity.refreshFromRetry(
						fe.disposition().name(),
						fe.outcome().name(),
						AnalysisPersistenceModels.nullableEnumName(fe.classification()),
						fe.severityStatus().name(),
						AnalysisPersistenceModels.nullableEnumName(fe.severity()),
						fe.semanticConfidence().orElse(null),
						fe.admission().name(),
						fe.primaryReason().name(),
						fe.admissionReason().name(),
						fe.status().name(),
						AnalysisPersistenceModels.EVIDENCE_SCHEMA_VERSION,
						payloadJson,
						now);
			} else {
				entity = new AnalysisFindingEntity(
						UUID.randomUUID(),
						tenantId,
						jobId,
						fe.findingId(),
						fe.disposition().name(),
						fe.outcome().name(),
						AnalysisPersistenceModels.nullableEnumName(fe.classification()),
						fe.severityStatus().name(),
						AnalysisPersistenceModels.nullableEnumName(fe.severity()),
						fe.semanticConfidence().orElse(null),
						fe.admission().name(),
						fe.primaryReason().name(),
						fe.admissionReason().name(),
						fe.status().name(),
						AnalysisPersistenceModels.EVIDENCE_SCHEMA_VERSION,
						payloadJson,
						now);
			}
			map.put(fe.findingId(), findingRepository.save(entity));
		}
		findingRepository.flush();
		return map;
	}

	private void ensureFenceLockAnchor(
			UUID tenantId,
			UUID repoId,
			AssessmentContext ctx,
			Instant now) {
		jdbcTemplate.update("""
				INSERT INTO intelligence.target_analysis_fences (
					fence_id, tenant_id, repository_connection_id, target_kind, target_ref,
					latest_source_revision, latest_validated_sha, latest_config_revision,
					last_fenced_job_id, last_validated_at, updated_at, row_version
				) VALUES (?, ?, ?, ?, ?, 0, ?, ?, NULL, ?, ?, 0)
				ON CONFLICT (tenant_id, repository_connection_id, target_kind, target_ref) DO NOTHING
				""",
				UUID.randomUUID(),
				tenantId,
				repoId,
				ctx.targetKind().name(),
				ctx.targetRef(),
				UNINITIALIZED_FENCE_MARKER,
				UNINITIALIZED_FENCE_MARKER,
				Timestamp.from(now),
				Timestamp.from(now));
	}

	private EffectCounters applyDriftEffects(
			UUID tenantId,
			UUID workspaceId,
			UUID repoId,
			AssessmentContext ctx,
			List<FindingEvidence> findings,
			Map<String, AnalysisFindingEntity> persistedFindings,
			Map<String, String> findingPayloadJsonByKey,
			Instant observedAt,
			Instant lockNow) {
		EffectCounters counters = new EffectCounters();
		for (FindingEvidence fe : findings) {
			Optional<VerifiedClaimIdentity> identityOpt = extractVerifiedClaimIdentity(
					tenantId, workspaceId, repoId, ctx, fe);
			if (identityOpt.isEmpty()) {
				continue;
			}
			VerifiedClaimIdentity identity = identityOpt.get();
			AnalysisFindingEntity findingEntity = persistedFindings.get(fe.findingId());
			String contentFingerprint = sha256LengthPrefixed(List.of(
					ctx.authoritativeTargetSha(),
					identity.docRef.documentVersion(),
					identity.docRef.documentBoundSha(),
					identity.canonicalSubjectKey,
					findingPayloadJsonByKey.get(fe.findingId())));

			if (fe.admission() == IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE) {
				boolean promotedToAnalyzed = fe.outcome() == ImpactOutcome.ACTIONABLE;
				String obsKind = promotedToAnalyzed ? OBS_MISMATCH_ANALYZED : OBS_MISMATCH_DETECTED;

				Optional<DriftIssueEntity> openOpt = issueRepository.findOpenForUpdate(
						tenantId, repoId, ctx.targetKind().name(), ctx.targetRef(), identity.issueIdentityKey);
				DriftIssueEntity issue;
				if (openOpt.isPresent()) {
					issue = openOpt.get();
					issue.applyMismatchObservation(
							ctx.authoritativeTargetSha(),
							promotedToAnalyzed,
							AnalysisPersistenceModels.nullableEnumName(fe.classification()),
							fe.severityStatus().name(),
							AnalysisPersistenceModels.nullableEnumName(fe.severity()),
							observedAt);
					counters.issuesUpdated++;
				} else {
					issue = new DriftIssueEntity(
							UUID.randomUUID(),
							tenantId,
							workspaceId,
							repoId,
							ctx.targetKind().name(),
							ctx.targetRef(),
							identity.documentUuid,
							identity.docRef.documentVersion(),
							identity.docRef.bindingId(),
							identity.claimId,
							identity.canonicalSubjectKey,
							identity.issueIdentityKey,
							AnalysisPersistenceModels.nullableEnumName(fe.classification()),
							fe.severityStatus().name(),
							AnalysisPersistenceModels.nullableEnumName(fe.severity()),
							promotedToAnalyzed ? "ANALYZED" : "DETECTED",
							ctx.authoritativeTargetSha(),
							observedAt);
					counters.issuesOpened++;
				}
				issue = issueRepository.saveAndFlush(issue);
				findingEntity.linkToIssue(issue.getDriftIssueId(), lockNow);
				findingRepository.saveAndFlush(findingEntity);

				if (insertObservationIfAbsent(
						tenantId, issue.getDriftIssueId(), findingEntity.getFindingId(),
						obsKind, ctx.authoritativeTargetSha(), identity.docRef,
						contentFingerprint, observedAt, lockNow)) {
					counters.observationsRecorded++;
				}
			} else if (fe.outcome() == ImpactOutcome.NO_IMPACT
					&& (isConsistentClaimVerdict(identity.docRef) || identity.verifiedSemanticConsistent)) {
				Optional<DriftIssueEntity> openOpt = issueRepository.findOpenForUpdate(
						tenantId, repoId, ctx.targetKind().name(), ctx.targetRef(), identity.issueIdentityKey);
				if (openOpt.isPresent()) {
					DriftIssueEntity openIssue = openOpt.get();
					openIssue.recordConsistentRevert(ctx.authoritativeTargetSha(), observedAt);
					issueRepository.saveAndFlush(openIssue);
					findingEntity.linkToIssue(openIssue.getDriftIssueId(), lockNow);
					findingRepository.saveAndFlush(findingEntity);
					counters.revertsRecorded++;

					if (insertObservationIfAbsent(
							tenantId, openIssue.getDriftIssueId(), findingEntity.getFindingId(),
							OBS_CONSISTENCY_REVERTED, ctx.authoritativeTargetSha(), identity.docRef,
							contentFingerprint, observedAt, lockNow)) {
						counters.observationsRecorded++;
					}
				}
			}
		}
		return counters;
	}

	private boolean insertObservationIfAbsent(
			UUID tenantId,
			UUID issueId,
			UUID findingId,
			String observationKind,
			String targetSha,
			DocumentEvidenceRef docRef,
			String contentFingerprint,
			Instant observedAt,
			Instant now) {
		if (evidenceRepository.findExistingObservation(tenantId, issueId, findingId, observationKind).isPresent()) {
			return false;
		}
		evidenceRepository.saveAndFlush(new DriftEvidenceEntity(
				UUID.randomUUID(),
				tenantId,
				issueId,
				findingId,
				observationKind,
				targetSha,
				docRef.documentVersion(),
				docRef.documentBoundSha(),
				contentFingerprint,
				observedAt,
				now));
		return true;
	}

	private Optional<VerifiedClaimIdentity> extractVerifiedClaimIdentity(
			UUID tenantId,
			UUID workspaceId,
			UUID repoId,
			AssessmentContext ctx,
			FindingEvidence fe) {
		DocumentEvidenceRef uniqueDocRef = null;
		boolean verifiedSemanticConsistent = false;
		var symbolSubjects = new LinkedHashSet<String>();
		var pathSubjects = new LinkedHashSet<String>();

		for (WitnessEvidence w : fe.witnesses()) {
			if (w.status() != WitnessStatus.VERIFIED) {
				continue;
			}
			if (w.semanticStatus().equals(Optional.of(SemanticStatus.BEHAVIOR_CONSISTENT))) {
				verifiedSemanticConsistent = true;
			}
			for (SourceLocator loc : w.sourceLocators()) {
				loc.symbolKey().ifPresent(sk -> symbolSubjects.add(JavaStructuralRepresentation.canonicalSymbolKey(sk)));
				pathSubjects.add(loc.path());
			}
			if (w.documentRef().isPresent()) {
				DocumentEvidenceRef dref = w.documentRef().get();
				if (dref.claimId().isEmpty()) {
					continue;
				}
				if (uniqueDocRef == null) {
					uniqueDocRef = dref;
				} else if (!sameClaimTuple(uniqueDocRef, dref)) {
					return Optional.empty();
				}
			}
		}

		if (uniqueDocRef == null) {
			return Optional.empty();
		}
		UUID documentUuid;
		try {
			documentUuid = UUID.fromString(uniqueDocRef.documentId());
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}

		String subjectKey;
		if (!symbolSubjects.isEmpty()) {
			if (symbolSubjects.size() > 1) {
				return Optional.empty();
			}
			subjectKey = symbolSubjects.iterator().next();
		} else if (pathSubjects.size() == 1) {
			subjectKey = "FILE:" + pathSubjects.iterator().next();
		} else if (!pathSubjects.isEmpty()) {
			List<String> sorted = new ArrayList<>(pathSubjects);
			sorted.sort(String::compareTo);
			subjectKey = "FILE_SET:" + String.join("|", sorted);
		} else {
			return Optional.empty();
		}

		String claimId = uniqueDocRef.claimId().orElseThrow();
		String identityKey = sha256LengthPrefixed(List.of(
				tenantId.toString(),
				workspaceId.toString(),
				repoId.toString(),
				ctx.targetKind().name(),
				ctx.targetRef(),
				documentUuid.toString(),
				uniqueDocRef.documentVersion(),
				uniqueDocRef.bindingId(),
				claimId));
		return Optional.of(new VerifiedClaimIdentity(
				documentUuid, uniqueDocRef, claimId, subjectKey, identityKey, verifiedSemanticConsistent));
	}

	private static boolean sameClaimTuple(DocumentEvidenceRef a, DocumentEvidenceRef b) {
		return Objects.equals(a.documentId(), b.documentId())
				&& Objects.equals(a.documentVersion(), b.documentVersion())
				&& Objects.equals(a.bindingId(), b.bindingId())
				&& Objects.equals(a.claimId(), b.claimId());
	}

	private static boolean isConsistentClaimVerdict(DocumentEvidenceRef docRef) {
		if (docRef.verdict().isEmpty()) {
			return false;
		}
		ClaimVerdict verdict = docRef.verdict().get();
		return verdict == ClaimVerdict.DECLARATION_CONSISTENT
				|| verdict == ClaimVerdict.BEHAVIOR_CONSISTENT_PROVEN
				|| verdict == ClaimVerdict.REFERENCED_ENTITY_REQUIRED;
	}

	private static boolean isScopeDeniedResult(EvidenceResult result) {
		return result.status() == com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceStatus.REJECTED
				&& result.diagnostics().stream()
						.anyMatch(d -> d.evidenceReason().equals(Optional.of(EvidenceReason.SCOPE_DENIED)));
	}

	private static void validateReceiptScope(
			ValidationReceipt receipt,
			UUID tenantId,
			UUID repoId,
			AssessmentContext ctx) {
		if (!Objects.equals(receipt.tenantId(), tenantId)
				|| !Objects.equals(receipt.repositoryConnectionId(), repoId)
				|| receipt.targetKind() != ctx.targetKind()
				|| !Objects.equals(receipt.targetRef(), ctx.targetRef())
				|| !Objects.equals(receipt.validatedHeadSha(), ctx.authoritativeTargetSha())
				|| ctx.configVersion().map(v -> !Objects.equals(receipt.configRevision(), v)).orElse(false)) {
			throw new IllegalArgumentException("ValidationReceipt scope mismatch against AssessmentContext");
		}
	}

	private static UUID parseRequiredUuid(String raw, String field) {
		Objects.requireNonNull(raw, field);
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Expected UUID for " + field, ex);
		}
	}

	private String toJson(Object value) {
		return jsonMapper.writeValueAsString(value);
	}

	private static String sha256LengthPrefixed(List<String> parts) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			writeInt32(buffer, parts.size());
			for (String part : parts) {
				byte[] bytes = Objects.requireNonNull(part, "part").getBytes(StandardCharsets.UTF_8);
				writeInt32(buffer, bytes.length);
				buffer.writeBytes(bytes);
			}
			return HexFormat.of().formatHex(digest.digest(buffer.toByteArray()));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private static void writeInt32(ByteArrayOutputStream buffer, int value) {
		buffer.write((value >>> 24) & 0xFF);
		buffer.write((value >>> 16) & 0xFF);
		buffer.write((value >>> 8) & 0xFF);
		buffer.write(value & 0xFF);
	}

	private record VerifiedClaimIdentity(
			UUID documentUuid,
			DocumentEvidenceRef docRef,
			String claimId,
			String canonicalSubjectKey,
			String issueIdentityKey,
			boolean verifiedSemanticConsistent) {
	}

	private static final class EffectCounters {
		int issuesOpened;
		int issuesUpdated;
		int revertsRecorded;
		int observationsRecorded;
	}
}
