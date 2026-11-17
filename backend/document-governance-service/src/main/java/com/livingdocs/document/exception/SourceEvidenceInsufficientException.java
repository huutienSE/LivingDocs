package com.livingdocs.document.exception;

public final class SourceEvidenceInsufficientException extends ReviewException {
    public SourceEvidenceInsufficientException() {
        super(ReviewErrorCode.SOURCE_EVIDENCE_INSUFFICIENT, "Source-derived versions require a bound source SHA.");
    }
}
