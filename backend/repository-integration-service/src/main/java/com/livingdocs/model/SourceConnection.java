package com.livingdocs.model;

import java.util.Objects;
import java.util.UUID;
import lombok.Value;
import lombok.experimental.Accessors;

/** Thông tin nghiệp vụ tối thiểu; không chứa thông tin xác thực hoặc tham chiếu bí mật. */
@Value
@Accessors(fluent = true)
public class SourceConnection {
    private final SourceScope scope;
    private final UUID repositoryConnectionId;
    private final String configRevision;
    private final boolean active;

    public SourceConnection(SourceScope scope, UUID repositoryConnectionId, String configRevision, boolean active) {
        this.scope = Objects.requireNonNull(scope);
        this.repositoryConnectionId = Objects.requireNonNull(repositoryConnectionId);
        this.configRevision = configRevision;
        this.active = active;
    }
}

