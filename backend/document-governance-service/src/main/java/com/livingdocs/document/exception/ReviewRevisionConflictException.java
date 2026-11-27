package com.livingdocs.document.exception;

public final class ReviewRevisionConflictException extends ReviewException {
    public ReviewRevisionConflictException() {
        super(ReviewErrorCode.REVISION_CONFLICT, "Reload the version before reviewing it.");
    }
}
