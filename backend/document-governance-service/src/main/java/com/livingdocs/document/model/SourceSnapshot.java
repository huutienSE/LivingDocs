package com.livingdocs.document.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trusted provider output. Browser requests carry only its opaque ID. */
public record SourceSnapshot(UUID id, AuthorizationScope scope, String headSha, String targetKind,
                             String targetRef, String comparisonSha, String configRevision,
                             Instant capturedAt, List<Evidence> evidence) {
    public SourceSnapshot { evidence = List.copyOf(evidence); }
    public record Evidence(String type, String sourceRef, String artifactRef) {}
}
