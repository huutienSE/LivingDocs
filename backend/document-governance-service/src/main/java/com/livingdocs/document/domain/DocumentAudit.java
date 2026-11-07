package com.livingdocs.document.domain;

import java.time.Instant;
import java.util.UUID;

public record DocumentAudit(UUID id, DocumentVersion before, DocumentVersion after,
                            UUID actorId, ReviewDecision decision, String correlationId, Instant occurredAt,
                            SourceReviewValidation sourceValidation) {}
