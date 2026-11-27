package com.livingdocs.model;

import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter @RequiredArgsConstructor
public final class GitHubAuthorizationVerification {
    private final String status;
    private final GitHubAuthorizationDiscovery.Installation installation;
    private final List<Permission> grantedPermissions;
    private final List<Permission> missingPermissions;
    private final Instant checkedAt;
    private final Boolean repositoryAccessVerified;

    public static GitHubAuthorizationVerification unverified(String status) {
        return new GitHubAuthorizationVerification(status, null, null, null, null, null);
    }

    @Getter @RequiredArgsConstructor
    public static final class Permission {
        private final String category;
        private final String name;
        private final String level;
    }
}
