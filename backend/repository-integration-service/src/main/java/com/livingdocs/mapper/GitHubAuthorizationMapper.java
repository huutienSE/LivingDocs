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
    GitHubAuthorizationView toView(GitHubAuthorizationRequirements source);
}
