package com.livingdocs.document.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Validated authority snapshot; no use-case permission filtering here. */
public record S01AccessContext(UUID identityId, UUID tenantId, long authzRevision,
                               Instant evaluatedAt, List<Grant> grants) {
    public S01AccessContext { grants = List.copyOf(grants); }
    public record Grant(UUID workspaceId, Set<String> roles, Set<String> permissions, Set<UUID> repositoryIds) {
        public Grant { roles = Set.copyOf(roles); permissions = Set.copyOf(permissions); repositoryIds = Set.copyOf(repositoryIds); }
    }
}
