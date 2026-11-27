package com.livingdocs.document;

import com.livingdocs.document.client.S01SubmitAuthorizationClient;
import com.livingdocs.document.model.*;

import com.livingdocs.document.exception.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class S01SubmitAuthorizationClientTest {
    final JsonMapper json = new JsonMapper();
    final UUID actor = UUID.randomUUID(), tenant = UUID.randomUUID(), workspace = UUID.randomUUID();
    HttpServer server;
    ExecutorService executor;
    String uri, response, authorization;
    int status = 200, calls;
    String contentType = "application/json";
    boolean stallBody;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/context", exchange -> {
            calls++;
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Location", "/context?redirected=true");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                if (stallBody) {
                    out.write(bytes, 0, 1);
                    out.flush();
                    try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                out.write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/context";
        response = json.writeValueAsString(envelope(context()));
    }

    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    Jwt jwt() {
        return Jwt.withTokenValue("user-access-token").header("alg", "RS256").subject(actor.toString()).build();
    }

    Map<String, Object> grant() {
        return new HashMap<>(Map.of("workspaceId", workspace.toString(), "roles", List.of("DEVELOPER"),
                "permissions", List.of("WORKSPACE_VIEW", "DOCUMENT_AUTHOR"), "repositoryIds", List.of()));
    }

    Map<String, Object> context() {
        return new HashMap<>(Map.of("identityId", actor.toString(), "tenantId", tenant.toString(), "authzRevision", 1,
                "evaluatedAt", Instant.now().toString(), "workspaceGrants", List.of(grant())));
    }

    Map<String, Object> envelope(Object data) { return Map.of("code", "ACCESS_CONTEXT_LOADED", "data", data); }

    S01SubmitAuthorizationClient client() { return new S01SubmitAuthorizationClient(new com.livingdocs.document.client.S01AccessContextClient(json), uri, true); }

    @Test void identityOnlyTokenForwardsBearerAndLoadsScopedAuthorityOnEveryCall() {
        var client = client();
        assertThat(client.resolve(jwt()).grants()).hasSize(1);
        assertThat(authorization).isEqualTo("Bearer user-access-token");
        response = json.writeValueAsString(envelope(new HashMap<>(Map.of("identityId", actor.toString()))));
        assertThatThrownBy(() -> client.resolve(jwt())).isInstanceOf(SubmitException.class);
        assertThat(calls).isEqualTo(2);
    }

    @Test void absentMembershipAndUpstreamAuthenticationOrDenialAreMapped() {
        var data = context();
        data.put("tenantId", null);
        response = json.writeValueAsString(envelope(data));
        assertThatThrownBy(() -> client().resolve(jwt())).isInstanceOf(SubmitException.class);
        status = 401;
        assertThatThrownBy(() -> client().resolve(jwt())).isInstanceOf(SubmitException.class);
        status = 403;
        assertThatThrownBy(() -> client().resolve(jwt())).isInstanceOf(SubmitException.class);
    }

    @Test void developerRoleAndPermissionsAreRequiredForTheTargetResource() {
        for (String missing : List.of("roles", "permissions")) {
            var grant = grant();
            grant.put(missing, List.of());
            var data = context();
            data.put("workspaceGrants", List.of(grant));
            response = json.writeValueAsString(envelope(data));
            var resolved = client().resolve(jwt());
            var version = new DocumentVersion(UUID.randomUUID(), tenant, UUID.randomUUID(), workspace, null, null, DocumentState.DRAFT, null, 0);
            assertThatThrownBy(() -> resolved.requireAuthor(version)).isInstanceOf(SubmitException.class);
        }
    }

    @Test void malformedMismatchedAndAmbiguousAuthorityFailsClosed() {
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (String key : context().keySet()) {
            var data = context();
            data.remove(key);
            invalid.add(data);
        }
        for (var value : List.of(-1, "1", new java.math.BigInteger("18446744073709551616"))) {
            var data = context(); data.put("authzRevision", value); invalid.add(data);
        }
        for (var entry : Map.<String, Object>of("identityId", UUID.randomUUID().toString(), "tenantId", "bad",
                "evaluatedAt", "bad", "workspaceGrants", List.of(grant(), grant())).entrySet()) {
            var data = context(); data.put(entry.getKey(), entry.getValue()); invalid.add(data);
        }
        for (var entry : Map.<String, Object>of("workspaceId", "bad", "roles", "STAFF", "permissions", List.of("STAFF", "STAFF"),
                "repositoryIds", List.of("bad")).entrySet()) {
            var grant = grant(); grant.put(entry.getKey(), entry.getValue());
            var data = context(); data.put("workspaceGrants", List.of(grant)); invalid.add(data);
        }
        for (var data : invalid) {
            response = json.writeValueAsString(envelope(data));
            assertThatThrownBy(() -> client().resolve(jwt())).as(response).isInstanceOf(SubmitException.class);
        }
        for (String body : List.of("{}", "not-json", "null", "{\"code\":\"OTHER\"}", "x".repeat(262145))) {
            response = body;
            assertThatThrownBy(() -> client().resolve(jwt())).isInstanceOf(SubmitException.class);
        }
    }

    @Test void redirectServerErrorsAndWrongContentTypeAreUnavailableWithoutFollowingRedirects() {
        for (int code : List.of(302, 404, 429, 500, 503)) {
            status = code;
            assertThatThrownBy(() -> client().resolve(jwt())).isInstanceOf(SubmitException.class);
        }
        assertThat(calls).isEqualTo(5);
        status = 200;
        contentType = "text/html";
        assertThatThrownBy(() -> client().resolve(jwt())).isInstanceOf(SubmitException.class);
    }

    @Test void stalledBodyHasBoundedDeadlineAndConnectionFailureIsUnavailable() {
        stallBody = true;
        long started = System.nanoTime();
        assertThatThrownBy(() -> client().resolve(jwt())).isInstanceOf(SubmitException.class);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(5));
        server.stop(0);
        assertThatThrownBy(() -> client().resolve(jwt())).isInstanceOf(SubmitException.class);
    }

    @Test void missingConfigOrPrincipalFailsClosedAndHttpRequiresExplicitLocalException() {
        assertThatThrownBy(() -> client().resolve(null)).isInstanceOf(SubmitException.class);
        assertThatThrownBy(() -> new S01SubmitAuthorizationClient(new com.livingdocs.document.client.S01AccessContextClient(json), "", false).resolve(jwt()))
                .isInstanceOf(SubmitException.class);
        for (String endpoint : List.of(uri, "https://user:secret@example.org/context", "https://example.org/context?x=1",
                "https://example.org/context#fragment", "/relative", "file:///context")) {
            assertThatThrownBy(() -> new S01SubmitAuthorizationClient(new com.livingdocs.document.client.S01AccessContextClient(json), endpoint, false)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls).isZero();
    }
}
