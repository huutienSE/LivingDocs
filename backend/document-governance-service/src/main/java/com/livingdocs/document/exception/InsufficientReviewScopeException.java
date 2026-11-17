package com.livingdocs.document.exception;

public final class InsufficientReviewScopeException extends ReviewException {
    public InsufficientReviewScopeException() {
        super(ReviewErrorCode.REVIEW_FORBIDDEN, "Staff access to this resource is required.");
    }
}
