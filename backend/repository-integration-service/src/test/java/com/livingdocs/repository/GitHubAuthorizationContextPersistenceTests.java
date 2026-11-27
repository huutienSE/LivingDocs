package com.livingdocs.repository;

import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.model.GitHubAuthorizationUser;
import com.livingdocs.service.GitHubAuthorizationContextService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Public context-service seam with real PostgreSQL/RLS and a controlled S01 HTTP boundary. */
@SpringBootTest(properties = {"spring.config.import=", "livingdocs.github.authorization.allow-local-http=true",
        "livingdocs.github.authorization.app-id=151", "livingdocs.github.authorization.app-slug=livingdocs-test",
        "livingdocs.github.authorization.app-name=LivingDocs Test", "livingdocs.github.authorization.client-id=Iv1.fixture151",
        "livingdocs.github.authorization.browser-origin=https://livingdocs.test",
        "livingdocs.github.authorization.callback-uri=https://livingdocs.test/api/v1/integrations/github/authorization/callback",
        "livingdocs.github.authorization.completion-uri=https://livingdocs.test/settings/integrations/github/complete"})
@ActiveProfiles("db")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql:.*")
class GitHubAuthorizationContextPersistenceTests {
    private static final UUID ACTOR = UUID.fromString("15100000-0000-4000-8000-000000000001");
    private static final UUID TENANT = UUID.fromString("15100000-0000-4000-8000-000000000003");
    private static final AtomicReference<UUID> WORKSPACE = new AtomicReference<>(UUID.randomUUID());
    private static final AtomicReference<UUID> ACTIVE_TENANT = new AtomicReference<>(TENANT);
    private static final AtomicReference<UUID> ACTIVE_ACTOR = new AtomicReference<>(ACTOR);
    private static final AtomicReference<String> ROLE = new AtomicReference<>("DEVELOPER");
    private static final java.util.concurrent.atomic.AtomicInteger EXCHANGES = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger USERS_READ = new java.util.concurrent.atomic.AtomicInteger();
    private static final AtomicReference<String> VERIFIER = new AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicInteger INSTALLATIONS_READ = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger REPOSITORIES_READ = new java.util.concurrent.atomic.AtomicInteger();
    private static final AtomicReference<String> DISCOVERY_BEARER = new AtomicReference<>();
    private static final AtomicReference<java.util.concurrent.CountDownLatch> EXCHANGE_ARRIVED = new AtomicReference<>();
    private static final AtomicReference<java.util.concurrent.CountDownLatch> EXCHANGE_RELEASE = new AtomicReference<>();
    private static final AtomicReference<java.util.concurrent.CountDownLatch> PROVIDER_ARRIVED = new AtomicReference<>();
    private static final AtomicReference<java.util.concurrent.CountDownLatch> PROVIDER_RELEASE = new AtomicReference<>();
    private static final AtomicReference<String> GATED_PATH = new AtomicReference<>("");
    private static final java.util.concurrent.atomic.AtomicInteger EXCHANGE_DELAY = new java.util.concurrent.atomic.AtomicInteger();
    private static final Path KEYS = fixtureKeys();
    private static final com.livingdocs.support.GitHubAppHttpFixture APP = new com.livingdocs.support.GitHubAppHttpFixture(KEYS);
    private static final HttpServer S01 = authority();
    private static final GitHubAuthorizationUser USER = new GitHubAuthorizationUser(ACTOR, "fixture-verified-human-token");
    @Autowired private GitHubAuthorizationContextService contexts;
    @Autowired private GitHubAuthorizationProperties properties;
    @Autowired private javax.sql.DataSource database;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        APP.configure(registry);
        registry.add("livingdocs.github.authorization.s01-base-uri", () -> "http://127.0.0.1:" + S01.getAddress().getPort());
        registry.add("livingdocs.github.authorization.state-key-directory", () -> KEYS.resolve("state").toString());
        registry.add("livingdocs.github.authorization.credential-key-directory", () -> KEYS.resolve("credential").toString());
        registry.add("livingdocs.github.authorization.client-secret-path", () -> KEYS.resolve("client-secret").toString());
        registry.add("livingdocs.github.authorization.api-base-uri", () -> "http://127.0.0.1:" + S01.getAddress().getPort());
        registry.add("livingdocs.github.authorization.oauth-base-uri", () -> "http://127.0.0.1:" + S01.getAddress().getPort());
    }

    @BeforeEach
    void newWorkspace() {
        WORKSPACE.set(UUID.randomUUID()); ACTIVE_TENANT.set(TENANT); ACTIVE_ACTOR.set(ACTOR); ROLE.set("DEVELOPER");
        properties.setContextTtl(java.time.Duration.ofMinutes(15)); properties.setClientId("Iv1.fixture151");
        properties.setStateKeyVersion("v1"); properties.setCredentialKeyVersion("v1");
        EXCHANGES.set(0); USERS_READ.set(0); VERIFIER.set(null);
        INSTALLATIONS_READ.set(0); REPOSITORIES_READ.set(0); DISCOVERY_BEARER.set(null);
        EXCHANGE_ARRIVED.set(null); EXCHANGE_RELEASE.set(null);
        PROVIDER_ARRIVED.set(null); PROVIDER_RELEASE.set(null); GATED_PATH.set(""); EXCHANGE_DELAY.set(0);
    }

    @Test
    void ownerCanStartInspectAndCancelATransientContextWithoutAConnectionOrGrant() {
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "liv151-start");
        assertNotNull(started.getContextId());
        assertTrue(started.getExpiresAt().isAfter(Instant.now()));
        assertTrue(started.getExpiresAt().isBefore(Instant.now().plusSeconds(901)));
        assertTrue(started.getAuthorizationUrl().startsWith("https://github.com/login/oauth/authorize?"));
        assertTrue(started.getAuthorizationUrl().contains("code_challenge_method=S256"));
        assertFalse(started.getAuthorizationUrl().contains("scope=repo"));
        assertTrue(started.getInstallationUrl().endsWith("/apps/livingdocs-test/installations/new"));
        var pending = contexts.inspect(USER, WORKSPACE.get(), started.getContextId(), "liv151-inspect");
        assertEquals("PENDING", pending.getStatus());
        contexts.cancel(USER, started.getContextId(), "liv151-cancel");
        contexts.cancel(USER, started.getContextId(), "liv151-cancel-repeat");
        var closed = assertThrows(GitHubAuthorizationException.class,
                () -> contexts.inspect(USER, WORKSPACE.get(), started.getContextId(), "liv151-closed"));
        assertEquals("AUTHORIZATION_CONTEXT_CONSUMED", closed.getError().name());
    }

    @Test
    void ownershipAndTenantRlsDoNotLeakAContextAndTheConnectionPoolRestoresTheOwnerScope() {
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "start");
        UUID ownerWorkspace = WORKSPACE.get();
        try {
            ACTIVE_TENANT.set(UUID.randomUUID());
            assertCode("AUTHORIZATION_CONTEXT_NOT_FOUND", () -> contexts.inspect(USER, ownerWorkspace, started.getContextId(), "other-tenant"));
            ACTIVE_TENANT.set(TENANT);
            UUID otherActor = UUID.randomUUID(); ACTIVE_ACTOR.set(otherActor);
            assertCode("AUTHORIZATION_CONTEXT_NOT_FOUND", () -> contexts.inspect(
                    new GitHubAuthorizationUser(otherActor, "fixture-other-human-token"), ownerWorkspace, started.getContextId(), "other-actor"));
            ACTIVE_ACTOR.set(ACTOR); WORKSPACE.set(UUID.randomUUID());
            assertCode("AUTHORIZATION_CONTEXT_NOT_FOUND", () -> contexts.inspect(USER, WORKSPACE.get(), started.getContextId(), "other-workspace"));
            WORKSPACE.set(ownerWorkspace);
            assertEquals("PENDING", contexts.inspect(USER, ownerWorkspace, started.getContextId(), "owner-restored").getStatus());
            ROLE.set("STAFF");
            assertCode("FORBIDDEN_SCOPE", () -> contexts.inspect(USER, ownerWorkspace, started.getContextId(), "membership-revoked"));
        } finally {
            ROLE.set("DEVELOPER"); ACTIVE_ACTOR.set(ACTOR); ACTIVE_TENANT.set(TENANT); WORKSPACE.set(ownerWorkspace);
            contexts.cancel(USER, started.getContextId(), "cancel");
        }
    }

    @Test
    void admissionIsAtomicWhenFiveRequestsCompeteForThreeActiveContexts() throws Exception {
        var created = new java.util.ArrayList<com.livingdocs.model.GitHubAuthorizationContextStart>();
        int rejected = 0;
        try (var clients = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new java.util.ArrayList<java.util.concurrent.Future<com.livingdocs.model.GitHubAuthorizationContextStart>>();
            for (int i = 0; i < 5; i++) attempts.add(clients.submit(
                    () -> contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "competing-start")));
            for (var attempt : attempts) {
                try { created.add(attempt.get()); }
                catch (java.util.concurrent.ExecutionException failure) {
                    assertInstanceOf(GitHubAuthorizationException.class, failure.getCause());
                    assertEquals("AUTHORIZATION_CONTEXT_LIMIT_REACHED", ((GitHubAuthorizationException) failure.getCause()).getError().name());
                    rejected++;
                }
            }
            assertEquals(3, created.size()); assertEquals(2, rejected);
        } finally { for (var context : created) contexts.cancel(USER, context.getContextId(), "cancel"); }
    }

    @Test
    void consentExpiresWithoutAGetExtendingTheLifetime() throws Exception {
        properties.setContextTtl(java.time.Duration.ofSeconds(2));
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "short-context");
        Thread.sleep(2100);
        assertCode("AUTHORIZATION_CONTEXT_EXPIRED", () -> contexts.inspect(USER, WORKSPACE.get(), started.getContextId(), "expired"));
        contexts.cancel(USER, started.getContextId(), "purge-expired-owner");
    }

    @Test
    void aChangedAppClientConfigurationRequiresANewContext() {
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "start");
        properties.setClientId("Iv1.changed151");
        assertCode("AUTHORIZATION_CONFIGURATION_CHANGED", () -> contexts.inspect(USER, WORKSPACE.get(), started.getContextId(), "changed"));
        contexts.cancel(USER, started.getContextId(), "cancel-old-config");
    }

    @Test
    void credentialAndStateKeyRingsCannotUseTheSameKeyMaterial() throws Exception {
        String material = Files.readString(KEYS.resolve("state/v1.key"));
        Files.writeString(KEYS.resolve("state/v2.key"), material);
        Files.writeString(KEYS.resolve("credential/v2.key"), material);
        properties.setStateKeyVersion("v2"); properties.setCredentialKeyVersion("v2");
        assertCode("GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE", () -> contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "same-key"));
    }

    @Test
    void callbackExchangesTheCodeOnceAndStoresAnOwnerBoundAuthorizationAfterVerifyingGitHubUserId() throws Exception {
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "start");
        var parameters = form(java.net.URI.create(started.getAuthorizationUrl()).getRawQuery());
        var cookies = java.util.Map.of("__Secure-ld-gh-" + started.getContextId(), started.getCookieNonce());
        var completed = contexts.complete(parameters.get("state"), cookies, "fixture-valid-code", null, "callback");
        assertEquals(started.getContextId(), completed.getContextId());
        assertEquals("https://livingdocs.test/settings/integrations/github/complete?contextId=" + started.getContextId(), completed.getCompletionUrl());
        assertEquals("AUTHORIZED", contexts.inspect(USER, WORKSPACE.get(), started.getContextId(), "authorized").getStatus());
        assertEquals(1, EXCHANGES.get()); assertEquals(1, USERS_READ.get());
        assertEquals(parameters.get("code_challenge"), Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(VERIFIER.get().getBytes(StandardCharsets.US_ASCII))));
        assertCode("AUTHORIZATION_CONTEXT_CONSUMED", () -> contexts.complete(parameters.get("state"), cookies, "fixture-valid-code", null, "replay"));
        assertEquals(1, EXCHANGES.get());
        contexts.cancel(USER, started.getContextId(), "cancel-authorized");
    }

    @Test
    void aWrongBrowserOrTamperedStateCannotConsumeThePendingContext() {
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "start");
        String state = form(java.net.URI.create(started.getAuthorizationUrl()).getRawQuery()).get("state");
        var cookies = java.util.Map.of("__Secure-ld-gh-" + started.getContextId(), started.getCookieNonce());
        assertCode("OAUTH_STATE_INVALID", () -> contexts.complete(state, java.util.Map.of(), "code", null, "no-cookie"));
        String tampered = state.substring(0, 20) + (state.charAt(20) == 'A' ? "B" : "A") + state.substring(21);
        assertCode("OAUTH_STATE_INVALID", () -> contexts.complete(tampered, cookies, "code", null, "tampered"));
        assertCode("OAUTH_STATE_INVALID", () -> contexts.complete(state, cookies, "code", "access_denied", "ambiguous"));
        assertEquals(0, EXCHANGES.get());
        assertEquals("PENDING", contexts.inspect(USER, WORKSPACE.get(), started.getContextId(), "pending").getStatus());
        contexts.complete(state, cookies, null, "access_denied", "user-denied");
        assertEquals(0, EXCHANGES.get());
        assertCode("AUTHORIZATION_CONTEXT_CONSUMED", () -> contexts.inspect(USER, WORKSPACE.get(), started.getContextId(), "failed"));
    }

    @Test
    void cancelWinsWhileExchangeIsWaitingAndLateCredentialsCannotResurrectTheContext() throws Exception {
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "start");
        var arrived = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        EXCHANGE_ARRIVED.set(arrived); EXCHANGE_RELEASE.set(release);
        var parameters = form(java.net.URI.create(started.getAuthorizationUrl()).getRawQuery());
        try (var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var callback = requests.submit(() -> contexts.complete(parameters.get("state"),
                    java.util.Map.of("__Secure-ld-gh-" + started.getContextId(), started.getCookieNonce()), "code", null, "callback"));
            try {
                assertTrue(arrived.await(1, java.util.concurrent.TimeUnit.SECONDS));
                contexts.cancel(USER, started.getContextId(), "cancel-during-provider-call");
            } finally { release.countDown(); }
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, callback::get);
            assertInstanceOf(GitHubAuthorizationException.class, failure.getCause());
            assertEquals("AUTHORIZATION_CONTEXT_CONSUMED", ((GitHubAuthorizationException) failure.getCause()).getError().name());
        }
        assertEquals(1, EXCHANGES.get()); assertEquals(1, USERS_READ.get());
        assertCode("AUTHORIZATION_CONTEXT_CONSUMED", () -> contexts.inspect(USER, WORKSPACE.get(), started.getContextId(), "still-cancelled"));
    }

    @Test
    void onlyOneConcurrentCallbackCanExchangeTheSameAuthorizationCode() throws Exception {
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "start");
        var parameters = form(java.net.URI.create(started.getAuthorizationUrl()).getRawQuery());
        var arrived = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        EXCHANGE_ARRIVED.set(arrived); EXCHANGE_RELEASE.set(release);
        int completed = 0, consumed = 0;
        try (var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var callbacks = new java.util.ArrayList<java.util.concurrent.Future<com.livingdocs.model.GitHubAuthorizationCallbackResult>>();
            for (int i = 0; i < 2; i++) callbacks.add(requests.submit(() -> contexts.complete(parameters.get("state"),
                    java.util.Map.of("__Secure-ld-gh-" + started.getContextId(), started.getCookieNonce()), "code", null, "racing-callback")));
            try { assertTrue(arrived.await(1, java.util.concurrent.TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            for (var callback : callbacks) {
                try { callback.get(); completed++; }
                catch (java.util.concurrent.ExecutionException failed) {
                    assertEquals("AUTHORIZATION_CONTEXT_CONSUMED", ((GitHubAuthorizationException) failed.getCause()).getError().name()); consumed++;
                }
            }
        }
        assertEquals(1, completed); assertEquals(1, consumed); assertEquals(1, EXCHANGES.get());
        contexts.cancel(USER, started.getContextId(), "cancel");
    }

    @Test
    void maintenancePurgesOnlyExpiredCredentialsWithoutGivingTheRuntimeRoleUnscopedReadAccess() throws Exception {
        properties.setContextTtl(java.time.Duration.ofSeconds(2));
        var expired = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "short");
        properties.setContextTtl(java.time.Duration.ofMinutes(15));
        var active = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "active");
        assertNotNull(storedPayload(expired.getContextId()));
        Thread.sleep(2100);
        assertTrue(contexts.purgeExpired() >= 1);
        assertNull(storedPayload(expired.getContextId()));
        assertNotNull(storedPayload(active.getContextId()));
        assertEquals("PENDING", contexts.inspect(USER, WORKSPACE.get(), active.getContextId(), "active-after-maintenance").getStatus());
        try (var connection = database.getConnection(); var query = connection.createStatement();
             var rows = query.executeQuery("select context_id from repository.github_authorization_contexts")) {
            assertFalse(rows.next(), "Runtime SELECT without a tenant must not bypass RLS");
        }
        contexts.cancel(USER, active.getContextId(), "cancel");
    }

    private String storedPayload(UUID contextId) throws Exception {
        // The database/RLS contract is itself the seam here; no business status is inferred from this query.
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (var scope = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                scope.setString(1, TENANT.toString()); scope.execute();
            }
            try (var query = connection.prepareStatement("select secret_payload from repository.github_authorization_contexts where context_id=?")) {
                query.setObject(1, contextId);
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
            } finally { connection.rollback(); }
        }
    }

    @Test
    void ciphertextCopiedFromAnotherContextCannotBecomeAUsableAuthorization() throws Exception {
        var first = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "first");
        var second = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "second");
        for (var context : java.util.List.of(first, second)) contexts.complete(
                form(java.net.URI.create(context.getAuthorizationUrl()).getRawQuery()).get("state"),
                java.util.Map.of("__Secure-ld-gh-" + context.getContextId(), context.getCookieNonce()), "code", null, "complete");
        String original = storedPayload(second.getContextId());
        try {
            replacePayload(second.getContextId(), storedPayload(first.getContextId()));
            assertCode("AUTHORIZATION_CONTEXT_UNAVAILABLE", () -> contexts.inspect(USER, WORKSPACE.get(), second.getContextId(), "swapped"));
            assertEquals("AUTHORIZED", contexts.inspect(USER, WORKSPACE.get(), first.getContextId(), "intact").getStatus());
        } finally {
            replacePayload(second.getContextId(), original);
            contexts.cancel(USER, first.getContextId(), "cancel-first"); contexts.cancel(USER, second.getContextId(), "cancel-second");
        }
    }

    @Test
    void authorizedOwnerCanDiscoverOneUserScopedInstallationAndRepositoryPage() {
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "start");
        assertCode("AUTHORIZATION_CONTEXT_NOT_READY", () -> contexts.installations(USER, started.getContextId(), 1, "pending"));
        assertEquals(0, INSTALLATIONS_READ.get());
        contexts.complete(form(java.net.URI.create(started.getAuthorizationUrl()).getRawQuery()).get("state"),
                java.util.Map.of("__Secure-ld-gh-" + started.getContextId(), started.getCookieNonce()), "code", null, "authorize");
        var installations = contexts.installations(USER, started.getContextId(), 1, "installations");
        assertEquals(1, installations.getPage()); assertFalse(installations.isHasNext());
        assertEquals(1, installations.getItems().size());
        assertEquals("15110", installations.getItems().getFirst().getInstallationId());
        assertEquals("fixture-owner", installations.getItems().getFirst().getAccountLogin());
        var repositories = contexts.repositories(USER, started.getContextId(), "15110", 1, "repositories");
        assertEquals(1, repositories.getItems().size()); assertFalse(repositories.isHasNext());
        assertEquals("15130", repositories.getItems().getFirst().getRepositoryId());
        assertEquals("fixture-owner/livingdocs", repositories.getItems().getFirst().getFullName());
        assertTrue(repositories.getItems().getFirst().isCanRead());
        assertEquals("Bearer ghu_liv151FixtureOnlyToken123", DISCOVERY_BEARER.get());
        assertEquals(1, INSTALLATIONS_READ.get()); assertEquals(1, REPOSITORIES_READ.get());
        contexts.cancel(USER, started.getContextId(), "cancel");
    }

    @Test
    void discoveryCannotReturnAPageAfterItsContextExpiresWhileTheProviderIsWaiting() throws Exception {
        properties.setContextTtl(java.time.Duration.ofSeconds(2));
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "start");
        complete(started);
        Thread.sleep(350);
        var arrived = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        PROVIDER_ARRIVED.set(arrived); PROVIDER_RELEASE.set(release); GATED_PATH.set("/user/installations");
        try (var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var page = requests.submit(() -> contexts.installations(USER, started.getContextId(), 1, "expires-in-flight"));
            try {
                assertTrue(arrived.await(1, java.util.concurrent.TimeUnit.SECONDS));
                Thread.sleep(Math.max(0, java.time.Duration.between(Instant.now(), started.getExpiresAt()).toMillis()) + 80);
            } finally { release.countDown(); }
            var failed = assertThrows(java.util.concurrent.ExecutionException.class, page::get);
            assertEquals("AUTHORIZATION_CONTEXT_EXPIRED", ((GitHubAuthorizationException)failed.getCause()).getError().name());
        } finally { contexts.cancel(USER, started.getContextId(), "cancel"); }
    }

    @Test
    void anExpiredContextCannotStoreTheCredentialReturnedByAnInFlightCallback() throws Exception {
        properties.setContextTtl(java.time.Duration.ofSeconds(2));
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "start");
        EXCHANGE_DELAY.set(800);
        var arrived = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        PROVIDER_ARRIVED.set(arrived); PROVIDER_RELEASE.set(release); GATED_PATH.set("/user");
        try (var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var callback = requests.submit(() -> complete(started));
            try {
                assertTrue(arrived.await(2, java.util.concurrent.TimeUnit.SECONDS));
                Thread.sleep(Math.max(0, java.time.Duration.between(Instant.now(), started.getExpiresAt()).toMillis()) + 80);
            } finally { release.countDown(); }
            var failed = assertThrows(java.util.concurrent.ExecutionException.class, callback::get);
            assertEquals("AUTHORIZATION_CONTEXT_EXPIRED", ((GitHubAuthorizationException)failed.getCause()).getError().name());
        }
        assertNull(storedPayload(started.getContextId()));
    }

    @Test
    void aConfigurationChangeDuringExchangeDiscardsTheReturnedCredential() throws Exception {
        var started = contexts.start(USER, WORKSPACE.get(), "https://livingdocs.test", "start");
        var arrived = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        EXCHANGE_ARRIVED.set(arrived); EXCHANGE_RELEASE.set(release);
        try (var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var callback = requests.submit(() -> complete(started));
            try { assertTrue(arrived.await(1, java.util.concurrent.TimeUnit.SECONDS)); properties.setClientId("Iv1.changed151"); }
            finally { release.countDown(); }
            var failed = assertThrows(java.util.concurrent.ExecutionException.class, callback::get);
            assertEquals("AUTHORIZATION_CONFIGURATION_CHANGED", ((GitHubAuthorizationException)failed.getCause()).getError().name());
        } finally { properties.setClientId("Iv1.fixture151"); }
        assertNull(storedPayload(started.getContextId()));
        assertCode("AUTHORIZATION_CONTEXT_CONSUMED", () -> contexts.inspect(USER, WORKSPACE.get(), started.getContextId(), "failed"));
    }

    private com.livingdocs.model.GitHubAuthorizationCallbackResult complete(com.livingdocs.model.GitHubAuthorizationContextStart context) {
        return contexts.complete(form(java.net.URI.create(context.getAuthorizationUrl()).getRawQuery()).get("state"),
                java.util.Map.of("__Secure-ld-gh-" + context.getContextId(), context.getCookieNonce()), "code", null, "callback");
    }

    private static void waitForProvider(String path) {
        var arrived = PROVIDER_ARRIVED.get(); var release = PROVIDER_RELEASE.get();
        if (path.equals(GATED_PATH.get()) && arrived != null && release != null) {
            arrived.countDown();
            try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private void replacePayload(UUID contextId, String ciphertext) throws Exception {
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (var scope = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                scope.setString(1, TENANT.toString()); scope.execute();
            }
            try (var update = connection.prepareStatement("update repository.github_authorization_contexts set secret_payload=? where context_id=?")) {
                update.setString(1, ciphertext); update.setObject(2, contextId); assertEquals(1, update.executeUpdate());
            }
            connection.commit();
        }
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(GitHubAuthorizationException.class, action).getError().name());
    }

    private static HttpServer authority() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/users/me/access-context", exchange -> {
                byte[] body = ("""
                    {"code":"ACCESS_CONTEXT_LOADED","message":"Access context loaded","data":{
                    "identityId":"%s","tenantId":"%s","authzRevision":1,"evaluatedAt":"%s",
                    "tenantRoles":[],"tenantPermissions":[],"workspaceGrants":[{"workspaceId":"%s",
                    "roles":["%s"],"permissions":["WORKSPACE_VIEW"],"repositoryIds":[]}]}}
                    """).formatted(ACTIVE_ACTOR.get(), ACTIVE_TENANT.get(), Instant.now(), WORKSPACE.get(), ROLE.get()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.createContext("/login/oauth/access_token", exchange -> {
                EXCHANGES.incrementAndGet();
                if (EXCHANGE_DELAY.get() > 0) {
                    try { Thread.sleep(EXCHANGE_DELAY.get()); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                var arrived = EXCHANGE_ARRIVED.get(); var release = EXCHANGE_RELEASE.get();
                if (arrived != null && release != null) {
                    arrived.countDown();
                    try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                var request = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                VERIFIER.set(request.get("code_verifier"));
                String body = "{\"access_token\":\"ghu_liv151FixtureOnlyToken123\",\"expires_in\":28800,\"refresh_token\":\"ghr_discard_fixture\",\"scope\":\"\",\"token_type\":\"bearer\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length());
                try (var out = exchange.getResponseBody()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            });
            server.createContext("/user", exchange -> {
                USERS_READ.incrementAndGet();
                waitForProvider("/user");
                String body = "{\"id\":15101,\"login\":\"livingdocs-fixture-user\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length());
                try (var out = exchange.getResponseBody()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            });
            server.createContext("/user/installations", exchange -> {
                waitForProvider("/user/installations");
                boolean repositories = exchange.getRequestURI().getPath().endsWith("/repositories");
                if (repositories) REPOSITORIES_READ.incrementAndGet(); else INSTALLATIONS_READ.incrementAndGet();
                DISCOVERY_BEARER.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String body = repositories
                        ? "{\"total_count\":1,\"repositories\":[{\"id\":15130,\"full_name\":\"fixture-owner/livingdocs\",\"permissions\":{\"pull\":true}}]}"
                        : "{\"total_count\":1,\"installations\":[{\"id\":15110,\"app_id\":151,\"account\":{\"id\":15120,\"login\":\"fixture-owner\"},\"repository_selection\":\"selected\",\"suspended_at\":null}]}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length());
                try (var out = exchange.getResponseBody()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            });
            server.createContext("/app", APP::handle);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start(); return server;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static java.util.Map<String, String> form(String value) {
        var result = new java.util.HashMap<String, String>();
        for (String pair : value.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    private static Path fixtureKeys() {
        try {
            Path directory = Files.createTempDirectory("liv151-fixture-keys-");
            for (String purpose : new String[]{"state", "credential"}) {
                Files.createDirectory(directory.resolve(purpose));
                byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
                Files.writeString(directory.resolve(purpose).resolve("v1.key"), Base64.getEncoder().encodeToString(key));
            }
            Files.writeString(directory.resolve("client-secret"), "liv151-fixture-client-secret-only");
            return directory;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
