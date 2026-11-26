package com.livingdocs.identity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class AuthorizationEvaluationRequest {
    @NotNull @Schema(allowableValues = {"REPOSITORY_CONNECT", "DOCUMENT_AUTHOR",
            "DOCUMENT_STAFF_REVIEW", "DOCUMENT_FINAL_APPROVE", "DOCUMENT_PUBLISH"})
    private String action;
    @NotNull private UUID tenantId;
    @NotNull private UUID workspaceId;
    @Schema(description = "Omit for REPOSITORY_CONNECT; required for every DOCUMENT_* action")
    private UUID repositoryConnectionId;
}
