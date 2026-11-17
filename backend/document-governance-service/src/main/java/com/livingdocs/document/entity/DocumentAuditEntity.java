package com.livingdocs.document.entity;

import com.livingdocs.document.model.DocumentState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "document_audits", schema = "document")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DocumentAuditEntity implements org.springframework.data.domain.Persistable<UUID> {
    @Transient @Builder.Default private boolean newEntity = true;

    @Override public boolean isNew() { return newEntity; }
    @PostLoad @PostPersist private void markStored() { newEntity = false; }

    @Id @Column(name = "document_audit_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "document_id", nullable = false) private UUID documentId;
    @Column(name = "document_version_id", nullable = false) private UUID versionId;
    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "repository_connection_id") private UUID repositoryConnectionId;
    @Column(name = "actor_ref", nullable = false) private String actorRef;
    @Column(name = "action", nullable = false, length = 100) private String action;
    @Enumerated(EnumType.STRING) @Column(name = "previous_state", length = 32)
    private DocumentState previousState;
    @Enumerated(EnumType.STRING) @Column(name = "new_state", nullable = false, length = 32)
    private DocumentState newState;
    @Column(name = "source_sha", length = 128) private String sourceSha;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
}
