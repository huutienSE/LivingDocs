package com.livingdocs.repository.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Thông tin nghiệp vụ tối thiểu; không chứa thông tin xác thực hoặc tham chiếu bí mật. */
public record SourceConnection(SourceScope scope, UUID repositoryConnectionId, String configRevision, boolean active) {
    public SourceConnection {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(repositoryConnectionId);
    }
}

