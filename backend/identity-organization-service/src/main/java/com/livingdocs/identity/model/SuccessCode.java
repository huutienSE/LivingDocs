package com.livingdocs.identity.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SuccessCode {

    IDENTITY_REGISTERED("IDENTITY_REGISTERED", "Registration completed"),
    LOGIN_SUCCEEDED("LOGIN_SUCCEEDED", "Login completed"),
    ACCESS_CONTEXT_LOADED("ACCESS_CONTEXT_LOADED", "Access context loaded"),
    SERVICE_TOKEN_ISSUED("SERVICE_TOKEN_ISSUED", "Service token issued"),
    AUTHORIZATION_EVALUATED("AUTHORIZATION_EVALUATED", "Authorization evaluated");

    private final String wireCode;
    private final String defaultMessage;
}
