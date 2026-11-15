package com.livingdocs.repository;

import com.livingdocs.entity.WebhookDeliveryEntity;
import java.util.Optional;
import java.util.UUID;

/** Tra cứu thông báo GitHub bằng khóa chống trùng. */
public interface WebhookDeliveryRepository extends ScopedRepository<WebhookDeliveryEntity> {

    Optional<WebhookDeliveryEntity> findByTenantIdAndGithubConnectionIdAndProviderDeliveryKey(
            UUID tenantId, UUID githubConnectionId, String providerDeliveryKey);
}

