package com.livingdocs.repository.infrastructure.persistence.repository;

import com.livingdocs.repository.infrastructure.persistence.entity.TrackedBranchEntity;
import java.util.Optional;
import java.util.UUID;

/** Nhánh theo dõi thuộc một kết nối kho mã nguồn. */
public interface TrackedBranchRepository extends ScopedRepository<TrackedBranchEntity> {

    Optional<TrackedBranchEntity> findByTenantIdAndRepositoryConnectionIdAndRefName(
            UUID tenantId, UUID repositoryConnectionId, String refName);

    Optional<TrackedBranchEntity> findByTenantIdAndRepositoryConnectionIdAndRefNameAndActiveTrue(
            UUID tenantId, UUID repositoryConnectionId, String refName);
}

