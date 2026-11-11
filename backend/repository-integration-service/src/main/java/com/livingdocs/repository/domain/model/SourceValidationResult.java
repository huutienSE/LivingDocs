package com.livingdocs.repository.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Quan sát trong một lần xác minh; chưa phải biên nhận đã ký hoặc lưu bền vững. */
public record SourceValidationResult(UUID validationId, UUID tenantId, UUID workspaceId, UUID repositoryConnectionId,
                                     TargetKind targetKind, String targetRef, String expectedHeadSha, String configRevision,
                                     ValidationStatus validationStatus, ValidationReason reason, String validatedHeadSha,
                                     String sourceRevision, Instant checkedAt, Instant expiresAt) {}

