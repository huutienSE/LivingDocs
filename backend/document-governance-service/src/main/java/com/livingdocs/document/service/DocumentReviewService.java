package com.livingdocs.document.service;

import com.livingdocs.document.model.StaffDecisionCommand;
import com.livingdocs.document.model.StaffReviewResult;
import com.livingdocs.document.security.ActorContext;
import java.util.UUID;

/** Records a guarded Staff review decision and its audit atomically. */
public interface DocumentReviewService {
    StaffReviewResult review(UUID versionId, StaffDecisionCommand request, long expectedRevision,
                          String key, ActorContext actor, String userAccessToken, String correlationId);
}
