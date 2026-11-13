package com.livingdocs.identity.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import lombok.Getter;

/** OpenAPI model for the RFC 9457 body serialized by Spring's ProblemDetail. */
@Getter
@Schema(name = "Problem", description = "RFC 9457 Problem Details with LivingDocs extensions")
public class ProblemResponse {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private URI type;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private int status;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String detail;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private URI instance;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String correlationId;
}
