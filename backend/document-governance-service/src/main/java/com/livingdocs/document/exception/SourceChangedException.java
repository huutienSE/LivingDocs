package com.livingdocs.document.exception;

public final class SourceChangedException extends ReviewException {
    public SourceChangedException() {
        super(ReviewErrorCode.SOURCE_CHANGED_REVALIDATION_REQUIRED, "The bound source is no longer current. Revalidate before passing Staff Review.");
    }
}
