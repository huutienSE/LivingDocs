package com.livingdocs.repository;

import com.livingdocs.model.SourceScope;
import java.util.Optional;
import java.util.UUID;

/**
 * Đọc phiên bản cấu hình hiện hành do S02 quản lý trong giao dịch tra cứu đã đặt tenant.
 * Phiên bản là chuỗi bất biến dùng để so khớp chính xác. Trả rỗng khi không có bản ghi trong phạm vi.
 */
@FunctionalInterface
public interface ConfigurationRevisionLookup {
    Optional<String> findCurrent(SourceScope scope, UUID repositoryConnectionId);
}

