package com.livingdocs.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    // chay webflux
    @Bean
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
                                "/actuator/**"
                        ).denyAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(Customizer.withDefaults())
                )
                .build();
    }
}
