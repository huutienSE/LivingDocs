
package com.livingdocs.gateway;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

import static org.mockito.Mockito.when;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_ISSUER_URI=https://issuer.example.test",
                "JWT_AUDIENCE=livingdocs-api",
                "management.server.port=0",
                "gateway.cors.allowed-origins=http://localhost:3000"
        }
)
@AutoConfigureWebTestClient
@Import(GatewayFoundationTest.TestEndpoints.class)
class GatewayFoundationTest {

    @Autowired
    WebTestClient client;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue("valid-test-token")
                .header("alg", "RS256")
                .subject("test-user")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();

        when(jwtDecoder.decode("valid-test-token"))
                .thenReturn(Mono.just(jwt));

        when(jwtDecoder.decode("invalid-test-token"))
                .thenReturn(Mono.error(
                        new BadJwtException("Invalid token")
                ));
    }

    @Test
    void missingTokenReturns401() {
        client.get()
                .uri("/api/test/echo")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void invalidTokenReturns401() {
        client.get()
                .uri("/api/test/echo")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer invalid-test-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validTokenCanReachTestEndpoint() {
        client.get()
                .uri("/api/test/echo")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer valid-test-token")
                .header("X-Correlation-ID", "req-a2-001")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo("req-a2-001");
    }

    @Test
    void internalPathIsDenied() {
        client.get()
                .uri("/internal/v1/private")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer valid-test-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void publicActuatorPathIsDenied() {
        client.get()
                .uri("/actuator/health")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer valid-test-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void allowedCorsPreflightDoesNotRequireJwt() {
        client.options()
                .uri("/api/test/echo")
                .header(HttpHeaders.ORIGIN,
                        "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                        "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                        "authorization,x-correlation-id")
                .exchange()
                .expectStatus().isOk()
                .expectHeader()
                .valueEquals(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:3000"
                );
    }

    @Test
    void unapprovedOriginIsRejected() {
        client.options()
                .uri("/api/test/echo")
                .header(HttpHeaders.ORIGIN,
                        "https://attacker.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                        "GET")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void missingCorrelationIdIsGeneratedEvenFor401() {
        client.get()
                .uri("/api/test/echo")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader()
                .exists("X-Correlation-ID");
    }


    @Test
    void allowedOriginReceivesCorsHeadersOnActualRequest() {
        client.get()
                .uri("/api/test/echo")
                .header(HttpHeaders.ORIGIN,
                        "http://localhost:3000")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer valid-test-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader()
                .valueEquals(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:3000"
                );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEndpoints {

        @Bean
        RouterFunction<ServerResponse> testRoutes() {

            return route(
                    GET("/api/test/echo"), request -> ServerResponse.ok().bodyValue(request.headers().firstHeader("X-Correlation-ID"))
            )
            .andRoute(
                    GET("/internal/v1/private"),
                    request -> ServerResponse.ok()
                            .bodyValue("SHOULD_NOT_BE_ACCESSIBLE")
            )
            .andRoute(
                    GET("/actuator/health"),
                    request -> ServerResponse.ok()
                            .bodyValue("SHOULD_NOT_BE_ACCESSIBLE")
            );
        }
    }

}
