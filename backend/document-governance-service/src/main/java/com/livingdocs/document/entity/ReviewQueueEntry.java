package com.livingdocs.document.entity;

import com.livingdocs.document.model.DriftSeverity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Read-only projection of service-owned tables; view runs with invoker RLS. */
@Entity
@Table(schema = "document", name = "staff_review_queue")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewQueueEntry {
    @Id @Column(name = "version_id") private UUID versionId;
    @Column(name = "tenant_id") private UUID tenantId;
    @Column(name = "document_id") private UUID documentId;
    @Column(name = "document_title") private String documentTitle;
    @Column(name = "version_no") private int versionNo;
    private String state;
    @Column(name = "review_stage") private String reviewStage;
    @Column(name = "workspace_id") private UUID workspaceId;
    @Column(name = "repository_connection_id") private UUID repositoryConnectionId;
    @Column(name = "template_id") private UUID templateId;
    @Column(name = "template_version_id") private UUID templateVersionId;
    @Column(name = "source_sha") private String sourceSha;
    @Enumerated(EnumType.STRING) @Column(name = "drift_severity", length = 16) private DriftSeverity driftSeverity;
    @Column(name = "severity_rank") private Integer severityRank;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "submitted_by") private UUID submittedBy;
}
