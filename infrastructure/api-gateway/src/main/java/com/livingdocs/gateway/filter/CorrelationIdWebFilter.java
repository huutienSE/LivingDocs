package com.livingdocs.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    private static final String HEADER = "X-Correlation-ID";

    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);

        String correlationId = (incoming != null && VALID_ID.matcher(incoming).matches())
                                    ? incoming : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> headers.set(HEADER, correlationId))
                .build();

        exchange.getResponse().getHeaders().set(HEADER, correlationId);

        return chain.filter(exchange.mutate().request(request).build());
    }


}
