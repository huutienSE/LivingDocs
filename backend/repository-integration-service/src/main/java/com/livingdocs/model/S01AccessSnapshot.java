package com.livingdocs.model;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;

/** A validated S01 projection, not a cached authorization decision. */
@Getter
public final class S01AccessSnapshot {
    private final UUID tenantId;
    private final Map<UUID, WorkspaceGrant> workspaceGrants;

    public S01AccessSnapshot(UUID tenantId, Map<UUID, WorkspaceGrant> workspaceGrants) {
        this.tenantId = tenantId;
        this.workspaceGrants = Map.copyOf(workspaceGrants);
    }

    @Getter
    public static final class WorkspaceGrant {
        private final Set<String> roles;
        private final Set<String> permissions;

        public WorkspaceGrant(Set<String> roles, Set<String> permissions) {
            this.roles = Set.copyOf(roles);
            this.permissions = Set.copyOf(permissions);
        }
    }
}
