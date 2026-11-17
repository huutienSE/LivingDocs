package com.livingdocs.document.model;

import com.livingdocs.document.exception.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ReviewTransitionPolicy {
    public void requireStaffReview(DocumentVersion version, ReviewAction action, String feedback,
                                   List<UUID> evidenceRefs) {
        if (version.state() != DocumentState.IN_REVIEW || version.reviewStage() != ReviewStage.STAFF_REVIEW) {
            throw new InvalidReviewTransitionException();
        }
        if (action == null) {
            throw new ReviewRequestException(ReviewErrorCode.INVALID_REQUEST, "A review decision is required.");
        }
        if (action == ReviewAction.RETURN_FOR_REVISION && (feedback == null || feedback.isBlank())) {
            throw new ReturnFeedbackRequiredException();
        }
        if (action == ReviewAction.PASS && evidenceRefs.isEmpty()) {
            throw new ReviewEvidenceRequiredException();
        }
        if (action == ReviewAction.PASS && version.repositoryConnectionId() != null
                && (version.sourceSha() == null || version.sourceSha().isBlank())) {
            throw new SourceEvidenceInsufficientException();
        }
    }
}
