package com.livingdocs.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SuccessCode {
    GITHUB_AUTHORIZATION_CONTEXT_READ("GITHUB_AUTHORIZATION_CONTEXT_READ", "GitHub authorization context retrieved"),
    GITHUB_AUTHORIZATION_CONTEXT_CREATED("GITHUB_AUTHORIZATION_CONTEXT_CREATED", "GitHub authorization context created"),
    GITHUB_INSTALLATIONS_LISTED("GITHUB_INSTALLATIONS_LISTED", "GitHub installations listed"),
    GITHUB_REPOSITORIES_LISTED("GITHUB_REPOSITORIES_LISTED", "GitHub repositories listed"),
    SOURCE_RESOLVED("SOURCE_RESOLVED", "Source resolved"),
    SOURCE_VALIDATED("SOURCE_VALIDATED", "Source validated");

    private final String wireCode;
    private final String defaultMessage;
}
