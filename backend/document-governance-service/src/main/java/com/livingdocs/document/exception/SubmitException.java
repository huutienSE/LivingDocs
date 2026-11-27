package com.livingdocs.document.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public final class SubmitException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public SubmitException(String code, HttpStatus status, String message) { super(message); this.code = code; this.status = status; }
    public static SubmitException forbidden() { return new SubmitException("FORBIDDEN_SCOPE", HttpStatus.FORBIDDEN, "Developer permission in the document scope is required."); }
    public static SubmitException unavailable() { return new SubmitException("AUTHORIZATION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "Current S01 authorization could not be verified."); }
}
