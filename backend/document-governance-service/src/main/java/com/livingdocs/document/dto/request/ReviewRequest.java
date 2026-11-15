package com.livingdocs.document.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.livingdocs.document.model.ReviewAction;
import com.livingdocs.document.model.StaffDecisionCommand;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

@Getter
@EqualsAndHashCode
@Schema(name = "StaffDecisionRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class ReviewRequest {
    // Local request budget: configurable deployment work can replace this reviewed cap.
    public static final int MAX_EVIDENCE_REFS = StaffDecisionCommand.MAX_EVIDENCE_REFS;

    @NotNull
    private final ReviewAction decision;
    @Schema(nullable = true, description = "Required and nonblank for RETURN_FOR_REVISION; stored verbatim.")
    private final String feedback;
    @ArraySchema(arraySchema = @Schema(nullable = true), maxItems = MAX_EVIDENCE_REFS, uniqueItems = true,
            schema = @Schema(type = "string", format = "uuid"))
    private final List<@NotNull UUID> evidenceRefs;

    @JsonCreator
    public ReviewRequest(@JsonProperty("decision") ReviewAction decision,
                         @JsonProperty("feedback") String feedback,
                         @JsonProperty("evidenceRefs") List<UUID> evidenceRefs) {
        var command = new StaffDecisionCommand(decision, feedback, evidenceRefs);
        this.decision = command.decision();
        this.feedback = command.feedback();
        this.evidenceRefs = command.evidenceRefs();
    }
}
