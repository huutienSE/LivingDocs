package com.livingdocs.document.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ReviewTransitionPolicy {
    public void requireStaffReview(DocumentVersion version, ReviewAction action, String feedback,
                                   List<UUID> evidenceRefs) {
        if (version.state() != DocumentState.IN_REVIEW || version.reviewStage() != ReviewStage.STAFF_REVIEW) {
            throw new ReviewException(409, "STATE_TRANSITION_FORBIDDEN", "Version must be in Staff Review.");
        }
        if (action == null) {
            throw new ReviewException(400, "INVALID_REQUEST", "A review decision is required.");
        }
        if (action == ReviewAction.RETURN_FOR_REVISION && (feedback == null || feedback.isBlank())) {
            throw new ReviewException(422, "RETURN_REASON_REQUIRED", "Return for revision requires feedback.");
        }
        if (action == ReviewAction.PASS && evidenceRefs.isEmpty()) {
            throw new ReviewException(422, "REVIEW_EVIDENCE_REQUIRED", "PASS requires stored review evidence.");
        }
        if (action == ReviewAction.PASS && version.repositoryConnectionId() != null
                && (version.sourceSha() == null || version.sourceSha().isBlank())) {
            throw new ReviewException(422, "SOURCE_EVIDENCE_INSUFFICIENT", "Source-derived versions require a bound source SHA.");
        }
    }
}
