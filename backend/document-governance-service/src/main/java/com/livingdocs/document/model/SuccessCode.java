package com.livingdocs.document.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SuccessCode {
    STAFF_REVIEW_RECORDED("STAFF_REVIEW_RECORDED", "Staff review decision recorded"),
    DOCUMENT_SUBMITTED("DOCUMENT_SUBMITTED", "Document submitted for Staff Review"),
    REVIEW_QUEUE_LOADED("REVIEW_QUEUE_LOADED", "Staff review queue loaded"),
    REVIEW_CONTEXT_LOADED("REVIEW_CONTEXT_LOADED", "Staff review context loaded");

    private final String wireCode;
    private final String defaultMessage;
}
