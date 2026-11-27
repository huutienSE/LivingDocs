package com.livingdocs.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component @Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class GitHubAuthorizationNoStoreFilter implements WebFilter {
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (GitHubAuthorizationGatewayPolicy.applies(exchange)) {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().setCacheControl("no-store");
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
}
