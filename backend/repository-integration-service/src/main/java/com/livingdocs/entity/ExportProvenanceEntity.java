package com.livingdocs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Thông tin truy vết nguồn gốc của thao tác xuất tài liệu. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "export_provenance", schema = "repository",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_export_provenance_tenant_id", columnNames = {"tenant_id", "provenance_id"}),
            @UniqueConstraint(name = "uq_export_provenance_operation", columnNames = {"tenant_id", "export_operation_id"})
        })
public class ExportProvenanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "provenance_id", nullable = false)
    private UUID provenanceId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "export_operation_id", nullable = false)
    private UUID exportOperationId;

    @Column(name = "branch_ref", nullable = false, length = 512)
    private String branchRef;

    @Column(name = "pull_request_ref", length = 255)
    private String pullRequestRef;

    @Column(name = "export_commit_sha", nullable = false, length = 128)
    private String exportCommitSha;

    @Generated(event = EventType.INSERT)
    @Column(name = "exported_at", nullable = false, insertable = false, updatable = false)
    private Instant exportedAt;

    public ExportProvenanceEntity(UUID tenantId, UUID exportOperationId, String branchRef,
                                  String pullRequestRef, String exportCommitSha) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.exportOperationId = Objects.requireNonNull(exportOperationId, "exportOperationId");
        this.branchRef = Objects.requireNonNull(branchRef, "branchRef");
        this.pullRequestRef = pullRequestRef;
        this.exportCommitSha = Objects.requireNonNull(exportCommitSha, "exportCommitSha");
    }
}
