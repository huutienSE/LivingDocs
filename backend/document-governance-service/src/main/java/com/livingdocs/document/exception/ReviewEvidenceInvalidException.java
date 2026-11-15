package com.livingdocs.document.exception;

public final class ReviewEvidenceInvalidException extends ReviewException {
    public ReviewEvidenceInvalidException() {
        super(ReviewErrorCode.REVIEW_EVIDENCE_INVALID, "Evidence must belong to this document version.");
    }
}
