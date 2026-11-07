package com.livingdocs.document.repository;

import com.livingdocs.document.domain.DocumentAudit;
import java.util.LinkedHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DocumentAuditRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DocumentAuditRepository(JdbcClient jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    public void insert(DocumentAudit a) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("decisionId", a.decision().id());
        metadata.put("previousReviewStage", a.before().reviewStage());
        metadata.put("newReviewStage", a.after().reviewStage());
        metadata.put("previousRevision", a.before().revision());
        metadata.put("newRevision", a.after().revision());
        metadata.put("evidenceRefs", a.decision().evidenceRefs());
        metadata.put("correlationId", a.correlationId());
        if (a.sourceValidation() != null) metadata.put("sourceValidation", a.sourceValidation());
        jdbc.sql("""
                INSERT INTO document.document_audits (document_audit_id, tenant_id, document_id,
                    document_version_id, workspace_id, repository_connection_id, actor_ref, action,
                    previous_state, new_state, source_sha, metadata, occurred_at)
                VALUES (:id, :tenant, :document, :version, :workspace, :repository, :actor, :action,
                    :previous, :next, :sha, CAST(:metadata AS jsonb), :at)
                """).param("id", a.id()).param("tenant", a.before().tenantId()).param("document", a.before().documentId())
                .param("version", a.before().id()).param("workspace", a.before().workspaceId())
                .param("repository", a.before().repositoryConnectionId()).param("actor", a.actorId().toString())
                .param("action", "STAFF_REVIEW_" + a.decision().decision().name())
                .param("previous", a.before().state().name()).param("next", a.after().state().name())
                .param("sha", a.before().sourceSha()).param("metadata", json.writeValueAsString(metadata))
                .param("at", java.sql.Timestamp.from(a.occurredAt())).update();
    }
}
