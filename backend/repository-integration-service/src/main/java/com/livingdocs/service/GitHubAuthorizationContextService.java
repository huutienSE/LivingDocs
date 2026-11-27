package com.livingdocs.service;

import com.livingdocs.model.GitHubAuthorizationContextSnapshot;
import com.livingdocs.model.GitHubAuthorizationContextStart;
import com.livingdocs.model.GitHubAuthorizationUser;
import java.util.UUID;

public interface GitHubAuthorizationContextService {
    GitHubAuthorizationContextStart start(GitHubAuthorizationUser actor, UUID workspace, String origin, String correlation);
    GitHubAuthorizationContextSnapshot inspect(GitHubAuthorizationUser actor, UUID workspace, UUID contextId, String correlation);
    void cancel(GitHubAuthorizationUser actor, UUID contextId, String correlation);
    com.livingdocs.model.GitHubAuthorizationCallbackResult complete(String state, java.util.Map<String, String> browserCookies,
            String code, String error, String correlation);
    int purgeExpired();
    com.livingdocs.model.GitHubAuthorizationDiscovery.Page<com.livingdocs.model.GitHubAuthorizationDiscovery.Installation>
        installations(GitHubAuthorizationUser actor, UUID contextId, int page, String correlation);
    com.livingdocs.model.GitHubAuthorizationDiscovery.Page<com.livingdocs.model.GitHubAuthorizationDiscovery.Repository>
        repositories(GitHubAuthorizationUser actor, UUID contextId, String installationId, int page, String correlation);
}
