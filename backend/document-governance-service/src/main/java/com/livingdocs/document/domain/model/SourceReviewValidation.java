package com.livingdocs.document.domain.model;

import com.livingdocs.document.domain.ReviewException;

import java.time.Instant;
import java.util.UUID;

/** Internal S04 validation context from a trusted source adapter, never from a browser or JWT. */
public record SourceReviewValidation(String validationId, UUID tenantId, UUID workspaceId,
                                    UUID versionId, long versionRevision, UUID repositoryConnectionId,
                                    String validatedHeadSha, Instant checkedAt, Instant expiresAt) {
    public void requireMatches(DocumentVersion version, Instant now) {
        if (validationId == null || validationId.isBlank() || validatedHeadSha == null || validatedHeadSha.isBlank()
                || !version.tenantId().equals(tenantId) || !version.workspaceId().equals(workspaceId)
                || !version.id().equals(versionId) || version.revision() != versionRevision
                || !version.repositoryConnectionId().equals(repositoryConnectionId)
                || checkedAt == null || checkedAt.isAfter(now) || expiresAt == null || !expiresAt.isAfter(now)
                || !expiresAt.isAfter(checkedAt)) {
            throw unavailable();
        }
        if (!version.sourceSha().equals(validatedHeadSha)) {
            throw new ReviewException(409, "SOURCE_CHANGED_REVALIDATION_REQUIRED",
                    "The bound source is no longer current. Revalidate before passing Staff Review.");
        }
    }

    public static ReviewException unavailable() {
        return new ReviewException(503, "SOURCE_VALIDATION_UNAVAILABLE",
                "Current source could not be verified. No review changes were committed.");
    }
}
