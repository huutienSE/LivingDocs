package com.livingdocs.document.exception;

public final class ReturnFeedbackRequiredException extends ReviewException {
    public ReturnFeedbackRequiredException() {
        super(ReviewErrorCode.RETURN_REASON_REQUIRED, "Return for revision requires feedback.");
    }
}
