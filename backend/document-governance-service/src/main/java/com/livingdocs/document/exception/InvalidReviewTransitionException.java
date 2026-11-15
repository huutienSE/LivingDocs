package com.livingdocs.document.exception;

public final class InvalidReviewTransitionException extends ReviewException {
    public InvalidReviewTransitionException() {
        super(ReviewErrorCode.STATE_TRANSITION_FORBIDDEN, "Version must be in Staff Review.");
    }
}
