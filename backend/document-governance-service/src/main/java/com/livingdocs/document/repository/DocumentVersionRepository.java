package com.livingdocs.document.repository;

import lombok.RequiredArgsConstructor;
import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.*;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class DocumentVersionRepository {
    private final EntityManager entities;
    private final DocumentVersionJpaRepository versions;

    public void setTenant(UUID tenantId) {
        // Transaction-local RLS context on the same connection as all ORM operations.
        entities.createNativeQuery("SELECT set_config('app.tenant_id', :tenant, true)", String.class)
                .setParameter("tenant", tenantId.toString()).getSingleResult();
    }

    public void setQueryTimeout(int milliseconds) {
        entities.createNativeQuery("SELECT set_config('statement_timeout', :timeout, true)", String.class)
                .setParameter("timeout", Integer.toString(milliseconds)).getSingleResult();
    }

    public Optional<DocumentVersion> find(UUID id, UUID tenantId, boolean forUpdate) {
        // Intentional native SQL: separate UPDATE/SHARE locks also freeze authorization scope.
        var scopes = entities.createNativeQuery("""
                SELECT c.workspace_id, d.repository_connection_id
                FROM document.document_versions v
                JOIN document.documents d ON d.tenant_id = v.tenant_id AND d.document_id = v.document_id
                JOIN document.document_collections c ON c.tenant_id = d.tenant_id AND c.collection_id = d.collection_id
                WHERE v.document_version_id = :id AND v.tenant_id = :tenant
                """ + (forUpdate ? " FOR UPDATE OF v FOR SHARE OF d, c" : ""), Object[].class)
                .setParameter("id", id).setParameter("tenant", tenantId).getResultList();
        if (scopes.isEmpty()) return Optional.empty();
        var version = versions.findById(id).orElse(null);
        if (version == null || !version.getTenantId().equals(tenantId)) return Optional.empty();
        Object[] scope = (Object[]) scopes.getFirst();
        return Optional.of(new DocumentVersion(version.getId(), version.getTenantId(), version.getDocumentId(),
                (UUID) scope[0], (UUID) scope[1], version.getSourceSha(), version.getState(),
                version.getReviewStage(), version.getRevision()));
    }

    public void update(DocumentVersion before, DocumentVersion after) {
        var version = versions.findById(before.id()).orElse(null);
        if (version == null || !version.getTenantId().equals(before.tenantId())
                || version.getRevision() != before.revision() || version.getState() != DocumentState.IN_REVIEW
                || version.getReviewStage() != ReviewStage.STAFF_REVIEW) {
            throw new ReviewRevisionConflictException();
        }
        version.review(after.state(), after.reviewStage());
        // Flush @Version update before appending history; outer service transaction owns rollback.
        versions.flush();
        if (version.getRevision() != after.revision()) throw new ReviewRevisionConflictException();
    }

    public boolean hasEvidence(UUID tenantId, UUID versionId, List<UUID> evidenceIds) {
        if (evidenceIds.isEmpty()) return true;
        // Scoped bulk existence check avoids loading evidence outside the review aggregate.
        var query = entities.createNativeQuery("""
                SELECT count(*) FROM document.document_evidence_refs
                WHERE tenant_id = :tenant AND document_version_id = :version AND document_evidence_id IN (:evidence)
                """, Long.class).unwrap(NativeQuery.class);
        query.setParameter("tenant", tenantId).setParameter("version", versionId);
        query.setParameterList("evidence", evidenceIds);
        return ((Number) query.getSingleResult()).longValue() == evidenceIds.size();
    }
}
