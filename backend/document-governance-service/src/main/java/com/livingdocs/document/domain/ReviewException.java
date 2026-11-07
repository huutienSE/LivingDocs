package com.livingdocs.document.domain;

public class ReviewException extends RuntimeException {
    private final int status;
    private final String code;

    public ReviewException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static ReviewException notFound() {
        return new ReviewException(404, "DOCUMENT_VERSION_NOT_FOUND", "Document version not found.");
    }

    public static ReviewException forbidden() {
        return new ReviewException(403, "REVIEW_FORBIDDEN", "Staff access to this resource is required.");
    }

    public static ReviewException revisionConflict() {
        return new ReviewException(412, "REVISION_CONFLICT", "Reload the version before reviewing it.");
    }
}
