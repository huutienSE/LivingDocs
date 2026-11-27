package com.livingdocs.identity.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter @RequiredArgsConstructor
public class AuthorizationEvaluationResponse {
    @Schema(allowableValues = {"ALLOW", "DENY"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private final String decision;
    private final String reasonCode;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String action;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID tenantId;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID workspaceId;
    private final UUID repositoryConnectionId;
    @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
    private final Long authzRevision;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final Instant evaluatedAt;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID decisionId;
}
