package com.livingdocs.repository.domain.model;

import java.util.UUID;

/** Kết quả đọc mới từ GitHub; bên triển khai phải kiểm tra cấu hình và phiên bản nguồn. */
public record SourceHead(SourceScope scope, UUID repositoryConnectionId, TargetKind targetKind, String targetRef,
                         String sha, String configRevision, String sourceRevision) {}

