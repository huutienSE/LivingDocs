package com.livingdocs.document.dto.request;

import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.ReviewAction;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import com.livingdocs.document.exception.ReviewException;

public record ReviewRequest(@NotNull ReviewAction decision, String feedback, List<@NotNull UUID> evidenceRefs) {
    // Local request budget: configurable deployment work can replace this reviewed cap.
    public static final int MAX_EVIDENCE_REFS = 100;

    public ReviewRequest {
        evidenceRefs = evidenceRefs == null ? List.of() : evidenceRefs;
        if (evidenceRefs.size() > MAX_EVIDENCE_REFS)
            throw new ReviewEvidenceLimitExceededException();
        if (new HashSet<>(evidenceRefs).size() != evidenceRefs.size())
            throw new DuplicateReviewEvidenceException();
        evidenceRefs = List.copyOf(evidenceRefs);
    }
}
