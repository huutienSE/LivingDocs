package com.livingdocs.identity.exception;

public final class LoginRateLimitedException extends RuntimeException {

    public static final String CODE = "LOGIN_RATE_LIMITED";

    private final long retryAfterSeconds;

    public LoginRateLimitedException(long retryAfterSeconds) {
        super("Too many login attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
