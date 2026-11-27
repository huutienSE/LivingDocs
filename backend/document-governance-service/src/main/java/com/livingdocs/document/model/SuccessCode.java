package com.livingdocs.document.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SuccessCode {
    STAFF_REVIEW_RECORDED("STAFF_REVIEW_RECORDED", "Staff review decision recorded"),
    DOCUMENT_SUBMITTED("DOCUMENT_SUBMITTED", "Document submitted for Staff Review"),
    DOCUMENT_CREATED("DOCUMENT_CREATED", "Document created"),
    DOCUMENT_LOADED("DOCUMENT_LOADED", "Document loaded"),
    DOCUMENT_VERSIONS_LOADED("DOCUMENT_VERSIONS_LOADED", "Document versions loaded"),
    DOCUMENT_VERSION_CREATED("DOCUMENT_VERSION_CREATED", "Draft created"),
    DOCUMENT_VERSION_LOADED("DOCUMENT_VERSION_LOADED", "Document version loaded"),
    DOCUMENT_CONTENT_UPDATED("DOCUMENT_CONTENT_UPDATED", "Draft content updated"),
    DOCUMENT_EVIDENCE_LOADED("DOCUMENT_EVIDENCE_LOADED", "Document evidence loaded");

    private final String wireCode;
    private final String defaultMessage;
}
