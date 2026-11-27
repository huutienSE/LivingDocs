package com.livingdocs.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Value;
import lombok.experimental.Accessors;

/** Kết quả của một lần xác minh. sourceRevision lấy từ quan sát đã được PostgreSQL chấp nhận. */
@Value
@Accessors(fluent = true)
public class SourceValidationResult {
    private final UUID validationId;
    private final UUID tenantId;
    private final UUID workspaceId;
    private final UUID repositoryConnectionId;
    private final TargetKind targetKind;
    private final String targetRef;
    private final String expectedHeadSha;
    private final String configRevision;
    private final ValidationStatus validationStatus;
    private final ValidationReason reason;
    private final String validatedHeadSha;
    private final String sourceRevision;
    private final Instant checkedAt;
    private final Instant expiresAt;
}

