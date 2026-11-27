package com.livingdocs.document.dto.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** API-123 data: version, current content reference, Staff/Manager decision history and evidence. */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonPropertyOrder({"version", "documentTitle", "versionNo", "workspaceId", "awaitingManagerDecision", "currentContent",
        "staffDecisions", "approvalDecisions", "evidence"})
@Schema(name = "ApprovalContextView", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"version", "documentTitle", "versionNo", "workspaceId", "awaitingManagerDecision",
                "currentContent", "staffDecisions", "approvalDecisions", "evidence"})
public class ApprovalContextResponse {
    private ReviewResponse version;
    private String documentTitle;
    @Schema(minimum = "1")
    private int versionNo;
    private UUID workspaceId;
    @Schema(description = "True only at IN_REVIEW / MANAGER_APPROVAL. It does not evaluate No Self Final Approval "
            + "or fresh S01 authorization; API-124 enforces those when a decision is submitted.")
    private boolean awaitingManagerDecision;
    @Schema(nullable = true)
    private Content currentContent;
    private List<StaffDecision> staffDecisions;
    private List<ManagerDecision> approvalDecisions;
    private List<Evidence> evidence;

    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    @JsonPropertyOrder({"revisionNo", "contentRef", "editorIdentityId", "createdAt"})
    @Schema(name = "ApprovalContent", nullable = true, additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"revisionNo", "contentRef", "editorIdentityId", "createdAt"})
    public static class Content {
        @Schema(minimum = "1")
        private int revisionNo;
        private String contentRef;
        private UUID editorIdentityId;
        private Instant createdAt;
    }

    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    @JsonPropertyOrder({"decisionId", "roundNo", "decision", "reviewerIdentityId", "reason", "decidedAt"})
    @Schema(name = "ApprovalStaffDecision", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"decisionId", "roundNo", "decision", "reviewerIdentityId", "reason", "decidedAt"})
    public static class StaffDecision {
        private UUID decisionId;
        @Schema(minimum = "1")
        private int roundNo;
        @Schema(allowableValues = {"PASS", "RETURN_FOR_REVISION"})
        private String decision;
        private UUID reviewerIdentityId;
        @Schema(nullable = true)
        private String reason;
        private Instant decidedAt;
    }

    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    @JsonPropertyOrder({"decisionId", "roundNo", "decision", "approverIdentityId", "reason", "decidedAt"})
    @Schema(name = "ApprovalManagerDecision", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"decisionId", "roundNo", "decision", "approverIdentityId", "reason", "decidedAt"})
    public static class ManagerDecision {
        private UUID decisionId;
        @Schema(minimum = "1")
        private int roundNo;
        @Schema(allowableValues = {"FINAL_APPROVE", "RETURN_TO_STAFF", "RETURN_TO_DEVELOPER", "REJECT"})
        private String decision;
        private UUID approverIdentityId;
        @Schema(nullable = true)
        private String reason;
        private Instant decidedAt;
    }

    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    @JsonPropertyOrder({"evidenceId", "evidenceType", "sourceRef", "artifactRef", "attachedAt"})
    @Schema(name = "ApprovalEvidence", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"evidenceId", "evidenceType", "sourceRef", "artifactRef", "attachedAt"})
    public static class Evidence {
        private UUID evidenceId;
        private String evidenceType;
        @Schema(nullable = true)
        private String sourceRef;
        @Schema(nullable = true)
        private String artifactRef;
        private Instant attachedAt;
    }
}
