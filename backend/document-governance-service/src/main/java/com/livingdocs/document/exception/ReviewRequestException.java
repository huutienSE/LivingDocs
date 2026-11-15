package com.livingdocs.document.exception;

public final class ReviewRequestException extends ReviewException {
    public ReviewRequestException(ReviewErrorCode code, String message) {
        super(code, message);
        if (code != ReviewErrorCode.INVALID_REQUEST && code != ReviewErrorCode.INVALID_IF_MATCH && code != ReviewErrorCode.PRECONDITION_REQUIRED) {
            throw new IllegalArgumentException("Unsupported error code for ReviewRequestException");
        }
    }
}
