package com.livingdocs.document.repository;

import com.livingdocs.document.domain.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ReviewDecisionRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ReviewDecisionRepository(JdbcClient jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    public Optional<ReviewDecision> findRequest(UUID tenant, UUID version, UUID actor, String key) {
        return jdbc.sql("""
                SELECT * FROM document.review_decisions
                WHERE tenant_id = :tenant AND document_version_id = :version
                  AND reviewer_identity_id = :actor AND idempotency_key = :key
                """).param("tenant", tenant).param("version", version).param("actor", actor).param("key", key)
                .query((rs, row) -> new ReviewDecision(rs.getObject("review_decision_id", UUID.class), tenant, version, actor,
                        ReviewAction.valueOf(rs.getString("decision")), rs.getString("reason"), rs.getInt("round_no"), key,
                        rs.getLong("expected_revision"), rs.getLong("resulting_revision"),
                        DocumentState.valueOf(rs.getString("resulting_state")),
                        rs.getString("resulting_stage") == null ? null : ReviewStage.valueOf(rs.getString("resulting_stage")),
                        json.readValue(rs.getString("evidence_refs"), new TypeReference<List<UUID>>() {}),
                        rs.getTimestamp("decided_at").toInstant())).optional();
    }

    public int nextRound(UUID tenant, UUID version) {
        // The version row is locked by the service before allocating a new review round.
        return jdbc.sql("""
                SELECT COALESCE(MAX(round_no), 0) + 1 FROM document.review_decisions
                WHERE tenant_id = :tenant AND document_version_id = :version
                """).param("tenant", tenant).param("version", version).query(Integer.class).single();
    }

    public com.livingdocs.document.dto.ReviewResponse response(ReviewDecision d) {
        String snapshot = jdbc.sql("SELECT response_snapshot::text FROM document.review_decisions WHERE tenant_id=:tenant AND review_decision_id=:id")
                .param("tenant", d.tenantId()).param("id", d.id()).query(String.class).single();
        if (snapshot == null) throw new ReviewException(409, "REPLAY_SNAPSHOT_UNAVAILABLE",
                "Historical response cannot be reconstructed from immutable audit.");
        return json.readValue(snapshot, com.livingdocs.document.dto.ReviewResponse.class);
    }

    public void insert(ReviewDecision d, com.livingdocs.document.dto.ReviewResponse response) {
        jdbc.sql("""
                INSERT INTO document.review_decisions (review_decision_id, tenant_id, document_version_id,
                    reviewer_identity_id, decision, reason, round_no, decided_at, idempotency_key,
                    expected_revision, resulting_revision, resulting_state, resulting_stage, evidence_refs, response_snapshot)
                VALUES (:id, :tenant, :version, :actor, :decision, :reason, :round, :at, :key,
                    :expected, :resulting, :state, :stage, CAST(:evidence AS jsonb), CAST(:response AS jsonb))
                """).param("id", d.id()).param("tenant", d.tenantId()).param("version", d.versionId())
                .param("actor", d.reviewerId()).param("decision", d.decision().name()).param("reason", d.feedback())
                .param("round", d.roundNo()).param("at", java.sql.Timestamp.from(d.decidedAt()))
                .param("key", d.idempotencyKey()).param("expected", d.expectedRevision())
                .param("resulting", d.resultingRevision()).param("state", d.resultingState().name())
                .param("stage", d.resultingStage() == null ? null : d.resultingStage().name())
                .param("evidence", json.writeValueAsString(d.evidenceRefs()))
                .param("response", json.writeValueAsString(response)).update();
    }
}
