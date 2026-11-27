package com.livingdocs.document.service;

import com.livingdocs.document.model.ApprovalContext;
import com.livingdocs.document.model.ApprovalQueuePage;
import com.livingdocs.document.security.ActorContext;
import java.util.UUID;

/** Read-only Manager use cases: API-122 approval queue and API-123 approval context. */
public interface DocumentApprovalQueryService {
    /** Versions at IN_REVIEW / MANAGER_APPROVAL inside the Manager's tenant/workspace/repository scope. */
    ApprovalQueuePage queue(ActorContext actor, int page, int size);

    /** Version, content, Staff/Manager decisions and evidence; 404 if absent in tenant, 403 if outside scope. */
    ApprovalContext context(UUID versionId, ActorContext actor);
}
