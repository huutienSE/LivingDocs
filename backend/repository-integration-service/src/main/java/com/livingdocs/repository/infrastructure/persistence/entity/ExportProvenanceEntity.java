package com.livingdocs.repository.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Thông tin truy vết nguồn gốc của thao tác xuất tài liệu. */
@Getter
@Setter
@NoArgsConstructor
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
    @Setter(AccessLevel.NONE)
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
    @Setter(AccessLevel.NONE)
    private Instant exportedAt;

}
