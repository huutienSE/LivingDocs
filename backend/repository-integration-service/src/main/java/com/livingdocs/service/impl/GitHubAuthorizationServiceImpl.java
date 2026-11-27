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
    private final com.livingdocs.client.GitHubAuthorizationAppCatalog catalog;
    private final com.livingdocs.service.GitHubAuthorizationContextService contexts;

    @Override public GitHubAuthorizationRequirements read(GitHubAuthorizationUser actor, GitHubAuthorizationQuery query, String correlation) {
        UUID workspace = UUID.fromString(query.getWorkspaceId());
        GitHubAuthorizationContextSnapshot context = null;
        if (query.getAuthorizationContextId() == null) GitHubAuthorizationPolicy.workspace(authority.read(actor, correlation), workspace);
        else context = contexts.inspect(actor, workspace, UUID.fromString(query.getAuthorizationContextId()), correlation);
        if (query.getInstallationId() != null) throw new GitHubAuthorizationException(GitHubAuthorizationError.GITHUB_AUTHORIZATION_UNAVAILABLE);
        var profile = catalog.read(correlation);
        // Versioned, reviewed whole-App manifest. These are requirements, never installation grants.
        return new GitHubAuthorizationRequirements(workspace,profile.getAppId(),profile.getSlug(),profile.getName(),
                "github-app-profile-v1",
                List.of(new Permission("REPOSITORY","metadata","read","Repository identity and access metadata"),
                        new Permission("REPOSITORY","contents","write","Read source and controlled published-document export"),
                        new Permission("REPOSITORY","pull_requests","write","Read PR context and create controlled export PR"),
                        new Permission("REPOSITORY","checks","write","Publish source-bound verification feedback")),
                List.of("push","pull_request"), context, context == null ? "USER_AUTHORIZATION_REQUIRED"
                        : "AUTHORIZED".equals(context.getStatus()) ? "INSTALLATION_SELECTION_REQUIRED" : "AUTHORIZATION_PENDING");
    }
}
