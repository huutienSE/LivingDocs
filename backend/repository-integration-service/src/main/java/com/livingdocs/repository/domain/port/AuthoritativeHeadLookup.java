package com.livingdocs.repository.domain.port;

import com.livingdocs.repository.domain.model.SourceConnection;
import com.livingdocs.repository.domain.model.SourceHead;
import com.livingdocs.repository.domain.model.TargetKind;
import java.util.Optional;

/** Đọc mới từ GitHub; chỉ trả rỗng khi xác nhận ref không tồn tại, lỗi phụ thuộc phải ném ngoại lệ. */
@FunctionalInterface
public interface AuthoritativeHeadLookup {
    Optional<SourceHead> readCurrent(SourceConnection connection, TargetKind kind, String ref);
}

