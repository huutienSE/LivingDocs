package com.livingdocs.document.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "documents", schema = "document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentEntity implements org.springframework.data.domain.Persistable<UUID> {
    @Transient private boolean fresh = true;
    @Id @Column(name = "document_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "collection_id", nullable = false) private UUID collectionId;
    @Column(name = "repository_connection_id") private UUID repositoryConnectionId;
    @Column(name = "title", nullable = false, length = 500) private String title;
    @Column(name = "ownership_ref", nullable = false) private String ownershipRef;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public DocumentEntity(UUID tenantId, UUID collectionId, UUID repositoryId, String title, UUID actor) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.collectionId = collectionId;
        this.repositoryConnectionId = repositoryId;
        this.title = title;
        this.ownershipRef = actor.toString();
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist private void stored() { fresh = false; }
}
