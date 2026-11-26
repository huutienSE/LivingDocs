package com.livingdocs.analysis.model;

import com.livingdocs.analysis.ast.JavaParseResult.SourceRange;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.Coverage;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceResult;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.FindingEvidence;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.SafeDiagnostic;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.WitnessEvidence;
import com.livingdocs.analysis.impact.ImpactClassifier.AssessmentContext;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactOutcome;
import com.livingdocs.analysis.impact.ImpactClassifier.ReportProcessingStatus;
import com.livingdocs.analysis.port.SourceAuthorityPort.ValidationReceipt;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Typed command, outcome, exception, and versioned JSONB models for M5 persistence. */
public final class AnalysisPersistenceModels {

	public static final String EVIDENCE_SCHEMA_VERSION = "m5-finding-evidence-v1";
	public static final String JOB_SUMMARY_SCHEMA_VERSION = "m5-job-summary-v1";

	private AnalysisPersistenceModels() {
	}

	public enum CurrentnessReceiptState {
		CURRENT,
		STALE,
		UNKNOWN,
		DENIED
	}

	public enum ApplyResultStatus {
		APPLIED_CURRENT,
		IDEMPOTENT_REPLAY,
		SUPPRESSED_STALE,
		DEFERRED_UNKNOWN_CURRENTNESS
	}

	public static class AnalysisPersistenceException extends RuntimeException {
		private final String errorCode;

		public AnalysisPersistenceException(String errorCode, String message) {
			super(message);
			this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
		}

		public String getErrorCode() {
			return errorCode;
		}
	}

	public static final class BindingConflictException extends AnalysisPersistenceException {
		public BindingConflictException(String message) {
			super("ANALYSIS_JOB_BINDING_CONFLICT", message);
		}
	}

	public static final class StaleAttemptException extends AnalysisPersistenceException {
		public StaleAttemptException(String message) {
			super("STALE_OR_EXPIRED_ATTEMPT", message);
		}
	}

	public static final class ScopeUnauthorizedException extends AnalysisPersistenceException {
		public ScopeUnauthorizedException(String message) {
			super("ANALYSIS_SCOPE_DENIED", message);
		}
	}

	public record AuthorizedJobWriteContext(
			UUID jobId,
			Instant leaseExpiresAt,
			AssessmentContext assessmentContext,
			boolean authorized) {

		public AuthorizedJobWriteContext {
			Objects.requireNonNull(jobId, "jobId");
			Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
			Objects.requireNonNull(assessmentContext, "assessmentContext");
		}
	}

	public record CurrentnessProof(
			CurrentnessReceiptState state,
			Optional<ValidationReceipt> receipt,
			Optional<String> reasonCode) {

		public CurrentnessProof {
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(receipt, "receipt");
			Objects.requireNonNull(reasonCode, "reasonCode");
		}
	}

	public record PersistAnalysisCommand(
			AuthorizedJobWriteContext jobContext,
			CurrentnessProof currentnessProof,
			EvidenceResult evidenceResult,
			Instant observedAt) {

		public PersistAnalysisCommand {
			Objects.requireNonNull(jobContext, "jobContext");
			Objects.requireNonNull(currentnessProof, "currentnessProof");
			Objects.requireNonNull(evidenceResult, "evidenceResult");
			Objects.requireNonNull(observedAt, "observedAt");
		}
	}

	public record PersistAnalysisOutcome(
			ApplyResultStatus status,
			UUID tenantId,
			UUID jobId,
			String processingStatus,
			String overallOutcome,
			String evidenceStatus,
			int findingsPersisted,
			int issuesOpened,
			int issuesUpdated,
			int revertsRecorded,
			int observationsRecorded,
			boolean coverageCheckpointEligible) {
	}

	// ---- Versioned JSONB projection models (stored once on analysis_findings / analysis_jobs) ----

	public record CoverageJsonPayload(
			String schemaVersion,
			int evaluatedFileCount,
			int excludedFileCount,
			int failedOrIncompleteFileCount,
			boolean completeCoverage) {

		public static CoverageJsonPayload from(Optional<Coverage> coverageOpt) {
			return coverageOpt
					.map(c -> new CoverageJsonPayload(
							JOB_SUMMARY_SCHEMA_VERSION,
							c.evaluatedFileCount(),
							c.excludedFileCount(),
							c.failedOrIncompleteFileCount(),
							c.completeCoverage()))
					.orElseGet(() -> new CoverageJsonPayload(JOB_SUMMARY_SCHEMA_VERSION, 0, 0, 0, false));
		}
	}

	public record DiagnosticJsonPayload(
			String diffCode,
			String impactCode,
			String evidenceReason,
			SourceLocatorJsonPayload location) {

		public static DiagnosticJsonPayload from(SafeDiagnostic diag) {
			return new DiagnosticJsonPayload(
					diag.diffCode().map(Enum::name).orElse(null),
					diag.impactCode().map(Enum::name).orElse(null),
					diag.evidenceReason().map(Enum::name).orElse(null),
					diag.location().map(SourceLocatorJsonPayload::from).orElse(null));
		}
	}

	public record SourceLocatorJsonPayload(
			String repository,
			String sideRole,
			String pinnedSha,
			String path,
			String filePresence,
			String basis,
			String symbolKey,
			String facetKind,
			String contextChangeKind,
			List<SourceRange> ranges) {

		public static SourceLocatorJsonPayload from(com.livingdocs.analysis.evidence.SourceEvidenceMapper.SourceLocator l) {
			return new SourceLocatorJsonPayload(
					l.repository(),
					l.sideRole().name(),
					l.pinnedSha(),
					l.path(),
					l.filePresence().name(),
					l.basis().name(),
					l.symbolKey().map(JavaStructuralRepresentation::canonicalSymbolKey).orElse(null),
					l.facetKind().map(Enum::name).orElse(null),
					l.contextChangeKind().map(Enum::name).orElse(null),
					l.ranges());
		}
	}

	public record DocumentRefJsonPayload(
			String documentId,
			String documentVersion,
			String documentBoundSha,
			String evaluatedTargetSha,
			String bindingId,
			String claimId,
			String sectionRef,
			String verdict) {

		public static DocumentRefJsonPayload from(com.livingdocs.analysis.evidence.SourceEvidenceMapper.DocumentEvidenceRef d) {
			return new DocumentRefJsonPayload(
					d.documentId(),
					d.documentVersion(),
					d.documentBoundSha(),
					d.evaluatedTargetSha(),
					d.bindingId(),
					d.claimId().orElse(null),
					d.sectionRef().orElse(null),
					d.verdict().map(Enum::name).orElse(null));
		}
	}

	public record NegativeBasisJsonPayload(
			String kind,
			List<String> verifiedEmptyLookupKeys,
			String provenanceRefId) {

		public static NegativeBasisJsonPayload from(com.livingdocs.analysis.evidence.SourceEvidenceMapper.NegativeBasisEvidence n) {
			List<String> keys = n.verifiedEmptyLookupKeys().stream()
					.map(k -> k.sideRole().name() + "|" + k.pinnedSha() + "|" + k.path() + "|"
							+ k.granularity().name() + "|"
							+ k.symbolKey().map(JavaStructuralRepresentation::canonicalSymbolKey).orElse(""))
					.toList();
			return new NegativeBasisJsonPayload(
					n.kind().name(),
					keys,
					n.provenanceRefId().orElse(null));
		}
	}

	public record WitnessJsonPayload(
			String witnessId,
			String status,
			List<String> reasons,
			List<SourceLocatorJsonPayload> sourceLocators,
			DocumentRefJsonPayload documentRef,
			NegativeBasisJsonPayload negativeBasis,
			String semanticStatus) {

		public static WitnessJsonPayload from(WitnessEvidence w) {
			return new WitnessJsonPayload(
					w.witnessId(),
					w.status().name(),
					w.reasons().stream().map(Enum::name).toList(),
					w.sourceLocators().stream().map(SourceLocatorJsonPayload::from).toList(),
					w.documentRef().map(DocumentRefJsonPayload::from).orElse(null),
					w.negativeBasis().map(NegativeBasisJsonPayload::from).orElse(null),
					w.semanticStatus().map(Enum::name).orElse(null));
		}
	}

	public record FindingEvidenceJsonPayload(
			String schemaVersion,
			String findingId,
			String disposition,
			String outcome,
			String classification,
			String severityStatus,
			String severity,
			Double semanticConfidence,
			String admission,
			String primaryReason,
			String admissionReason,
			String status,
			List<WitnessJsonPayload> witnesses) {

		public static FindingEvidenceJsonPayload from(FindingEvidence fe) {
			return new FindingEvidenceJsonPayload(
					EVIDENCE_SCHEMA_VERSION,
					fe.findingId(),
					fe.disposition().name(),
					fe.outcome().name(),
					fe.classification().map(Enum::name).orElse(null),
					fe.severityStatus().name(),
					fe.severity().map(Enum::name).orElse(null),
					fe.semanticConfidence().orElse(null),
					fe.admission().name(),
					fe.primaryReason().name(),
					fe.admissionReason().name(),
					fe.status().name(),
					fe.witnesses().stream().map(WitnessJsonPayload::from).toList());
		}
	}

	public static String nullableEnumName(Optional<? extends Enum<?>> value) {
		return value.map(Enum::name).orElse(null);
	}

	public static String nullableOutcomeName(Optional<ImpactOutcome> value) {
		return value.map(Enum::name).orElse(null);
	}

	public static String nullableProcessingName(Optional<ReportProcessingStatus> value) {
		return value.map(Enum::name).orElse(null);
	}
}
