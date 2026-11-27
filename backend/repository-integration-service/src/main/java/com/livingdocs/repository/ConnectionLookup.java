package com.livingdocs.repository;

import com.livingdocs.model.SourceConnection;
import com.livingdocs.model.SourceScope;
import java.util.Optional;
import java.util.UUID;

/** Tra cứu kết nối trong phạm vi đã xác thực; kết nối ngoài phạm vi trả kết quả rỗng. */
@FunctionalInterface
public interface ConnectionLookup {
    Optional<SourceConnection> find(SourceScope scope, UUID repositoryConnectionId);
}

