package com.livingdocs.document.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Recorded review outcome, including the acknowledgement retained for an exact retry. */
public record StaffReviewResult(UUID documentId, UUID versionId, DocumentState state,
                                ReviewStage reviewStage, long revision, SourceBinding sourceBinding,
                                Receipt receipt) {
    public StaffReviewResult { Objects.requireNonNull(receipt); }

    public record Receipt(String outcomeCode, String description) {
        public Receipt {
            Objects.requireNonNull(outcomeCode);
            Objects.requireNonNull(description);
        }

        public static Receipt recorded() {
            var outcome = SuccessCode.STAFF_REVIEW_RECORDED;
            return new Receipt(outcome.getWireCode(), outcome.getDefaultMessage());
        }
    }

    public record SourceBinding(UUID repositoryConnectionId, String targetKind, String targetRef,
                                String headSha, String comparisonSha, String configRevision, Instant capturedAt) {}

    public static StaffReviewResult recorded(ReviewDecision decision, DocumentVersion version) {
        var binding = version.repositoryConnectionId() == null ? null
                : new SourceBinding(version.repositoryConnectionId(), null, null, version.sourceSha(), null, null, null);
        return new StaffReviewResult(version.documentId(), decision.versionId(), decision.resultingState(),
                decision.resultingStage(), decision.resultingRevision(), binding, Receipt.recorded());
    }
}
