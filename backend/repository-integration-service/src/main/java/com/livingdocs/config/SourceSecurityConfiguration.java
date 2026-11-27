package com.livingdocs.config;

import com.livingdocs.exception.SourceApiError;
import com.livingdocs.exception.SourceProblems;
import com.livingdocs.security.SourceServiceIdentityMapper;
import com.livingdocs.security.SourceBodyLimitFilter;
import com.livingdocs.security.SourceTransportFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.config.ObjectPostProcessor;

@Configuration(proxyBeanMethods = false)
public class SourceSecurityConfiguration {
    private static SourceServiceIdentityMapper denyUnconfiguredIdentity() {
        // Chưa có contract claim workload/scope: tuyệt đối không suy quyền từ user JWT/header/body.
        return jwt -> { throw new AuthenticationServiceException("Service identity contract is not configured"); };
    }

    private static JwtDecoder denyUnconfiguredTrust() {
        return token -> { throw new org.springframework.security.oauth2.jwt.JwtException(
                "Service token trust is not configured"); };
    }

    @Bean
    SecurityFilterChain sourceSecurityFilterChain(HttpSecurity http, ObjectProvider<JwtDecoder> decoders,
                                                ObjectProvider<SourceServiceIdentityMapper> identityMappers,
                                                SourceProblems problems,
                                                @Value("${livingdocs.source.http.allow-insecure-transport:false}") boolean allowInsecure) throws Exception {
        JwtDecoder decoder = decoders.getIfAvailable(SourceSecurityConfiguration::denyUnconfiguredTrust);
        SourceServiceIdentityMapper identities = identityMappers.getIfAvailable(SourceSecurityConfiguration::denyUnconfiguredIdentity);
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/internal/v1/source/resolve").hasAuthority("source:resolve")
                        .requestMatchers("/internal/v1/source/validate").hasAuthority("source:validate")
                        .requestMatchers("/internal/v1/repository-bindings/verify").hasAuthority("repository-binding:verify")
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) ->
                                problems.write(SourceApiError.INVALID_SERVICE_IDENTITY, request, response))
                        .accessDeniedHandler((request, response, failure) ->
                                problems.write(SourceApiError.FORBIDDEN_SCOPE, request, response)))
                .oauth2ResourceServer(oauth -> oauth
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler((request, response, failure) -> problems.write(
                                        failure instanceof AuthenticationServiceException ? SourceApiError.SOURCE_UNAVAILABLE
                                                : SourceApiError.INVALID_SERVICE_IDENTITY, request, response));
                                return filter;
                            }
                        })
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(identities::authenticate))
                        .authenticationEntryPoint((request, response, failure) -> problems.write(
                                failure instanceof AuthenticationServiceException
                                        ? SourceApiError.SOURCE_UNAVAILABLE : SourceApiError.INVALID_SERVICE_IDENTITY,
                                request, response)));
        http.addFilterAfter(new SourceBodyLimitFilter(), AuthorizationFilter.class);
        http.addFilterBefore(new SourceTransportFilter(allowInsecure), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
