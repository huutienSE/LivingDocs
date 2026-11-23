package com.livingdocs.document.exception;

public class ReviewAuthorizationUnavailableException extends ReviewException {
    public ReviewAuthorizationUnavailableException() {
        super(ReviewErrorCode.AUTHORIZATION_UNAVAILABLE, "Review authorization is unavailable. No changes were committed.");
    }
}
