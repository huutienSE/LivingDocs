package com.livingdocs.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Fresh user-scoped association proof and the owner-bound context; no credential leaves the context service. */
@Getter @RequiredArgsConstructor
public final class GitHubAuthorizationSelection {
    private final GitHubAuthorizationContextSnapshot context;
    private final GitHubAuthorizationDiscovery.Installation installation;
    private final String appId;
    private final java.time.Instant credentialExpiresAt;
}
