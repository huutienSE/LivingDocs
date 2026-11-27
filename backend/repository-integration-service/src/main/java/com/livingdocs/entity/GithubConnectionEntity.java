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
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.generator.EventType;

/** Kết nối GitHub; credentialRef chỉ là tham chiếu bí mật, không chứa mã truy cập thô. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "github_connections", schema = "repository",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_github_connections_tenant_id", columnNames = {"tenant_id", "github_connection_id"}),
            @UniqueConstraint(name = "uq_github_connections_installation", columnNames = {"tenant_id", "external_installation_ref"})
        })
public class GithubConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "github_connection_id", nullable = false)
    private UUID githubConnectionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "external_installation_ref", nullable = false, length = 255)
    private String externalInstallationRef;

    @Column(name = "credential_ref", nullable = false, length = 512)
    private String credentialRef;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public GithubConnectionEntity(UUID tenantId, String externalInstallationRef, String credentialRef,
                                  String status, Instant connectedAt) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.externalInstallationRef = Objects.requireNonNull(externalInstallationRef, "externalInstallationRef");
        this.credentialRef = Objects.requireNonNull(credentialRef, "credentialRef");
        this.status = Objects.requireNonNull(status, "status");
        this.connectedAt = connectedAt;
    }

    public void markConnected() {
        status = "CONNECTED";
    }
}
