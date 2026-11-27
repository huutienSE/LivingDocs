package com.livingdocs.document.repository;

import com.livingdocs.document.model.ApprovalContext;
import com.livingdocs.document.model.ApprovalQueueItem;
import com.livingdocs.document.model.ApprovalScope;
import com.livingdocs.document.model.DocumentState;
import com.livingdocs.document.model.ReviewStage;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries for API-122/123. Runs inside the caller's transaction after the tenant RLS
 * context is set; every query also filters by tenant explicitly (defence in depth).
 * Workspace/repository scope is resolved through S04-owned tables only (collections → documents → versions).
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
@RequiredArgsConstructor
public class ApprovalQueryRepository {
    private static final String QUEUE_SCOPE = """
            FROM document.document_versions v
            JOIN document.documents d ON d.tenant_id = v.tenant_id AND d.document_id = v.document_id
            JOIN document.document_collections c ON c.tenant_id = d.tenant_id AND c.collection_id = d.collection_id
            WHERE v.tenant_id = :tenant
              AND v.state = 'IN_REVIEW' AND v.review_stage = 'MANAGER_APPROVAL'
              AND c.workspace_id::text = ANY (string_to_array(:workspaces, ','))
              AND (d.repository_connection_id IS NULL
                   OR (c.workspace_id::text || ':' || d.repository_connection_id::text) = ANY (string_to_array(:pairs, ',')))
            """;

    private final EntityManager entities;

    public void setTenant(UUID tenantId) {
        // Transaction-local RLS context on the same connection as the queries below.
        entities.createNativeQuery("SELECT set_config('app.tenant_id', :tenant, true)", String.class)
                .setParameter("tenant", tenantId.toString()).getSingleResult();
    }

    public long countQueue(ApprovalScope scope) {
        var query = entities.createNativeQuery("SELECT count(*) " + QUEUE_SCOPE, Long.class);
        bindScope(query, scope);
        return ((Number) query.getSingleResult()).longValue();
    }

    /** Oldest Staff PASS first, so versions waiting longest are handled first; ties are deterministic. */
    public List<ApprovalQueueItem> findQueue(ApprovalScope scope, int page, int size) {
        var query = entities.createNativeQuery("""
                SELECT v.document_version_id, v.document_id, d.title, v.version_no, c.workspace_id,
                       d.repository_connection_id, v.source_sha, v.revision, v.state, v.review_stage,
                       (SELECT max(r.decided_at) FROM document.review_decisions r
                         WHERE r.tenant_id = v.tenant_id AND r.document_version_id = v.document_version_id
                           AND r.decision = 'PASS') AS staff_passed_at
                """ + QUEUE_SCOPE + """
                ORDER BY staff_passed_at ASC NULLS LAST, v.created_at ASC, v.document_version_id ASC
                LIMIT :limit OFFSET :offset
                """, Object[].class);
        bindScope(query, scope);
        query.setParameter("limit", size).setParameter("offset", (long) page * size);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(r -> new ApprovalQueueItem((UUID) r[1], (UUID) r[0], (String) r[2],
                ((Number) r[3]).intValue(), (UUID) r[4], (UUID) r[5], (String) r[6], ((Number) r[7]).longValue(),
                DocumentState.valueOf((String) r[8]), ReviewStage.valueOf((String) r[9]), instant(r[10]))).toList();
    }

    /** Version header with its scope; empty when the version does not exist in this tenant. */
    public Optional<Header> findHeader(UUID tenantId, UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT v.document_version_id, v.document_id, d.title, v.version_no, c.workspace_id,
                       d.repository_connection_id, v.state, v.review_stage, v.revision, v.source_sha,
                       v.source_target_kind, v.source_target_ref, v.source_comparison_sha,
                       v.source_config_revision, v.source_captured_at
                FROM document.document_versions v
                JOIN document.documents d ON d.tenant_id = v.tenant_id AND d.document_id = v.document_id
                JOIN document.document_collections c ON c.tenant_id = d.tenant_id AND c.collection_id = d.collection_id
                WHERE v.tenant_id = :tenant AND v.document_version_id = :version
                """, Object[].class).setParameter("tenant", tenantId).setParameter("version", versionId).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        Object[] r = rows.getFirst();
        UUID repository = (UUID) r[5];
        var binding = repository == null ? null : new ApprovalContext.SourceBinding(repository, (String) r[10],
                (String) r[11], (String) r[9], (String) r[12], (String) r[13], instant(r[14]));
        String stage = (String) r[7];
        var version = new ApprovalContext.Version((UUID) r[1], (UUID) r[0], DocumentState.valueOf((String) r[6]),
                stage == null ? null : ReviewStage.valueOf(stage), ((Number) r[8]).longValue(), binding);
        return Optional.of(new Header(version, (String) r[2], ((Number) r[3]).intValue(), (UUID) r[4], repository));
    }

    public Optional<ApprovalContext.Content> findCurrentContent(UUID tenantId, UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT revision_no, content_ref, editor_identity_id, created_at
                FROM document.content_revisions
                WHERE tenant_id = :tenant AND document_version_id = :version
                ORDER BY revision_no DESC LIMIT 1
                """, Object[].class).setParameter("tenant", tenantId).setParameter("version", versionId).getResultList();
        return rows.stream().findFirst().map(r -> new ApprovalContext.Content(((Number) r[0]).intValue(),
                (String) r[1], (UUID) r[2], instant(r[3])));
    }

    public List<ApprovalContext.StaffDecision> findStaffDecisions(UUID tenantId, UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT review_decision_id, round_no, decision, reviewer_identity_id, reason, decided_at
                FROM document.review_decisions
                WHERE tenant_id = :tenant AND document_version_id = :version
                ORDER BY round_no ASC, decided_at ASC, review_decision_id ASC
                """, Object[].class).setParameter("tenant", tenantId).setParameter("version", versionId).getResultList();
        return rows.stream().map(r -> new ApprovalContext.StaffDecision((UUID) r[0], ((Number) r[1]).intValue(),
                (String) r[2], (UUID) r[3], (String) r[4], instant(r[5]))).toList();
    }

    public List<ApprovalContext.ManagerDecision> findApprovalDecisions(UUID tenantId, UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT approval_decision_id, round_no, decision, approver_identity_id, reason, decided_at
                FROM document.approval_decisions
                WHERE tenant_id = :tenant AND document_version_id = :version
                ORDER BY round_no ASC, decided_at ASC, approval_decision_id ASC
                """, Object[].class).setParameter("tenant", tenantId).setParameter("version", versionId).getResultList();
        return rows.stream().map(r -> new ApprovalContext.ManagerDecision((UUID) r[0], ((Number) r[1]).intValue(),
                (String) r[2], (UUID) r[3], (String) r[4], instant(r[5]))).toList();
    }

    public List<ApprovalContext.Evidence> findEvidence(UUID tenantId, UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT document_evidence_id, evidence_type, source_ref, artifact_ref, attached_at
                FROM document.document_evidence_refs
                WHERE tenant_id = :tenant AND document_version_id = :version
                ORDER BY attached_at ASC, document_evidence_id ASC
                """, Object[].class).setParameter("tenant", tenantId).setParameter("version", versionId).getResultList();
        return rows.stream().map(r -> new ApprovalContext.Evidence((UUID) r[0], (String) r[1], (String) r[2],
                (String) r[3], instant(r[4]))).toList();
    }

    private static void bindScope(jakarta.persistence.Query query, ApprovalScope scope) {
        query.setParameter("tenant", scope.tenantId())
                .setParameter("workspaces", String.join(",", scope.workspaceIds().stream().map(UUID::toString).toList()))
                .setParameter("pairs", String.join(",", scope.workspaceRepositoryPairs()));
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass().getName());
        };
    }

    /** Version header plus the scope needed for the authorization check. */
    public record Header(ApprovalContext.Version version, String documentTitle, int versionNo, UUID workspaceId,
                         UUID repositoryConnectionId) {
    }
}
