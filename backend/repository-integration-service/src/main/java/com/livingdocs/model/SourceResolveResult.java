package com.livingdocs.model;

import java.util.UUID;
import lombok.Value;
import lombok.experimental.Accessors;

/** Kết quả nghiệp vụ tra SHA. CURRENT biểu diễn đầu nguồn đã ghi thành công vào V3. */
@Value
@Accessors(fluent = true)
public class SourceResolveResult {
    private final SourceScope scope;
    private final UUID repositoryConnectionId;
    private final TargetKind targetKind;
    private final String targetRef;
    private final ValidationReason reason;
    private final String resolvedSha;
    private final String configRevision;
    private final String sourceRevision;
}
