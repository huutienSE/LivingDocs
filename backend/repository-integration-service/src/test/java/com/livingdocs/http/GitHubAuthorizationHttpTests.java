package com.livingdocs.http;

import com.livingdocs.client.S01AccessContextClient;
import com.livingdocs.client.GitHubAuthorizationIo;
import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.config.GitHubAuthorizationSecurityConfiguration;
import com.livingdocs.config.GitHubAuthorizationHttpConfiguration;
import com.livingdocs.controller.GitHubAuthorizationController;
import com.livingdocs.exception.GitHubAuthorizationProblems;
import com.livingdocs.exception.SourceProblems;
import com.livingdocs.mapper.GitHubAuthorizationMapperImpl;
import com.livingdocs.security.SourceCorrelationFilter;
import com.livingdocs.config.SourceSecurityConfiguration;
import com.livingdocs.config.SourceHttpConfiguration;
import com.livingdocs.service.impl.GitHubAuthorizationServiceImpl;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real S02 MVC/security and HTTP to a controlled S01/JWKS boundary; not S01/GitHub E2E. */
@SpringBootTest(classes = GitHubAuthorizationHttpTests.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.profiles.active=",
        "server.http2.enabled=true",
        "livingdocs.github.authorization.allow-local-http=true",
        "livingdocs.github.authorization.app-id=151", "livingdocs.github.authorization.app-slug=livingdocs-test",
        "livingdocs.github.authorization.app-name=LivingDocs Test"})
@AutoConfigureMockMvc
class GitHubAuthorizationHttpTests {
    private static final UUID ACTOR = UUID.fromString("15100000-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE = UUID.fromString("15100000-0000-4000-8000-000000000002");
    private static final UUID TENANT = UUID.fromString("15100000-0000-4000-8000-000000000003");
    private static final RSAKey KEY = key();
    private static final AtomicReference<String> ROLE = new AtomicReference<>("DEVELOPER");
    private static final AtomicReference<UnaryOperator<String>> CONTEXT = new AtomicReference<>(s -> s);
    private static final AtomicReference<String> MEDIA = new AtomicReference<>("application/json");
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger BODY_DELAY = new AtomicInteger();
    private static final AtomicInteger JWKS_DELAY = new AtomicInteger();
    private static final AtomicReference<String> FORWARDED_TOKEN = new AtomicReference<>();
    private static final AtomicReference<java.util.concurrent.CountDownLatch> ARRIVED = new AtomicReference<>();
    private static final AtomicReference<java.util.concurrent.CountDownLatch> RELEASE = new AtomicReference<>();
    private static final HttpServer S01 = upstream();
    @Autowired private MockMvc mvc;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;

    @BeforeEach
    void resetProvider() {
        ROLE.set("DEVELOPER"); CONTEXT.set(s -> s); MEDIA.set("application/json"); STATUS.set(200);
        CALLS.set(0); FORWARDED_TOKEN.set(null);
        BODY_DELAY.set(0);
        JWKS_DELAY.set(0);
        ARRIVED.set(null); RELEASE.set(null);
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + S01.getAddress().getPort();
        r.add("livingdocs.github.authorization.s01-base-uri", () -> base);
        r.add("livingdocs.github.authorization.jwk-set-uri", () -> base + "/.well-known/jwks.json");
    }

    @Test
    void developerCanReadRequirementsBeforeAnyConnectionOrInstallationExists() throws Exception {
        String success = mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("GITHUB_AUTHORIZATION_CONTEXT_READ"))
                .andExpect(jsonPath("$.data.workspaceId").value(WORKSPACE.toString()))
                .andExpect(jsonPath("$.data.requiredPermissions.length()").value(4))
                .andExpect(jsonPath("$.data.context.status").value("NONE"))
                .andExpect(jsonPath("$.data.verification.status").value("USER_AUTHORIZATION_REQUIRED"))
                .andExpect(jsonPath("$.data.verification.grantedPermissions").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        saveEvidence("http-success.json", success);
        org.junit.jupiter.api.Assertions.assertEquals(1, CALLS.get());
    }

    @Test
    void eachReadReevaluatesMembershipAndForwardsOnlyTheVerifiedBearer() throws Exception {
        String bearer = token(ACTOR);
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk());
        ROLE.set("STAFF");
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Role", "TECHNICAL_LEAD").header("X-Tenant-ID", TENANT.toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE"));
        org.junit.jupiter.api.Assertions.assertEquals(2, CALLS.get());
        org.junit.jupiter.api.Assertions.assertEquals("Bearer " + bearer, FORWARDED_TOKEN.get());
    }

    @Test
    void technicalLeadCanReadWithoutAnyRepositoryGrant() throws Exception {
        ROLE.set("TECHNICAL_LEAD");
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAFF", "TENANT_ADMIN", "PLATFORM_ADMIN"})
    void unrelatedRolesCannotReadEvenWithWorkspaceView(String role) throws Exception {
        ROLE.set(role);
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownWorkspaceIsHiddenEvenWhenClientClaimsMembership() throws Exception {
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token(ACTOR)).header("X-Workspace-ID", WORKSPACE.toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void userWithoutTenantCannotRead() throws Exception {
        CONTEXT.set(s -> s.replace("\"" + TENANT + "\"", "null"));
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aHumanJwtDoesNotBecomeASourceWorkloadCredential() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/v1/source/resolve")
                        .secure(true).header("Authorization", "Bearer " + token(ACTOR)).contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
        org.junit.jupiter.api.Assertions.assertEquals(0, CALLS.get());
    }

    @Test
    void workspaceViewIsRequiredEvenForADeveloper() throws Exception {
        CONTEXT.set(s -> s.replace("\"WORKSPACE_VIEW\"", "\"REPOSITORY_READ\""));
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale", "duplicate", "malformed", "oversized", "tenantRoles", "repositoryIds", "otherGrant"})
    void ambiguousAuthorityProjectionCannotGrantAccess(String fault) throws Exception {
        CONTEXT.set(s -> switch (fault) {
            case "stale" -> s.replaceAll("\"evaluatedAt\":\"[^\"]+\"", "\"evaluatedAt\":\"2020-01-01T00:00:00Z\"");
            case "duplicate" -> s.replace("\"repositoryIds\":[]}]", "\"repositoryIds\":[]},{\"workspaceId\":\"" + WORKSPACE + "\",\"roles\":[],\"permissions\":[],\"repositoryIds\":[]}]");
            case "oversized" -> " ".repeat(256 * 1024 + 1) + s;
            case "tenantRoles" -> s.replace("\"tenantRoles\":[]", "\"tenantRoles\":null");
            case "repositoryIds" -> s.replace("\"repositoryIds\":[]", "\"repositoryIds\":\"not an array\"");
            case "otherGrant" -> s.replace("\"repositoryIds\":[]}]", "\"repositoryIds\":[]},{\"workspaceId\":\"15100000-0000-4000-8000-000000000099\",\"roles\":null,\"permissions\":[],\"repositoryIds\":[]}]");
            default -> "invalid json";
        });
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void authorityUnauthorizedRemainsUnauthorized() throws Exception {
        STATUS.set(401);
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IDENTITY_INACTIVE", "TENANT_INACTIVE", "TENANT_MEMBERSHIP_REQUIRED", "AUTHZ_DENIED", "UNKNOWN"})
    void authorityDenialMustHaveAPublishedReason(String reason) throws Exception {
        STATUS.set(403); MEDIA.set("application/problem+json");
        CONTEXT.set(s -> "{\"code\":\"" + reason + "\"}");
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().is(reason.equals("UNKNOWN") ? 503 : 403));
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspaceId=bad", "workspaceId=00000000-0000-0000-0000-000000000000",
            "workspaceId=%s&workspaceId=%s", "workspaceId=%s&role=DEVELOPER", "workspaceId=%s&installationId=12",
            "workspaceId=%s&installationPage=1", "workspaceId=%s&authorizationContextId=bad",
            "workspaceId=%s&authorizationContextId=%s&installationId=9223372036854775808",
            "workspaceId=%s&authorizationContextId=%s&installationId=1&installationPage=2147483648"})
    void invalidQueryFailsBeforeCallingAuthority(String query) throws Exception {
        String expanded = query.replace("%s", WORKSPACE.toString());
        mvc.perform(get("/api/v1/integrations/github/authorization?" + expanded)
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        org.junit.jupiter.api.Assertions.assertEquals(0, CALLS.get());
    }

    @Test
    void bodyWithUndeclaredLengthIsRejectedBeforeCallingAuthority() throws Exception {
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)).content("unexpected")
                        .with(request -> new org.springframework.mock.web.MockHttpServletRequest() {
                            { setMethod(request.getMethod()); setRequestURI(request.getRequestURI());
                              setParameters(request.getParameterMap()); setContent("unexpected".getBytes(StandardCharsets.UTF_8));
                              addHeader("Authorization", request.getHeader("Authorization")); }
                            @Override public long getContentLengthLong() { return -1; }
                            @Override public int getContentLength() { return -1; }
                        }))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(0, CALLS.get());
    }

    @Test
    void actualHttp2BodyWithoutLengthTimesOutWithoutReadingOnAnOrphanedWorker() throws Exception {
        var client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_2).build();
        try {
            var uri = java.net.URI.create("http://127.0.0.1:" + port
                    + "/api/v1/integrations/github/authorization?workspaceId=" + WORKSPACE);
            String bearer = token(ACTOR);
            var warm = client.send(java.net.http.HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + bearer)
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            org.junit.jupiter.api.Assertions.assertEquals(java.net.http.HttpClient.Version.HTTP_2, warm.version());
            org.junit.jupiter.api.Assertions.assertEquals(200, warm.statusCode(), warm.body());
            java.util.concurrent.Flow.Publisher<java.nio.ByteBuffer> stalled = subscriber -> subscriber.onSubscribe(
                    new java.util.concurrent.Flow.Subscription() {
                        @Override public void request(long count) { }
                        @Override public void cancel() { }
                    });
            var bad = client.send(java.net.http.HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + bearer)
                    .timeout(java.time.Duration.ofSeconds(4))
                    .method("GET", java.net.http.HttpRequest.BodyPublishers.fromPublisher(stalled)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            org.junit.jupiter.api.Assertions.assertEquals(400, bad.statusCode(), bad.body());
            org.junit.jupiter.api.Assertions.assertTrue(bad.body().contains("INVALID_REQUEST"));
            org.junit.jupiter.api.Assertions.assertEquals(1, CALLS.get(), "Stalled body must not reach S01");

            var subscriber = new AtomicReference<java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer>>();
            var requested = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Flow.Publisher<java.nio.ByteBuffer> delayedEmpty = listener -> {
                subscriber.set(listener);
                listener.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                    @Override public void request(long count) { requested.countDown(); }
                    @Override public void cancel() { }
                });
            };
            var empty = client.sendAsync(java.net.http.HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + bearer)
                    .timeout(java.time.Duration.ofSeconds(4))
                    .method("GET", java.net.http.HttpRequest.BodyPublishers.fromPublisher(delayedEmpty)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            org.junit.jupiter.api.Assertions.assertTrue(requested.await(1, java.util.concurrent.TimeUnit.SECONDS));
            Thread.sleep(150); // Let Tomcat observe HEADERS before the client sends END_STREAM.
            subscriber.get().onComplete();
            var resumed = empty.get(4, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertEquals(200, resumed.statusCode(), resumed.body());
            org.junit.jupiter.api.Assertions.assertEquals(2, CALLS.get(), "Empty END_STREAM must resume the verified request once");
        } finally { client.shutdownNow(); }
    }

    @Test
    void missingTokenCannotBeReplacedByTrustedLookingHeaders() throws Exception {
        String problem = mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("X-User-ID", ACTOR.toString()).header("X-Role", "DEVELOPER"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andReturn().getResponse().getContentAsString();
        saveEvidence("http-problem.json", problem);
        org.junit.jupiter.api.Assertions.assertEquals(0, CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "audience", "issuer", "expired", "future", "no-kid"})
    void invalidHumanClaimsAreRejectedBeforeAuthority(String invalidClaim) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(invalidClaim.equals("actor") ? "service-principal" : ACTOR.toString())
                .issuer(invalidClaim.equals("issuer") ? "urn:untrusted" : "urn:livingdocs:s01")
                .audience(invalidClaim.equals("audience") ? "livingdocs-internal" : "livingdocs-api")
                .issueTime(Date.from(now.minusSeconds(900))).notBeforeTime(Date.from(now.minusSeconds(900)))
                .expirationTime(Date.from(now.plusSeconds(invalidClaim.equals("expired") ? -60 : 20)));
        if (invalidClaim.equals("future")) claims.notBeforeTime(Date.from(now.plusSeconds(60)));
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256);
        if (!invalidClaim.equals("no-kid")) header.keyID("test-key");
        var jwt = new SignedJWT(header.build(), claims.build()); jwt.sign(new RSASSASigner(KEY));
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + jwt.serialize()))
                .andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertEquals(0, CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 404, 429, 500, 503})
    void upstreamFailureDoesNotInventGrantedPermissions(int upstreamStatus) throws Exception {
        STATUS.set(upstreamStatus);
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_CONTEXT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void identityMismatchIsUnavailableRatherThanAValidAuthorizationDecision() throws Exception {
        CONTEXT.set(s -> s.replace(ACTOR.toString(), UUID.randomUUID().toString()));
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void successBodyWithUnexpectedMediaTypeIsNotAnAuthorityResponse() throws Exception {
        MEDIA.set("text/html");
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void stalledAuthorityBodyIsBoundedAndNeverBecomesAnAuthorizationSuccess() throws Exception {
        BODY_DELAY.set(4000);
        long start = System.nanoTime();
        mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                        .header("Authorization", "Bearer " + token(ACTOR)))
                .andExpect(status().isServiceUnavailable());
        org.junit.jupiter.api.Assertions.assertTrue(System.nanoTime() - start < 3_000_000_000L,
                "S01 complete response, including body, must be bounded by its 1.5s I/O budget");
    }

    @Test
    void runtimeOpenApiDescribesTheActualTypedHttpBoundary() throws Exception {
        String specification = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/integrations/github/authorization'].get.operationId").value("api046_get"))
                .andExpect(jsonPath("$.paths['/api/v1/integrations/github/authorization'].get.parameters[0].name").value("workspaceId"))
                .andExpect(jsonPath("$.components.schemas.GitHubAuthorizationView.required.length()").value(8))
                .andExpect(jsonPath("$.paths['/api/v1/integrations/github/authorization'].get.responses['503'].content['application/problem+json'].schema['$ref']").value("#/components/schemas/GitHubAuthorizationProblem"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andReturn().getResponse().getContentAsString();
        saveEvidence("runtime-openapi.json", specification);
    }

    private static void saveEvidence(String file, String body) throws Exception {
        java.nio.file.Path evidence = java.nio.file.Path.of("target/liv151-build", file);
        java.nio.file.Files.createDirectories(evidence.getParent());
        java.nio.file.Files.writeString(evidence, body);
    }

    @Test
    void saturatedAuthorityIoRejectsAnotherReadWithoutStartingAnotherNetworkCall() throws Exception {
        var arrived = new java.util.concurrent.CountDownLatch(16);
        var release = new java.util.concurrent.CountDownLatch(1);
        ARRIVED.set(arrived); RELEASE.set(release);
        String bearer = token(ACTOR);
        try (var clients = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = new ArrayList<java.util.concurrent.Future<Integer>>();
            try {
                for (int i = 0; i < 16; i++) requests.add(clients.submit(() -> mvc.perform(
                        get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                                .header("Authorization", "Bearer " + bearer)).andReturn().getResponse().getStatus()));
                org.junit.jupiter.api.Assertions.assertTrue(arrived.await(1, java.util.concurrent.TimeUnit.SECONDS));
                mvc.perform(get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                                .header("Authorization", "Bearer " + bearer))
                        .andExpect(status().isServiceUnavailable());
                org.junit.jupiter.api.Assertions.assertEquals(16, CALLS.get(), "Overload must not start a seventeenth authority call");
            } finally { release.countDown(); }
            for (var request : requests) org.junit.jupiter.api.Assertions.assertEquals(200, request.get());
        }
    }

    @Test
    void jwksRefreshWaitIsIncludedInEachRequestsOneSecondTrustBudget() throws Exception {
        JWKS_DELAY.set(4000);
        String signed = token(ACTOR);
        var original = SignedJWT.parse(signed);
        var unknownKey = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("unavailable-key").build(), original.getJWTClaimsSet());
        unknownKey.sign(new RSASSASigner(KEY));
        long start = System.nanoTime();
        try (var clients = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 3; i++) requests.add(clients.submit(() -> mvc.perform(
                    get("/api/v1/integrations/github/authorization").param("workspaceId", WORKSPACE.toString())
                            .header("Authorization", "Bearer " + unknownKey.serialize()))
                    .andReturn().getResponse().getStatus()));
            for (var request : requests) org.junit.jupiter.api.Assertions.assertEquals(503, request.get());
        }
        org.junit.jupiter.api.Assertions.assertTrue(System.nanoTime() - start < 2_500_000_000L,
                "Waiting for another JWKS refresh must not multiply the one-second trust budget");
        org.junit.jupiter.api.Assertions.assertEquals(0, CALLS.get());
    }

    private static HttpServer upstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                boolean jwks = exchange.getRequestURI().getPath().equals("/.well-known/jwks.json");
                if (!jwks) {
                    CALLS.incrementAndGet(); FORWARDED_TOKEN.set(exchange.getRequestHeaders().getFirst("Authorization"));
                }
                String body = jwks
                        ? new JWKSet(KEY.toPublicJWK()).toString()
                        : """
                        {"code":"ACCESS_CONTEXT_LOADED","message":"Access context loaded","data":{
                          "identityId":"%s","tenantId":"%s","authzRevision":1,"evaluatedAt":"%s",
                          "tenantRoles":[],"tenantPermissions":[],"workspaceGrants":[{
                          "workspaceId":"%s","roles":["%s"],"permissions":["WORKSPACE_VIEW"],"repositoryIds":[]}]}}
                        """.formatted(ACTOR,TENANT,Instant.now(),WORKSPACE,ROLE.get());
                if (!jwks) body = CONTEXT.get().apply(body);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", jwks ? "application/json" : MEDIA.get());
                exchange.sendResponseHeaders(jwks ? 200 : STATUS.get(), bytes.length);
                if (jwks && JWKS_DELAY.get() > 0) {
                    try { Thread.sleep(JWKS_DELAY.get()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                var arrived = ARRIVED.get(); var release = RELEASE.get();
                if (!jwks && arrived != null && release != null) {
                    arrived.countDown();
                    try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (!jwks && BODY_DELAY.get() > 0) {
                    try { Thread.sleep(BODY_DELAY.get()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static RSAKey key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey(pair.getPrivate()).keyID("test-key").build();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String token(UUID actor) throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                new JWTClaimsSet.Builder().subject(actor.toString()).issuer("urn:livingdocs:s01")
                        .audience("livingdocs-api").issueTime(Date.from(now)).notBeforeTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(900))).build());
        jwt.sign(new RSASSASigner(KEY)); return jwt.serialize();
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.test.context.TestComponent
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    @EnableConfigurationProperties(GitHubAuthorizationProperties.class)
    @Import({GitHubAuthorizationController.class, GitHubAuthorizationServiceImpl.class, S01AccessContextClient.class,
            GitHubAuthorizationSecurityConfiguration.class, GitHubAuthorizationProblems.class,
            GitHubAuthorizationMapperImpl.class, SourceCorrelationFilter.class, SourceProblems.class,
            SourceSecurityConfiguration.class, SourceHttpConfiguration.class, GitHubAuthorizationHttpConfiguration.class,
            GitHubAuthorizationIo.class})
    static class Application {
        @Bean
        org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer emptyHttpFraming() {
            // MockMvc has no container framing; an empty GET uses Content-Length: 0 here.
            return builder -> builder.defaultRequest(get("/").content(new byte[0]));
        }
        /** Only this test application exposes documentation anonymously; production trust is unchanged. */
        @Bean @Order(0)
        SecurityFilterChain testDocumentation(HttpSecurity http) throws Exception {
            return http.securityMatcher("/v3/api-docs/**").authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
    }
}
