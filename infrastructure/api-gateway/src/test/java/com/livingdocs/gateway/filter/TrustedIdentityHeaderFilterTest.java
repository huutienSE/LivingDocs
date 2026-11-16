package com.livingdocs.gateway.filter;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedIdentityHeaderFilterTest {

    @Test
    void removesClientIdentityClaimsButPreservesBearerAndTracing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/documents/id")
                .header("X-User-Id", "forged")
                .header("X-Identity-Id", "forged")
                .header("X-Role", "MANAGER")
                .header("X-Tenant-Id", "forged")
                .header("X-Workspace-Id", "forged")
                .header("X-Roles", "MANAGER")
                .header("X-Permission", "PUBLISH")
                .header("X-Permissions", "PUBLISH")
                .header("X-Forwarded-User", "forged")
                .header("X-Forwarded-Roles", "MANAGER")
                .header("X-Authenticated-User", "forged")
                .header("Authorization", "Bearer token")
                .header("X-Correlation-ID", "trace-1"));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = routed -> {
            downstream.set(routed);
            return Mono.empty();
        };

        new TrustedIdentityHeaderFilter().filter(exchange, chain).block();

        for (String name : new String[] {"X-User-Id", "X-Identity-Id", "X-Role", "X-Roles",
                "X-Tenant-Id", "X-Workspace-Id", "X-Permission", "X-Permissions",
                "X-Forwarded-User", "X-Forwarded-Roles", "X-Authenticated-User"}) {
            assertThat(downstream.get().getRequest().getHeaders().getFirst(name)).as(name).isNull();
        }
        assertThat(downstream.get().getRequest().getHeaders().getFirst("Authorization")).isEqualTo("Bearer token");
        assertThat(downstream.get().getRequest().getHeaders().getFirst("X-Correlation-ID")).isEqualTo("trace-1");
    }
}
