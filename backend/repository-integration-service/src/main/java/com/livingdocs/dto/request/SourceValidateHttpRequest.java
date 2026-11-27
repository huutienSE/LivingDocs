package com.livingdocs.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(name = "SourceValidationRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class SourceValidateHttpRequest {
    @NotNull
    @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    @Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    private String repositoryConnectionId;
    @NotNull
    private SourceTargetKind targetKind;
    @NotBlank @Size(max = 512)
    private String targetRef;
    @NotNull @Pattern(regexp = "(?!0{40}$)[0-9a-fA-F]{40}")
    private String boundHeadSha;
    @NotBlank @Size(max = 255)
    private String configRevision;
}
