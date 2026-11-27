package com.livingdocs.document.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(schema = "document", name = "template_versions") @Immutable
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentTemplateVersionEntity {
    @Id @Column(name = "template_version_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "template_id", nullable = false) private UUID templateId;
    @Column(name = "version_no", nullable = false) private int versionNo;
    @Column(name = "structure_ref", nullable = false, columnDefinition = "text") private String structureRef;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "formatting_rules", nullable = false, columnDefinition = "jsonb")
    private String formattingRulesJson;
    @Column(name = "published_at") private Instant publishedAt;
}
