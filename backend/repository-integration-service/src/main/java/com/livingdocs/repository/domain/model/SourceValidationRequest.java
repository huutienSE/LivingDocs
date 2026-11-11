package com.livingdocs.repository.domain.model;

import java.util.UUID;

/** Nguồn và phiên bản kỳ vọng đã gắn với tài liệu. */
public record SourceValidationRequest(UUID repositoryConnectionId, TargetKind targetKind, String targetRef,
                                      String expectedHeadSha, String expectedConfigRevision) {}

