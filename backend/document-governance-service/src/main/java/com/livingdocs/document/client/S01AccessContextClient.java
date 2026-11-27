package com.livingdocs.document.client;

import com.livingdocs.document.exception.S01AuthorityException;
import com.livingdocs.document.model.S01AccessContext;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API-038 authority snapshot; not API-039 evaluation or Service JWT issuance. */
@Component
public class S01AccessContextClient {
    private static final int MAX_RESPONSE_BYTES = 262144;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public S01AccessContextClient(JsonMapper json) { this.json = json; }

    public static URI endpoint(String uri, boolean localHttp) {
        URI endpoint = uri.isBlank() ? null : URI.create(uri);
        if (endpoint != null && (endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("https".equals(endpoint.getScheme()) || localHttp && "http".equals(endpoint.getScheme()))))
            throw new IllegalArgumentException("S01 access context requires HTTPS; local HTTP must be explicit.");
        return endpoint;
    }

    public S01AccessContext resolve(Jwt jwt, URI endpoint) {
        return resolve(jwt, endpoint, null);
    }

    public S01AccessContext resolve(Jwt jwt, URI endpoint, String correlationId) {
        if (jwt == null) throw S01AuthorityException.unauthenticated();
        if (endpoint == null) throw S01AuthorityException.unavailable();
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + jwt.getTokenValue()).header("Accept", "application/json");
            if (correlationId != null && correlationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                request.header("X-Correlation-ID", correlationId);
            var pending = http.sendAsync(request.GET().build(), ignored -> new LimitedBodySubscriber());
            HttpResponse<byte[]> response;
            try {
                response = pending.get(3, TimeUnit.SECONDS);
            } catch (InterruptedException | TimeoutException e) {
                pending.cancel(true);
                throw e;
            }
            if (response.statusCode() == 401) throw S01AuthorityException.unauthenticated();
            if (response.statusCode() == 403) throw S01AuthorityException.forbidden();
            if (response.statusCode() != 200
                    || !response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim()
                            .equalsIgnoreCase("application/json")) throw S01AuthorityException.unavailable();
            var root = json.readTree(response.body());
            if (!"ACCESS_CONTEXT_LOADED".equals(text(root.path("code")))) throw S01AuthorityException.unavailable();
            var data = root.path("data");
            UUID identity = uuid(data.path("identityId"));
            if (!identity.toString().equals(jwt.getSubject())) throw S01AuthorityException.unavailable();
            if (data.path("tenantId").isNull()) throw S01AuthorityException.forbidden();
            UUID tenant = uuid(data.path("tenantId"));
            if (!data.path("authzRevision").isIntegralNumber()
                    || !data.path("authzRevision").canConvertToLong() || data.path("authzRevision").asLong() < 0
                    || !data.path("workspaceGrants").isArray()) throw S01AuthorityException.unavailable();
            Instant.parse(text(data.path("evaluatedAt")));
            var seen = new HashSet<UUID>();
            var grants = new ArrayList<S01AccessContext.Grant>();
            for (var grant : data.path("workspaceGrants")) {
                UUID workspace = uuid(grant.path("workspaceId"));
                if (!seen.add(workspace)) throw S01AuthorityException.unavailable();
                var roles = strings(grant.path("roles"));
                var permissions = strings(grant.path("permissions"));
                if (!grant.path("repositoryIds").isArray()) throw S01AuthorityException.unavailable();
                var repositories = new HashSet<UUID>();
                for (var repository : grant.path("repositoryIds"))
                    if (!repositories.add(uuid(repository))) throw S01AuthorityException.unavailable();
                grants.add(new S01AccessContext.Grant(workspace, roles, permissions, repositories));
            }
            return new S01AccessContext(identity, tenant, data.path("authzRevision").asLong(),
                    Instant.parse(text(data.path("evaluatedAt"))), grants);
        } catch (S01AuthorityException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw S01AuthorityException.unavailable();
        } catch (Exception e) {
            // Never expose upstream bodies, credentials or tokens in a Problem.
            throw S01AuthorityException.unavailable();
        }
    }

    private static String text(JsonNode node) {
        if (!node.isString() || node.asString().isBlank()) throw S01AuthorityException.unavailable();
        return node.asString();
    }

    private static UUID uuid(JsonNode node) {
        String value = text(node);
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) throw S01AuthorityException.unavailable();
        return id;
    }

    private static Set<String> strings(JsonNode node) {
        if (!node.isArray()) throw S01AuthorityException.unavailable();
        var values = new HashSet<String>();
        for (var value : node) if (!values.add(text(value))) throw S01AuthorityException.unavailable();
        return values;
    }

    /** Bound the response while receiving it; the HTTP deadline covers body completion. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(S01AuthorityException.unavailable());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
