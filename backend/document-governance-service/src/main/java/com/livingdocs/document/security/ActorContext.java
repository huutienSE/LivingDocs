package com.livingdocs.document.security;

import com.livingdocs.document.domain.model.DocumentVersion;
import com.livingdocs.document.domain.ReviewException;
import java.util.Set;
import java.util.UUID;

/** Internal trusted context; never deserialized from a request body or user headers. */
public record ActorContext(UUID identityId, UUID tenantId, Set<WorkspaceGrant> grants) {
    public ActorContext { grants = Set.copyOf(grants); }

    public record WorkspaceGrant(UUID workspaceId, Set<String> roles, Set<UUID> repositoryIds) {
        public WorkspaceGrant {
            roles = Set.copyOf(roles);
            repositoryIds = Set.copyOf(repositoryIds);
        }
    }

    public void requireStaffReview(DocumentVersion version) {
        if (!tenantId.equals(version.tenantId()) || grants.stream().noneMatch(g ->
                g.workspaceId().equals(version.workspaceId()) && g.roles().contains("STAFF")
                        && (version.repositoryConnectionId() == null
                            || g.repositoryIds().contains(version.repositoryConnectionId())))) {
            throw ReviewException.forbidden();
        }
    }
}
