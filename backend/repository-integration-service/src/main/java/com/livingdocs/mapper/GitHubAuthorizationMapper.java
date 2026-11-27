package com.livingdocs.mapper;

import com.livingdocs.dto.response.GitHubAuthorizationView;
import com.livingdocs.model.GitHubAuthorizationRequirements;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface GitHubAuthorizationMapper {
    @Mapping(target = "workspaceId", expression = "java(source.getWorkspaceId())")
    @Mapping(target = "app", expression = "java(new GitHubAuthorizationView.App(source.getAppId(), source.getAppSlug(), source.getAppName(), \"https://github.com/apps/\" + source.getAppSlug() + \"/installations/new\"))")
    @Mapping(target = "requirementsRevision", expression = "java(source.getRevision())")
    @Mapping(target = "requiredPermissions", expression = "java(source.getPermissions())")
    @Mapping(target = "requiredEvents", expression = "java(source.getEvents())")
    @Mapping(target = "context", expression = "java(source.getContext() == null ? new GitHubAuthorizationView.Context(\"NONE\", null, null) : new GitHubAuthorizationView.Context(source.getContext().getStatus(), source.getContext().getContextId(), source.getContext().getExpiresAt()))")
    @Mapping(target = "verification", expression = "java(new GitHubAuthorizationView.Verification(source.getVerificationStatus()))")
    GitHubAuthorizationView toView(GitHubAuthorizationRequirements source);

    @Mapping(target = "contextId", expression = "java(source.getContextId())")
    @Mapping(target = "expiresAt", expression = "java(source.getExpiresAt())")
    @Mapping(target = "authorizationUrl", expression = "java(source.getAuthorizationUrl())")
    @Mapping(target = "installationUrl", expression = "java(source.getInstallationUrl())")
    com.livingdocs.dto.response.GitHubAuthorizationContextView toContext(com.livingdocs.model.GitHubAuthorizationContextStart source);

    @Mapping(target = "items", expression = "java(source.getItems().stream().map(this::toInstallation).toList())")
    @Mapping(target = "page", expression = "java(source.getPage())")
    @Mapping(target = "hasNext", expression = "java(source.isHasNext())")
    com.livingdocs.dto.response.GitHubInstallationListView toInstallations(com.livingdocs.model.GitHubAuthorizationDiscovery.Page<com.livingdocs.model.GitHubAuthorizationDiscovery.Installation> source);

    @Mapping(target = "installationId", expression = "java(source.getInstallationId())")
    @Mapping(target = "accountId", expression = "java(source.getAccountId())")
    @Mapping(target = "accountLogin", expression = "java(source.getAccountLogin())")
    @Mapping(target = "repositorySelection", expression = "java(source.getRepositorySelection())")
    @Mapping(target = "suspended", expression = "java(source.isSuspended())")
    com.livingdocs.dto.response.GitHubInstallationListView.Item toInstallation(com.livingdocs.model.GitHubAuthorizationDiscovery.Installation source);

    @Mapping(target = "items", expression = "java(source.getItems().stream().map(this::toRepository).toList())")
    @Mapping(target = "page", expression = "java(source.getPage())")
    @Mapping(target = "hasNext", expression = "java(source.isHasNext())")
    com.livingdocs.dto.response.GitHubRepositoryListView toRepositories(com.livingdocs.model.GitHubAuthorizationDiscovery.Page<com.livingdocs.model.GitHubAuthorizationDiscovery.Repository> source);

    @Mapping(target = "repositoryId", expression = "java(source.getRepositoryId())")
    @Mapping(target = "fullName", expression = "java(source.getFullName())")
    @Mapping(target = "canRead", expression = "java(source.isCanRead())")
    com.livingdocs.dto.response.GitHubRepositoryListView.Item toRepository(com.livingdocs.model.GitHubAuthorizationDiscovery.Repository source);
}
