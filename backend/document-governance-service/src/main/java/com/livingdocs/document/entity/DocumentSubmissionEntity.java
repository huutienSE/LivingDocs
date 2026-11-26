package com.livingdocs.document.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity 
@Table(schema = "document", name = "submit_requests") @Immutable
@Getter 
@Builder 
@NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DocumentSubmissionEntity implements org.springframework.data.domain.Persistable<UUID> {
    @Transient @Builder.Default private boolean newEntity = true;
    public boolean isNew() { return newEntity; }
    @PostLoad @PostPersist private void stored() { newEntity = false; }
    @Id @Column(name = "submission_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "document_version_id", nullable = false) private UUID versionId;
    @Column(name = "actor_identity_id", nullable = false) private UUID actorId;
    @Column(name = "idempotency_key", nullable = false, length = 128) private String key;
    @Column(name = "expected_revision", nullable = false) private long expectedRevision;
    @Column(name = "resulting_revision", nullable = false) private long resultingRevision;
    @Column(length = 2000) private String note;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_snapshot", columnDefinition = "jsonb", nullable = false) private String responseSnapshot;
    @Column(name = "submitted_at", nullable = false) private Instant submittedAt;
}
