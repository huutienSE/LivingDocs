package com.livingdocs.exception;

/** Một thành phần phụ thuộc không thể cung cấp bằng chứng xác minh nguồn. */
public final class SourceUnavailableException extends RuntimeException {
    public SourceUnavailableException(String message) {
        super(message);
    }
}
