package com.livingdocs.document.client;

import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.SubmitActorContext;
import org.springframework.http.HttpStatus;
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
public class S01SubmitAuthorizationClient {
    private static final int MAX_RESPONSE_BYTES = 262144;
    private final URI endpoint;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public S01SubmitAuthorizationClient(JsonMapper json,
            @Value("${livingdocs.submit.access-context-uri:}") String uri,
            @Value("${livingdocs.submit.allow-insecure-http:false}") boolean localHttp) {
        this.json = json;
        endpoint = uri.isBlank() ? null : URI.create(uri);
        if (endpoint != null && (endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("https".equals(endpoint.getScheme()) || localHttp && "http".equals(endpoint.getScheme())))) {
            throw new IllegalArgumentException("S01 access context requires HTTPS; HTTP needs an explicit local exception.");
        }
    }

    public SubmitActorContext resolve(Jwt jwt) {
        if (jwt == null) throw new SubmitException("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "A valid S01 token is required.");
        if (endpoint == null) throw SubmitException.unavailable();
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + jwt.getTokenValue()).header("Accept", "application/json").GET().build();
            var pending = http.sendAsync(request, ignored -> new LimitedBodySubscriber());
            HttpResponse<byte[]> response;
            try {
                response = pending.get(3, TimeUnit.SECONDS);
            } catch (InterruptedException | TimeoutException e) {
                pending.cancel(true);
                throw e;
            }
            if (response.statusCode() == 401) throw new SubmitException("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "A valid S01 token is required.");
            if (response.statusCode() == 403) throw SubmitException.forbidden();
            if (response.statusCode() != 200
                    || !response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim()
                            .equalsIgnoreCase("application/json")) throw SubmitException.unavailable();
            var root = json.readTree(response.body());
            if (!"ACCESS_CONTEXT_LOADED".equals(text(root.path("code")))) throw SubmitException.unavailable();
            var data = root.path("data");
            UUID identity = uuid(data.path("identityId"));
            if (!identity.toString().equals(jwt.getSubject())) throw SubmitException.unavailable();
            if (data.path("tenantId").isNull()) throw SubmitException.forbidden();
            UUID tenant = uuid(data.path("tenantId"));
            if (!data.path("authzRevision").isIntegralNumber()
                    || !data.path("authzRevision").canConvertToLong() || data.path("authzRevision").asLong() < 0
                    || !data.path("workspaceGrants").isArray()) throw SubmitException.unavailable();
            Instant.parse(text(data.path("evaluatedAt")));
            var seen = new HashSet<UUID>();
            var grants = new ArrayList<SubmitActorContext.Grant>();
            for (var grant : data.path("workspaceGrants")) {
                UUID workspace = uuid(grant.path("workspaceId"));
                if (!seen.add(workspace)) throw SubmitException.unavailable();
                var roles = strings(grant.path("roles"));
                var permissions = strings(grant.path("permissions"));
                if (!grant.path("repositoryIds").isArray()) throw SubmitException.unavailable();
                var repositories = new HashSet<UUID>();
                for (var repository : grant.path("repositoryIds"))
                    if (!repositories.add(uuid(repository))) throw SubmitException.unavailable();
                grants.add(new SubmitActorContext.Grant(workspace, roles, permissions, repositories));
            }
            return new SubmitActorContext(identity, tenant, grants);
        } catch (SubmitException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SubmitException.unavailable();
        } catch (Exception e) {
            // Never expose upstream bodies, credentials or tokens in a Problem.
            throw SubmitException.unavailable();
        }
    }

    private static String text(JsonNode node) {
        if (!node.isString() || node.asString().isBlank()) throw SubmitException.unavailable();
        return node.asString();
    }

    private static UUID uuid(JsonNode node) {
        String value = text(node);
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) throw SubmitException.unavailable();
        return id;
    }

    private static Set<String> strings(JsonNode node) {
        if (!node.isArray()) throw SubmitException.unavailable();
        var values = new HashSet<String>();
        for (var value : node) if (!values.add(text(value))) throw SubmitException.unavailable();
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
                    body.completeExceptionally(SubmitException.unavailable());
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
