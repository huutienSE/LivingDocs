package com.livingdocs.document.model;

import com.livingdocs.document.exception.DuplicateReviewEvidenceException;
import com.livingdocs.document.exception.ReviewEvidenceLimitExceededException;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Transport-independent input shared by HTTP and other callers of Staff Review. */
public record StaffDecisionCommand(ReviewAction decision, String feedback, List<UUID> evidenceRefs) {
    public static final int MAX_EVIDENCE_REFS = 100;

    public StaffDecisionCommand {
        var refs = evidenceRefs == null ? List.<UUID>of() : evidenceRefs;
        if (refs.size() > MAX_EVIDENCE_REFS) throw new ReviewEvidenceLimitExceededException();
        if (new HashSet<>(refs).size() != refs.size()) throw new DuplicateReviewEvidenceException();
        evidenceRefs = List.copyOf(refs);
    }
}
