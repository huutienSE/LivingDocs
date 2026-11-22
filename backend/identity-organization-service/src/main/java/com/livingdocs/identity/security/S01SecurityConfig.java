package com.livingdocs.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class S01SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        ObjectMapper json = new ObjectMapper();
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json", "/actuator/health",
                                "/actuator/health/**", "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me",
                                "/api/v1/users/me/access-context").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ignored) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            writeProblem(json, request, response, 401, "UNAUTHENTICATED");
                        })
                        .accessDeniedHandler((request, response, ignored) ->
                                writeProblem(json, request, response, 403, "AUTHZ_DENIED")))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {})
                        .authenticationEntryPoint((request, response, ignored) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            writeProblem(json, request, response, 401, "UNAUTHENTICATED");
                        }))
                .build();
    }

    // xác minh chữ ký RS256, key ID, issuer, audience, subject và thời gian
    @Bean
    JwtDecoder jwtDecoder(JwtKeyManager keys,
                          @Value("${livingdocs.security.jwt.issuer}") String issuer,
                          @Value("${livingdocs.security.jwt.audience}") String audience) {
        return token -> {
            try {
                SignedJWT parsed = SignedJWT.parse(token);
                if (!JWSAlgorithm.RS256.equals(parsed.getHeader().getAlgorithm())
                        || parsed.getHeader().getKeyID() == null) {
                    throw new BadJwtException("Unsupported access token header");
                }
                RSAKey publicKey = (RSAKey) JWKSet.parse(keys.publicJwksJson())
                        .getKeyByKeyId(parsed.getHeader().getKeyID());
                if (publicKey == null) {
                    throw new BadJwtException("Unknown access token key");
                }
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey.toRSAPublicKey())
                        .signatureAlgorithm(SignatureAlgorithm.RS256).build();
                OAuth2TokenValidator<Jwt> claims = jwt -> {
                    try {
                        UUID.fromString(jwt.getSubject());
                        Instant issued = jwt.getIssuedAt();
                        if (!jwt.getAudience().contains(audience) || issued == null
                                || jwt.getNotBefore() == null || jwt.getExpiresAt() == null
                                || issued.isAfter(Instant.now().plusSeconds(30))
                                || jwt.getExpiresAt().isAfter(issued.plusSeconds(930))) {
                            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                        }
                        return OAuth2TokenValidatorResult.success();
                    } catch (RuntimeException ex) {
                        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                    }
                };
                decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer), claims));
                return decoder.decode(token);
            } catch (BadJwtException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new BadJwtException("Invalid access token", ex);
            }
        };
    }

    private void writeProblem(ObjectMapper json, HttpServletRequest request,
                              HttpServletResponse response, int status, String code) throws java.io.IOException {
        String correlation = request.getHeader("X-Correlation-ID");
        if (correlation == null || !correlation.matches("[A-Za-z0-9._:-]{1,128}")) {
            correlation = UUID.randomUUID().toString();
        }
        response.setStatus(status);
        response.setContentType(MediaType.valueOf("application/problem+json").toString());
        response.setHeader("X-Correlation-ID", correlation);
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), Map.of(
                "type", "about:blank", "title", status == 401 ? "Unauthenticated" : "Forbidden",
                "status", status, "detail", "The request is not authorized",
                "instance", "urn:uuid:" + UUID.randomUUID(), "code", code,
                "correlationId", correlation));
    }
}
