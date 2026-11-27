package com.livingdocs.document.exception;

public final class InvalidReviewQueueQueryException extends ReviewException {
    public InvalidReviewQueueQueryException() {
        super(ReviewErrorCode.INVALID_REQUEST, "Invalid queue filters or pagination outside the configured query budget.");
    }
}
