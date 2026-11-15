package com.livingdocs.document.exception;

public final class SourceValidationUnavailableException extends ReviewException {
    public SourceValidationUnavailableException() {
        super(ReviewErrorCode.SOURCE_VALIDATION_UNAVAILABLE, "Current source could not be verified. No review changes were committed.");
    }
}
