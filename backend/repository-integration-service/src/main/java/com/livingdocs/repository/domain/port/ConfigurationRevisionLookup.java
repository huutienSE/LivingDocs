package com.livingdocs.repository.domain.port;

import com.livingdocs.repository.domain.model.SourceScope;
import java.util.Optional;
import java.util.UUID;

/**
 * Lấy phiên bản cấu hình hiện hành do S02 quản lý. V1 chưa có cơ chế cung cấp phiên bản này.
 * Không lấy expectedConfigRevision từ yêu cầu, biên nhận cũ hoặc tự sinh UUID làm giá trị thay thế.
 * Trả rỗng nếu chưa có bằng chứng cấu hình đáng tin cậy; thực hiện trong giao dịch tra cứu kết nối.
 */
@FunctionalInterface
public interface ConfigurationRevisionLookup {
    Optional<String> findCurrent(SourceScope scope, UUID repositoryConnectionId);
}

