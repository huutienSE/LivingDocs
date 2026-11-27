package com.livingdocs.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Scoped response policy for the six LIV-151 operations, including security failures. */
public final class GitHubAuthorizationGatewayPolicy {
    private GitHubAuthorizationGatewayPolicy() {}
    public static boolean applies(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().startsWith("/api/v1/integrations/github/");
    }
    public static Mono<Void> problem(ServerWebExchange exchange, HttpStatus status, String code) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().setCacheControl("no-store");
        String correlation = response.getHeaders().getFirst("X-Correlation-ID");
        if (correlation == null || !correlation.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) correlation = UUID.randomUUID().toString();
        response.getHeaders().set("X-Correlation-ID", correlation);
        if (status == HttpStatus.UNAUTHORIZED) response.getHeaders().set("WWW-Authenticate", "Bearer");
        String body = "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"GitHub authorization request could not be completed\","
                + "\"instance\":\"urn:uuid:" + UUID.randomUUID() + "\",\"code\":\"" + code
                + "\",\"correlationId\":\"" + correlation + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
