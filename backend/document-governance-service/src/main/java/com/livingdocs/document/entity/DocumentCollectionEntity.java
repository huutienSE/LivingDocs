package com.livingdocs.document.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "document_collections", schema = "document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentCollectionEntity {
    @Id @Column(name = "collection_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "status", nullable = false) private String status;
}
