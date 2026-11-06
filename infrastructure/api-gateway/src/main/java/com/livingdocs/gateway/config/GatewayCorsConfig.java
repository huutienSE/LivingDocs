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

        // LIV-133: FE Staff/Manager commands require If-Match (dùng cơ chế optimistic lock khi phê duyệt kèm theo ETag cũ mà họ đang xem, chặn ghi đè khi ETag bị lệch)
        // Idempotency-Key (chống lặp req, mã khóa giúp BE nhận diện xử lý đúng 1 lần khi click nhiều lần)
        cors.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Accept", "X-Correlation-ID", "If-Match", "Idempotency-Key")
        );

        cors.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Accept", "X-Correlation-ID")
        );

        // các header mà trình duyệt có thể đọc từ response
        // Browser must be able to read the strong ETag returned by review-context and mutations.
        cors.setExposedHeaders(List.of("X-Correlation-ID", "ETag"));

        // cau hinh credentials, caching
        cors.setAllowCredentials(false); // khong cho phep gui kem coookie
        cors.setMaxAge(3600L); // luu ket qua cua OPTION vao cache

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
