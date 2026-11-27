package com.livingdocs.document.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity @Table(schema = "document", name = "document_templates") @Immutable
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentTemplateEntity {
    @Id @Column(name = "template_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "scope_type", nullable = false, length = 16) private String scopeType;
    @Column(name = "scope_ref", nullable = false) private UUID scopeRef;
    @Column(nullable = false, length = 255) private String name;
    @Column(name = "document_type", nullable = false, length = 100) private String documentType;
}
