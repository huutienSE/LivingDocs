package com.livingdocs.gateway;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.mockito.Mockito.when;

/**
 * Exercises production YAML routes and real TCP connections, without requiring
 * unfinished S01 JWT issuance or modifying runtime routing configuration.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_ISSUER_URI=https://issuer.example.test",
                "JWT_AUDIENCE=livingdocs-api",
                "management.server.port=0",
                "spring.cloud.gateway.server.webflux.httpclient.connect-timeout=300",
                "spring.cloud.gateway.server.webflux.httpclient.response-timeout=300ms"
        }
)
@AutoConfigureWebTestClient
class GatewayDownstreamFailureIntegrationTest {

    // This upstream accepts TCP/HTTP but never responds within Gateway's timeout.
    private static final DisposableServer SLOW_S04 = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> response.sendString(
                    Mono.delay(Duration.ofSeconds(3)).map(ignored -> "late-response")))
            .bindNow();

    @DynamicPropertySource
    static void downstreamUris(DynamicPropertyRegistry registry) {
        // Port 1 should refuse connections on normal developer/CI machines.
        // The response-timeout test uses a bound, deterministic HTTP server.
        registry.add("S02_SERVICE_URI", () -> "http://127.0.0.1:1");
        registry.add("S04_SERVICE_URI", () -> "http://127.0.0.1:" + SLOW_S04.port());
    }

    @Autowired
    WebTestClient client;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void validAuthentication() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("fault-test-token")
                .header("alg", "RS256")
                .subject("gateway-fault-test")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
        when(jwtDecoder.decode("fault-test-token")).thenReturn(Mono.just(jwt));
    }

    @Test
    void refusedConnectionToS02Returns503() {
        client.get()
                .uri("/api/v1/repositories/repo-1/connection")
                .header(HttpHeaders.AUTHORIZATION, "Bearer fault-test-token")
                .header("X-Correlation-ID", "liv115-s02-down")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("X-Correlation-ID", "liv115-s02-down")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE")
                .jsonPath("$.correlationId").isEqualTo("liv115-s02-down");
    }

    @Test
    void slowS04Returns504() {
        client.get()
                .uri("/api/v1/documents/doc-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer fault-test-token")
                .header("X-Correlation-ID", "liv115-s04-timeout")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("X-Correlation-ID", "liv115-s04-timeout")
                .expectBody()
                .jsonPath("$.status").isEqualTo(504)
                .jsonPath("$.code").isEqualTo("UPSTREAM_TIMEOUT")
                .jsonPath("$.correlationId").isEqualTo("liv115-s04-timeout");
    }

    @AfterAll
    static void disposeSlowDownstream() {
        SLOW_S04.disposeNow();
    }
}
