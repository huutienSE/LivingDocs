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
    AUTHORIZATION_EVALUATED("AUTHORIZATION_EVALUATED", "Authorization evaluated"),
    REPOSITORY_GRANT_REQUESTED("REPOSITORY_GRANT_REQUESTED", "Repository grant requested"),
    REPOSITORY_GRANTS_LOADED("REPOSITORY_GRANTS_LOADED", "Repository grants loaded"),
    REPOSITORY_GRANT_LOADED("REPOSITORY_GRANT_LOADED", "Repository grant loaded"),
    REPOSITORY_GRANT_VERIFIED("REPOSITORY_GRANT_VERIFIED", "Repository grant verified"),
    REPOSITORY_GRANT_REVOKED("REPOSITORY_GRANT_REVOKED", "Repository grant revoked");

    private final String wireCode;
    private final String defaultMessage;
}
