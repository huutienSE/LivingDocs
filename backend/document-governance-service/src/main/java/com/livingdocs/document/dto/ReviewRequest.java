package com.livingdocs.document.dto;

import com.livingdocs.document.domain.ReviewAction;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record ReviewRequest(@NotNull ReviewAction decision, String feedback, List<@NotNull UUID> evidenceRefs) {
    public ReviewRequest {
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
