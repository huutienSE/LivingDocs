package com.livingdocs.document.exception;

public final class DocumentVersionNotFoundException extends ReviewException {
    public DocumentVersionNotFoundException() {
        super(ReviewErrorCode.DOCUMENT_VERSION_NOT_FOUND, "Document version not found.");
    }
}
