package com.livingdocs.identity.service;

import com.livingdocs.identity.dto.response.RepositoryGrantResponse;
import java.util.List;
import java.util.UUID;

public interface RepositoryGrantService {
    RepositoryGrantResponse request(UUID actor, UUID tenant, UUID workspace, UUID member,
                                    UUID connection, String provenance, UUID idempotencyKey);
    List<RepositoryGrantResponse> list(UUID actor, UUID tenant, UUID workspace);
    RepositoryGrantResponse get(UUID actor, UUID tenant, UUID workspace, UUID grant);
    RepositoryGrantResponse verify(UUID actor, UUID tenant, UUID workspace, UUID grant);
    RepositoryGrantResponse revoke(UUID actor, UUID tenant, UUID workspace, UUID grant);
}
