package com.livingdocs.document.entity;

import com.livingdocs.document.model.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only history; runtime database grants remain the final write boundary. */
@Entity
@Table(name = "review_decisions", schema = "document")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ReviewDecisionEntity implements org.springframework.data.domain.Persistable<UUID> {
    @Transient @Builder.Default private boolean newEntity = true;

    @Override public boolean isNew() { return newEntity; }
    @PostLoad @PostPersist private void markStored() { newEntity = false; }

    @Id @Column(name = "review_decision_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "document_version_id", nullable = false) private UUID versionId;
    @Column(name = "reviewer_identity_id", nullable = false) private UUID reviewerId;
    @Enumerated(EnumType.STRING) @Column(name = "decision", nullable = false, length = 32)
    private ReviewAction decision;
    @Column(name = "reason", columnDefinition = "text") private String feedback;
    @Column(name = "round_no", nullable = false) private int roundNo;
    @Column(name = "idempotency_key", length = 128) private String idempotencyKey;
    @Column(name = "expected_revision") private Long expectedRevision;
    @Column(name = "resulting_revision") private Long resultingRevision;
    @Enumerated(EnumType.STRING) @Column(name = "resulting_state", length = 32)
    private DocumentState resultingState;
    @Enumerated(EnumType.STRING) @Column(name = "resulting_stage", length = 32)
    private ReviewStage resultingStage;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_refs", nullable = false, columnDefinition = "jsonb")
    private String evidenceRefsJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_snapshot", columnDefinition = "jsonb")
    private String responseSnapshot;
    @Column(name = "decided_at", nullable = false) private Instant decidedAt;
}
