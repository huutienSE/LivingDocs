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
@Table(schema = "identity", name = "repository_grants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositoryGrant {

    public RepositoryGrant(UUID tenantId, UUID workspaceId, UUID workspaceMembershipId,
                           UUID repositoryConnectionId, UUID operatorIdentityId, String provenance) {
        this.grantId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
        this.workspaceMembershipId = workspaceMembershipId;
        this.repositoryConnectionId = repositoryConnectionId;
        this.operatorIdentityId = operatorIdentityId;
        this.provenance = provenance;
        this.status = "ACTIVE";
        this.verificationStatus = "PENDING";
    }

    public void revoke() {
        if (!"ACTIVE".equals(status)) throw new IllegalStateException("Grant is not active");
        status = "REVOKED";
        endedAt = Instant.now();
    }

    @Id
    @Column(name = "grant_id", nullable = false)
    private UUID grantId;

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

    @Column(name = "repository_connection_id")
    private UUID repositoryConnectionId;

    @Column(name = "status")
    private String status;

    @Column(name = "verification_status")
    private String verificationStatus;

    @Column(name = "verification_source")
    private String verificationSource;

    @Column(name = "provenance")
    private String provenance;

    @Column(name = "operator_identity_id")
    private UUID operatorIdentityId;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Version
    @Column(name = "row_version")
    private Long rowVersion;
}
