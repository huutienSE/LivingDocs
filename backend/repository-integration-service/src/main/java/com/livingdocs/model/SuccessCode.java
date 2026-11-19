package com.livingdocs.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SuccessCode {
    SOURCE_RESOLVED("SOURCE_RESOLVED", "Source resolved"),
    SOURCE_VALIDATED("SOURCE_VALIDATED", "Source validated");

    private final String wireCode;
    private final String defaultMessage;
}
