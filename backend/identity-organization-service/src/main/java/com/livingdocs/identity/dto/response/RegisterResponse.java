package com.livingdocs.identity.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RegisterResponse {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final UUID identityId;
}
