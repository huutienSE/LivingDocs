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
    AUTHORIZATION_CONTEXT_NOT_FOUND(404, "Authorization context was not found in the authorized scope"),
    AUTHORIZATION_CONTEXT_EXPIRED(409, "Authorization context has expired; start authorization again"),
    AUTHORIZATION_CONTEXT_CONSUMED(409, "Authorization context is no longer available; start authorization again"),
    AUTHORIZATION_CONTEXT_NOT_READY(409, "User authorization has not completed"),
    AUTHORIZATION_CONFIGURATION_CHANGED(409, "GitHub App configuration changed; start authorization again"),
    AUTHORIZATION_CONTEXT_LIMIT_REACHED(429, "The active authorization context limit has been reached"),
    OAUTH_STATE_INVALID(400, "User authorization state is missing, invalid or expired"),
    GITHUB_REAUTHORIZATION_REQUIRED(409, "GitHub user authorization must be started again"),
    GITHUB_ACCESS_DENIED(403, "GitHub denied access for this authorization"),
    GITHUB_AUTHORIZATION_UNAVAILABLE(503, "GitHub authorization cannot currently be verified"),
    AUTHORIZATION_CONTEXT_UNAVAILABLE(503, "Authorization context cannot currently be verified"),
    GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE(503, "GitHub authorization configuration is unavailable"),
    METHOD_NOT_ALLOWED(405, "The request method is not supported for this endpoint"),
    NOT_ACCEPTABLE(406, "Requested response media type is not supported"),
    PAYLOAD_TOO_LARGE(413, "The request body exceeds the supported size"),
    UNSUPPORTED_MEDIA_TYPE(415, "This endpoint requires application/json"),
    INTERNAL_ERROR(500, "Authorization request could not be completed");
    private final int status;
    private final String detail;
}
