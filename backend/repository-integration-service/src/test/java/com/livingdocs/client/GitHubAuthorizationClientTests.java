package com.livingdocs.client;

import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** External HTTP adapter seam; no mocks of business services or persistence. */
class GitHubAuthorizationClientTests {
    private static final String ITEM = "{\"id\":15110,\"app_id\":151,\"account\":{\"id\":15120,\"login\":\"owner\"},\"repository_selection\":\"selected\",\"suspended_at\":null}";
    private final AtomicReference<String> body = new AtomicReference<>("{\"total_count\":1,\"installations\":[" + ITEM + "]}");
    private final AtomicReference<String> media = new AtomicReference<>("application/json");
    private final AtomicReference<String> link = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger(), delay = new AtomicInteger();
    private GitHubAuthorizationIo io;
    private GitHubAuthorizationClient github;
    private GitHubAuthorizationProperties properties;
    private HttpServer server;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", media.get());
            if (link.get() != null) exchange.getResponseHeaders().set("Link", link.get());
            exchange.sendResponseHeaders(status.get(), response.length);
            if (delay.get() > 0) {
                try { Thread.sleep(delay.get()); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()); server.start();
        properties = new GitHubAuthorizationProperties(); properties.setAllowLocalHttp(true); properties.setAppId("151");
        properties.setApiBaseUri("http://127.0.0.1:" + server.getAddress().getPort());
        io = new GitHubAuthorizationIo();
        github = new GitHubAuthorizationClient(properties, io, JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build());
    }
    @AfterEach
    void close() { if (github != null) github.close(); if (io != null) io.close(); if (server != null) server.stop(0); }

    @ParameterizedTest @ValueSource(ints = {302, 403, 404, 429, 500, 502, 503})
    void providerFailureIsUnavailableAndIsNeverRetried(int value) {
        status.set(value); assertCode("GITHUB_AUTHORIZATION_UNAVAILABLE", () -> github.installations("fixture-token", 1, "failure"));
        assertEquals(1, calls.get());
    }

    @Test
    void aRevokedGitHubTokenRequiresReauthorizationWithoutLoggingOutTheLivingDocsUser() {
        status.set(401); assertCode("GITHUB_REAUTHORIZATION_REQUIRED", () -> github.installations("fixture-token", 1, "revoked"));
    }

    @ParameterizedTest @ValueSource(strings = {"app", "id", "overflow", "duplicate", "selection", "suspended", "missing", "count", "json", "trailing", "duplicate-json"})
    void invalidProviderEvidenceCannotBecomeAnInstallationPage(String fault) {
        body.set(switch (fault) {
            case "app" -> body.get().replace("\"app_id\":151", "\"app_id\":999");
            case "id" -> body.get().replace("\"id\":15110", "\"id\":0");
            case "overflow" -> body.get().replace("\"id\":15110", "\"id\":9223372036854775808");
            case "duplicate" -> "{\"total_count\":2,\"installations\":[" + ITEM + "," + ITEM + "]}";
            case "selection" -> body.get().replace("selected", "unknown");
            case "suspended" -> body.get().replace("\"suspended_at\":null", "\"suspended_at\":\"not-a-date\"");
            case "missing" -> body.get().replace(",\"suspended_at\":null", "");
            case "count" -> body.get().replace("\"total_count\":1", "\"total_count\":\"1\"");
            case "trailing" -> body.get() + " {}";
            case "duplicate-json" -> body.get().replace("\"total_count\":1", "\"total_count\":0,\"total_count\":1");
            default -> "invalid json";
        });
        assertCode("GITHUB_AUTHORIZATION_UNAVAILABLE", () -> github.installations("fixture-token", 1, "malformed"));
    }

    @Test
    void followsNoPaginationLinksAndAcceptsOnlyTheConstructedNextPage() {
        body.set(body.get().replace("\"total_count\":1", "\"total_count\":101"));
        link.set("<" + properties.getApiBaseUri() + "/user/installations?per_page=100&page=2>; rel=\"next\"");
        assertTrue(github.installations("fixture-token", 1, "page").isHasNext()); assertEquals(1, calls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"host", "path", "page", "size"})
    void untrustedOrInconsistentNextPageIsNotReturnedToTheCaller(String fault) {
        body.set(body.get().replace("\"total_count\":1", "\"total_count\":101"));
        String next = properties.getApiBaseUri() + "/user/installations?per_page=100&page=2";
        next = switch (fault) {
            case "host" -> "https://untrusted.invalid/user/installations?per_page=100&page=2";
            case "path" -> next.replace("/user/installations", "/other");
            case "page" -> next.replace("page=2", "page=3");
            default -> next.replace("per_page=100", "per_page=1");
        };
        link.set("<" + next + ">; rel=\"next\"");
        assertCode("GITHUB_AUTHORIZATION_UNAVAILABLE", () -> github.installations("fixture-token", 1, "bad-link"));
        assertEquals(1, calls.get());
    }

    @Test
    void missingNextPageEvidenceCannotSilentlyHideTheRestOfTheInstallations() {
        body.set(body.get().replace("\"total_count\":1", "\"total_count\":101"));
        assertCode("GITHUB_AUTHORIZATION_UNAVAILABLE", () -> github.installations("fixture-token", 1, "missing-next"));
    }

    @Test
    void wrongMediaAndOversizedBodiesCannotBeParsedAsProviderEvidence() {
        media.set("text/html"); assertCode("GITHUB_AUTHORIZATION_UNAVAILABLE", () -> github.installations("fixture-token", 1, "html"));
        media.set("application/json"); body.set(" ".repeat(1024 * 1024 + 1));
        assertCode("GITHUB_AUTHORIZATION_UNAVAILABLE", () -> github.installations("fixture-token", 1, "oversized"));
    }

    @Test
    void completeProviderBodyHasATwoSecondDeadline() {
        delay.set(4000); long start = System.nanoTime();
        assertCode("GITHUB_AUTHORIZATION_UNAVAILABLE", () -> github.installations("fixture-token", 1, "stalled"));
        assertTrue(System.nanoTime() - start < 3_000_000_000L);
    }

    @Test
    void configurationCannotPointTheGithubAdapterAtAnArbitraryHttpsHost() {
        properties.setApiBaseUri("https://untrusted.invalid");
        assertCode("GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE", () -> github.installations("fixture-token", 1, "config"));
        assertEquals(0, calls.get());
    }

    private static void assertCode(String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(GitHubAuthorizationException.class, action).getError().name());
    }
}
