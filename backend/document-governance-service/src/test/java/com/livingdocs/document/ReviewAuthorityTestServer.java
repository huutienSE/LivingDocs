package com.livingdocs.document;

import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.json.JsonMapper;

/** API-038 contract stub for S04 component tests; this is not a real S01 issuer. */
final class ReviewAuthorityTestServer {
    static final Map<UUID, Object> CONTEXTS = new ConcurrentHashMap<>();
    static final Map<UUID, Integer> STATUSES = new ConcurrentHashMap<>();
    static final Map<UUID, Integer> CALLS = new ConcurrentHashMap<>();
    static final HttpServer SERVER = start();

    static String uri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/api/v1/users/me/access-context";
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
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
}
