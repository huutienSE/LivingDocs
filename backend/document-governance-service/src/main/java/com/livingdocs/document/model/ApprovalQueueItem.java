package com.livingdocs.document.model;

import java.time.Instant;
import java.util.UUID;

/** One version waiting at IN_REVIEW / MANAGER_APPROVAL inside the Manager's scope. */
public record ApprovalQueueItem(UUID documentId, UUID versionId, String documentTitle, int versionNo,
                                UUID workspaceId, UUID repositoryConnectionId, String sourceSha, long revision,
                                DocumentState state, ReviewStage reviewStage, Instant staffPassedAt) {
}
