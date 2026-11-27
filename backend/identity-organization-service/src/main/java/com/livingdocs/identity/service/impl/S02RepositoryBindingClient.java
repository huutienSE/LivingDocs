package com.livingdocs.identity.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.identity.dto.request.ServiceTokenRequest;
import com.livingdocs.identity.security.ServiceJwtIssuer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.livingdocs.identity.service.RepositoryBindingClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Production HTTP adapter. There is no local or test-only binding fallback. */
@Component
public class S02RepositoryBindingClient implements RepositoryBindingClient {
    private final ServiceJwtIssuer issuer;
    private final MeterRegistry metrics;
    private final ObjectMapper json;
    private final HttpClient http;
    private final String baseUrl, path, audience, operation;
    private final Duration timeout;
    private final boolean allowHttp;

    public S02RepositoryBindingClient(ServiceJwtIssuer issuer, MeterRegistry metrics,
            @Value("${livingdocs.s02.binding.base-url:}") String baseUrl,
            @Value("${livingdocs.s02.binding.path:/internal/v1/repository-bindings/verify}") String path,
            @Value("${livingdocs.s02.binding.audience:livingdocs-s02-internal}") String audience,
            @Value("${livingdocs.s02.binding.operation:repository-binding:verify}") String operation,
            @Value("${livingdocs.s02.binding.timeout-ms:1500}") int timeoutMs,
            @Value("${livingdocs.s02.binding.allow-http:false}") boolean allowHttp) {
        this.issuer = issuer;
        this.metrics = metrics;
        this.json = new ObjectMapper();
        this.baseUrl = baseUrl;
        this.path = path;
        this.audience = audience;
        this.operation = operation;
        this.timeout = Duration.ofMillis(Math.max(100, Math.min(timeoutMs, 5000)));
        this.allowHttp = allowHttp;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }


    @Override
    public BindingEvidence lookup(UUID tenantId, UUID workspaceId, UUID connectionId) {
        long started = System.nanoTime();
        String outcome = "unavailable";
        try {
            if (baseUrl.isBlank() || !path.startsWith("/internal/") || path.contains("?")
                    || audience.isBlank() || operation.isBlank()) throw new Unavailable();
            URI uri = URI.create(baseUrl + path);
            if (!"https".equals(uri.getScheme()) && !(allowHttp && "http".equals(uri.getScheme())))
                throw new Unavailable();
            ServiceTokenRequest assertion = new ServiceTokenRequest();
            assertion.setAudience(audience);
            assertion.setOperation(operation);
            assertion.setMethod("POST");
            assertion.setPath(path);
            assertion.setTenantId(tenantId);
            assertion.setWorkspaceId(workspaceId);
            assertion.setRepositoryConnectionId(connectionId);
            String body = json.writeValueAsString(new BindingRequest(tenantId, workspaceId, connectionId));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Authorization", "Bearer " + issuer.issue("s01", assertion))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 || response.statusCode() == 409) {
                outcome = "invalid";
                return null;
            }
            if (response.statusCode() != 200) throw new Unavailable();
            JsonNode root = json.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isObject()) throw new Unavailable();
            BindingEvidence evidence = new BindingEvidence(
                    UUID.fromString(data.path("tenantId").asText()),
                    UUID.fromString(data.path("workspaceId").asText()),
                    UUID.fromString(data.path("repositoryConnectionId").asText()),
                    data.path("status").asText(), data.path("bindingRevision").asLong(0),
                    Instant.parse(data.path("observedAt").asText()));
            if (!tenantId.equals(evidence.tenantId()) || !workspaceId.equals(evidence.workspaceId())
                    || !connectionId.equals(evidence.connectionId())) {
                outcome = "invalid";
                return null;
            }
            outcome = "received";
            return evidence;
        } catch (Unavailable ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        } catch (Exception ex) {
            throw new Unavailable();
        } finally {
            Timer.builder("livingdocs.s01.s02.binding.lookup")
                    .tag("outcome", outcome).register(metrics).record(System.nanoTime() - started,
                            java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private record BindingRequest(UUID tenantId, UUID workspaceId, UUID repositoryConnectionId) {}
}
