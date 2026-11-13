package com.livingdocs.identity.exception;

public final class IdentityAlreadyExistsException extends RuntimeException {

    public static final String CODE = "IDENTITY_ALREADY_EXISTS";

    public IdentityAlreadyExistsException(Throwable cause) {
        super("Identity already exists", cause);
    }
}
