package com.livingdocs.repository.infrastructure.persistence.repository;

import com.livingdocs.repository.infrastructure.persistence.entity.GithubConnectionEntity;
import java.util.Optional;
import java.util.UUID;

/** Kết nối GitHub. */
public interface GithubConnectionRepository extends ScopedRepository<GithubConnectionEntity> {

    Optional<GithubConnectionEntity> findByTenantIdAndGithubConnectionId(UUID tenantId, UUID githubConnectionId);

    Optional<GithubConnectionEntity> findByTenantIdAndExternalInstallationRef(UUID tenantId, String externalInstallationRef);
}

