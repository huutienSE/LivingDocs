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

/** Nhánh mã nguồn được theo dõi trong một kết nối kho mã nguồn. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tracked_branches", schema = "repository",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_tracked_branches_tenant_id", columnNames = {"tenant_id", "tracked_branch_id"}),
            @UniqueConstraint(name = "uq_tracked_branches_ref", columnNames = {"tenant_id", "repository_connection_id", "ref_name"})
        })
public class TrackedBranchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "tracked_branch_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private UUID trackedBranchId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "repository_connection_id", nullable = false)
    private UUID repositoryConnectionId;

    @Column(name = "ref_name", nullable = false, length = 512)
    private String refName;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "configured_at", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant configuredAt;

}
