package com.livingdocs.document.dto.request;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class SubmitReviewRequest{
     @Schema(nullable = true) @Size(max = 2000) private String note;
}
