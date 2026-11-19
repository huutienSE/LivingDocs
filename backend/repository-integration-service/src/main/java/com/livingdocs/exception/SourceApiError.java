package com.livingdocs.exception;

import com.livingdocs.model.ValidationReason;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Mã và thông báo HTTP công khai; không đưa lỗi provider/SQL ra bên gọi. */
@Getter
@RequiredArgsConstructor
public enum SourceApiError {
    INVALID_SERVICE_IDENTITY(401, "Service identity is missing or invalid"),
    FORBIDDEN_SCOPE(403, "Service identity does not have the required resource permission"),
    INVALID_REQUEST(400, "Request does not match the source contract"),
    METHOD_NOT_ALLOWED(405, "This operation requires POST"),
    PAYLOAD_TOO_LARGE(413, "Request body exceeds 16 KiB"),
    UNSUPPORTED_MEDIA_TYPE(415, "This operation requires application/json"),
    NOT_ACCEPTABLE(406, "Requested response media type is not supported"),
    NOT_FOUND(404, "Resource was not found"),
    UNKNOWN_CONNECTION(404, "Repository connection was not found in the authorized scope"),
    CONNECTION_INACTIVE(409, "Repository connection is not ready"),
    CONFIG_CHANGED(409, "Source configuration has changed"),
    UNKNOWN_REF(404, "Source target was not found or is not tracked"),
    STALE_SHA(409, "Current source head differs from the expected SHA"),
    SOURCE_OBSERVATION_CONFLICT(409, "Another source observation was accepted first"),
    VALIDATION_EXPIRED(409, "Source validation evidence has expired"),
    SOURCE_UNAVAILABLE(503, "Source cannot currently be verified"),
    INTERNAL_ERROR(500, "Source operation could not be completed");

    private final int status;
    private final String detail;

    public static SourceApiError from(ValidationReason reason) {
        return switch (reason) {
            case INVALID_REQUEST -> INVALID_REQUEST;
            case UNKNOWN_CONNECTION -> UNKNOWN_CONNECTION;
            case CONNECTION_INACTIVE -> CONNECTION_INACTIVE;
            case CONFIG_CHANGED -> CONFIG_CHANGED;
            case UNKNOWN_REF -> UNKNOWN_REF;
            case STALE_SHA -> STALE_SHA;
            case SOURCE_OBSERVATION_CONFLICT -> SOURCE_OBSERVATION_CONFLICT;
            case VALIDATION_EXPIRED -> VALIDATION_EXPIRED;
            case INVALID_SOURCE, SOURCE_BINDING_MISMATCH, SOURCE_UNAVAILABLE -> SOURCE_UNAVAILABLE;
            case CURRENT -> throw new IllegalArgumentException("CURRENT is not an error");
        };
    }
}
