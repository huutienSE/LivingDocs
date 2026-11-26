package com.livingdocs.document.model;

import java.time.Instant;

/** The durable receipt preserves the original response outcome across retries. */
public record SubmitResult(DocumentVersion version, Binding binding, String code, String message) {
    public record Binding(String targetKind, String targetRef, String comparisonSha, String configRevision, Instant capturedAt) {
        public boolean complete() {
            return ("PR".equals(targetKind) || "TRACKED_BRANCH".equals(targetKind)) && targetRef != null && !targetRef.isBlank()
                    && configRevision != null && !configRevision.isBlank() && capturedAt != null && !capturedAt.isAfter(Instant.now())
                    && (comparisonSha == null || comparisonSha.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"));
        }
    }
    public static SubmitResult recorded(DocumentVersion v, Binding b) {
        return new SubmitResult(v, b, SuccessCode.DOCUMENT_SUBMITTED.getWireCode(), SuccessCode.DOCUMENT_SUBMITTED.getDefaultMessage());
    }
}
