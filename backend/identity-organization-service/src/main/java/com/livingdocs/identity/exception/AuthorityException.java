package com.livingdocs.identity.exception;

import lombok.Getter;

@Getter
public class AuthorityException extends RuntimeException {
    private final String code;

    public AuthorityException(String code) {
        super(code);
        this.code = code;
    }
}
