package com.livingdocs.dto.response;

import com.livingdocs.dto.request.SourceTargetKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Getter
@Schema(requiredProperties = {"validationStatus", "reason", "validationId", "tenantId", "workspaceId",
        "repositoryConnectionId", "targetKind", "targetRef", "validatedHeadSha", "configRevision",
        "sourceRevision", "checkedAt", "expiresAt"})
public class SourceValidationReceipt {
    @Schema(allowableValues = "PASS") private final String validationStatus;
    @Schema(allowableValues = "CURRENT") private final String reason;
    private final UUID validationId;
    private final UUID tenantId;
    private final UUID workspaceId;
    private final UUID repositoryConnectionId;
    private final SourceTargetKind targetKind;
    private final String targetRef;
    private final String validatedHeadSha;
    private final String configRevision;
    private final String sourceRevision;
    private final Instant checkedAt;
    private final Instant expiresAt;

    public SourceValidationReceipt(String validationStatus, String reason, UUID validationId, UUID tenantId,
                                    UUID workspaceId, UUID repositoryConnectionId, SourceTargetKind targetKind,
                                    String targetRef, String validatedHeadSha, String configRevision,
                                    String sourceRevision, Instant checkedAt, Instant expiresAt) {
        this.validationStatus = validationStatus;
        this.reason = reason;
        this.validationId = validationId;
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
        this.repositoryConnectionId = repositoryConnectionId;
        this.targetKind = targetKind;
        this.targetRef = targetRef;
        this.validatedHeadSha = validatedHeadSha;
        this.configRevision = configRevision;
        this.sourceRevision = sourceRevision;
        this.checkedAt = checkedAt;
        this.expiresAt = expiresAt;
    }
}
