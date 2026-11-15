package com.livingdocs.model;

import java.util.UUID;
import lombok.Value;
import lombok.experimental.Accessors;

/** Kết quả đọc mới từ GitHub; bên triển khai phải kiểm tra cấu hình và phiên bản nguồn. */
@Value
@Accessors(fluent = true)
public class SourceHead {
    private final SourceScope scope;
    private final UUID repositoryConnectionId;
    private final TargetKind targetKind;
    private final String targetRef;
    private final String sha;
    private final String configRevision;
    private final String sourceRevision;
}

