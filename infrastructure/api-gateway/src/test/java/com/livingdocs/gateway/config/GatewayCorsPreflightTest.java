//package com.livingdocs.gateway.config;
//
//import java.util.List;
//import java.util.Locale;
//import java.util.concurrent.atomic.AtomicBoolean;
//
//import org.junit.jupiter.api.Test;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.HttpMethod;
//import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
//import org.springframework.mock.web.server.MockServerWebExchange;
//import org.springframework.web.cors.reactive.CorsWebFilter;
//import reactor.core.publisher.Mono;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertFalse;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//
///**
// * LIV-133: regression for browser preflight headers used by Staff Review.
// * Uses the real GatewayCorsConfig through the WebFlux CORS filter;
// * does not start Gateway, JWT validation, or an S04 downstream service.
// */
//class GatewayCorsPreflightTest {
//
//    @Test
//    void staffDecisionPreflightAllowsConcurrencyHeadersWithoutCredentials() {
//        var configurationSource = new GatewayCorsConfig().corsConfigurationSource("http://localhost:3000");
//        var corsFilter = new CorsWebFilter(configurationSource);
//        var request = MockServerHttpRequest.options(
//                        "/api/v1/document-versions/00000000-0000-4000-8000-000000000001/staff-decisions")
//                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
//                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
//                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "If-Match,Idempotency-Key,Authorization,Content-Type")
//                .build();
//        var exchange = MockServerWebExchange.from(request);
//        var downstreamCalled = new AtomicBoolean(false);
//
//        corsFilter.filter(exchange, e -> {
//            downstreamCalled.set(true);
//            return Mono.empty();
//        }).block();
//
//        HttpHeaders response = exchange.getResponse().getHeaders();
//        assertEquals("http://localhost:3000", response.getAccessControlAllowOrigin());
//        assertTrue(response.getAccessControlAllowMethods().contains(HttpMethod.POST));
//
//        List<String> allowed = response.getAccessControlAllowHeaders().stream()
//                .map(header -> header.toLowerCase(Locale.ROOT))
//                .toList();
//        for (String required : List.of("If-Match", "Idempotency-Key", "Authorization", "Content-Type")) {
//            assertTrue(allowed.contains(required.toLowerCase(Locale.ROOT)),
//                    () -> "Missing CORS preflight header: " + required + "; actual=" + allowed);
//        }
//
//        assertFalse("true".equalsIgnoreCase(response.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)),
//                "Cookie credentials must remain disabled in the LIV-133 baseline");
//        assertFalse(downstreamCalled.get(), "A preflight must not be sent to S04");
//    }
//}
