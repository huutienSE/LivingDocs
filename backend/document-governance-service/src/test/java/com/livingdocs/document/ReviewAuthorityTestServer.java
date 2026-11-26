package com.livingdocs.document;

import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API-038/039 and workload issuer stubs for component tests, never production authority. */
final class ReviewAuthorityTestServer {
    static final Map<UUID, Object> CONTEXTS = new ConcurrentHashMap<>();
    static final Map<UUID, Integer> STATUSES = new ConcurrentHashMap<>();
    static final Map<UUID, Integer> CALLS = new ConcurrentHashMap<>();
    static final Map<UUID, String> FRESH_DECISIONS = new ConcurrentHashMap<>();
    static final Map<UUID, Integer> FRESH_STATUSES = new ConcurrentHashMap<>();
    static final Map<UUID, Integer> FRESH_CALLS = new ConcurrentHashMap<>();
    static final Map<UUID, Runnable> AFTER_EVALUATION = new ConcurrentHashMap<>();
    static final Map<String, JsonNode> ASSERTIONS = new ConcurrentHashMap<>();
    static final String SECRET_PATH = secretPath();
    static final HttpServer SERVER = start();

    static String uri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/api/v1/users/me/access-context";
    }

    static String base() { return "http://127.0.0.1:" + SERVER.getAddress().getPort(); }

    private static String secretPath() {
        try {
            Path path = Files.createTempFile("s04-test-workload-", ".secret");
            Files.writeString(path, "test-only-workload-secret");
            path.toFile().deleteOnExit();
            return path.toString();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }

    static Map<String, Object> grant(UUID workspace, List<String> roles, List<String> permissions, List<UUID> repos) {
        return Map.of("workspaceId", workspace, "roles", roles, "permissions", permissions, "repositoryIds", repos);
    }

    static Map<String, Object> context(UUID identity, UUID tenant, List<?> grants) {
        var data = new HashMap<String, Object>();
        data.put("identityId", identity);
        data.put("tenantId", tenant);
        data.put("authzRevision", 1);
        data.put("evaluatedAt", Instant.now().toString());
        data.put("tenantRoles", List.of());
        data.put("tenantPermissions", List.of());
        data.put("workspaceGrants", grants);
        return data;
    }

    static void allow(ReviewApiIntegrationTest.Fixture f) {
        CONTEXTS.put(f.actor(), context(f.actor(), f.tenant(), List.of(grant(f.workspace(),
                List.of("STAFF"), List.of("WORKSPACE_VIEW", "DOCUMENT_STAFF_REVIEW"), List.of(f.repository())))));
    }

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/users/me/access-context", exchange -> {
                try {
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    UUID identity = UUID.fromString(SignedJWT.parse(authorization.substring(7)).getJWTClaimsSet().getSubject());
                    CALLS.merge(identity, 1, Integer::sum);
                    int status = STATUSES.getOrDefault(identity, 200);
                    Object data = CONTEXTS.getOrDefault(identity, context(identity, null, List.of()));
                    byte[] body = new JsonMapper().writeValueAsString(Map.of("code", "ACCESS_CONTEXT_LOADED", "data", data))
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    try (var out = exchange.getResponseBody()) { out.write(body); }
                } catch (Exception e) {
                    exchange.sendResponseHeaders(500, -1);
                } finally { exchange.close(); }
            });
            server.createContext("/internal/v1/auth/service-tokens", exchange -> {
                try {
                    String expected = "Basic " + Base64.getEncoder().encodeToString("s04:test-only-workload-secret".getBytes(StandardCharsets.UTF_8));
                    if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401, -1); return;
                    }
                    var request = new JsonMapper().readTree(exchange.getRequestBody().readAllBytes());
                    String assertion = UUID.randomUUID().toString();
                    ASSERTIONS.put(assertion, request);
                    respond(exchange, 200, Map.of("code", "SERVICE_TOKEN_ISSUED", "data",
                            Map.of("accessToken", assertion, "tokenType", "Bearer", "expiresIn", 60)));
                } catch (Exception ex) { exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            });
            server.createContext("/internal/v1/authorization/evaluate", exchange -> {
                try {
                    String user = exchange.getRequestHeaders().getFirst("Authorization").substring(7);
                    UUID identity = UUID.fromString(SignedJWT.parse(user).getJWTClaimsSet().getSubject());
                    String assertion = exchange.getRequestHeaders().getFirst("X-LD-Service-Assertion").substring(7);
                    var issued = ASSERTIONS.get(assertion);
                    var request = new JsonMapper().readTree(exchange.getRequestBody().readAllBytes());
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(user.getBytes(StandardCharsets.US_ASCII)));
                    if (issued == null || !hash.equals(issued.path("actorTokenSha256").asString())
                            || !"authorization:evaluate".equals(issued.path("operation").asString())
                            || !"livingdocs-s01-internal".equals(issued.path("audience").asString())
                            || !"POST".equals(issued.path("method").asString())
                            || !"/internal/v1/authorization/evaluate".equals(issued.path("path").asString())) {
                        exchange.sendResponseHeaders(401, -1); return;
                    }
                    for (String field : List.of("action", "tenantId", "workspaceId", "repositoryConnectionId"))
                        if (!issued.path(field).equals(request.path(field))) throw new IllegalArgumentException("Scope mismatch");
                    FRESH_CALLS.merge(identity, 1, Integer::sum);
                    var result = new HashMap<String, Object>();
                    result.put("decision", FRESH_DECISIONS.getOrDefault(identity, "ALLOW"));
                    result.put("reasonCode", result.get("decision").equals("ALLOW") ? null : "NOT_AUTHORIZED");
                    for (String field : List.of("action", "tenantId", "workspaceId", "repositoryConnectionId"))
                        result.put(field, request.path(field).asString());
                    result.put("authzRevision", 2);
                    result.put("evaluatedAt", Instant.now().toString());
                    result.put("decisionId", UUID.randomUUID());
                    var hook = AFTER_EVALUATION.remove(identity);
                    if (hook != null) hook.run();
                    respond(exchange, FRESH_STATUSES.getOrDefault(identity, 200), Map.of("code", "AUTHORIZATION_EVALUATED", "data", result));
                } catch (Exception ex) { exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, Object value) throws Exception {
        byte[] body = new JsonMapper().writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
}
