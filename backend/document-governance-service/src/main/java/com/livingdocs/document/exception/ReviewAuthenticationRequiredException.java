package com.livingdocs.document.exception;

public final class ReviewAuthenticationRequiredException extends ReviewException {
    public ReviewAuthenticationRequiredException() {
        super(ReviewErrorCode.UNAUTHENTICATED, "A verified access token is required.");
    }
}
