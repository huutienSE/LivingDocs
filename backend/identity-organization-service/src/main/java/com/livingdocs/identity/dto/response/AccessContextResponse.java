package com.livingdocs.identity.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AccessContextResponse {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID identityId;
    @Schema(nullable = true)
    private final UUID tenantId;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final long authzRevision;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final Instant evaluatedAt;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<String> tenantRoles;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<String> tenantPermissions;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final List<WorkspaceGrant> workspaceGrants;

    @Getter
    @RequiredArgsConstructor
    public static class WorkspaceGrant {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private final UUID workspaceId;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private final List<String> roles;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private final List<String> permissions;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private final List<UUID> repositoryIds;
    }
}
