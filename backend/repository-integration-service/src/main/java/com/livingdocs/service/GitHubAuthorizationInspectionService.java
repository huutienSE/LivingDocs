package com.livingdocs.service;

import com.livingdocs.model.GitHubAuthorizationInspection;
import com.livingdocs.model.GitHubAuthorizationUser;
import java.util.UUID;

public interface GitHubAuthorizationInspectionService {
    GitHubAuthorizationInspection inspect(GitHubAuthorizationUser actor, UUID workspace, UUID context,
            String installationId, int page, String correlation);
}
