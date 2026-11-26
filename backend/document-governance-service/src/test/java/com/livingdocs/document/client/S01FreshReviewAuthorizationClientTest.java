package com.livingdocs.document.client;

import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class S01FreshReviewAuthorizationClientTest {
    @TempDir Path directory;
    final JsonMapper json = new JsonMapper();
    final DocumentVersion version = new DocumentVersion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "a".repeat(40), DocumentState.IN_REVIEW, ReviewStage.STAFF_REVIEW, 0);
    HttpServer server;
    ExecutorService executor;
    Path secret;
    String base, issuanceAuthorization, evaluationAuthorization, assertion;
    JsonNode issuance, evaluation;
    int issueStatus = 200, evaluateStatus = 200, issueCalls, evaluateCalls;
    String decision = "ALLOW", problemCode = "INVALID_SERVICE_ASSERTION";
    Consumer<ObjectNode> mutate = ignored -> {};
    boolean oversized, stallBody;

    @BeforeEach void start() throws Exception {
        secret = directory.resolve("s04.secret");
        Files.writeString(secret, "unit-workload-secret\n");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/tokens", exchange -> {
            issueCalls++;
            issuanceAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            issuance = json.readTree(exchange.getRequestBody().readAllBytes());
            send(exchange, issueStatus, json.writeValueAsBytes(Map.of("code", "SERVICE_TOKEN_ISSUED", "data",
                    Map.of("accessToken", "test-assertion", "tokenType", "Bearer", "expiresIn", 60))));
        });
        server.createContext("/evaluate", exchange -> {
            evaluateCalls++;
            evaluationAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            assertion = exchange.getRequestHeaders().getFirst("X-LD-Service-Assertion");
            evaluation = json.readTree(exchange.getRequestBody().readAllBytes());
            var result = evaluation.deepCopy();
            ((ObjectNode) result).put("decision", decision).put("authzRevision", 7)
                    .put("decisionId", UUID.randomUUID().toString()).put("evaluatedAt", Instant.now().toString());
            if ("ALLOW".equals(decision)) ((ObjectNode) result).putNull("reasonCode");
            else ((ObjectNode) result).put("reasonCode", "NOT_AUTHORIZED");
            mutate.accept((ObjectNode) result);
            byte[] body = evaluateStatus == 200
                    ? json.writeValueAsBytes(Map.of("code", "AUTHORIZATION_EVALUATED", "data", result))
                    : json.writeValueAsBytes(Map.of("code", problemCode));
            if (oversized) body = new byte[262145];
            if (stallBody) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body, 0, 1); out.flush();
                    try { Thread.sleep(4000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                } finally { exchange.close(); }
            } else send(exchange, evaluateStatus, body);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    private void send(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] bytes) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
        finally { exchange.close(); }
    }

    S01FreshReviewAuthorizationClient client() {
        return new S01FreshReviewAuthorizationClient(json, base + "/tokens", base + "/evaluate", secret.toString(), true);
    }

    @Test void bindsServiceAssertionToExactUserActionAndResourceWithoutCaching() throws Exception {
        var client = client();
        var first = client.requireAllowed(version, "test-user-token", "test-correlation");
        var second = client.requireAllowed(version, "test-user-token", "test-correlation");
        assertThat(first.appliesTo(version)).isTrue();
        assertThat(first.authzRevision()).isEqualTo(7);
        assertThat(first.decisionId()).isNotEqualTo(second.decisionId());
        assertThat(issueCalls).isEqualTo(2);
        assertThat(evaluateCalls).isEqualTo(2);
        assertThat(issuanceAuthorization).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString("s04:unit-workload-secret".getBytes(StandardCharsets.UTF_8)));
        assertThat(issuance.path("actorTokenSha256").asString()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("test-user-token".getBytes(StandardCharsets.US_ASCII))));
        assertThat(issuance.path("audience").asString()).isEqualTo("livingdocs-s01-internal");
        assertThat(issuance.path("operation").asString()).isEqualTo("authorization:evaluate");
        assertThat(issuance.path("method").asString()).isEqualTo("POST");
        assertThat(issuance.path("path").asString()).isEqualTo("/internal/v1/authorization/evaluate");
        assertThat(evaluationAuthorization).isEqualTo("Bearer test-user-token");
        assertThat(assertion).isEqualTo("Bearer test-assertion");
        for (String field : List.of("tenantId", "workspaceId", "repositoryConnectionId", "action"))
            assertThat(issuance.path(field)).isEqualTo(evaluation.path(field));
        assertThat(evaluation.path("action").asString()).isEqualTo("DOCUMENT_STAFF_REVIEW");
    }

    @Test void denialAndCredentialFailuresFailClosedWithCorrectBoundary() {
        decision = "DENY";
        assertThatThrownBy(() -> client().requireAllowed(version, "user", "test"))
                .isInstanceOf(InsufficientReviewScopeException.class);
        decision = "ALLOW";
        for (int status : List.of(400, 401, 403, 503)) {
            issueStatus = status;
            assertThatThrownBy(() -> client().requireAllowed(version, "user", "test"))
                    .isInstanceOf(ReviewAuthorizationUnavailableException.class);
        }
        issueStatus = 200;
        evaluateStatus = 401;
        assertThatThrownBy(() -> client().requireAllowed(version, "user", "test"))
                .isInstanceOf(ReviewAuthorizationUnavailableException.class);
        problemCode = "UNAUTHENTICATED";
        assertThatThrownBy(() -> client().requireAllowed(version, "user", "test"))
                .isInstanceOf(ReviewAuthenticationRequiredException.class);
        for (int status : List.of(302, 400, 403, 503)) {
            evaluateStatus = status;
            assertThatThrownBy(() -> client().requireAllowed(version, "user", "test"))
                    .isInstanceOf(ReviewAuthorizationUnavailableException.class);
        }
    }

    @Test void mismatchedMalformedOrCachedDecisionsCannotAuthorize() {
        List<Consumer<ObjectNode>> invalid = List.of(
                n -> n.put("tenantId", UUID.randomUUID().toString()),
                n -> n.put("workspaceId", UUID.randomUUID().toString()),
                n -> n.put("repositoryConnectionId", UUID.randomUUID().toString()),
                n -> n.put("action", "DOCUMENT_PUBLISH"),
                n -> n.put("decision", "UNKNOWN"),
                n -> n.put("decisionId", "invalid"),
                n -> n.put("authzRevision", -1),
                n -> n.put("reasonCode", "NOT_AUTHORIZED"),
                n -> n.put("evaluatedAt", Instant.now().minusSeconds(60).toString()),
                n -> n.put("evaluatedAt", Instant.now().plusSeconds(60).toString()));
        for (var invalidResult : invalid) {
            mutate = invalidResult;
            assertThatThrownBy(() -> client().requireAllowed(version, "user", "test"))
                    .isInstanceOf(ReviewAuthorizationUnavailableException.class);
        }
    }

    @Test void unsupportedScopeMissingSecretsOrInsecureOriginCannotBypassFreshEvaluation() throws Exception {
        var repositoryFree = new DocumentVersion(version.id(), version.tenantId(), version.documentId(), version.workspaceId(),
                null, version.sourceSha(), version.state(), version.reviewStage(), version.revision());
        assertThatThrownBy(() -> client().requireAllowed(repositoryFree, "user", "test"))
                .isInstanceOf(ReviewAuthorizationUnavailableException.class);
        Files.delete(secret);
        assertThatThrownBy(() -> client().requireAllowed(version, "user", "test"))
                .isInstanceOf(ReviewAuthorizationUnavailableException.class);
        assertThat(issueCalls).isZero();
        assertThatThrownBy(() -> new S01FreshReviewAuthorizationClient(json, base + "/tokens", base + "/evaluate", "", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S01FreshReviewAuthorizationClient(json, "https://issuer.test/tokens", "https://other.test/evaluate", "", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void responseBudgetAndWholeBodyDeadlineAreEnforced() {
        oversized = true;
        assertThatThrownBy(() -> client().requireAllowed(version, "user", "test"))
                .isInstanceOf(ReviewAuthorizationUnavailableException.class);
        oversized = false;
        stallBody = true;
        assertThatThrownBy(() -> client().requireAllowed(version, "user", "test"))
                .isInstanceOf(ReviewAuthorizationUnavailableException.class);
    }
}
