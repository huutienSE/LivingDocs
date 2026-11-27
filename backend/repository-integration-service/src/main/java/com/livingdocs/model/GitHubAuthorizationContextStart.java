package com.livingdocs.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Internal result: cookieNonce must be used only for the secure cookie, never a JSON DTO. */
@Getter @RequiredArgsConstructor
public final class GitHubAuthorizationContextStart {
    private final UUID contextId;
    private final Instant expiresAt;
    private final String authorizationUrl;
    private final String installationUrl;
    private final String cookieNonce;
}
