
package com.livingdocs.gateway;

import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_ISSUER_URI=https://issuer.example.test",
                "JWT_AUDIENCE=livingdocs-api",
                "management.server.port=0"
        }
)
@AutoConfigureWebTestClient
class GatewayRoutingIntegrationTest {

    private static final DisposableServer S01 = startStub("S01");
    private static final DisposableServer S02 = startStub("S02");
    private static final DisposableServer S04 = startStub("S04");

    @DynamicPropertySource
    static void configureDownstreamUris(DynamicPropertyRegistry registry) {
        registry.add("S01_SERVICE_URI",
                () -> "http://127.0.0.1:" + S01.port());
        registry.add("S02_SERVICE_URI",
                () -> "http://127.0.0.1:" + S02.port());
        registry.add("S04_SERVICE_URI",
                () -> "http://127.0.0.1:" + S04.port());
    }

    private static DisposableServer startStub(String service) {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response
                        .header("Content-Type", "text/plain")
                        .sendString(Mono.just(
                                service + "|"
                                        + request.method().name() + "|"
                                        + request.uri() + "|"
                                        + request.requestHeaders()
                                        .get("X-Correlation-ID")
                        )))
                .bindNow();
    }

    @Autowired
    WebTestClient client;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void configureJwt() {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue("routing-token")
                .header("alg", "RS256")
                .subject("test-user")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();

        when(jwtDecoder.decode("routing-token"))
                .thenReturn(Mono.just(jwt));
    }

    @Test
    void routesIdentityRequestToS01() {
        client.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer routing-token")
                .header("X-Correlation-ID", "b2-s01")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-ID", "b2-s01")
                .expectBody(String.class)
                .isEqualTo("S01|GET|/api/v1/users/me|b2-s01");
    }

    @Test
    void routesRepositoryRequestToS02() {
        client.get()
                .uri("/api/v1/repositories/repo-1/connection")
                .header(HttpHeaders.AUTHORIZATION, "Bearer routing-token")
                .header("X-Correlation-ID", "b2-s02")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo(
                        "S02|GET|/api/v1/repositories/repo-1/connection|b2-s02"
                );
    }

    @Test
    void routesDocumentRequestToS04() {
        client.get()
                .uri("/api/v1/documents/doc-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer routing-token")
                .header("X-Correlation-ID", "b2-s04")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo("S04|GET|/api/v1/documents/doc-1|b2-s04");
    }

    @Test
    void publicLoginDoesNotRequireJwt() {
        client.post()
                .uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body ->
                        org.junit.jupiter.api.Assertions.assertTrue(
                                body.startsWith("S01|POST|/api/v1/auth/login|")
                        )
                );
    }

    @Test
    void protectedRouteWithoutJwtReturns401() {
        client.get()
                .uri("/api/v1/documents/doc-1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void unapprovedPathIsNotRouted() {
        client.get()
                .uri("/api/v1/documents/doc-1/private")
                .header(HttpHeaders.AUTHORIZATION, "Bearer routing-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void internalPathRemainsBlocked() {
        client.get()
                .uri("/internal/v1/source/resolve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer routing-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    @AfterAll
    static void stopStubs() {
        S01.disposeNow();
        S02.disposeNow();
        S04.disposeNow();
    }
}
