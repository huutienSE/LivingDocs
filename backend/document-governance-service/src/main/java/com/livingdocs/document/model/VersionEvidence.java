package com.livingdocs.document.model;
import java.util.List;
import java.util.UUID;

public record VersionEvidence(UUID versionId, VersionDetails.Binding sourceBinding, List<Item> evidence) {
    public record Item(UUID evidenceId, String type, String sourceRef, String artifactRef) {}
}
