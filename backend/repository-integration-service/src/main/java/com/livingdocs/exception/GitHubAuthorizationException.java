package com.livingdocs.exception;

import com.livingdocs.model.GitHubAuthorizationError;
import lombok.Getter;

@Getter
public final class GitHubAuthorizationException extends RuntimeException {
    private final GitHubAuthorizationError error;
    public GitHubAuthorizationException(GitHubAuthorizationError error) {
        super(error.getDetail());
        this.error = error;
    }
}
