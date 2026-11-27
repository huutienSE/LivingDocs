package com.livingdocs.document.security;

import com.livingdocs.document.exception.ApiProblems;
import com.livingdocs.document.exception.ReviewErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;

@Configuration
public class ReviewSecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${livingdocs.security.issuer-uri:}") String issuer,
                          @Value("${livingdocs.security.jwk-set-uri:}") String jwks,
                          @Value("${livingdocs.security.audience:}") String audience) {
        // Missing S01 trust configuration must never enable a bypass.
        if (issuer.isBlank() || jwks.isBlank() || audience.isBlank()) {
            return token -> { throw new BadJwtException("Access-token trust is not configured."); };
        }
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            boolean identitySubject = false;
            try {
                identitySubject = java.util.UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject());
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // S01 access tokens identify a canonical Identity UUID.
            }
            if (jwt.getExpiresAt() == null || !identitySubject || !jwt.getAudience().contains(audience)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), requiredClaims));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiProblems problems) throws Exception {
        var entryPoint = (AuthenticationEntryPoint) (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            problems.write(request, response, ReviewErrorCode.UNAUTHENTICATED, "A valid access token is required.");
        };
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/v3/api-docs", "/v3/api-docs.yaml").authenticated()
                        .requestMatchers("/api/v1/document-versions/**").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/approval-queue").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler((request, response, exception) ->
                        problems.write(request, response, ReviewErrorCode.REVIEW_FORBIDDEN, "Access denied.")))
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint(entryPoint)).build();
    }
}
