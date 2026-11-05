package com.livingdocs.document.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewDecision(UUID id, UUID tenantId, UUID versionId, UUID reviewerId,
                             ReviewAction decision, String feedback, int roundNo,
                             String idempotencyKey, long expectedRevision, long resultingRevision,
                             DocumentState resultingState, ReviewStage resultingStage,
                             List<UUID> evidenceRefs, Instant decidedAt) {
    public ReviewDecision {
        evidenceRefs = List.copyOf(evidenceRefs);
    }
}
