package com.livingdocs.identity.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter @RequiredArgsConstructor
public class ServiceTokenResponse {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String accessToken;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String tokenType;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final long expiresIn;
}
