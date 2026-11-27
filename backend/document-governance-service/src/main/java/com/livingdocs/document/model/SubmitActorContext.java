package com.livingdocs.document.model;

import com.livingdocs.document.exception.SubmitException;
import java.util.*;

/** Effective access returned by S01 for the verified human, not browser role claims. */
public record SubmitActorContext(UUID identityId, UUID tenantId, List<Grant> grants) {
    public SubmitActorContext { grants = List.copyOf(grants); }
    public record Grant(UUID workspaceId, Set<String> roles, Set<String> permissions, Set<UUID> repositoryIds) {
        public Grant { roles = Set.copyOf(roles); permissions = Set.copyOf(permissions); repositoryIds = Set.copyOf(repositoryIds); }
    }
    public void requireAuthor(DocumentVersion version) {
        if (!tenantId.equals(version.tenantId()) || grants.stream().noneMatch(g ->
                g.workspaceId().equals(version.workspaceId()) && g.roles().contains("DEVELOPER")
                && g.permissions().containsAll(Set.of("WORKSPACE_VIEW", "DOCUMENT_AUTHOR"))
                && (version.repositoryConnectionId() == null || g.repositoryIds().contains(version.repositoryConnectionId())))) {
            throw SubmitException.forbidden();
        }
    }
}
