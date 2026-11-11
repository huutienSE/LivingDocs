package com.livingdocs.repository.infrastructure.persistence.repository;

import com.livingdocs.repository.infrastructure.persistence.entity.SourceRevisionEntity;
import java.util.Optional;
import java.util.UUID;

/** Tra cứu phiên bản đã quan sát; không thay thế kiểm tra head hiện hành trên GitHub. */
public interface SourceRevisionRepository extends ScopedRepository<SourceRevisionEntity> {

    Optional<SourceRevisionEntity> findByTenantIdAndRepositoryConnectionIdAndSourceRevisionId(
            UUID tenantId, UUID repositoryConnectionId, UUID sourceRevisionId);

    Optional<SourceRevisionEntity> findByTenantIdAndRepositoryConnectionIdAndTargetRefAndCommitSha(
            UUID tenantId, UUID repositoryConnectionId, String targetRef, String commitSha);
}

