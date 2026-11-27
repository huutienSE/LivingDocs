package com.livingdocs.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter @RequiredArgsConstructor
public final class GitHubAuthorizationContextBinding {
    private final UUID contextId;
    private final UUID tenantId;
    private final UUID workspaceId;
    private final UUID actorIdentityId;
    private final String appId;
    private final String appConfigVersion;
    private final Instant expiresAt;
}
