package com.livingdocs.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "identity", name = "tenant_memberships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantMembership {

    public TenantMembership(UUID tenantId, UUID identityId) {
        this.membershipId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.identityId = identityId;
        this.status = "ACTIVE";
        this.joinedAt = Instant.now();
    }

    public void revoke() {
        if (!"ACTIVE".equals(status)) throw new IllegalStateException("Membership is not active");
        status = "REVOKED";
        endedAt = Instant.now();
    }

    @Id
    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "identity_id")
    private UUID identityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "identity_id", insertable = false, updatable = false)
    private Identity identity;

    @Column(name = "status")
    private String status;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    // optimistic lock
    //tự tăng rowVersion mỗi khi update bản ghi
    @Version
    @Column(name = "row_version")
    private Long rowVersion;
}
