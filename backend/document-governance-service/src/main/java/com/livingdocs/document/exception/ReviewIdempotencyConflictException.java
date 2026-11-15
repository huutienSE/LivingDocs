package com.livingdocs.document.exception;

public final class ReviewIdempotencyConflictException extends ReviewException {
    public ReviewIdempotencyConflictException() {
        super(ReviewErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key was already used for another request.");
    }
}
