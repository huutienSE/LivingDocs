package com.livingdocs.document.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "content_revisions", schema = "document")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentRevisionEntity implements org.springframework.data.domain.Persistable<UUID> {
    @Transient private boolean fresh = true;
    @Id @Column(name = "content_revision_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "document_version_id", nullable = false) private UUID versionId;
    @Column(name = "revision_no", nullable = false) private int revisionNo;
    @Column(name = "content_ref", nullable = false, columnDefinition = "text") private String contentRef;
    @Column(name = "content_markdown", columnDefinition = "text") private String content;
    @Column(name = "editor_identity_id", nullable = false) private UUID editorIdentityId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public ContentRevisionEntity(UUID tenantId, UUID versionId, int number, String content, UUID actor) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.versionId = versionId;
        this.revisionNo = number;
        this.contentRef = "urn:livingdocs:content-revision:" + id;
        this.content = content;
        this.editorIdentityId = actor;
        this.createdAt = Instant.now();
    }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist private void stored() { fresh = false; }
}
