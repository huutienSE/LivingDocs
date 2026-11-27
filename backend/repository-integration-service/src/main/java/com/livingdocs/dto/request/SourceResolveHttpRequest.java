package com.livingdocs.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "SourceResolveRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class SourceResolveHttpRequest {
    @NotNull
    @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    @Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    private String repositoryConnectionId;
    @NotNull
    private SourceTargetKind targetKind;
    @NotBlank @Size(max = 512)
    private String targetRef;
    @Pattern(regexp = "(?!0{40}$)[0-9a-fA-F]{40}")
    private String explicitSha;
}
