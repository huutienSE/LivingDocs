package com.livingdocs.document.exception;

/** S01 API-038 could not prove the Manager scope; read APIs fail closed instead of guessing. */
public final class ApprovalAuthorizationUnavailableException extends ReviewException {
    public ApprovalAuthorizationUnavailableException() {
        super(ReviewErrorCode.AUTHORIZATION_UNAVAILABLE, "Manager authorization is unavailable. Retry later.");
    }
}
