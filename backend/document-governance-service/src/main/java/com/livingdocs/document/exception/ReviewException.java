package com.livingdocs.document.exception;

import java.util.Objects;
import lombok.Getter;

/** Base for typed review failures; RFC 9457 serialization belongs to the HTTP boundary. */
@Getter
public abstract class ReviewException extends RuntimeException {
    private final ReviewErrorCode errorCode;

    protected ReviewException(ReviewErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    protected ReviewException(ReviewErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public int status() { return errorCode.getStatus().value(); }

}
