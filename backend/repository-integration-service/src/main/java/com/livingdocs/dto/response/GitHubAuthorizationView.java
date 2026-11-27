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
    private final Context context = new Context("NONE");
    private final Verification verification = new Verification("USER_AUTHORIZATION_REQUIRED");

    public GitHubAuthorizationView(UUID workspaceId, App app, String requirementsRevision,
                                   List<Permission> requiredPermissions, List<String> requiredEvents) {
        this.workspaceId = workspaceId;
        this.app = app;
        this.requirementsRevision = requirementsRevision;
        this.requiredPermissions = List.copyOf(requiredPermissions);
        this.requiredEvents = List.copyOf(requiredEvents);
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
    @Schema(name = "GitHubAuthorizationContextView", requiredProperties = "status")
    public static final class Context {
        @Schema(allowableValues = "NONE")
        private final String status;
    }
    @Getter @RequiredArgsConstructor
    @Schema(name = "GitHubAuthorizationVerificationView", requiredProperties = "status")
    public static final class Verification {
        @Schema(allowableValues = "USER_AUTHORIZATION_REQUIRED")
        private final String status;
    }
}
