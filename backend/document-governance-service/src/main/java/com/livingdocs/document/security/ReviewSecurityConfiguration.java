package com.livingdocs.document.security;

import com.livingdocs.document.api.controller.ApiProblems;
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
        // S01 is not implemented in this checkout. Missing trust configuration must never enable a bypass.
        if (issuer.isBlank() || jwks.isBlank() || audience.isBlank()) {
            return token -> { throw new BadJwtException("Access-token trust is not configured."); };
        }
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            if (jwt.getExpiresAt() == null || jwt.getSubject() == null || !jwt.getAudience().contains(audience)) {
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
            problems.write(request, response, 401, "UNAUTHENTICATED", "A valid access token is required.");
        };
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/document-versions/**").authenticated().anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler((request, response, exception) ->
                        problems.write(request, response, 403, "REVIEW_FORBIDDEN", "Access denied.")))
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint(entryPoint)).build();
    }
}
