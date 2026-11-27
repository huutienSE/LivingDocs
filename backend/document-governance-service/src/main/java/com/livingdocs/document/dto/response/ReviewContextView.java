package com.livingdocs.document.dto.response;

import com.livingdocs.document.model.ContentFormat;
import com.livingdocs.document.model.DocumentState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/** Canonical DB snapshot. Unknown legacy content is null, never replaced by an artifact reference. */
@Getter @Builder
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "documentTitle", "versionNo", "content", "evidence", "reviewRound"})
public class ReviewContextView {
    private final ReviewResponse version;
    private final String documentTitle;
    @Schema(minimum = "1") private final int versionNo;
    @Schema(nullable = true) private final String content;
    @Schema(nullable = true) private final ContentFormat contentFormat;
    @Schema(nullable = true) private final String contentRef;
    @Schema(nullable = true, minimum = "1") private final Integer contentRevisionNo;
    @Schema(nullable = true) private final UUID submittedBy;
    @Schema(nullable = true) private final Instant submittedAt;
    @Schema(minimum = "0", description = "Highest persisted review round, zero when no decision has been recorded")
    private final int reviewRound;
    @Schema(nullable = true) private final Template template;
    @Schema(nullable = true) private final Baseline baseline;
    private final List<Evidence> evidence;

    @Getter @Builder
    @Schema(name = "ReviewContextEvidence", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"ref", "kind", "attachedAt"})
    public static class Evidence {
        @Schema(description = "S04 document_evidence_id UUID belonging to this tenant and version") private final UUID ref;
        private final String kind;
        @Schema(nullable = true) private final String sourceRef;
        @Schema(nullable = true) private final String artifactRef;
        private final Instant attachedAt;
    }

    @Getter @Builder
    @Schema(name = "ReviewContextTemplate", nullable = true, additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"templateId", "templateVersionId", "name", "documentType", "versionNo", "structureRef", "formattingRules"})
    public static class Template {
        private final UUID templateId;
        private final UUID templateVersionId;
        private final String name;
        private final String documentType;
        @Schema(minimum = "1") private final int versionNo;
        private final String structureRef;
        @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
        private final Map<String, Object> formattingRules;
        @Schema(nullable = true) private final Instant publishedAt;
    }

    @Getter @Builder
    @Schema(name = "ReviewContextBaseline", nullable = true, additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"versionId", "versionNo", "state"})
    public static class Baseline {
        private final UUID versionId;
        @Schema(minimum = "1") private final int versionNo;
        private final DocumentState state;
        @Schema(nullable = true) private final String sourceSha;
        @Schema(nullable = true) private final String content;
        @Schema(nullable = true) private final ContentFormat contentFormat;
        @Schema(nullable = true) private final String contentRef;
        @Schema(nullable = true, minimum = "1") private final Integer contentRevisionNo;
    }
}
