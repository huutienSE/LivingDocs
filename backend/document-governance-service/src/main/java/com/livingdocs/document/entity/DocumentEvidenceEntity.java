package com.livingdocs.document.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Read-only mapping of canonical evidence used by Submit. Flyway owns the table. */
@Entity
@Table(name = "document_evidence_refs", schema = "document")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentEvidenceEntity {
    @Id
    @Column(name = "document_evidence_id", updatable = false)
    private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;
    @Column(name = "document_version_id", nullable = false, updatable = false)
    private UUID versionId;
    @Column(name = "evidence_type", nullable = false, updatable = false)
    private String type;
    @Column(name = "source_ref", columnDefinition = "text", updatable = false)
    private String sourceRef;
    @Column(name = "artifact_ref", columnDefinition = "text", updatable = false)
    private String artifactRef;

    public DocumentEvidenceEntity(UUID tenantId, UUID versionId,
            com.livingdocs.document.model.SourceSnapshot.Evidence evidence) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.versionId = versionId;
        this.type = evidence.type();
        this.sourceRef = evidence.sourceRef();
        this.artifactRef = evidence.artifactRef();
    }
}
