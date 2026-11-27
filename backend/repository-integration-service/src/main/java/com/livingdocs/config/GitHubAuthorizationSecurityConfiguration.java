package com.livingdocs.config;

import com.livingdocs.exception.GitHubAuthorizationProblems;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.client.GitHubAuthorizationIo;
import com.livingdocs.model.GitHubAuthorizationError;
import com.livingdocs.security.GitHubAuthorizationRequestFilter;
import java.time.*;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubAuthorizationProperties.class)
public class GitHubAuthorizationSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain githubAuthorizationSecurity(HttpSecurity http, GitHubAuthorizationProperties p,
                                                   GitHubAuthorizationProblems problems, GitHubAuthorizationIo io) throws Exception {
        var requestSecurityContext = new org.springframework.security.web.context.RequestAttributeSecurityContextRepository();
        http.securityMatcher("/api/v1/integrations/github/authorization", "/api/v1/integrations/github/authorization-contexts", "/api/v1/integrations/github/authorization-contexts/**")
                .csrf(c -> c.disable()).requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(s -> s.securityContextRepository(requestSecurityContext))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((r,s,x) -> problems.write(GitHubAuthorizationError.UNAUTHENTICATED,r,s))
                        .accessDeniedHandler((r,s,x) -> problems.write(GitHubAuthorizationError.FORBIDDEN_SCOPE,r,s)))
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(userDecoder(p, io)))
                        .withObjectPostProcessor(new org.springframework.security.config.ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setSecurityContextRepository(requestSecurityContext);
                                filter.setAuthenticationFailureHandler((r,s,x) -> problems.write(
                                        x instanceof AuthenticationServiceException ? GitHubAuthorizationError.USER_TOKEN_TRUST_UNAVAILABLE
                                                : GitHubAuthorizationError.UNAUTHENTICATED,r,s));
                                return filter;
                            }
                        })
                        .authenticationEntryPoint((r,s,x) -> problems.write(GitHubAuthorizationError.UNAUTHENTICATED,r,s)));
        http.addFilterAfter(new GitHubAuthorizationRequestFilter(problems,p,io), AuthorizationFilter.class);
        http.addFilterAfter(new com.livingdocs.security.GitHubAuthorizationContextRequestFilter(problems,p,io,false), AuthorizationFilter.class);
        http.addFilterBefore(new com.livingdocs.security.GitHubAuthorizationDeadlineFilter(problems), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean @Order(-1)
    SecurityFilterChain githubAuthorizationCallback(HttpSecurity http, GitHubAuthorizationProperties p,
                                                   GitHubAuthorizationProblems problems, GitHubAuthorizationIo io) throws Exception {
        return http.securityMatcher("/api/v1/integrations/github/authorization/callback")
                .csrf(c -> c.disable()).requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .addFilterBefore(new com.livingdocs.security.GitHubAuthorizationDeadlineFilter(problems), AuthorizationFilter.class)
                .addFilterAfter(new com.livingdocs.security.GitHubAuthorizationContextRequestFilter(problems,p,io,true), AuthorizationFilter.class)
                .build();
    }

    /** Deliberately NOT a JwtDecoder bean: source workload trust must remain untouched. */
    private JwtDecoder userDecoder(GitHubAuthorizationProperties p, GitHubAuthorizationIo io) {
        if (p.getJwkSetUri().isBlank()) return token -> { throw new AuthenticationServiceException("User trust is not configured"); };
        final NimbusJwtDecoder decoder;
        try {
            String uri = p.trustedUri(p.getJwkSetUri()).toString();
            var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(500)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
            factory.setReadTimeout(Duration.ofSeconds(1));
            var rest = new RestTemplate(factory);
            rest.getMessageConverters().clear();
            rest.getMessageConverters().add(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8) {
                @Override protected String readInternal(Class<? extends String> type, org.springframework.http.HttpInputMessage message)
                        throws java.io.IOException {
                    byte[] bytes = message.getBody().readNBytes(65537);
                    if (bytes.length > 65536) throw new java.io.IOException("JWKS response exceeds the configured bound");
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            });
            decoder = NimbusJwtDecoder.withJwkSetUri(uri).jwsAlgorithm(SignatureAlgorithm.RS256)
                    .restOperations(rest).build();
            OAuth2TokenValidator<Jwt> identity = jwt -> {
                try {
                    if (!UUID.fromString(jwt.getSubject()).toString().equalsIgnoreCase(jwt.getSubject())
                            || UUID.fromString(jwt.getSubject()).equals(new UUID(0,0))
                            || !p.getIssuer().equals(jwt.getClaimAsString("iss")) || !jwt.getAudience().contains(p.getAudience())
                            || jwt.getHeaders().get("kid") == null || !"RS256".equals(jwt.getHeaders().get("alg"))
                            || jwt.getIssuedAt() == null || jwt.getNotBefore() == null || jwt.getExpiresAt() == null
                            || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(30))
                            || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                            || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(930))) return invalid();
                    return OAuth2TokenValidatorResult.success();
                } catch (RuntimeException e) { return invalid(); }
            };
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)), identity));
        } catch (RuntimeException failure) {
            return token -> { throw new AuthenticationServiceException("User trust is not configured"); };
        }
        return token -> {
            try { return io.call(Duration.ofSeconds(1), GitHubAuthorizationError.USER_TOKEN_TRUST_UNAVAILABLE,
                    () -> decoder.decode(token)); }
            catch (GitHubAuthorizationException busyOrExpired) {
                throw new AuthenticationServiceException("User trust cannot currently be verified");
            }
            catch (JwtException e) {
                for (Throwable cause=e; cause!=null; cause=cause.getCause()) {
                    if (cause instanceof java.io.IOException || cause instanceof org.springframework.web.client.RestClientException)
                        throw new AuthenticationServiceException("User trust cannot be verified");
                }
                throw e;
            }
        };
    }
    private static OAuth2TokenValidatorResult invalid() { return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token")); }
}
