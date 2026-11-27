package com.livingdocs.model;

import java.util.UUID;
import lombok.Value;
import lombok.experimental.Accessors;

/** Yêu cầu tra SHA trong phạm vi đã xác thực. explicitSha là SHA kỳ vọng tùy chọn. */
@Value
@Accessors(fluent = true)
public class SourceResolveRequest {
    private final UUID repositoryConnectionId;
    private final TargetKind targetKind;
    private final String targetRef;
    private final String explicitSha;
}
