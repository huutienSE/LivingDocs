package com.livingdocs.analysis.port;

import com.livingdocs.analysis.ast.JavaParseResult.SymbolKey;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FacetKind;
import com.livingdocs.analysis.impact.ImpactClassifier.AssessmentContext;
import com.livingdocs.analysis.impact.ImpactClassifier.GroundedDocumentClaimRef;
import com.livingdocs.analysis.impact.ImpactClassifier.GroundedSourceFacetRef;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticAssessmentInput;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal port isolating S03 from S05 (API-164..167) semantic execution, deadline & timeout reconciliation.
 */
public interface SemanticAssessmentPort {

	SemanticExecutionResult evaluate(SemanticExecutionRequest request);

	SemanticExecutionResult reconcileTimedOutExecution(SemanticReconcileRequest request);

	enum ExecutionStatus {
		COMPLETED,
		TIMED_OUT_PENDING_RECONCILE,
		UNAVAILABLE,
		DENIED
	}

	record SemanticCandidate(
			String path,
			SymbolKey symbolKey,
			FacetKind facetKind,
			String documentId,
			String documentVersion,
			String documentBoundSha,
			String bindingId,
			String claimId,
			List<GroundedSourceFacetRef> groundedSourceRefs,
			List<GroundedDocumentClaimRef> groundedDocumentRefs) {

		public SemanticCandidate {
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(symbolKey, "symbolKey");
			Objects.requireNonNull(facetKind, "facetKind");
			Objects.requireNonNull(documentId, "documentId");
			Objects.requireNonNull(documentVersion, "documentVersion");
			Objects.requireNonNull(documentBoundSha, "documentBoundSha");
			Objects.requireNonNull(bindingId, "bindingId");
			Objects.requireNonNull(claimId, "claimId");
			groundedSourceRefs = List.copyOf(Objects.requireNonNull(groundedSourceRefs, "groundedSourceRefs"));
			groundedDocumentRefs = List.copyOf(Objects.requireNonNull(groundedDocumentRefs, "groundedDocumentRefs"));
		}
	}

	record SemanticExecutionRequest(
			String idempotencyKey,
			AssessmentContext context,
			Instant deadline,
			List<SemanticCandidate> candidates) {

		public SemanticExecutionRequest {
			Objects.requireNonNull(idempotencyKey, "idempotencyKey");
			Objects.requireNonNull(context, "context");
			Objects.requireNonNull(deadline, "deadline");
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
		}
	}

	record SemanticReconcileRequest(
			String executionId,
			AssessmentContext context) {

		public SemanticReconcileRequest {
			Objects.requireNonNull(executionId, "executionId");
			Objects.requireNonNull(context, "context");
		}
	}

	record SemanticExecutionResult(
			ExecutionStatus status,
			Optional<String> executionId,
			List<SemanticAssessmentInput> assessments,
			Optional<String> reasonCode) {

		public SemanticExecutionResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(executionId, "executionId");
			assessments = List.copyOf(Objects.requireNonNull(assessments, "assessments"));
			Objects.requireNonNull(reasonCode, "reasonCode");
		}
	}
}
