package com.livingdocs.analysis.service.impl;

import com.livingdocs.analysis.ast.JavaParseResult.SourceRange;
import com.livingdocs.analysis.diff.StructuralDiff;
import com.livingdocs.analysis.diff.StructuralDiff.SideRole;
import com.livingdocs.analysis.diff.StructuralDiff.StructuralDiffResult;
import com.livingdocs.analysis.diff.StructuralDiffInput;
import com.livingdocs.analysis.diff.StructuralDiffInput.FileAssociation;
import com.livingdocs.analysis.diff.StructuralDiffInput.Intent;
import com.livingdocs.analysis.diff.StructuralDiffInput.Side;
import com.livingdocs.analysis.entity.AnalysisJobEntity;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.DocumentEvidenceRef;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceInput;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceResult;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceStatus;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.FindingEvidence;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.SnapshotProof;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.SourceLocator;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.WitnessEvidence;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.WitnessStatus;
import com.livingdocs.analysis.impact.ImpactClassifier;
import com.livingdocs.analysis.impact.ImpactClassifier.AssessmentContext;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentLookupKey;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentLookupResponse;
import com.livingdocs.analysis.impact.ImpactClassifier.GroundedDocumentClaimRef;
import com.livingdocs.analysis.impact.ImpactClassifier.GroundedSourceFacetRef;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactClassificationInput;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactOutcome;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactReport;
import com.livingdocs.analysis.impact.ImpactClassifier.IssueAdmission;
import com.livingdocs.analysis.impact.ImpactClassifier.LookupCompleteness;
import com.livingdocs.analysis.impact.ImpactClassifier.ReasonCode;
import com.livingdocs.analysis.impact.ImpactClassifier.ScopeCompleteness;
import com.livingdocs.analysis.impact.ImpactClassifier.ScopeManifestRef;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticAssessmentInput;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticStatus;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.ApplyResultStatus;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.AuthorizedJobWriteContext;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.BindingConflictException;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.CurrentnessProof;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.CurrentnessReceiptState;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.PersistAnalysisCommand;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.PersistAnalysisOutcome;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.StaleAttemptException;
import com.livingdocs.analysis.port.DocumentMappingPort;
import com.livingdocs.analysis.port.DocumentMappingPort.MappingLookupRequest;
import com.livingdocs.analysis.port.DocumentMappingPort.MappingLookupResult;
import com.livingdocs.analysis.port.DocumentMappingPort.MappingLookupStatus;
import com.livingdocs.analysis.port.ScopeAuthorizationPort;
import com.livingdocs.analysis.port.ScopeAuthorizationPort.AuthorizationDecision;
import com.livingdocs.analysis.port.ScopeAuthorizationPort.DecisionStatus;
import com.livingdocs.analysis.port.ScopeAuthorizationPort.WorkerScopeRequest;
import com.livingdocs.analysis.port.SemanticAssessmentPort;
import com.livingdocs.analysis.port.SemanticAssessmentPort.ExecutionStatus;
import com.livingdocs.analysis.port.SemanticAssessmentPort.SemanticCandidate;
import com.livingdocs.analysis.port.SemanticAssessmentPort.SemanticExecutionRequest;
import com.livingdocs.analysis.port.SemanticAssessmentPort.SemanticExecutionResult;
import com.livingdocs.analysis.port.SemanticAssessmentPort.SemanticReconcileRequest;
import com.livingdocs.analysis.port.SourceAuthorityPort;
import com.livingdocs.analysis.port.SourceAuthorityPort.CurrentnessCheckRequest;
import com.livingdocs.analysis.port.SourceAuthorityPort.CurrentnessCheckResult;
import com.livingdocs.analysis.port.SourceAuthorityPort.CurrentnessVerdict;
import com.livingdocs.analysis.port.SourceAuthorityPort.ResolutionStatus;
import com.livingdocs.analysis.port.SourceAuthorityPort.ResolvedSnapshotsResult;
import com.livingdocs.analysis.port.SourceAuthorityPort.SnapshotPair;
import com.livingdocs.analysis.port.SourceAuthorityPort.SnapshotResolutionRequest;
import com.livingdocs.analysis.port.SourceAuthorityPort.ValidationReceipt;
import com.livingdocs.analysis.repository.AnalysisFindingRepository;
import com.livingdocs.analysis.repository.AnalysisJobRepository;
import com.livingdocs.analysis.security.S03TenantContext;
import com.livingdocs.analysis.service.AnalysisResultWriterService;
import com.livingdocs.analysis.service.DriftAnalysisOrchestratorService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DriftAnalysisOrchestratorServiceImpl implements DriftAnalysisOrchestratorService {

	private static final Set<String> WORKER_CAPABILITIES = Set.of("s03:analysis:execute");
	private static final String ADAPTER_NOT_CONFIGURED = "ADAPTER_NOT_CONFIGURED";

	private final ScopeAuthorizationPort scopeAuthorizationPort;
	private final SourceAuthorityPort sourceAuthorityPort;
	private final DocumentMappingPort documentMappingPort;
	private final SemanticAssessmentPort semanticAssessmentPort;
	private final AnalysisResultWriterService writerService;
	private final S03TenantContext tenantContext;
	private final AnalysisJobRepository jobRepository;
	private final AnalysisFindingRepository findingRepository;
	private final JsonMapper jsonMapper;
	private final Clock clock;
	private final StructuralDiff structuralDiff;
	private final ImpactClassifier classifier;
	private final SourceEvidenceMapper evidenceMapper;

	@Autowired
	public DriftAnalysisOrchestratorServiceImpl(
			ObjectProvider<ScopeAuthorizationPort> scopeAuthorizationPortProvider,
			ObjectProvider<SourceAuthorityPort> sourceAuthorityPortProvider,
			ObjectProvider<DocumentMappingPort> documentMappingPortProvider,
			ObjectProvider<SemanticAssessmentPort> semanticAssessmentPortProvider,
			AnalysisResultWriterService writerService,
			S03TenantContext tenantContext,
			AnalysisJobRepository jobRepository,
			AnalysisFindingRepository findingRepository,
			JsonMapper jsonMapper) {
		this(
				scopeAuthorizationPortProvider.getIfAvailable(DriftAnalysisOrchestratorServiceImpl::unconfiguredScopeAuthPort),
				sourceAuthorityPortProvider.getIfAvailable(DriftAnalysisOrchestratorServiceImpl::unconfiguredSourceAuthorityPort),
				documentMappingPortProvider.getIfAvailable(DriftAnalysisOrchestratorServiceImpl::unconfiguredDocumentMappingPort),
				semanticAssessmentPortProvider.getIfAvailable(DriftAnalysisOrchestratorServiceImpl::unconfiguredSemanticPort),
				writerService,
				tenantContext,
				jobRepository,
				findingRepository,
				jsonMapper,
				Clock.systemUTC());
	}

	public DriftAnalysisOrchestratorServiceImpl(
			ScopeAuthorizationPort scopeAuthorizationPort,
			SourceAuthorityPort sourceAuthorityPort,
			DocumentMappingPort documentMappingPort,
			SemanticAssessmentPort semanticAssessmentPort,
			AnalysisResultWriterService writerService,
			S03TenantContext tenantContext,
			AnalysisJobRepository jobRepository,
			AnalysisFindingRepository findingRepository,
			JsonMapper jsonMapper,
			Clock clock) {
		this.scopeAuthorizationPort = Objects.requireNonNull(scopeAuthorizationPort, "scopeAuthorizationPort");
		this.sourceAuthorityPort = Objects.requireNonNull(sourceAuthorityPort, "sourceAuthorityPort");
		this.documentMappingPort = Objects.requireNonNull(documentMappingPort, "documentMappingPort");
		this.semanticAssessmentPort = Objects.requireNonNull(semanticAssessmentPort, "semanticAssessmentPort");
		this.writerService = Objects.requireNonNull(writerService, "writerService");
		this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
		this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
		this.findingRepository = Objects.requireNonNull(findingRepository, "findingRepository");
		this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.structuralDiff = new StructuralDiff();
		this.classifier = new ImpactClassifier();
		this.evidenceMapper = new SourceEvidenceMapper();
	}

	@Override
	public OrchestrationResult executeAnalysis(AnalyzeTargetCommand command) {
		Objects.requireNonNull(command, "command");
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
					"DriftAnalysisOrchestratorService must not execute inside an active DB transaction");
		}

		// 1. Worker Scope Gate (fail closed outside DB transaction).
		AuthorizationDecision authDecision = scopeAuthorizationPort.verifyWorkerScope(new WorkerScopeRequest(
				command.servicePrincipal(),
				command.tenantId(),
				command.workspaceId(),
				command.repositoryConnectionId(),
				command.externalJobKey(),
				command.attemptId(),
				WORKER_CAPABILITIES));
		if (authDecision.status() == DecisionStatus.DENIED) {
			return deniedResult(authDecision.denyReasonCode().or(() -> Optional.of("WORKER_SCOPE_DENIED")));
		}
		if (authDecision.status() == DecisionStatus.UNAVAILABLE) {
			return unavailableResult(authDecision.denyReasonCode().or(() -> Optional.of("WORKER_SCOPE_UNAVAILABLE")));
		}

		// 2. Fast-path crash-recovery read for already-finalized job.
		Optional<ExistingJobSnapshot> existingSnapOpt = tenantContext.readInTenant(command.tenantId(), () -> {
			Optional<AnalysisJobEntity> opt = jobRepository.findScopedByExternalKey(
					command.tenantId(), command.externalJobKey());
			if (opt.isEmpty()) {
				return Optional.empty();
			}
			AnalysisJobEntity existing = opt.get();
			if (!coreBindingMatches(existing, command)) {
				throw new BindingConflictException(
						"Immutable job binding mismatch for job " + command.externalJobKey());
			}
			int findingCount = existing.isFinalized()
					? findingRepository.findScopedByJobId(command.tenantId(), existing.getJobId()).size()
					: 0;
			return Optional.of(new ExistingJobSnapshot(existing, findingCount));
		});
		if (existingSnapOpt.isPresent() && existingSnapOpt.get().entity().isFinalized()) {
			PersistAnalysisOutcome replayOutcome = buildFinalizedReplayOutcome(
					existingSnapOpt.get().entity(), existingSnapOpt.get().findingCount());
			return new OrchestrationResult(
					OrchestrationStatus.IDEMPOTENT_REPLAY,
					computeCheckpointDecision(command, replayOutcome),
					false,
					Optional.of(replayOutcome),
					Optional.empty(),
					Optional.empty());
		}

		// 3. Pre-flight Currentness Check (#1) before fetching snapshots or calling S04/S05.
		CurrentnessCheckResult preCheck = sourceAuthorityPort.validateCurrentness(new CurrentnessCheckRequest(
				command.tenantId(),
				command.workspaceId(),
				command.repositoryConnectionId(),
				command.targetKind(),
				command.targetRef(),
				command.authoritativeTargetSha(),
				command.configRevision()));
		if (preCheck.verdict() == CurrentnessVerdict.DENIED) {
			return deniedResult(preCheck.reasonCode().or(() -> Optional.of("PREFLIGHT_SCOPE_DENIED")));
		}
		if (preCheck.verdict() == CurrentnessVerdict.UNKNOWN) {
			return new OrchestrationResult(
					OrchestrationStatus.DEFERRED_UNKNOWN_CURRENTNESS,
					CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED,
					true,
					Optional.empty(),
					Optional.empty(),
					preCheck.reasonCode());
		}
		if (preCheck.verdict() == CurrentnessVerdict.STALE) {
			if (existingSnapOpt.isEmpty()) {
				return new OrchestrationResult(
						OrchestrationStatus.SUPPRESSED_STALE,
						CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED,
						false,
						Optional.empty(),
						Optional.empty(),
						preCheck.reasonCode().or(() -> Optional.of("PREFLIGHT_STALE")));
			}
			return finalizeExistingJobAsStale(command, preCheck);
		}
		if (!isValidCurrentReceipt(command, preCheck)) {
			return contractViolationResult("INVALID_PREFLIGHT_VALIDATION_RECEIPT");
		}

		// 4. Pinned Snapshot & Manifest Resolution (S02).
		ResolvedSnapshotsResult resolved = sourceAuthorityPort.resolveSnapshots(new SnapshotResolutionRequest(
				command.tenantId(),
				command.workspaceId(),
				command.repositoryConnectionId(),
				command.targetKind(),
				command.targetRef(),
				command.intent(),
				command.comparisonBeforeSha(),
				command.authoritativeTargetSha(),
				command.configRevision()));
		if (resolved.status() == ResolutionStatus.DENIED) {
			return deniedResult(resolved.unavailableOrDenyReason().or(() -> Optional.of("SNAPSHOT_SCOPE_DENIED")));
		}
		if (resolved.status() == ResolutionStatus.UNAVAILABLE) {
			return unavailableResult(resolved.unavailableOrDenyReason().or(() -> Optional.of("S02_SNAPSHOT_UNAVAILABLE")));
		}
		if (!validatePinnedSnapshots(command, resolved)) {
			return contractViolationResult("UNPINNED_OR_WRONG_SNAPSHOT_SHA");
		}

		String repoStr = command.repositoryConnectionId().toString();
		ScopeCompleteness declaredScopeCompleteness = deriveScopeCompleteness(resolved);

		// 5. M2 Structural Diff on verified pinned snapshots.
		List<StructuralDiffResult> diffResults = resolved.snapshotPairs().stream()
				.map(pair -> structuralDiff.compare(new StructuralDiffInput(
						repoStr,
						command.intent(),
						pair.beforeSide(),
						pair.afterOrTargetSide(),
						pair.explicitAssociation().map(a -> new FileAssociation(a.beforePath(), a.afterPath())))))
				.toList();

		// 6. S04 Version-Scoped Reverse Lookup.
		AssessmentContext ctx = new AssessmentContext(
				command.tenantId().toString(),
				command.workspaceId().toString(),
				repoStr,
				command.targetKind(),
				command.targetRef(),
				command.externalJobKey(),
				command.attemptId(),
				Optional.of(command.configRevision()),
				command.authoritativeTargetSha(),
				command.comparisonBeforeSha(),
				command.intent(),
				declaredScopeCompleteness,
				command.whitespaceOnlyNoImpactPolicyEnabled(),
				resolved.scopeManifest());

		List<DocumentLookupKey> lookupKeys = ImpactClassifier.buildLookupKeys(ctx, diffResults);
		List<DocumentLookupResponse> lookupResponses;
		if (lookupKeys.isEmpty()) {
			lookupResponses = List.of();
		} else {
			MappingLookupResult mappingResult = documentMappingPort.lookupBySourceKeys(
					new MappingLookupRequest(ctx, lookupKeys));
			if (mappingResult.status() == MappingLookupStatus.DENIED) {
				return deniedResult(mappingResult.reasonCode().or(() -> Optional.of("MAPPING_SCOPE_DENIED")));
			}
			lookupResponses = assembleLookupResponses(lookupKeys, mappingResult);
		}

		// 7. Pass 1 M3 + M4 & Optional Two-Pass S05 Semantic Grounding.
		List<ScopeManifestRef> manifests = resolved.scopeManifest().map(List::of).orElseGet(List::of);
		ImpactReport pass1Report = classifier.classify(new ImpactClassificationInput(
				ctx, diffResults, lookupResponses, List.of(),
				resolved.controlledExports(), command.callerExclusions()));
		EvidenceResult finalEvidence = evidenceMapper.map(new EvidenceInput(
				Optional.of(ctx), pass1Report, diffResults, resolved.snapshotProofs(), lookupResponses,
				List.of(), manifests, command.callerExclusions(), resolved.controlledExports()));

		Optional<String> pendingSemanticExecutionId = Optional.empty();
		List<SemanticCandidate> candidates = extractSemanticCandidates(finalEvidence);
		if (!candidates.isEmpty() && command.semanticEvaluationEnabled()) {
			SemanticExecutionResult semResult = invokeOrReconcileSemantic(command, ctx, candidates);
			if (semResult.status() == ExecutionStatus.DENIED) {
				return deniedResult(semResult.reasonCode().or(() -> Optional.of("SEMANTIC_SCOPE_DENIED")));
			}
			pendingSemanticExecutionId = semResult.executionId();

			List<SemanticAssessmentInput> pass2Semantics = buildPass2Semantics(ctx, candidates, semResult);
			if (!pass2Semantics.isEmpty()) {
				ImpactReport pass2Report = classifier.classify(new ImpactClassificationInput(
						ctx, diffResults, lookupResponses, pass2Semantics,
						resolved.controlledExports(), command.callerExclusions()));
				finalEvidence = evidenceMapper.map(new EvidenceInput(
						Optional.of(ctx), pass2Report, diffResults, resolved.snapshotProofs(), lookupResponses,
						pass2Semantics, manifests, command.callerExclusions(), resolved.controlledExports()));
			}
		}

		// 8. Post-Evaluation Currentness Recheck (#2) before committing transactional write.
		CurrentnessCheckResult postCheck = sourceAuthorityPort.validateCurrentness(new CurrentnessCheckRequest(
				command.tenantId(),
				command.workspaceId(),
				command.repositoryConnectionId(),
				command.targetKind(),
				command.targetRef(),
				command.authoritativeTargetSha(),
				command.configRevision()));
		if (postCheck.verdict() == CurrentnessVerdict.DENIED) {
			return deniedResult(postCheck.reasonCode().or(() -> Optional.of("POSTCHECK_SCOPE_DENIED")));
		}
		if (postCheck.verdict() == CurrentnessVerdict.CURRENT && !isValidCurrentReceipt(command, postCheck)) {
			return contractViolationResult("INVALID_POSTCHECK_VALIDATION_RECEIPT");
		}

		CurrentnessProof proof = switch (postCheck.verdict()) {
			case CURRENT -> new CurrentnessProof(
					CurrentnessReceiptState.CURRENT, postCheck.receipt(), postCheck.reasonCode());
			case STALE -> new CurrentnessProof(
					CurrentnessReceiptState.STALE, postCheck.receipt(), postCheck.reasonCode());
			case UNKNOWN -> new CurrentnessProof(
					CurrentnessReceiptState.UNKNOWN, Optional.empty(), postCheck.reasonCode());
			case DENIED -> throw new IllegalStateException("Unexpected DENIED verdict after guard");
		};

		PersistAnalysisOutcome persistOutcome = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(command.jobId(), command.leaseExpiresAt(), ctx, true),
				proof,
				finalEvidence,
				clock.instant()));

		// 9. Map Writer Outcome & Checkpoint Decision.
		OrchestrationStatus status = switch (persistOutcome.status()) {
			case APPLIED_CURRENT -> OrchestrationStatus.APPLIED_CURRENT;
			case IDEMPOTENT_REPLAY -> OrchestrationStatus.IDEMPOTENT_REPLAY;
			case SUPPRESSED_STALE -> OrchestrationStatus.SUPPRESSED_STALE;
			case DEFERRED_UNKNOWN_CURRENTNESS -> OrchestrationStatus.DEFERRED_UNKNOWN_CURRENTNESS;
		};
		boolean retryable = persistOutcome.status() == ApplyResultStatus.DEFERRED_UNKNOWN_CURRENTNESS;
		return new OrchestrationResult(
				status,
				computeCheckpointDecision(command, persistOutcome),
				retryable,
				Optional.of(persistOutcome),
				pendingSemanticExecutionId,
				postCheck.reasonCode());
	}

	private OrchestrationResult finalizeExistingJobAsStale(
			AnalyzeTargetCommand command,
			CurrentnessCheckResult preCheck) {
		return tenantContext.writeInTenant(command.tenantId(), () -> {
			AnalysisJobEntity job = jobRepository.findForUpdateByExternalKey(
					command.tenantId(), command.externalJobKey())
					.orElseThrow(() -> new IllegalStateException(
							"Expected locked analysis job row for " + command.externalJobKey()));
			if (!coreBindingMatches(job, command)) {
				throw new BindingConflictException(
						"Immutable job binding mismatch for job " + command.externalJobKey());
			}
			int findingCount = findingRepository.findScopedByJobId(command.tenantId(), job.getJobId()).size();
			if (job.isFinalized()) {
				PersistAnalysisOutcome replayOutcome = buildFinalizedReplayOutcome(job, findingCount);
				return new OrchestrationResult(
						OrchestrationStatus.IDEMPOTENT_REPLAY,
						computeCheckpointDecision(command, replayOutcome),
						false,
						Optional.of(replayOutcome),
						Optional.empty(),
						Optional.empty());
			}

			Instant lockNow = clock.instant();
			if (Objects.equals(job.getCurrentAttemptId(), command.attemptId())) {
				if (!job.hasActiveLeaseFor(command.attemptId(), lockNow)) {
					throw new StaleAttemptException("Lease expired for attempt " + command.attemptId());
				}
			} else {
				boolean canReclaim = "RETRY_PENDING".equals(job.getStatus())
						|| !job.getLeaseExpiresAt().isAfter(lockNow);
				if (!canReclaim || !command.leaseExpiresAt().isAfter(lockNow)) {
					throw new StaleAttemptException("Cannot supersede active attempt "
							+ job.getCurrentAttemptId() + " with " + command.attemptId());
				}
				job.claimRetryAttempt(command.attemptId(), command.leaseExpiresAt(), lockNow);
			}

			long observedRev = preCheck.receipt().map(ValidationReceipt::sourceRevision).orElse(-1L);
			String evStatus = job.getEvidenceStatus() != null ? job.getEvidenceStatus() : "REJECTED";
			job.markSuppressedStale(
					command.attemptId(),
					observedRev,
					job.getProcessingStatus(),
					job.getOverallOutcome(),
					evStatus,
					job.getCoverageSummary(),
					job.getSafeDiagnostics(),
					lockNow);
			jobRepository.saveAndFlush(job);

			PersistAnalysisOutcome staleOutcome = new PersistAnalysisOutcome(
					ApplyResultStatus.SUPPRESSED_STALE,
					command.tenantId(),
					job.getJobId(),
					job.getProcessingStatus(),
					job.getOverallOutcome(),
					evStatus,
					findingCount,
					0,
					0,
					0,
					0,
					false);
			return new OrchestrationResult(
					OrchestrationStatus.SUPPRESSED_STALE,
					CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED,
					false,
					Optional.of(staleOutcome),
					Optional.empty(),
					preCheck.reasonCode());
		});
	}

	private PersistAnalysisOutcome buildFinalizedReplayOutcome(AnalysisJobEntity existing, int findingCount) {
		boolean completeCoverage = parseCompleteCoverage(existing.getCoverageSummary());
		boolean checkpointEligible = "APPLIED_CURRENT".equals(existing.getResultDisposition()) && completeCoverage;
		return new PersistAnalysisOutcome(
				ApplyResultStatus.IDEMPOTENT_REPLAY,
				existing.getTenantId(),
				existing.getJobId(),
				existing.getProcessingStatus(),
				existing.getOverallOutcome(),
				existing.getEvidenceStatus(),
				findingCount,
				0,
				0,
				0,
				0,
				checkpointEligible);
	}

	private boolean parseCompleteCoverage(String coverageJson) {
		if (coverageJson == null || coverageJson.isBlank() || "{}".equals(coverageJson.trim())) {
			return false;
		}
		try {
			JsonNode root = jsonMapper.readTree(coverageJson);
			return root.path("completeCoverage").asBoolean(false);
		} catch (RuntimeException ex) {
			return false;
		}
	}

	private static boolean coreBindingMatches(AnalysisJobEntity job, AnalyzeTargetCommand command) {
		return Objects.equals(job.getWorkspaceId(), command.workspaceId())
				&& Objects.equals(job.getRepositoryConnectionId(), command.repositoryConnectionId())
				&& Objects.equals(job.getTargetKind(), command.targetKind().name())
				&& Objects.equals(job.getTargetRef(), command.targetRef())
				&& Objects.equals(job.getComparisonIntent(), command.intent().name())
				&& Objects.equals(job.getComparisonBeforeSha(), command.comparisonBeforeSha().orElse(null))
				&& Objects.equals(job.getAuthoritativeTargetSha(), command.authoritativeTargetSha())
				&& Objects.equals(job.getConfigRevision(), command.configRevision())
				&& job.isWhitespaceNoImpactEnabled() == command.whitespaceOnlyNoImpactPolicyEnabled();
	}

	private static boolean isValidCurrentReceipt(AnalyzeTargetCommand command, CurrentnessCheckResult check) {
		if (check.receipt().isEmpty()) {
			return false;
		}
		ValidationReceipt r = check.receipt().get();
		return Objects.equals(r.tenantId(), command.tenantId())
				&& Objects.equals(r.repositoryConnectionId(), command.repositoryConnectionId())
				&& r.targetKind() == command.targetKind()
				&& Objects.equals(r.targetRef(), command.targetRef())
				&& Objects.equals(r.validatedHeadSha(), command.authoritativeTargetSha())
				&& Objects.equals(r.configRevision(), command.configRevision());
	}

	private static boolean validatePinnedSnapshots(AnalyzeTargetCommand command, ResolvedSnapshotsResult resolved) {
		String repoStr = command.repositoryConnectionId().toString();
		if (resolved.scopeManifest().isPresent()) {
			ScopeManifestRef m = resolved.scopeManifest().get();
			if (!repoStr.equals(m.repository())
					|| m.targetKind() != command.targetKind()
					|| !command.targetRef().equals(m.targetRef())
					|| m.intent() != command.intent()
					|| !command.comparisonBeforeSha().equals(m.comparisonBeforeSha())
					|| !command.authoritativeTargetSha().equals(m.authoritativeTargetSha())) {
				return false;
			}
		}

		for (SnapshotPair pair : resolved.snapshotPairs()) {
			Side after = pair.afterOrTargetSide();
			if (!repoStr.equals(after.snapshot().repository())
					|| !command.authoritativeTargetSha().equals(after.snapshot().sha())) {
				return false;
			}
			if (command.intent() == Intent.INITIAL_ASSESSMENT) {
				if (pair.beforeSide().isPresent() || pair.explicitAssociation().isPresent()) {
					return false;
				}
			} else {
				if (pair.beforeSide().isEmpty()) {
					return false;
				}
				Side before = pair.beforeSide().get();
				String expectedBeforeSha = command.comparisonBeforeSha().orElseThrow();
				if (!repoStr.equals(before.snapshot().repository())
						|| !expectedBeforeSha.equals(before.snapshot().sha())) {
					return false;
				}
				if (pair.explicitAssociation().isPresent()) {
					var assoc = pair.explicitAssociation().get();
					if (!repoStr.equals(assoc.beforeSnapshot().repository())
							|| !expectedBeforeSha.equals(assoc.beforeSnapshot().sha())
							|| !repoStr.equals(assoc.afterSnapshot().repository())
							|| !command.authoritativeTargetSha().equals(assoc.afterSnapshot().sha())) {
						return false;
					}
				}
			}
		}

		for (SnapshotProof proof : resolved.snapshotProofs()) {
			String proofRepo = proof.provenance().snapshot().repository();
			String proofSha = proof.provenance().snapshot().sha();
			if (!repoStr.equals(proofRepo)) {
				return false;
			}
			if (proof.sideRole() == SideRole.BEFORE) {
				if (command.intent() != Intent.REVISION_COMPARISON
						|| !command.comparisonBeforeSha().map(s -> s.equals(proofSha)).orElse(false)) {
					return false;
				}
			} else if (proof.sideRole() == SideRole.AFTER || proof.sideRole() == SideRole.TARGET) {
				if (!command.authoritativeTargetSha().equals(proofSha)) {
					return false;
				}
			} else {
				return false;
			}
		}
		return true;
	}

	private static ScopeCompleteness deriveScopeCompleteness(ResolvedSnapshotsResult resolved) {
		if (resolved.snapshotPairs().isEmpty()) {
			return resolved.scopeManifest().filter(m -> m.eligibleFileCount() == 0).isPresent()
					? ScopeCompleteness.EMPTY_VERIFIED
					: ScopeCompleteness.EMPTY_UNVERIFIED;
		}
		return resolved.scopeManifest().filter(m -> m.eligibleFileCount() > resolved.snapshotPairs().size()).isPresent()
				? ScopeCompleteness.PARTIAL
				: ScopeCompleteness.COMPLETE;
	}

	private static List<DocumentLookupResponse> assembleLookupResponses(
			List<DocumentLookupKey> lookupKeys,
			MappingLookupResult mappingResult) {
		var out = new ArrayList<DocumentLookupResponse>(mappingResult.responses());
		if (mappingResult.status() == MappingLookupStatus.UNAVAILABLE
				|| mappingResult.status() == MappingLookupStatus.INCOMPLETE) {
			Set<DocumentLookupKey> presentKeys = new LinkedHashSet<>();
			for (DocumentLookupResponse r : mappingResult.responses()) {
				presentKeys.add(r.key());
			}
			LookupCompleteness fallbackCompleteness = mappingResult.status() == MappingLookupStatus.UNAVAILABLE
					? LookupCompleteness.UNAVAILABLE
					: LookupCompleteness.INCOMPLETE;
			Optional<String> reason = mappingResult.reasonCode()
					.or(() -> Optional.of("MAPPING_" + mappingResult.status().name()));
			for (DocumentLookupKey key : lookupKeys) {
				if (!presentKeys.contains(key)) {
					out.add(new DocumentLookupResponse(key, fallbackCompleteness, List.of(), reason));
				}
			}
		}
		return List.copyOf(out);
	}

	private static List<SemanticCandidate> extractSemanticCandidates(EvidenceResult finalEvidence) {
		var candidates = new LinkedHashSet<SemanticCandidate>();
		for (FindingEvidence fe : finalEvidence.findings()) {
			if (fe.outcome() != ImpactOutcome.INCONCLUSIVE
					|| fe.admission() != IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE
					|| fe.primaryReason() != ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS
					|| fe.status() != EvidenceStatus.VERIFIED) {
				continue;
			}
			var sourceRefs = new ArrayList<GroundedSourceFacetRef>();
			var docRefs = new ArrayList<GroundedDocumentClaimRef>();
			for (WitnessEvidence w : fe.witnesses()) {
				if (w.status() != WitnessStatus.VERIFIED) {
					continue;
				}
				for (SourceLocator loc : w.sourceLocators()) {
					if ((loc.sideRole() == SideRole.AFTER || loc.sideRole() == SideRole.TARGET)
							&& loc.symbolKey().isPresent()
							&& loc.facetKind().isPresent()) {
						if (loc.ranges().isEmpty()) {
							sourceRefs.add(new GroundedSourceFacetRef(
									loc.sideRole(), loc.pinnedSha(), loc.path(),
									loc.symbolKey().get(), loc.facetKind().get(), Optional.empty()));
						} else {
							for (SourceRange range : loc.ranges()) {
								sourceRefs.add(new GroundedSourceFacetRef(
										loc.sideRole(), loc.pinnedSha(), loc.path(),
										loc.symbolKey().get(), loc.facetKind().get(), Optional.of(range)));
							}
						}
					}
				}
				if (w.documentRef().isPresent()) {
					DocumentEvidenceRef dref = w.documentRef().get();
					if (dref.claimId().isPresent()) {
						docRefs.add(new GroundedDocumentClaimRef(
								dref.documentId(),
								dref.documentVersion(),
								dref.documentBoundSha(),
								dref.bindingId(),
								dref.claimId().get(),
								dref.sectionRef()));
					}
				}
			}
			if (!sourceRefs.isEmpty() && !docRefs.isEmpty()) {
				GroundedSourceFacetRef primarySrc = sourceRefs.getFirst();
				GroundedDocumentClaimRef primaryDoc = docRefs.getFirst();
				candidates.add(new SemanticCandidate(
						primarySrc.path(),
						primarySrc.symbolKey(),
						primarySrc.facetKind(),
						primaryDoc.documentId(),
						primaryDoc.documentVersion(),
						primaryDoc.documentBoundSha(),
						primaryDoc.bindingId(),
						primaryDoc.claimId(),
						sourceRefs,
						docRefs));
			}
		}
		return List.copyOf(candidates);
	}

	private SemanticExecutionResult invokeOrReconcileSemantic(
			AnalyzeTargetCommand command,
			AssessmentContext ctx,
			List<SemanticCandidate> candidates) {
		if (command.priorTimedOutSemanticExecutionId().isPresent()) {
			SemanticExecutionResult reconciled = semanticAssessmentPort.reconcileTimedOutExecution(
					new SemanticReconcileRequest(command.priorTimedOutSemanticExecutionId().get(), ctx));
			if (reconciled.status() == ExecutionStatus.DENIED) {
				return reconciled;
			}
			if (reconciled.status() == ExecutionStatus.COMPLETED && !reconciled.assessments().isEmpty()) {
				return reconciled;
			}
		}
		String idempotencyKey = sha256LengthPrefixed(List.of(
				"s05-exec-v1",
				ctx.tenantId(),
				ctx.jobId(),
				ctx.attemptId(),
				ctx.authoritativeTargetSha()));
		return semanticAssessmentPort.evaluate(new SemanticExecutionRequest(
				idempotencyKey,
				ctx,
				clock.instant().plus(command.semanticTimeout()),
				candidates));
	}

	private static List<SemanticAssessmentInput> buildPass2Semantics(
			AssessmentContext ctx,
			List<SemanticCandidate> candidates,
			SemanticExecutionResult semResult) {
		if (!semResult.assessments().isEmpty()) {
			return semResult.assessments();
		}
		if (semResult.status() == ExecutionStatus.TIMED_OUT_PENDING_RECONCILE
				|| semResult.status() == ExecutionStatus.UNAVAILABLE) {
			SemanticStatus fallbackStatus = semResult.status() == ExecutionStatus.TIMED_OUT_PENDING_RECONCILE
					? SemanticStatus.TIMED_OUT
					: SemanticStatus.UNAVAILABLE;
			var out = new ArrayList<SemanticAssessmentInput>(candidates.size());
			for (SemanticCandidate cand : candidates) {
				out.add(new SemanticAssessmentInput(
						ctx.tenantId(),
						ctx.workspaceId(),
						ctx.repository(),
						ctx.targetKind(),
						ctx.targetRef(),
						ctx.jobId(),
						ctx.attemptId(),
						ctx.authoritativeTargetSha(),
						cand.path(),
						cand.symbolKey(),
						cand.documentId(),
						cand.documentVersion(),
						cand.documentBoundSha(),
						fallbackStatus,
						true,
						cand.groundedSourceRefs(),
						cand.groundedDocumentRefs(),
						Optional.empty(),
						semResult.reasonCode(),
						Optional.empty()));
			}
			return List.copyOf(out);
		}
		return List.of();
	}

	private static CheckpointDecision computeCheckpointDecision(
			AnalyzeTargetCommand command,
			PersistAnalysisOutcome outcome) {
		if (outcome.status() != ApplyResultStatus.APPLIED_CURRENT
				&& outcome.status() != ApplyResultStatus.IDEMPOTENT_REPLAY) {
			return CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED;
		}
		if (!outcome.coverageCheckpointEligible()) {
			return CheckpointDecision.HOLD_CHECKPOINT_INCOMPLETE_COVERAGE;
		}
		if (command.intent() == Intent.REVISION_COMPARISON
				&& command.previousCheckpointSha().isPresent()
				&& !command.comparisonBeforeSha().equals(command.previousCheckpointSha())) {
			return CheckpointDecision.REQUIRES_CATCH_UP_RECONCILIATION;
		}
		return CheckpointDecision.ADVANCE_CHECKPOINT;
	}

	private static OrchestrationResult deniedResult(Optional<String> reason) {
		return new OrchestrationResult(
				OrchestrationStatus.DENIED_SCOPE,
				CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED,
				false,
				Optional.empty(),
				Optional.empty(),
				reason);
	}

	private static OrchestrationResult unavailableResult(Optional<String> reason) {
		return new OrchestrationResult(
				OrchestrationStatus.BLOCKED_DEPENDENCY_UNAVAILABLE,
				CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED,
				true,
				Optional.empty(),
				Optional.empty(),
				reason);
	}

	private static OrchestrationResult contractViolationResult(String reason) {
		return new OrchestrationResult(
				OrchestrationStatus.FAILED_CONTRACT_VIOLATION,
				CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED,
				false,
				Optional.empty(),
				Optional.empty(),
				Optional.of(reason));
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

	private static ScopeAuthorizationPort unconfiguredScopeAuthPort() {
		return new ScopeAuthorizationPort() {
			@Override
			public AuthorizationDecision verifyUserScope(UserScopeRequest request) {
				return new AuthorizationDecision(
						DecisionStatus.UNAVAILABLE, Optional.of(ADAPTER_NOT_CONFIGURED), Optional.empty());
			}

			@Override
			public AuthorizationDecision verifyWorkerScope(WorkerScopeRequest request) {
				return new AuthorizationDecision(
						DecisionStatus.UNAVAILABLE, Optional.of(ADAPTER_NOT_CONFIGURED), Optional.empty());
			}
		};
	}

	private static SourceAuthorityPort unconfiguredSourceAuthorityPort() {
		return new SourceAuthorityPort() {
			@Override
			public CurrentnessCheckResult validateCurrentness(CurrentnessCheckRequest request) {
				return new CurrentnessCheckResult(
						CurrentnessVerdict.UNKNOWN, Optional.empty(), Optional.of(ADAPTER_NOT_CONFIGURED));
			}

			@Override
			public ResolvedSnapshotsResult resolveSnapshots(SnapshotResolutionRequest request) {
				return new ResolvedSnapshotsResult(
						ResolutionStatus.UNAVAILABLE, Optional.empty(), List.of(), List.of(), List.of(),
						Optional.of(ADAPTER_NOT_CONFIGURED));
			}
		};
	}

	private static DocumentMappingPort unconfiguredDocumentMappingPort() {
		return request -> new MappingLookupResult(
				MappingLookupStatus.UNAVAILABLE, List.of(), Optional.of(ADAPTER_NOT_CONFIGURED));
	}

	private static SemanticAssessmentPort unconfiguredSemanticPort() {
		return new SemanticAssessmentPort() {
			@Override
			public SemanticExecutionResult evaluate(SemanticExecutionRequest request) {
				return new SemanticExecutionResult(
						ExecutionStatus.UNAVAILABLE, Optional.empty(), List.of(), Optional.of(ADAPTER_NOT_CONFIGURED));
			}

			@Override
			public SemanticExecutionResult reconcileTimedOutExecution(SemanticReconcileRequest request) {
				return new SemanticExecutionResult(
						ExecutionStatus.UNAVAILABLE, Optional.empty(), List.of(), Optional.of(ADAPTER_NOT_CONFIGURED));
			}
		};
	}

	private record ExistingJobSnapshot(AnalysisJobEntity entity, int findingCount) {
	}
}
