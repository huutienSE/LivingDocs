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
    private final com.livingdocs.service.GitHubAuthorizationInspectionService inspections;

    @Override public GitHubAuthorizationRequirements read(GitHubAuthorizationUser actor, GitHubAuthorizationQuery query, String correlation) {
        UUID workspace = UUID.fromString(query.getWorkspaceId());
        GitHubAuthorizationContextSnapshot context = null;
        GitHubAuthorizationInspection inspection = null;
        if (query.getInstallationId() != null) {
            inspection = inspections.inspect(actor, workspace, UUID.fromString(query.getAuthorizationContextId()),
                    query.getInstallationId(), query.getInstallationPage() == null ? 1 : Integer.parseInt(query.getInstallationPage()), correlation);
            context = inspection.getContext();
        }
        else if (query.getAuthorizationContextId() == null) GitHubAuthorizationPolicy.workspace(authority.read(actor, correlation), workspace);
        else context = contexts.inspect(actor, workspace, UUID.fromString(query.getAuthorizationContextId()), correlation);
        var profile = inspection == null ? catalog.read(correlation) : inspection.getApp();
        // Versioned, reviewed whole-App manifest. These are requirements, never installation grants.
        var required = List.of(new Permission("REPOSITORY","metadata","read","Repository identity and access metadata"),
                        new Permission("REPOSITORY","contents","write","Read source and controlled published-document export"),
                        new Permission("REPOSITORY","pull_requests","write","Read PR context and create controlled export PR"),
                        new Permission("REPOSITORY","checks","write","Publish source-bound verification feedback"));
        var verification = inspection == null ? GitHubAuthorizationVerification.unverified(context == null ? "USER_AUTHORIZATION_REQUIRED"
                : "AUTHORIZED".equals(context.getStatus()) ? "INSTALLATION_SELECTION_REQUIRED" : "AUTHORIZATION_PENDING")
                : GitHubAuthorizationPermissionPolicy.evaluate(inspection, required);
        return new GitHubAuthorizationRequirements(workspace,profile.getAppId(),profile.getSlug(),profile.getName(),
                "github-app-profile-v1", required, List.of("push","pull_request"), context, verification);
    }
}
