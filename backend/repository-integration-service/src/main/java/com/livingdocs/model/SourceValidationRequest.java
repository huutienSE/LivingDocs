package com.livingdocs.model;

import java.util.UUID;
import lombok.Value;
import lombok.experimental.Accessors;

/** Nguồn và phiên bản kỳ vọng đã gắn với tài liệu. */
@Value
@Accessors(fluent = true)
public class SourceValidationRequest {
    private final UUID repositoryConnectionId;
    private final TargetKind targetKind;
    private final String targetRef;
    private final String expectedHeadSha;
    private final String expectedConfigRevision;
}

