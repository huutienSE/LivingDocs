package com.livingdocs.model;

import java.time.Instant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** In-memory credential, never an HTTP response or log object. No refresh token is retained. */
@Getter @RequiredArgsConstructor
public final class GitHubUserAuthorization {
    private final String accessToken;
    private final String githubUserId;
    private final Instant expiresAt;
}
