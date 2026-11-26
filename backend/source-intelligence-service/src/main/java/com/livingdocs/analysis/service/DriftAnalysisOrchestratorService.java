package com.livingdocs.analysis.service;

import com.livingdocs.analysis.diff.StructuralDiffInput.Intent;
import com.livingdocs.analysis.impact.ImpactClassifier.CallerExclusion;
import com.livingdocs.analysis.impact.ImpactClassifier.TargetKind;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.PersistAnalysisOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Non-transactional M6 pipeline orchestrator coordinating authorization, S02 currentness
 * and snapshot resolution, M2 structural diff, S04 reverse lookup, M3/M4 grounded impact
 * & evidence, optional two-pass S05 semantic assessment, and conditional M5 persistence.
 */
public interface DriftAnalysisOrchestratorService {

	OrchestrationResult executeAnalysis(AnalyzeTargetCommand command);

	enum OrchestrationStatus {
		APPLIED_CURRENT,
		IDEMPOTENT_REPLAY,
		SUPPRESSED_STALE,
		DEFERRED_UNKNOWN_CURRENTNESS,
		DENIED_SCOPE,
		BLOCKED_DEPENDENCY_UNAVAILABLE,
		FAILED_CONTRACT_VIOLATION
	}

	enum CheckpointDecision {
		ADVANCE_CHECKPOINT,
		REQUIRES_CATCH_UP_RECONCILIATION,
		HOLD_CHECKPOINT_INCOMPLETE_COVERAGE,
		HOLD_CHECKPOINT_NOT_APPLIED
	}

	record AnalyzeTargetCommand(
			UUID jobId,
			String servicePrincipal,
			UUID tenantId,
			UUID workspaceId,
			UUID repositoryConnectionId,
			String externalJobKey,
			String attemptId,
			Instant leaseExpiresAt,
			TargetKind targetKind,
			String targetRef,
			Intent intent,
			Optional<String> comparisonBeforeSha,
			String authoritativeTargetSha,
			String configRevision,
			boolean whitespaceOnlyNoImpactPolicyEnabled,
			List<CallerExclusion> callerExclusions,
			boolean semanticEvaluationEnabled,
			Duration semanticTimeout,
			Optional<String> priorTimedOutSemanticExecutionId,
			Optional<String> previousCheckpointSha) {

		public AnalyzeTargetCommand {
			Objects.requireNonNull(jobId, "jobId");
			if (Objects.requireNonNull(servicePrincipal, "servicePrincipal").isBlank()) {
				throw new IllegalArgumentException("servicePrincipal cannot be blank");
			}
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(workspaceId, "workspaceId");
			Objects.requireNonNull(repositoryConnectionId, "repositoryConnectionId");
			if (Objects.requireNonNull(externalJobKey, "externalJobKey").isBlank()) {
				throw new IllegalArgumentException("externalJobKey cannot be blank");
			}
			if (Objects.requireNonNull(attemptId, "attemptId").isBlank()) {
				throw new IllegalArgumentException("attemptId cannot be blank");
			}
			Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
			Objects.requireNonNull(targetKind, "targetKind");
			if (Objects.requireNonNull(targetRef, "targetRef").isBlank()) {
				throw new IllegalArgumentException("targetRef cannot be blank");
			}
			Objects.requireNonNull(intent, "intent");
			Objects.requireNonNull(comparisonBeforeSha, "comparisonBeforeSha");
			if (Objects.requireNonNull(authoritativeTargetSha, "authoritativeTargetSha").isBlank()) {
				throw new IllegalArgumentException("authoritativeTargetSha cannot be blank");
			}
			if (Objects.requireNonNull(configRevision, "configRevision").isBlank()) {
				throw new IllegalArgumentException("configRevision cannot be blank");
			}
			callerExclusions = List.copyOf(Objects.requireNonNull(callerExclusions, "callerExclusions"));
			Objects.requireNonNull(semanticTimeout, "semanticTimeout");
			if (semanticTimeout.isZero() || semanticTimeout.isNegative()) {
				throw new IllegalArgumentException("semanticTimeout must be positive");
			}
			Objects.requireNonNull(priorTimedOutSemanticExecutionId, "priorTimedOutSemanticExecutionId");
			if (priorTimedOutSemanticExecutionId.isPresent() && priorTimedOutSemanticExecutionId.get().isBlank()) {
				throw new IllegalArgumentException("priorTimedOutSemanticExecutionId cannot be blank");
			}
			Objects.requireNonNull(previousCheckpointSha, "previousCheckpointSha");
			if (previousCheckpointSha.isPresent() && previousCheckpointSha.get().isBlank()) {
				throw new IllegalArgumentException("previousCheckpointSha cannot be blank");
			}

			if (intent == Intent.REVISION_COMPARISON
					&& (comparisonBeforeSha.isEmpty() || comparisonBeforeSha.get().isBlank())) {
				throw new IllegalArgumentException("REVISION_COMPARISON requires non-blank comparisonBeforeSha");
			}
			if (intent == Intent.INITIAL_ASSESSMENT && comparisonBeforeSha.isPresent()) {
				throw new IllegalArgumentException("INITIAL_ASSESSMENT cannot specify comparisonBeforeSha");
			}
		}
	}

	record OrchestrationResult(
			OrchestrationStatus status,
			CheckpointDecision checkpointDecision,
			boolean retryable,
			Optional<PersistAnalysisOutcome> persistOutcome,
			Optional<String> semanticExecutionId,
			Optional<String> diagnosticReason) {

		public OrchestrationResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(checkpointDecision, "checkpointDecision");
			Objects.requireNonNull(persistOutcome, "persistOutcome");
			Objects.requireNonNull(semanticExecutionId, "semanticExecutionId");
			Objects.requireNonNull(diagnosticReason, "diagnosticReason");
		}
	}
}
