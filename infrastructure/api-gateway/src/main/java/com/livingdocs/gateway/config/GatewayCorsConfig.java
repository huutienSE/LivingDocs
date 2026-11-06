package com.livingdocs.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;


import java.util.Arrays;
import java.util.List;

@Configuration
public class GatewayCorsConfig {
    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource(@Value("${gateway.cors.allowed-origins}") String origins){
        CorsConfiguration cors = new CorsConfiguration();

        // xu ly danh sach origin duoc phep
        cors.setAllowedOrigins(Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList()
        );

        // OPTIONS trinh duyet gui di truoc de kiem tra truoc khi gui cacs req chinh thuc
        cors.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        );

        cors.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Accept", "X-Correlation-ID")
        );

        cors.setExposedHeaders(List.of("X-Correlation-ID"));

        // cau hinh credentials, caching
        cors.setAllowCredentials(false); // khong cho phep gui kem coookie
        cors.setMaxAge(3600L); // luu ket qua cua OPTION vao cache

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
