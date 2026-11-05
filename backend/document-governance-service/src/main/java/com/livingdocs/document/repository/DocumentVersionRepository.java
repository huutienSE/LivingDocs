package com.livingdocs.document.repository;

import com.livingdocs.document.domain.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentVersionRepository {
    private final JdbcClient jdbc;

    public DocumentVersionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void setTenant(UUID tenantId) {
        // Transaction-local: never leak a tenant onto a pooled connection's next request.
        jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)")
                .param("tenant", tenantId.toString()).query(String.class).single();
    }

    public Optional<DocumentVersion> find(UUID id, UUID tenantId, boolean forUpdate) {
        return jdbc.sql("""
                SELECT v.*, d.repository_connection_id, c.workspace_id
                FROM document.document_versions v
                JOIN document.documents d ON d.tenant_id = v.tenant_id AND d.document_id = v.document_id
                JOIN document.document_collections c ON c.tenant_id = d.tenant_id AND c.collection_id = d.collection_id
                WHERE v.document_version_id = :id AND v.tenant_id = :tenant
                """ + (forUpdate ? " FOR UPDATE OF v FOR SHARE OF d, c" : ""))
                .param("id", id).param("tenant", tenantId)
                .query((rs, row) -> new DocumentVersion(rs.getObject("document_version_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class), rs.getObject("repository_connection_id", UUID.class),
                        rs.getString("source_sha"), DocumentState.valueOf(rs.getString("state")),
                        rs.getString("review_stage") == null ? null : ReviewStage.valueOf(rs.getString("review_stage")),
                        rs.getLong("revision"))).optional();
    }

    public void update(DocumentVersion before, DocumentVersion after) {
        int changed = jdbc.sql("""
                UPDATE document.document_versions
                SET state = :state, review_stage = :stage, revision = :next
                WHERE tenant_id = :tenant AND document_version_id = :id AND revision = :expected
                  AND state = 'IN_REVIEW' AND review_stage = 'STAFF_REVIEW'
                """).param("state", after.state().name())
                .param("stage", after.reviewStage() == null ? null : after.reviewStage().name())
                .param("next", after.revision()).param("expected", before.revision())
                .param("id", before.id()).param("tenant", before.tenantId()).update();
        if (changed != 1) throw ReviewException.revisionConflict();
    }

    public boolean hasEvidence(UUID tenantId, UUID versionId, UUID evidenceId) {
        return jdbc.sql("""
                SELECT count(*) FROM document.document_evidence_refs
                WHERE tenant_id = :tenant AND document_version_id = :version AND document_evidence_id = :evidence
                """).param("tenant", tenantId).param("version", versionId).param("evidence", evidenceId)
                .query(Integer.class).single() == 1;
    }
}
