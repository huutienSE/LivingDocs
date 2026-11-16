package com.livingdocs.gateway.filter;

import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

//============
//TẦNG Gateway Filter Chain: Xóa các identity headers giả mạo trước khi forward
// global filter
// thuộc Spring Cloud Gateway
//============
@Component
public class TrustedIdentityHeaderFilter implements GlobalFilter, Ordered {

    private static final List<String> UNTRUSTED_HEADERS = List.of(
            "X-User-Id", "X-Identity-Id", "X-Role", "X-Roles", "X-Tenant-Id",
            "X-Workspace-Id", "X-Permission", "X-Permissions", "X-Forwarded-User",
            "X-Forwarded-Roles", "X-Authenticated-User");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange.mutate().request(exchange.getRequest().mutate()
                .headers(headers -> UNTRUSTED_HEADERS.forEach(headers::remove)).build()).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
