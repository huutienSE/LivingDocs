package com.livingdocs.document.model;

import java.util.UUID;

/** Resource scope resolved by S04 before a Document Version exists. */
public record AuthorizationScope(UUID tenantId, UUID workspaceId, UUID repositoryConnectionId) {
    public static AuthorizationScope of(DocumentVersion version) {
        return new AuthorizationScope(version.tenantId(), version.workspaceId(), version.repositoryConnectionId());
    }
}
