package com.livingdocs.identity.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SuccessCode {

    IDENTITY_REGISTERED("IDENTITY_REGISTERED", "Registration completed");

    private final String wireCode;
    private final String defaultMessage;
}
