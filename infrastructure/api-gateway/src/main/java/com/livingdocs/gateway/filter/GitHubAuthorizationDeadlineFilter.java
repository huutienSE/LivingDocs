package com.livingdocs.gateway.filter;

import java.time.Duration;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Wraps NettyWriteResponseFilter as well as routing: deadline includes the full response body. */
@Component
public class GitHubAuthorizationDeadlineFilter implements GlobalFilter, Ordered {
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Mono<Void> result = chain.filter(exchange);
        return GitHubAuthorizationGatewayPolicy.applies(exchange) ? result.timeout(Duration.ofSeconds(12)) : result;
    }
    @Override public int getOrder() { return -2; }
}
