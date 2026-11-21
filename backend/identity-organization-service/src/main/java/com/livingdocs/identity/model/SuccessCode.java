package com.livingdocs.identity.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SuccessCode {

    IDENTITY_REGISTERED("IDENTITY_REGISTERED", "Registration completed"),
    LOGIN_SUCCEEDED("LOGIN_SUCCEEDED", "Login completed"),
    ACCESS_CONTEXT_LOADED("ACCESS_CONTEXT_LOADED", "Access context loaded");

    private final String wireCode;
    private final String defaultMessage;
}
