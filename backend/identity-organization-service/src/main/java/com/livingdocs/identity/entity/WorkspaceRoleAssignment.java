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
@Table(schema = "identity", name = "workspace_role_assignments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkspaceRoleAssignment {

    public WorkspaceRoleAssignment(UUID tenantId, UUID workspaceId, UUID workspaceMembershipId, String roleCode) {
        this.assignmentId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
        this.workspaceMembershipId = workspaceMembershipId;
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

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "workspace_membership_id")
    private UUID workspaceMembershipId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id", insertable = false, updatable = false),
        @JoinColumn(name = "workspace_id", referencedColumnName = "workspace_id", insertable = false, updatable = false),
        @JoinColumn(name = "workspace_membership_id", referencedColumnName = "workspace_membership_id", insertable = false, updatable = false)
    })
    private WorkspaceMembership workspaceMembership;

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
