package com.livingdocs.gateway.config;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;


//================
// TẦN SecurityWwbFilterChain,
// Chịu trách nhiệm cung cấp cấu hình JWT decoder (giải mã) để xác minh chữ ký và claims
//================
@Configuration
public class GatewayJwtTrustConfig {

    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwksUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences[0]}") String audience) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .webClient(boundedJwksWebClient(jwksUri)).build();

        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));

        OAuth2TokenValidator<Jwt> contract = token -> {
            try {
                UUID.fromString(token.getSubject());
                if (!issuer.equals(token.getClaimAsString("iss"))
                        || !token.getAudience().contains(audience)
                        || token.getIssuedAt() == null || token.getNotBefore() == null
                        || token.getExpiresAt() == null
                        || token.getIssuedAt().isAfter(Instant.now().plusSeconds(30))
                        || token.getExpiresAt().isAfter(token.getIssuedAt().plusSeconds(930))
                        || token.getHeaders().get("kid") == null
                        || !"RS256".equals(token.getHeaders().get("alg"))) {
                    return invalid();
                }
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException ex) {
                return invalid();
            }
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, contract));

        return decoder;
    }

    private static OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    private static WebClient boundedJwksWebClient(String jwksUri) {
        WebClient upstream = WebClient.create();
        AtomicReference<Mono<String>> cached = new AtomicReference<>();
        AtomicLong refreshAfter = new AtomicLong();
        long refreshInterval = Duration.ofSeconds(30).toNanos();
        return WebClient.builder().filter((request, next) -> {
            if (!jwksUri.equals(request.url().toString())) {
                return Mono.error(new IllegalStateException("Unexpected JWKS request URL"));
            }
            Mono<String> body = cached.get();
            long now = System.nanoTime();
            if (body == null || now - refreshAfter.get() >= 0) {
                synchronized (cached) {
                    body = cached.get();
                    if (body == null || now - refreshAfter.get() >= 0) {
                        body = upstream.get().uri(jwksUri).retrieve().bodyToMono(String.class)
                                .timeout(Duration.ofSeconds(2)).cache();
                        cached.set(body);
                        refreshAfter.set(now + refreshInterval);
                    }
                }
            }
            return body.map(json -> ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(json).build());
        }).build();
    }
}
