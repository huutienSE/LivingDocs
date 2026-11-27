package com.livingdocs.security;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseCookie;

public final class GitHubAuthorizationCookies {
    private GitHubAuthorizationCookies() {}
    public static ResponseCookie nonce(UUID context, String value, Instant expires) {
        return ResponseCookie.from("__Secure-ld-gh-" + context, value).secure(true).httpOnly(true).sameSite("Lax")
                .path("/api/v1/integrations/github").maxAge(Math.max(0, Duration.between(Instant.now(), expires).toSeconds())).build();
    }
    public static ResponseCookie clear(UUID context) { return nonce(context, "", Instant.EPOCH); }
}
