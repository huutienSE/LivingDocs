package com.livingdocs.repository.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Phạm vi lấy từ quyền truy cập đã xác thực, không lấy từ dữ liệu chưa kiểm chứng. */
public record SourceScope(UUID tenantId, UUID workspaceId) {
    public SourceScope {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(workspaceId);
    }
}

