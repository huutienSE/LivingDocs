package com.livingdocs.exception;

import lombok.Getter;

@Getter
public final class SourceApiException extends RuntimeException {
    private final SourceApiError error;

    public SourceApiException(SourceApiError error) {
        this(error, error.getDetail());
    }

    private SourceApiException(SourceApiError error, String message) {
        super(message);
        this.error = error;
    }

    public static SourceApiException unavailable(UnavailableReason reason) {
        return new SourceApiException(SourceApiError.SOURCE_UNAVAILABLE, reason.message);
    }

    public boolean isSourceUnavailable() {
        return error == SourceApiError.SOURCE_UNAVAILABLE;
    }

    /** Nguyên nhân nội bộ để chẩn đoán. Phản hồi HTTP dùng thông báo công khai trong SourceApiError. */
    public enum UnavailableReason {
        GITHUB_RESPONSE_TOO_LARGE("GitHub response exceeds the maximum allowed size"),
        GITHUB_ACCESS_UNVERIFIED("GitHub repository access could not be verified"),
        GITHUB_SOURCE_UNVERIFIED("GitHub source data could not be verified"),
        STORED_OBSERVATION_UNVERIFIED("Stored source observation could not be verified"),
        REQUEST_DEADLINE_EXCEEDED("Source request deadline exceeded"),
        REQUEST_BUDGET_UNAVAILABLE("Source operation could not complete within the available request budget");

        private final String message;

        UnavailableReason(String message) {
            this.message = message;
        }
    }
}
