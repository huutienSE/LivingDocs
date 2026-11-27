package com.livingdocs.document.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity @Table(schema = "document", name = "documents") @Immutable
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentEntity {
    @Id @Column(name = "document_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "title", nullable = false, length = 500) private String title;
}
