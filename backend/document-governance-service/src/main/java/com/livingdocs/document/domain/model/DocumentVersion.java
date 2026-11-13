package com.livingdocs.document.domain.model;

import java.util.UUID;

/** Persisted version and its scope, resolved only through S04-owned tables. */
public record DocumentVersion(UUID id, UUID tenantId, UUID documentId, UUID workspaceId,
                              UUID repositoryConnectionId, String sourceSha,
                              DocumentState state, ReviewStage reviewStage, long revision) {
    public DocumentVersion reviewed(ReviewAction action) {
        return new DocumentVersion(id, tenantId, documentId, workspaceId, repositoryConnectionId,
                sourceSha, action == ReviewAction.PASS ? DocumentState.IN_REVIEW : DocumentState.DRAFT,
                action == ReviewAction.PASS ? ReviewStage.MANAGER_APPROVAL : null, revision + 1);
    }
}
