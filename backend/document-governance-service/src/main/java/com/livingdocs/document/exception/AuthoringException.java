package com.livingdocs.document.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AuthoringException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AuthoringException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public static AuthoringException notFound() {
        return new AuthoringException(HttpStatus.NOT_FOUND, "DOCUMENT_RESOURCE_NOT_FOUND", "Document resource was not found.");
    }
    public static AuthoringException forbidden() {
        return new AuthoringException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Permission in the resource scope is required.");
    }
    public static AuthoringException sourceUnavailable() {
        return new AuthoringException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_SNAPSHOT_UNAVAILABLE", "A trusted source snapshot could not be obtained.");
    }
}
