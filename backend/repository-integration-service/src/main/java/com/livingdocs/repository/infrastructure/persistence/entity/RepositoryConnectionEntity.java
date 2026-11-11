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
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.generator.EventType;

/** Kết nối kho mã nguồn trong phạm vi một đơn vị và không gian làm việc. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "repository_connections", schema = "repository",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_repository_connections_tenant_id", columnNames = {"tenant_id", "repository_connection_id"}),
            @UniqueConstraint(name = "uq_repository_connections_workspace_repo", columnNames = {"tenant_id", "workspace_id", "external_repo_id"})
        })
public class RepositoryConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "repository_connection_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private UUID repositoryConnectionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "github_connection_id", nullable = false)
    private UUID githubConnectionId;

    @Column(name = "external_repo_id", nullable = false, length = 100)
    private String externalRepoId;

    @Column(name = "default_branch", length = 255)
    private String defaultBranch;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

}
