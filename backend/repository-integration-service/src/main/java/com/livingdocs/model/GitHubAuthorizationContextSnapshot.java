package com.livingdocs.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter @RequiredArgsConstructor
public final class GitHubAuthorizationContextSnapshot {
    private final UUID contextId;
    private final Instant expiresAt;
    private final String status;
}
