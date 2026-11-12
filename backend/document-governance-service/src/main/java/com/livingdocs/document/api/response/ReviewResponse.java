package com.livingdocs.document.api.response;

import com.livingdocs.document.domain.model.*;
import java.util.UUID;
import java.time.Instant;

/** DocumentVersionView: unknown source attributes remain null, never guessed. */
public record ReviewResponse(UUID documentId, UUID versionId, DocumentState state, ReviewStage reviewStage,
                             long revision, SourceBinding sourceBinding) {
    public record SourceBinding(UUID repositoryConnectionId, String targetKind, String targetRef,
                                String headSha, String comparisonSha, String configRevision, Instant capturedAt) {}
    public static ReviewResponse from(ReviewDecision decision, DocumentVersion version) {
        return new ReviewResponse(version.documentId(), decision.versionId(), decision.resultingState(),
                decision.resultingStage(), decision.resultingRevision(),
                version.repositoryConnectionId() == null ? null : new SourceBinding(
                        version.repositoryConnectionId(), null, null, version.sourceSha(), null, null, null));
    }
}
