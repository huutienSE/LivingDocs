package com.livingdocs.document.exception;

/** The actor has no MANAGER + DOCUMENT_FINAL_APPROVE grant covering the requested scope. */
public final class InsufficientApprovalScopeException extends ReviewException {
    public InsufficientApprovalScopeException() {
        super(ReviewErrorCode.APPROVAL_FORBIDDEN, "Manager approval access to this resource is required.");
    }
}
