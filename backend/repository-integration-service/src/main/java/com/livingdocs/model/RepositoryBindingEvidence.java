package com.livingdocs.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter @RequiredArgsConstructor
public final class RepositoryBindingEvidence {
    private final UUID tenantId;
    private final UUID workspaceId;
    private final UUID repositoryConnectionId;
    private final String status;
    private final long bindingRevision;
    private final Instant observedAt;
}
