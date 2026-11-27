package com.livingdocs.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import com.livingdocs.gateway.filter.GitHubAuthorizationGatewayPolicy;
import org.springframework.http.HttpStatus;

// ===========
// thuộc tầng SPRING SECURITY SecurityWebFilterChain,
// Tạo SecurityWebFilterChain, quy định public/protected API
// authen, author, jwt decode
//==========

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean @Order(0)
    public SecurityWebFilterChain githubAuthorizationCallbackChain(ServerHttpSecurity http) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET,
                        "/api/v1/integrations/github/authorization/callback"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(rules -> rules.anyExchange().permitAll()).build();
    }

    // chay webflux
    @Bean @Order(1)
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, UrlBasedCorsConfigurationSource corsConfigurationSource){
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource)) // goi den GatewayCorsConfig de ap dung quy tac CORS
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(
                                "/internal/v1",
                                "/internal/v1/**",
                                "/actuator",
                                "/actuator/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui",
                                "/swagger-ui/**"
                        ).denyAll()

                        // Public authentication entry points
                        .pathMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login"
                        ).permitAll()

                        .anyExchange().authenticated()
                )

                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(Customizer.withDefaults())
                                .authenticationEntryPoint((exchange, error) -> GitHubAuthorizationGatewayPolicy.applies(exchange)
                                        ? GitHubAuthorizationGatewayPolicy.problem(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
                                        : new BearerTokenServerAuthenticationEntryPoint().commence(exchange, error))
                                .accessDeniedHandler((exchange, error) -> GitHubAuthorizationGatewayPolicy.applies(exchange)
                                        ? GitHubAuthorizationGatewayPolicy.problem(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE")
                                        : new BearerTokenServerAccessDeniedHandler().handle(exchange, error))
                )
                .exceptionHandling(errors -> errors.authenticationEntryPoint((exchange, error) ->
                        GitHubAuthorizationGatewayPolicy.applies(exchange)
                                ? GitHubAuthorizationGatewayPolicy.problem(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
                                : new BearerTokenServerAuthenticationEntryPoint().commence(exchange, error)))
                .build();
    }
}
