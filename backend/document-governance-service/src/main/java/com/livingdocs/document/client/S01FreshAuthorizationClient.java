package com.livingdocs.document.client;

import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.DocumentVersion;
import com.livingdocs.document.model.AuthorizationScope;
import com.livingdocs.document.model.AuthorizationDecision;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S04 consumer of the S01 PR2 contract. Never caches ALLOW or falls back to API-038. */
@Component
public class S01FreshAuthorizationClient {
    private static final String EVALUATE_PATH = "/internal/v1/authorization/evaluate";
    private static final int MAX_RESPONSE_BYTES = 262144;
    private final JsonMapper json;
    private final URI tokenEndpoint;
    private final URI evaluationEndpoint;
    private final Path secretPath;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public S01FreshAuthorizationClient(JsonMapper json,
            @Value("${livingdocs.authorization.service-token-uri:${livingdocs.review.service-token-uri:}}") String tokenUri,
            @Value("${livingdocs.authorization.authorization-uri:${livingdocs.review.authorization-uri:}}") String evaluationUri,
            @Value("${livingdocs.authorization.workload-secret-path:${livingdocs.review.workload-secret-path:}}") String secret,
            @Value("${livingdocs.authorization.allow-insecure-http:${livingdocs.review.allow-insecure-http:false}}") boolean localHttp) {
        this.json = json;
        tokenEndpoint = endpoint(tokenUri, localHttp);
        evaluationEndpoint = endpoint(evaluationUri, localHttp);
        if (tokenEndpoint != null && evaluationEndpoint != null
                && (!tokenEndpoint.getScheme().equals(evaluationEndpoint.getScheme())
                || !tokenEndpoint.getAuthority().equals(evaluationEndpoint.getAuthority()))) {
            throw new IllegalArgumentException("S01 internal endpoints must use the same trusted origin.");
        }
        secretPath = secret.isBlank() ? null : Path.of(secret);
    }

    @Transactional(propagation = Propagation.NEVER)
    public AuthorizationDecision requireAllowed(DocumentVersion version, String userAccessToken,
                                                       String correlationId, String action) {
        return requireAllowed(AuthorizationScope.of(version), userAccessToken, correlationId, action);
    }

    @Transactional(propagation = Propagation.NEVER)
    public AuthorizationDecision requireAllowed(AuthorizationScope version, String userAccessToken,
                                                String correlationId, String action) {
        if (!Set.of("DOCUMENT_AUTHOR", "DOCUMENT_STAFF_REVIEW").contains(action)) throw unavailable();
        // PR2 DOCUMENT_* requires a repository. Do not invent one or bypass fresh authorization.
        if (version.repositoryConnectionId() == null || tokenEndpoint == null || evaluationEndpoint == null
                || secretPath == null || userAccessToken == null || userAccessToken.isBlank()) throw unavailable();
        try {
            String secret = Files.readString(secretPath, StandardCharsets.UTF_8).strip();
            if (secret.isEmpty()) throw unavailable();
            String basic = Base64.getEncoder().encodeToString(("s04:" + secret).getBytes(StandardCharsets.UTF_8));
            String actorHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(userAccessToken.getBytes(StandardCharsets.US_ASCII)));
            var issuance = json.createObjectNode();
            issuance.put("audience", "livingdocs-s01-internal");
            issuance.put("operation", "authorization:evaluate");
            issuance.put("method", "POST");
            issuance.put("path", EVALUATE_PATH);
            issuance.put("tenantId", version.tenantId().toString());
            issuance.put("workspaceId", version.workspaceId().toString());
            issuance.put("repositoryConnectionId", version.repositoryConnectionId().toString());
            issuance.put("action", action);
            issuance.put("actorTokenSha256", actorHash);
            var issued = post(tokenEndpoint, "Basic " + basic, null, issuance, correlationId);
            var token = data(issued, "SERVICE_TOKEN_ISSUED");
            if (!"Bearer".equals(text(token.path("tokenType")))
                    || !token.path("expiresIn").isIntegralNumber() || !token.path("expiresIn").canConvertToLong()
                    || token.path("expiresIn").asLong() <= 0 || token.path("expiresIn").asLong() > 60) throw unavailable();
            String assertion = text(token.path("accessToken"));
            var evaluation = json.createObjectNode();
            evaluation.put("action", action);
            evaluation.put("tenantId", version.tenantId().toString());
            evaluation.put("workspaceId", version.workspaceId().toString());
            evaluation.put("repositoryConnectionId", version.repositoryConnectionId().toString());
            Instant started = Instant.now();
            var response = post(evaluationEndpoint, "Bearer " + userAccessToken, assertion, evaluation, correlationId);
            if (response.statusCode() == 401) {
                var problem = json.readTree(response.body());
                if ("UNAUTHENTICATED".equals(problem.path("code").asString()))
                    throw S01AuthorityException.unauthenticated();
            }
            var result = data(response, "AUTHORIZATION_EVALUATED");
            if (!action.equals(text(result.path("action")))
                    || !version.tenantId().equals(uuid(result.path("tenantId")))
                    || !version.workspaceId().equals(uuid(result.path("workspaceId")))
                    || !version.repositoryConnectionId().equals(uuid(result.path("repositoryConnectionId")))) throw unavailable();
            UUID decisionId = uuid(result.path("decisionId"));
            Instant evaluated = Instant.parse(text(result.path("evaluatedAt")));
            // Allow bounded clock skew, but do not accept a cached decision predating this request.
            if (evaluated.isBefore(started.minusSeconds(5)) || evaluated.isAfter(Instant.now().plusSeconds(5))) throw unavailable();
            String decision = text(result.path("decision"));
            if ("DENY".equals(decision)) throw S01AuthorityException.forbidden();
            if (!"ALLOW".equals(decision) || !result.path("reasonCode").isNull()
                    || !result.path("authzRevision").isIntegralNumber()
                    || !result.path("authzRevision").canConvertToLong() || result.path("authzRevision").asLong() < 0) throw unavailable();
            return new AuthorizationDecision(decisionId, action, version.tenantId(), version.workspaceId(),
                    version.repositoryConnectionId(), result.path("authzRevision").asLong(), evaluated);
        } catch (S01AuthorityException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception ex) {
            // Never include upstream bodies, tokens, Basic credentials or secret paths in errors.
            throw unavailable();
        }
    }

    private HttpResponse<byte[]> post(URI uri, String authorization, String assertion, JsonNode body,
                                       String correlationId) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                .header("Authorization", authorization).header("Accept", "application/json")
                .header("Content-Type", "application/json").header("Cache-Control", "no-store")
                .header("X-Correlation-ID", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (assertion != null) request.header("X-LD-Service-Assertion", "Bearer " + assertion);
        var pending = http.sendAsync(request.build(), ignored -> new LimitedBodySubscriber());
        try {
            return pending.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException ex) {
            pending.cancel(true);
            throw ex;
        }
    }

    private JsonNode data(HttpResponse<byte[]> response, String code) {
        if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("")
                .split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw unavailable();
        var root = json.readTree(response.body());
        if (!code.equals(text(root.path("code"))) || !root.path("data").isObject()) throw unavailable();
        return root.path("data");
    }

    private static URI endpoint(String value, boolean localHttp) {
        if (value.isBlank()) return null;
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || localHttp && "http".equals(uri.getScheme())))
            throw new IllegalArgumentException("S01 internal endpoints require HTTPS; local HTTP must be explicit.");
        return uri;
    }

    private static String text(JsonNode value) {
        if (!value.isString() || value.asString().isBlank()) throw unavailable();
        return value.asString();
    }

    private static UUID uuid(JsonNode value) {
        String text = text(value);
        UUID id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text)) throw unavailable();
        return id;
    }

    private static S01AuthorityException unavailable() {
        return S01AuthorityException.unavailable();
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(unavailable()); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
