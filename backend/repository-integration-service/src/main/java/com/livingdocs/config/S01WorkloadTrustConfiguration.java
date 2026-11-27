package com.livingdocs.config;

import com.livingdocs.client.GitHubAuthorizationIo;
import com.livingdocs.model.GitHubAuthorizationError;
import com.livingdocs.model.SourceScope;
import com.livingdocs.security.SourceServiceAuthentication;
import com.livingdocs.security.SourceServiceIdentityMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestTemplate;

/** Receiver for the S01 PR2/PR3 service-token contract; never accepts the user-token trust profile. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "livingdocs.source.s01.enabled", havingValue = "true")
public class S01WorkloadTrustConfiguration {
    @Bean
    JwtDecoder s01WorkloadDecoder(@Value("${livingdocs.source.s01.jwk-set-uri:}") String endpoint,
            @Value("${livingdocs.source.s01.allow-local-http:false}") boolean local, GitHubAuthorizationIo io) {
        final NimbusJwtDecoder decoder;
        try {
            URI uri = URI.create(endpoint);
            boolean fixture = local && "http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1").contains(uri.getHost());
            if ((!fixture && !"https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
                    .followRedirects(HttpClient.Redirect.NEVER).build());
            factory.setReadTimeout(Duration.ofSeconds(1));
            var rest = new RestTemplate(factory);
            rest.getMessageConverters().clear();
            rest.getMessageConverters().add(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8) {
                @Override protected String readInternal(Class<? extends String> type, org.springframework.http.HttpInputMessage message)
                        throws java.io.IOException {
                    byte[] bytes = message.getBody().readNBytes(65537);
                    if (bytes.length > 65536) throw new java.io.IOException("JWKS exceeds the supported size");
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            });
            decoder = NimbusJwtDecoder.withJwkSetUri(endpoint).jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(rest).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(5)), jwt -> {
                try {
                    if (!"urn:livingdocs:s01".equals(jwt.getClaimAsString("iss")) || !List.of("livingdocs-s02-internal").equals(jwt.getAudience())
                            || !"service".equals(jwt.getClaimAsString("token_use")) || !"RS256".equals(jwt.getHeaders().get("alg"))
                            || !(jwt.getHeaders().get("kid") instanceof String kid) || kid.isBlank()
                            || jwt.getIssuedAt() == null || jwt.getNotBefore() == null || jwt.getExpiresAt() == null
                            || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(5))
                            || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                            || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(60))
                            || jwt.getNotBefore().isBefore(jwt.getIssuedAt().minusSeconds(5))
                            || jwt.getId() == null || !UUID.fromString(jwt.getId()).toString().equals(jwt.getId())) return invalid();
                    return OAuth2TokenValidatorResult.success();
                } catch (RuntimeException failed) { return invalid(); }
            }));
        } catch (RuntimeException invalid) {
            return token -> { throw new AuthenticationServiceException("S01 workload trust is unavailable"); };
        }
        return token -> {
            try { return io.call(Duration.ofSeconds(1), GitHubAuthorizationError.USER_TOKEN_TRUST_UNAVAILABLE, () -> decoder.decode(token)); }
            catch (com.livingdocs.exception.GitHubAuthorizationException timeout) {
                throw new AuthenticationServiceException("S01 workload trust is unavailable");
            } catch (JwtException invalid) {
                for (Throwable cause = invalid; cause != null; cause = cause.getCause())
                    if (cause instanceof java.io.IOException || cause instanceof org.springframework.web.client.RestClientException)
                        throw new AuthenticationServiceException("S01 workload trust is unavailable");
                throw invalid;
            }
        };
    }

    @Bean
    SourceServiceIdentityMapper s01WorkloadIdentityMapper() {
        return jwt -> {
            try {
                String operation = jwt.getClaimAsString("operation");
                String expectedSubject, path;
                switch (operation) {
                    case "repository-binding:verify" -> { expectedSubject="workload:s01"; path="/internal/v1/repository-bindings/verify"; }
                    case "source:resolve" -> { expectedSubject="workload:s03"; path="/internal/v1/source/resolve"; }
                    case "source:validate" -> { expectedSubject="workload:s04"; path="/internal/v1/source/validate"; }
                    default -> throw new IllegalArgumentException();
                }
                if (!expectedSubject.equals(jwt.getSubject()) || !"POST".equals(jwt.getClaimAsString("http_method"))
                        || !path.equals(jwt.getClaimAsString("http_path")) || jwt.hasClaim("action") || jwt.hasClaim("actor_token_sha256"))
                    throw new IllegalArgumentException();
                return new SourceServiceAuthentication(expectedSubject,
                        new SourceScope(uuid(jwt.getClaimAsString("tenant_id")),uuid(jwt.getClaimAsString("workspace_id"))),
                        uuid(jwt.getClaimAsString("repository_connection_id")), List.of(new SimpleGrantedAuthority(operation)));
            } catch (RuntimeException invalid) {
                throw new org.springframework.security.oauth2.server.resource.InvalidBearerTokenException("Service token does not match the receiver contract");
            }
        };
    }
    private static UUID uuid(String text) {
        UUID value = UUID.fromString(text);
        if (!value.toString().equalsIgnoreCase(text) || value.equals(new UUID(0,0))) throw new IllegalArgumentException();
        return value;
    }
    private static OAuth2TokenValidatorResult invalid() { return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token")); }
}
