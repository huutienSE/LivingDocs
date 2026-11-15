package com.livingdocs.document.service;

import com.livingdocs.document.dto.request.ReviewRequest;
import com.livingdocs.document.dto.response.ReviewResponse;
import com.livingdocs.document.security.ActorContext;
import java.util.UUID;

public interface DocumentReviewService {
    ReviewResponse review(UUID versionId, ReviewRequest request, long expectedRevision,
                          String key, ActorContext actor, String correlationId);
}
