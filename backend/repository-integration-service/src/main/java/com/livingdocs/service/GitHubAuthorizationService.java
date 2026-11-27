package com.livingdocs.service;

import com.livingdocs.dto.request.GitHubAuthorizationQuery;
import com.livingdocs.model.GitHubAuthorizationRequirements;
import com.livingdocs.model.GitHubAuthorizationUser;

public interface GitHubAuthorizationService {
    GitHubAuthorizationRequirements read(GitHubAuthorizationUser actor, GitHubAuthorizationQuery query, String correlation);
}
