package com.livingdocs.document.client;

import com.livingdocs.document.model.AuthorizationScope;
import com.livingdocs.document.model.SourceSnapshot;
import java.util.UUID;

public interface SourceSnapshotProvider {
    SourceSnapshot resolve(UUID snapshotId, AuthorizationScope scope);
}
