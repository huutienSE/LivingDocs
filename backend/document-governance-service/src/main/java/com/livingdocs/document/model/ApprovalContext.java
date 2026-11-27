package com.livingdocs.document.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for API-123: everything a Manager needs before deciding, read only from S04-owned tables.
 * Eligibility (No Self Final Approval, fresh S01 authorization, source guard) is decided by API-124, not here.
 */
public record ApprovalContext(Version version, String documentTitle, int versionNo, UUID workspaceId,
                              boolean awaitingManagerDecision, Content currentContent,
                              List<StaffDecision> staffDecisions, List<ManagerDecision> approvalDecisions,
                              List<Evidence> evidence) {
    public ApprovalContext {
        staffDecisions = List.copyOf(staffDecisions);
        approvalDecisions = List.copyOf(approvalDecisions);
        evidence = List.copyOf(evidence);
    }

    /** Same field names as the shared DocumentVersionView DTO. */
    public record Version(UUID documentId, UUID versionId, DocumentState state, ReviewStage reviewStage,
                          long revision, SourceBinding sourceBinding) {
    }

    /** Persisted source provenance; unknown legacy fields stay null and are never guessed. */
    public record SourceBinding(UUID repositoryConnectionId, String targetKind, String targetRef, String headSha,
                                String comparisonSha, String configRevision, Instant capturedAt) {
    }

    public record Content(int revisionNo, String contentRef, UUID editorIdentityId, Instant createdAt) {
    }

    public record StaffDecision(UUID decisionId, int roundNo, String decision, UUID reviewerIdentityId,
                                String reason, Instant decidedAt) {
    }

    public record ManagerDecision(UUID decisionId, int roundNo, String decision, UUID approverIdentityId,
                                  String reason, Instant decidedAt) {
    }

    public record Evidence(UUID evidenceId, String evidenceType, String sourceRef, String artifactRef,
                           Instant attachedAt) {
    }
}
