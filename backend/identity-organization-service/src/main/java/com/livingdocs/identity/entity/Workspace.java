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
@Table(schema = "identity", name = "workspaces")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Workspace {

    public Workspace(UUID tenantId, UUID workspaceId, String name) {
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
        this.name = name;
        this.status = "ACTIVE";
    }

    @Id
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "name")
    private String name;

    @Column(name = "status")
    private String status;

    // optimistic lock
    //tự tăng rowVersion mỗi khi update bản ghi
    @Version
    @Column(name = "row_version")
    private Long rowVersion;
}
