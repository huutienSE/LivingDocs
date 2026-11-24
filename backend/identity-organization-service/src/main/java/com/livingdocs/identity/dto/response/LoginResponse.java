package com.livingdocs.identity.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
public class LoginResponse {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String accessToken;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final String tokenType = "Bearer";
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final long expiresIn;

    public LoginResponse(String accessToken, long expiresIn) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
    }
}
