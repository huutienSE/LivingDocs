package com.livingdocs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** Biên nhận xác minh mã nguồn theo thời điểm. Nghiệp vụ kiểm tra điều kiện hiệu lực. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "source_validation_receipts", schema = "repository",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_source_validation_tenant_id", columnNames = {"tenant_id", "validation_id"})
        })
public class SourceValidationReceiptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "validation_id", nullable = false)
    private UUID validationId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "repository_connection_id", nullable = false)
    private UUID repositoryConnectionId;

    @Column(name = "target_ref", nullable = false, length = 512)
    private String targetRef;

    @Column(name = "validated_head_sha", nullable = false, length = 128)
    private String validatedHeadSha;

    @Column(name = "config_revision", nullable = false, length = 255)
    private String configRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 16)
    private SourceValidationStatus validationStatus;

    @Generated(event = EventType.INSERT)
    @Column(name = "checked_at", nullable = false, insertable = false, updatable = false)
    private Instant checkedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public SourceValidationReceiptEntity(UUID tenantId, UUID repositoryConnectionId, String targetRef,
                                         String validatedHeadSha, String configRevision,
                                         SourceValidationStatus validationStatus, Instant expiresAt) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.repositoryConnectionId = Objects.requireNonNull(repositoryConnectionId, "repositoryConnectionId");
        this.targetRef = Objects.requireNonNull(targetRef, "targetRef");
        this.validatedHeadSha = Objects.requireNonNull(validatedHeadSha, "validatedHeadSha");
        this.configRevision = Objects.requireNonNull(configRevision, "configRevision");
        this.validationStatus = Objects.requireNonNull(validationStatus, "validationStatus");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
