package com.livingdocs.repository.domain.port;

import com.livingdocs.repository.domain.model.SourceConnection;
import com.livingdocs.repository.domain.model.SourceScope;
import java.util.Optional;
import java.util.UUID;

/** Tra cứu kết nối trong phạm vi đã xác thực; kết nối ngoài phạm vi trả kết quả rỗng. */
@FunctionalInterface
public interface ConnectionLookup {
    Optional<SourceConnection> find(SourceScope scope, UUID repositoryConnectionId);
}

