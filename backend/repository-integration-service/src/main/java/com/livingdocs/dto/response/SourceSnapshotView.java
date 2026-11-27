package com.livingdocs.dto.response;

import com.livingdocs.dto.request.SourceTargetKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Getter;

@Getter
@Schema(requiredProperties = {"tenantId", "workspaceId", "repositoryConnectionId", "targetKind", "targetRef", "resolvedSha", "configRevision"})
public class SourceSnapshotView {
    private final UUID tenantId;
    private final UUID workspaceId;
    private final UUID repositoryConnectionId;
    private final SourceTargetKind targetKind;
    private final String targetRef;
    private final String resolvedSha;
    private final String configRevision;

    public SourceSnapshotView(UUID tenantId, UUID workspaceId, UUID repositoryConnectionId, SourceTargetKind targetKind,
                              String targetRef, String resolvedSha, String configRevision) {
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
        this.repositoryConnectionId = repositoryConnectionId;
        this.targetKind = targetKind;
        this.targetRef = targetRef;
        this.resolvedSha = resolvedSha;
        this.configRevision = configRevision;
    }
}
