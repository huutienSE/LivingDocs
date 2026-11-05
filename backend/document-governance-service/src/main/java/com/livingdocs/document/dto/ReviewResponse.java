package com.livingdocs.document.dto;

import com.livingdocs.document.domain.*;
import java.util.UUID;

public record ReviewResponse(UUID versionId, DocumentState state, ReviewStage reviewStage,
                             long revision) {
    public static ReviewResponse from(ReviewDecision decision) {
        return new ReviewResponse(decision.versionId(), decision.resultingState(), decision.resultingStage(),
                decision.resultingRevision());
    }

    public static ReviewResponse from(DocumentVersion version) {
        return new ReviewResponse(version.id(), version.state(), version.reviewStage(), version.revision());
    }
}
