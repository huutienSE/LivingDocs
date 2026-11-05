package com.livingdocs.gateway.filter;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayDownstreamErrorFilter implements GlobalFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorResume(error -> {
                    HttpStatus status = classify(error);

                    if (status == null || exchange.getResponse().isCommitted()) {
                        return Mono.error(error);
                    }

                    return writeError(exchange, status);
                });
    }

    private HttpStatus classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            // ResponseStatusException with HTTP 504.
            if (cause instanceof ResponseStatusException statusException && statusException.getStatusCode().value() == 504) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }

            if (cause instanceof ReadTimeoutException || cause instanceof ConnectTimeoutException || cause instanceof TimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }

            if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        }

        return null;
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String correlationId = response.getHeaders().getFirst("X-Correlation-ID");

        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        boolean timeout = (status == HttpStatus.GATEWAY_TIMEOUT);

        String title = timeout ? "Gateway Timeout" : "Service Unavailable";

        String code = timeout ? "UPSTREAM_TIMEOUT" : "UPSTREAM_UNAVAILABLE";

        String detail = timeout ? "Downstream service did not respond in time" : "Downstream service is unavailable";

        String body = """
                {
                  "type": "about:blank",
                  "title": "%s",
                  "status": %d,
                  "detail": "%s",
                  "instance": "urn:uuid:%s",
                  "code": "%s",
                  "correlationId": "%s"
                }
                """
                .formatted(title, status.value(), detail, UUID.randomUUID(), code, correlationId
        );

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
