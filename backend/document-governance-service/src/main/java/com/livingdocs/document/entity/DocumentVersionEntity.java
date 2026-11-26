package com.livingdocs.document.entity;

import com.livingdocs.document.model.DocumentState;
import com.livingdocs.document.model.ReviewStage;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
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
    // Persisted provenance is read-only in the Submit/Review slice.
    @Column(name = "source_target_kind", length = 32, insertable = false, updatable = false)
    private String sourceTargetKind;
    @Column(name = "source_target_ref", length = 255, insertable = false, updatable = false)
    private String sourceTargetRef;
    @Column(name = "source_comparison_sha", length = 128, insertable = false, updatable = false)
    private String sourceComparisonSha;
    @Column(name = "source_config_revision", length = 255, insertable = false, updatable = false)
    private String sourceConfigRevision;
    @Column(name = "source_captured_at", insertable = false, updatable = false)
    private Instant sourceCapturedAt;
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
