package com.livingdocs.dto.request;

import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
public class RepositoryBindingRequest {
    @NotNull private UUID tenantId;
    @NotNull private UUID workspaceId;
    @NotNull private UUID repositoryConnectionId;
}
