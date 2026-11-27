package com.livingdocs.repository;

import com.livingdocs.entity.GithubConnectionEntity;
import java.util.Optional;
import java.util.UUID;

/** Kết nối GitHub. */
public interface GithubConnectionRepository extends ScopedRepository<GithubConnectionEntity> {

    Optional<GithubConnectionEntity> findByTenantIdAndGithubConnectionId(UUID tenantId, UUID githubConnectionId);

    Optional<GithubConnectionEntity> findByTenantIdAndExternalInstallationRef(UUID tenantId, String externalInstallationRef);
}

