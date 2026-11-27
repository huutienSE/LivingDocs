package com.livingdocs.document.dto.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.livingdocs.document.model.DocumentState;
import com.livingdocs.document.model.ReviewStage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** API-122 data: one page of versions waiting for a Manager decision. */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonPropertyOrder({"items", "page", "size", "totalElements", "totalPages"})
@Schema(name = "ApprovalQueueView", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items", "page", "size", "totalElements", "totalPages"})
public class ApprovalQueueResponse {
    private List<Item> items;
    @Schema(minimum = "0")
    private int page;
    @Schema(minimum = "1")
    private int size;
    @Schema(minimum = "0", format = "int64")
    private long totalElements;
    @Schema(minimum = "0")
    private int totalPages;

    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    @JsonPropertyOrder({"documentId", "versionId", "documentTitle", "versionNo", "workspaceId", "repositoryConnectionId",
            "sourceSha", "revision", "state", "reviewStage", "staffPassedAt"})
    @Schema(name = "ApprovalQueueItem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"documentId", "versionId", "documentTitle", "versionNo", "workspaceId",
                    "repositoryConnectionId", "sourceSha", "revision", "state", "reviewStage", "staffPassedAt"})
    public static class Item {
        private UUID documentId;
        private UUID versionId;
        private String documentTitle;
        @Schema(minimum = "1")
        private int versionNo;
        private UUID workspaceId;
        @Schema(nullable = true)
        private UUID repositoryConnectionId;
        @Schema(nullable = true)
        private String sourceSha;
        @Schema(minimum = "0", format = "int64")
        private long revision;
        private DocumentState state;
        private ReviewStage reviewStage;
        @Schema(nullable = true, description = "Latest Staff PASS time; null for legacy rows without a PASS decision.")
        private Instant staffPassedAt;
    }
}
