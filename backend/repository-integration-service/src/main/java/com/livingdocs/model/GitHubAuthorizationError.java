package com.livingdocs.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter @RequiredArgsConstructor
public enum GitHubAuthorizationError {
    UNAUTHENTICATED(401, "User identity is missing or invalid"),
    USER_TOKEN_TRUST_UNAVAILABLE(503, "User token trust cannot currently be verified"),
    INVALID_REQUEST(400, "Request does not match the authorization contract"),
    FORBIDDEN_SCOPE(403, "User does not have the required Workspace permission"),
    WORKSPACE_NOT_FOUND(404, "Workspace was not found in the authorized scope"),
    AUTHORIZATION_CONTEXT_UNAVAILABLE(503, "Authorization context cannot currently be verified"),
    GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE(503, "GitHub authorization configuration is unavailable"),
    METHOD_NOT_ALLOWED(405, "This endpoint supports GET and HEAD"),
    NOT_ACCEPTABLE(406, "Requested response media type is not supported"),
    INTERNAL_ERROR(500, "Authorization request could not be completed");
    private final int status;
    private final String detail;
}
