package com.livingdocs.model;

import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter @RequiredArgsConstructor
public final class GitHubAuthorizationRequirements {
    private final UUID workspaceId;
    private final String appId;
    private final String appSlug;
    private final String appName;
    private final String revision;
    private final List<Permission> permissions;
    private final List<String> events;
    private final GitHubAuthorizationContextSnapshot context;
    private final String verificationStatus;

    @Getter @RequiredArgsConstructor
    @Schema(name = "GitHubRequiredPermission", requiredProperties = {"category", "name", "level", "purpose"})
    public static final class Permission {
        private final String category;
        private final String name;
        private final String level;
        private final String purpose;
    }
}
