package com.livingdocs.identity.dto.response;

import lombok.Getter;

@Getter
public class LoginResponse {

    private final String accessToken;
    private final String tokenType = "Bearer";
    private final long expiresIn;

    public LoginResponse(String accessToken, long expiresIn) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
    }
}
