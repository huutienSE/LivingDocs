package com.livingdocs.document.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A validated S01 API-039 ALLOW; contains no user or workload credentials. */
public record AuthorizationDecision(UUID decisionId, String action, UUID tenantId, UUID workspaceId,
                                          UUID repositoryConnectionId, long authzRevision, Instant evaluatedAt) {
    public boolean appliesTo(DocumentVersion version) {
        return appliesTo(version, "DOCUMENT_STAFF_REVIEW");
    }
    public boolean appliesTo(DocumentVersion version, String expectedAction) {
        return expectedAction.equals(action) && tenantId.equals(version.tenantId())
                && workspaceId.equals(version.workspaceId())
                && Objects.equals(repositoryConnectionId, version.repositoryConnectionId());
    }
}
