package com.livingdocs.document.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import static org.assertj.core.api.Assertions.*;

class ReviewSecurityConfigurationTest {
    @Test
    void missingTrustConfiguration_shouldFailClosed() {
        var config = new ReviewSecurityConfiguration();
        assertThatThrownBy(() -> config.jwtDecoder("", "", "").decode("any-token")).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> config.jwtDecoder("https://issuer.invalid", "https://issuer.invalid/jwks", "").decode("any-token"))
                .isInstanceOf(BadJwtException.class);
    }
}
