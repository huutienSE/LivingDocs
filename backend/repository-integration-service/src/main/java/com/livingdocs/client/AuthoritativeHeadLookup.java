package com.livingdocs.client;

import com.livingdocs.model.SourceConnection;
import com.livingdocs.model.SourceHead;
import com.livingdocs.model.TargetKind;
import java.util.Optional;

/**
 * Đọc mới từ GitHub; chỉ trả rỗng khi xác nhận ref không tồn tại.
 * Lỗi phụ thuộc dự kiến được báo bằng {@link com.livingdocs.exception.SourceUnavailableException}.
 */
@FunctionalInterface
public interface AuthoritativeHeadLookup {
    Optional<SourceHead> readCurrent(SourceConnection connection, TargetKind kind, String ref);
}

