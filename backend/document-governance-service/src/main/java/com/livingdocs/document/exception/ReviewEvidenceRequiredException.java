package com.livingdocs.document.exception;

public final class ReviewEvidenceRequiredException extends ReviewException {
    public ReviewEvidenceRequiredException() {
        super(ReviewErrorCode.REVIEW_EVIDENCE_REQUIRED, "PASS requires stored review evidence.");
    }
}
