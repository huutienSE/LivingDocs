package com.livingdocs.service.impl;

import com.livingdocs.client.GitHubAuthorizationAppCatalog;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.model.*;
import com.livingdocs.service.GitHubAuthorizationContextService;
import com.livingdocs.service.GitHubAuthorizationInspectionService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class GitHubAuthorizationInspectionServiceImpl implements GitHubAuthorizationInspectionService {
    private final GitHubAuthorizationContextService contexts;
    private final GitHubAuthorizationAppCatalog catalog;

    @Override public GitHubAuthorizationInspection inspect(GitHubAuthorizationUser actor, UUID workspace, UUID context,
            String installationId, int page, String correlation) {
        // Never probe installation metadata using App privileges before fresh user-scoped association proof.
        var selection = contexts.select(actor, workspace, context, installationId, page, correlation);
        var profile = catalog.read(correlation);
        if (!profile.getAppId().equals(selection.getAppId()))
            throw new GitHubAuthorizationException(GitHubAuthorizationError.AUTHORIZATION_CONFIGURATION_CHANGED);
        var permissions = catalog.installation(profile, selection.getInstallation(), correlation);
        var current = contexts.inspect(actor, workspace, context, correlation);
        Instant checkedAt = Instant.now();
        if (!checkedAt.isBefore(selection.getCredentialExpiresAt()))
            throw new GitHubAuthorizationException(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_EXPIRED);
        GitHubAuthorizationDeadline.check();
        return new GitHubAuthorizationInspection(profile, current, permissions, checkedAt);
    }
}
