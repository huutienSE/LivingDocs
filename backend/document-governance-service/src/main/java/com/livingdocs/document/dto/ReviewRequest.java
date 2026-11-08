package com.livingdocs.document.dto;

import com.livingdocs.document.domain.ReviewAction;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import com.livingdocs.document.domain.ReviewException;

public record ReviewRequest(@NotNull ReviewAction decision, String feedback, List<@NotNull UUID> evidenceRefs) {
    // Local request budget: configurable deployment work can replace this reviewed cap.
    public static final int MAX_EVIDENCE_REFS = 100;

    public ReviewRequest {
        evidenceRefs = evidenceRefs == null ? List.of() : evidenceRefs;
        if (evidenceRefs.size() > MAX_EVIDENCE_REFS)
            throw new ReviewException(422, "EVIDENCE_LIMIT_EXCEEDED", "At most 100 evidence references are accepted.");
        if (new HashSet<>(evidenceRefs).size() != evidenceRefs.size())
            throw new ReviewException(422, "DUPLICATE_EVIDENCE", "Evidence references must be unique.");
        evidenceRefs = List.copyOf(evidenceRefs);
    }
}
