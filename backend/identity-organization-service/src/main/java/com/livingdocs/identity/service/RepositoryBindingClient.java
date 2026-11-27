package com.livingdocs.identity.service;

import java.time.Instant;
import java.util.UUID;

/** S02 is the sole authority for a Repository Connection binding. */
public interface RepositoryBindingClient {
    BindingEvidence lookup(UUID tenantId, UUID workspaceId, UUID connectionId);

    record BindingEvidence(UUID tenantId, UUID workspaceId, UUID connectionId,
                           String status, long revision, Instant observedAt) {
        public boolean activeFor(UUID tenant, UUID workspace, UUID connection, long minimumRevision) {
            return tenant.equals(tenantId) && workspace.equals(workspaceId)
                    && connection.equals(connectionId) && "ACTIVE".equals(status)
                    && revision > 0 && revision >= minimumRevision && observedAt != null
                    && !observedAt.isAfter(Instant.now().plusSeconds(5))
                    && !observedAt.isBefore(Instant.now().minusSeconds(5));
        }
    }

    class Unavailable extends RuntimeException {
        public Unavailable() { super("Repository binding authority unavailable"); }
    }
}
