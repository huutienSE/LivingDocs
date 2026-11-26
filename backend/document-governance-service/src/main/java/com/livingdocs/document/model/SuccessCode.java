package com.livingdocs.document.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SuccessCode {
    STAFF_REVIEW_RECORDED("STAFF_REVIEW_RECORDED", "Staff review decision recorded"),
    DOCUMENT_SUBMITTED("DOCUMENT_SUBMITTED", "Document submitted for Staff Review");

    private final String wireCode;
    private final String defaultMessage;
}
