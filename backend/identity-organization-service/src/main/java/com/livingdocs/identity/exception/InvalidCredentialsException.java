package com.livingdocs.identity.exception;

public final class InvalidCredentialsException extends RuntimeException {

    public static final String CODE = "INVALID_CREDENTIALS";

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
