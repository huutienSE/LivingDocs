package com.livingdocs.repository.infrastructure.persistence.repository;

import com.livingdocs.repository.infrastructure.persistence.entity.RepositoryConnectionEntity;
import java.util.Optional;
import java.util.UUID;

/** Kết nối kho mã nguồn theo đơn vị và không gian làm việc. */
public interface RepositoryConnectionRepository extends ScopedRepository<RepositoryConnectionEntity> {

    Optional<RepositoryConnectionEntity> findByTenantIdAndWorkspaceIdAndRepositoryConnectionId(
            UUID tenantId, UUID workspaceId, UUID repositoryConnectionId);

    Optional<RepositoryConnectionEntity> findByTenantIdAndWorkspaceIdAndExternalRepoId(
            UUID tenantId, UUID workspaceId, String externalRepoId);
}

