package com.livingdocs.model;

import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Created only from a verified security principal; no generated toString exposing the Bearer. */
@Getter @RequiredArgsConstructor
public final class GitHubAuthorizationUser {
    private final UUID identityId;
    private final String bearerToken;
}
