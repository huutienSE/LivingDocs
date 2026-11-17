package com.livingdocs.document.entity;

import com.livingdocs.document.model.DocumentState;
import com.livingdocs.document.model.ReviewStage;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Only the fields owned by the staff-review transition are writable here. */
@Entity
@Table(name = "document_versions", schema = "document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentVersionEntity {
    @Id
    @Column(name = "document_version_id", updatable = false)
    private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;
    @Column(name = "source_sha", length = 128, updatable = false)
    private String sourceSha;
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private DocumentState state;
    @Enumerated(EnumType.STRING)
    @Column(name = "review_stage", length = 32)
    private ReviewStage reviewStage;
    @Version
    @Column(name = "revision", nullable = false)
    private long revision;

    public void review(DocumentState state, ReviewStage reviewStage) {
        this.state = state;
        this.reviewStage = reviewStage;
    }
}
