package com.livingdocs.document.dto.response;

import com.livingdocs.document.model.DriftSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter @Builder
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"versionId", "documentId", "documentTitle", "versionNo", "state", "reviewStage", "workspaceId"})
public class ReviewQueueItem {
    private final UUID versionId;
    private final UUID documentId;
    private final String documentTitle;
    @Schema(minimum = "1") private final int versionNo;
    @Schema(allowableValues = "IN_REVIEW") private final String state;
    @Schema(allowableValues = "STAFF_REVIEW") private final String reviewStage;
    private final UUID workspaceId;
    @Schema(nullable = true) private final UUID repositoryConnectionId;
    @Schema(nullable = true) private final UUID templateId;
    @Schema(nullable = true) private final UUID templateVersionId;
    @Schema(nullable = true) private final String sourceSha;
    @Schema(nullable = true) private final DriftSeverity driftSeverity;
    @Schema(nullable = true) private final Instant submittedAt;
    @Schema(nullable = true) private final UUID submittedBy;
}
