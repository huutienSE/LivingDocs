package com.livingdocs.repository.infrastructure.persistence.repository;

import com.livingdocs.repository.infrastructure.persistence.entity.ExportOperationEntity;
import java.util.Optional;
import java.util.UUID;

/** Thao tác xuất tài liệu thuộc kết nối kho mã nguồn. */
public interface ExportOperationRepository extends ScopedRepository<ExportOperationEntity> {
    Optional<ExportOperationEntity> findByTenantIdAndRepositoryConnectionIdAndExportOperationId(
            UUID tenantId, UUID repositoryConnectionId, UUID exportOperationId);
}

