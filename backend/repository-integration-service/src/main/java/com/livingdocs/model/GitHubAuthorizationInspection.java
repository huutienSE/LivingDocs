package com.livingdocs.model;

import java.time.Instant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Fresh provider evidence; contains no user token or App credential. */
@Getter @RequiredArgsConstructor
public final class GitHubAuthorizationInspection {
    private final GitHubAppProfile app;
    private final GitHubAuthorizationContextSnapshot context;
    private final GitHubInstallationPermissions permissions;
    private final Instant checkedAt;
}
