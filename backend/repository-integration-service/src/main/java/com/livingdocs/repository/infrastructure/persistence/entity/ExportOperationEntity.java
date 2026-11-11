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

/** Thao tác xuất tài liệu; publishedVersionId tham chiếu logic đến S04. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "export_operations", schema = "repository",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_export_operations_tenant_id", columnNames = {"tenant_id", "export_operation_id"})
        })
public class ExportOperationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "export_operation_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private UUID exportOperationId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "repository_connection_id", nullable = false)
    private UUID repositoryConnectionId;

    @Column(name = "published_version_id", nullable = false)
    private UUID publishedVersionId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "REQUESTED";

    @Column(name = "target_ref", nullable = false, length = 512)
    private String targetRef;

    @Generated(event = EventType.INSERT)
    @Column(name = "requested_at", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant requestedAt;

}
