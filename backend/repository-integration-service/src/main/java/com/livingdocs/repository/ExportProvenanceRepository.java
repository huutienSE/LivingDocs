package com.livingdocs.repository;

import com.livingdocs.entity.ExportProvenanceEntity;

import java.util.Optional;
import java.util.UUID;

/** Truy vết theo thao tác xuất tài liệu, tối đa một bản ghi cho mỗi thao tác. */
public interface ExportProvenanceRepository extends ScopedRepository<ExportProvenanceEntity> {
    Optional<ExportProvenanceEntity> findByTenantIdAndExportOperationId(UUID tenantId, UUID exportOperationId);
}

