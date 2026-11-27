package com.livingdocs.dto.response;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Getter
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"tenantId","workspaceId","repositoryConnectionId","status","bindingRevision","observedAt"},
        additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
public final class RepositoryBindingView {
    private final UUID tenantId;
    private final UUID workspaceId;
    private final UUID repositoryConnectionId;
    private final String status;
    private final long bindingRevision;
    private final Instant observedAt;
    public RepositoryBindingView(UUID tenantId, UUID workspaceId, UUID repositoryConnectionId, String status,
            long bindingRevision, Instant observedAt) {
        this.tenantId=tenantId; this.workspaceId=workspaceId; this.repositoryConnectionId=repositoryConnectionId;
        this.status=status; this.bindingRevision=bindingRevision; this.observedAt=observedAt;
    }
}
