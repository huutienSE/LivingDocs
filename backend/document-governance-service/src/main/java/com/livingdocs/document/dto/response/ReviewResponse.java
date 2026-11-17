package com.livingdocs.document.dto.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.livingdocs.document.model.DocumentState;
import com.livingdocs.document.model.ReviewStage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;
import java.time.Instant;

/** DocumentVersionView: unknown source attributes remain null, never guessed. */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({"documentId", "versionId", "state", "reviewStage", "revision", "sourceBinding"})
@Schema(name = "DocumentVersionView", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"documentId", "versionId", "state", "reviewStage", "revision", "sourceBinding"})
public class ReviewResponse {
    private UUID documentId;
    private UUID versionId;
    private DocumentState state;
    @Schema(nullable = true)
    private ReviewStage reviewStage;
    @Schema(minimum = "0", format = "int64")
    private long revision;
    @Schema(nullable = true)
    private SourceBinding sourceBinding;

    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonPropertyOrder({"repositoryConnectionId", "targetKind", "targetRef", "headSha", "comparisonSha", "configRevision", "capturedAt"})
    @Schema(name = "SourceBinding", nullable = true,
            description = "Persisted source context; unknown legacy fields remain null. Repository-free documents return null.")
    public static class SourceBinding {
        private UUID repositoryConnectionId;
        @Schema(nullable = true, allowableValues = {"PR", "TRACKED_BRANCH"})
        private String targetKind;
        @Schema(nullable = true)
        private String targetRef;
        @Schema(nullable = true)
        private String headSha;
        @Schema(nullable = true)
        private String comparisonSha;
        @Schema(nullable = true)
        private String configRevision;
        @Schema(nullable = true)
        private Instant capturedAt;
    }
}
