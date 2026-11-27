package com.livingdocs.document.model;
import java.time.Instant;
import java.util.UUID;

public record VersionDetails(UUID documentId, UUID versionId, int versionNo, DocumentState state,
        ReviewStage reviewStage, long revision, String creatorActorRef, UUID initiatedByIdentityId,
        String content, String contentRef, boolean contentAvailable, String contentFormat, Binding sourceBinding) {
    public record Binding(UUID repositoryConnectionId, String targetKind, String targetRef, String headSha,
            String comparisonSha, String configRevision, Instant capturedAt) {}
}
