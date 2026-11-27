package com.livingdocs.repository;

import com.livingdocs.entity.RepositoryConnectionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Kết nối kho mã nguồn theo đơn vị và không gian làm việc. */
public interface RepositoryConnectionRepository extends ScopedRepository<RepositoryConnectionEntity> {

    interface BindingRow {
        String getRepositoryStatus();
        String getGithubStatus();
        long getBindingRevision();
    }

    @Query(value = """
            select r.status as "repositoryStatus", g.status as "githubStatus",
                   case when r.binding_parent_revision=g.config_revision then r.binding_revision else 0 end as "bindingRevision"
            from repository.repository_connections r
            join repository.github_connections g on g.tenant_id=r.tenant_id and g.github_connection_id=r.github_connection_id
            where r.tenant_id=:tenantId and r.workspace_id=:workspaceId and r.repository_connection_id=:connectionId
            """, nativeQuery=true)
    Optional<BindingRow> findBinding(@Param("tenantId") UUID tenant, @Param("workspaceId") UUID workspace,
            @Param("connectionId") UUID connection);

    Optional<RepositoryConnectionEntity> findByTenantIdAndWorkspaceIdAndRepositoryConnectionId(
            UUID tenantId, UUID workspaceId, UUID repositoryConnectionId);

    Optional<RepositoryConnectionEntity> findByTenantIdAndWorkspaceIdAndExternalRepoId(
            UUID tenantId, UUID workspaceId, String externalRepoId);

    @Query(value = """
            select concat('v1:', r.config_revision, ':', g.config_revision)
            from repository.repository_connections r
            join repository.github_connections g
              on g.tenant_id = r.tenant_id and g.github_connection_id = r.github_connection_id
            where r.tenant_id = :tenantId
              and r.workspace_id = :workspaceId
              and r.repository_connection_id = :repositoryConnectionId
            """, nativeQuery = true)
    Optional<String> findConfigurationRevision(@Param("tenantId") UUID tenantId,
            @Param("workspaceId") UUID workspaceId, @Param("repositoryConnectionId") UUID repositoryConnectionId);
}

