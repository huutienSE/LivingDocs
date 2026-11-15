package com.livingdocs.document.exception;

public final class DuplicateReviewEvidenceException extends ReviewException {
    public DuplicateReviewEvidenceException() {
        super(ReviewErrorCode.DUPLICATE_EVIDENCE, "Evidence references must be unique.");
    }
}
