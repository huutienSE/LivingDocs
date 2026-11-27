package com.livingdocs.document.entity;

import com.livingdocs.document.model.ContentFormat;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity @Table(schema = "document", name = "content_revisions") @Immutable
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentContentRevisionEntity {
    @Id @Column(name = "content_revision_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "document_version_id", nullable = false) private UUID versionId;
    @Column(name = "revision_no", nullable = false) private int revisionNo;
    @Column(name = "content_ref", nullable = false, columnDefinition = "text") private String contentRef;
    @Column(name = "content_text", columnDefinition = "text") private String content;
    @Enumerated(EnumType.STRING) @Column(name = "content_format", length = 16) private ContentFormat contentFormat;
    @Column(name = "editor_identity_id", nullable = false) private UUID editorIdentityId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
}
