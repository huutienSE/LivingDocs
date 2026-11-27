package com.livingdocs.exception;

import com.livingdocs.model.GitHubAuthorizationError;
import lombok.Getter;

@Getter
public final class GitHubAuthorizationException extends RuntimeException {
    private final GitHubAuthorizationError error;
    private final long retryAfterSeconds;
    public GitHubAuthorizationException(GitHubAuthorizationError error) {
        this(error, 0);
    }
    public GitHubAuthorizationException(GitHubAuthorizationError error, long retryAfterSeconds) {
        super(error.getDetail());
        this.error = error;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
