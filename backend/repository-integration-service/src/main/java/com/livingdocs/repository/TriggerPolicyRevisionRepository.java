package com.livingdocs.repository;

import com.livingdocs.entity.TriggerPolicyRevisionEntity;
import com.livingdocs.entity.PolicyScopeType;
import java.util.Optional;
import java.util.UUID;

/** Chính sách theo phạm vi; chỉ đọc chính sách cấp nền tảng bằng phương thức riêng. */
public interface TriggerPolicyRevisionRepository extends ScopedRepository<TriggerPolicyRevisionEntity> {

    Optional<TriggerPolicyRevisionEntity> findFirstByTenantIdAndScopeTypeAndScopeRefOrderByRevisionDesc(
            UUID tenantId, PolicyScopeType scopeType, UUID scopeRef);

    /** V1 bảo đảm chỉ chính sách PLATFORM có cả tenantId và scopeRef bằng null. */
    Optional<TriggerPolicyRevisionEntity> findFirstByTenantIdIsNullAndScopeRefIsNullOrderByRevisionDesc();
}

