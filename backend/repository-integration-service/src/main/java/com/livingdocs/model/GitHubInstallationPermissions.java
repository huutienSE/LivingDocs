package com.livingdocs.model;

import java.util.Map;
import lombok.Getter;

@Getter
public final class GitHubInstallationPermissions {
    private final GitHubAuthorizationDiscovery.Installation installation;
    private final Map<String,String> permissions;
    public GitHubInstallationPermissions(GitHubAuthorizationDiscovery.Installation installation, Map<String,String> permissions) {
        this.installation=installation; this.permissions=Map.copyOf(permissions);
    }
}
