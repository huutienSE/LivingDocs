package com.livingdocs.repository;

import com.livingdocs.entity.SourceValidationReceiptEntity;

import java.util.Optional;
import java.util.UUID;

/** Tra cứu bằng chứng; bên gọi vẫn phải kiểm tra binding, hiệu lực và currentness. */
public interface SourceValidationReceiptRepository extends ScopedRepository<SourceValidationReceiptEntity> {

    Optional<SourceValidationReceiptEntity> findByTenantIdAndRepositoryConnectionIdAndValidationId(
            UUID tenantId, UUID repositoryConnectionId, UUID validationId);
}

