package com.livingdocs.service.impl;

import com.livingdocs.client.S01AccessContextClient;
import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.dto.request.GitHubAuthorizationQuery;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.model.*;
import com.livingdocs.model.GitHubAuthorizationRequirements.Permission;
import com.livingdocs.service.GitHubAuthorizationService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Advisory requirements query: no connection, grant, context or source-observation writes. */
@Service @RequiredArgsConstructor
public class GitHubAuthorizationServiceImpl implements GitHubAuthorizationService {
    private final S01AccessContextClient authority;
    private final GitHubAuthorizationProperties config;

    @Override public GitHubAuthorizationRequirements read(GitHubAuthorizationUser actor, GitHubAuthorizationQuery query, String correlation) {
        UUID workspace = UUID.fromString(query.getWorkspaceId());
        var access = authority.read(actor,correlation);
        if (access.getTenantId() == null)
            throw new GitHubAuthorizationException(GitHubAuthorizationError.FORBIDDEN_SCOPE);
        var grant = access.getWorkspaceGrants().get(workspace);
        if (grant == null) throw new GitHubAuthorizationException(GitHubAuthorizationError.WORKSPACE_NOT_FOUND);
        if (!grant.getPermissions().contains("WORKSPACE_VIEW")
                || Collections.disjoint(grant.getRoles(), Set.of("DEVELOPER", "TECHNICAL_LEAD")))
            throw new GitHubAuthorizationException(GitHubAuthorizationError.FORBIDDEN_SCOPE);
        if (query.getAuthorizationContextId()!=null)
            throw new GitHubAuthorizationException(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE);
        try {
            if (!config.getAppId().matches("[1-9][0-9]{0,18}") || Long.parseLong(config.getAppId())<=0
                    || !config.getAppSlug().matches("[a-z0-9][a-z0-9-]{0,99}")
                    || config.getAppName().isBlank() || config.getAppName().length()>200) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new GitHubAuthorizationException(GitHubAuthorizationError.GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE); }
        // Versioned, reviewed whole-App manifest. These are requirements, never installation grants.
        return new GitHubAuthorizationRequirements(workspace,config.getAppId(),config.getAppSlug(),config.getAppName(),
                "github-app-profile-v1",
                List.of(new Permission("REPOSITORY","metadata","read","Repository identity and access metadata"),
                        new Permission("REPOSITORY","contents","write","Read source and controlled published-document export"),
                        new Permission("REPOSITORY","pull_requests","write","Read PR context and create controlled export PR"),
                        new Permission("REPOSITORY","checks","write","Publish source-bound verification feedback")),
                List.of("push","pull_request"));
    }
}
