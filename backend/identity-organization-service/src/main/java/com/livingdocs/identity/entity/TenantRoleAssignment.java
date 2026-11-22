package com.livingdocs.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "identity", name = "tenant_role_assignments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantRoleAssignment {

    public TenantRoleAssignment(UUID tenantId, UUID tenantMembershipId, String roleCode) {
        this.assignmentId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.tenantMembershipId = tenantMembershipId;
        this.roleCode = roleCode;
        this.status = "ACTIVE";
    }

    public void revoke() {
        if (!"ACTIVE".equals(status)) throw new IllegalStateException("Role is not active");
        status = "REVOKED";
        endedAt = Instant.now();
    }

    @Id
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "tenant_membership_id")
    private UUID tenantMembershipId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id", insertable = false, updatable = false),
        @JoinColumn(name = "tenant_membership_id", referencedColumnName = "membership_id", insertable = false, updatable = false)
    })
    private TenantMembership tenantMembership;

    @Column(name = "role_code")
    private String roleCode;

    @Column(name = "status")
    private String status;

    @Column(name = "ended_at")
    private Instant endedAt;

    // optimistic lock
    //tự tăng rowVersion mỗi khi update bản ghi
    @Version
    @Column(name = "row_version")
    private Long rowVersion;
}
