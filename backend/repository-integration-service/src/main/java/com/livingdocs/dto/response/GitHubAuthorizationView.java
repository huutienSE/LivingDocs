package com.livingdocs.dto.response;

import com.livingdocs.model.GitHubAuthorizationRequirements.Permission;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Schema(requiredProperties = {"workspaceId", "integrationType", "app", "requirementsRevision",
        "requiredPermissions", "requiredEvents", "context", "verification"})
public class GitHubAuthorizationView {
    private final UUID workspaceId;
    @Schema(allowableValues = "GITHUB_APP")
    private final String integrationType = "GITHUB_APP";
    private final App app;
    private final String requirementsRevision;
    private final List<Permission> requiredPermissions;
    private final List<String> requiredEvents;
    private final Context context;
    private final Verification verification;

    public GitHubAuthorizationView(UUID workspaceId, App app, String requirementsRevision,
                                   List<Permission> requiredPermissions, List<String> requiredEvents, Context context, Verification verification) {
        this.workspaceId = workspaceId;
        this.app = app;
        this.requirementsRevision = requirementsRevision;
        this.requiredPermissions = List.copyOf(requiredPermissions);
        this.requiredEvents = List.copyOf(requiredEvents);
        this.context = context; this.verification = verification;
    }

    @Getter @RequiredArgsConstructor
    @Schema(name = "GitHubAppView", requiredProperties = {"appId", "slug", "name", "installationUrl"})
    public static final class App {
        private final String appId;
        private final String slug;
        private final String name;
        private final String installationUrl;
    }
    @Getter @RequiredArgsConstructor
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(name = "GitHubAuthorizationContextView", requiredProperties = "status")
    public static final class Context {
        @Schema(allowableValues = {"NONE", "PENDING", "AUTHORIZED"})
        private final String status;
        private final UUID contextId;
        private final java.time.Instant expiresAt;
    }
    @Getter @RequiredArgsConstructor
    @Schema(name = "GitHubAuthorizationVerificationView", requiredProperties = "status")
    public static final class Verification {
        @Schema(allowableValues = {"USER_AUTHORIZATION_REQUIRED", "AUTHORIZATION_PENDING", "INSTALLATION_SELECTION_REQUIRED"})
        private final String status;
    }
}
