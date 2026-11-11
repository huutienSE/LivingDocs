package com.livingdocs.repository.infrastructure.persistence.repository;

import com.livingdocs.repository.infrastructure.persistence.entity.WebhookDeliveryEntity;
import java.util.Optional;
import java.util.UUID;

/** Tra cứu thông báo GitHub bằng khóa chống trùng. */
public interface WebhookDeliveryRepository extends ScopedRepository<WebhookDeliveryEntity> {

    Optional<WebhookDeliveryEntity> findByTenantIdAndGithubConnectionIdAndProviderDeliveryKey(
            UUID tenantId, UUID githubConnectionId, String providerDeliveryKey);
}

