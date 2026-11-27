package com.livingdocs.document.dto.request;

import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@io.swagger.v3.oas.annotations.media.Schema(additionalProperties = io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
public class DraftContentRequest {
    @NotNull @Size(max = 100000) private String content;
}
