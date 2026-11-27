package com.livingdocs.model;

import com.livingdocs.exception.GitHubAuthorizationException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/** S02 policy over a fresh, validated S01 snapshot. */
public final class GitHubAuthorizationPolicy {
    private GitHubAuthorizationPolicy() {}

    public static UUID tenant(S01AccessSnapshot snapshot) {
        if (snapshot.getTenantId() == null) throw new GitHubAuthorizationException(GitHubAuthorizationError.FORBIDDEN_SCOPE);
        return snapshot.getTenantId();
    }

    public static UUID workspace(S01AccessSnapshot snapshot, UUID workspace) {
        UUID tenant = tenant(snapshot);
        var grant = snapshot.getWorkspaceGrants().get(workspace);
        if (grant == null) throw new GitHubAuthorizationException(GitHubAuthorizationError.WORKSPACE_NOT_FOUND);
        if (!grant.getPermissions().contains("WORKSPACE_VIEW")
                || Collections.disjoint(grant.getRoles(), Set.of("DEVELOPER", "TECHNICAL_LEAD")))
            throw new GitHubAuthorizationException(GitHubAuthorizationError.FORBIDDEN_SCOPE);
        return tenant;
    }
}
