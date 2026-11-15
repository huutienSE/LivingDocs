package com.livingdocs.model;

import java.util.Objects;
import java.util.UUID;
import lombok.Value;
import lombok.experimental.Accessors;

/** Phạm vi lấy từ quyền truy cập đã xác thực, không lấy từ dữ liệu chưa kiểm chứng. */
@Value
@Accessors(fluent = true)
public class SourceScope {
    private final UUID tenantId;
    private final UUID workspaceId;

    public SourceScope(UUID tenantId, UUID workspaceId) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.workspaceId = Objects.requireNonNull(workspaceId);
    }
}

