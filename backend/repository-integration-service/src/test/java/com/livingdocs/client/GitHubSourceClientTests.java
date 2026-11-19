package com.livingdocs.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.livingdocs.client.GitHubInstallationTokenProvider.RepositoryAccess;
import com.livingdocs.exception.SourceApiException;
import java.net.SocketTimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class GitHubSourceClientTests {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realHttpWaitIsLimitedByTheRemainingRequestBudget(boolean stallsBody) throws Exception {
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(handlers);
        http.createContext("/graphql", exchange -> {
            try {
                if (!stallsBody) {
                    Thread.sleep(1500);
                }
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                if (stallsBody) {
                    Thread.sleep(1500);
                }
                exchange.getResponseBody().write('}');
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });
        http.start();
        RestClient.Builder realBuilder = GitHubHttp.clientBuilder()
                .baseUrl("http://127.0.0.1:" + http.getAddress().getPort());
        // Constructor của client cố định host GitHub. Dùng cùng transport qua RestClient để đo timeout thực.
        RestClient real = realBuilder.build();
        try (var deadline = new com.livingdocs.model.SourceRequestDeadline(
                java.time.Duration.ofMillis(200), System::nanoTime)) {
            deadline.bind();
            assertTimeout(java.time.Duration.ofSeconds(1), () ->
                    assertThrows(org.springframework.web.client.ResourceAccessException.class,
                            () -> real.post().uri("/graphql").exchange((request, response) ->
                                    GitHubHttp.readBody(response, JsonMapper.builder().build()))));
        } finally {
            http.stop(0);
            handlers.shutdownNow();
        }
    }
    private static final long REPO = 9007199254740993L;
    private static final long FORK = 9007199254740995L;
    private static final String NODE_ID = "R_github_fixture";
    private static final String REF = "refs/heads/Release/v1";
    private static final String SHA = "a".repeat(40);
    private static final String MERGE_SHA = "b".repeat(40);
    private static final String SECRET = "test_installation_token";

    private final GitHubInstallationTokenProvider tokens = mock(GitHubInstallationTokenProvider.class);
    private final RepositoryAccess access = mock(RepositoryAccess.class);
    private MockRestServiceServer server;
    private GitHubSourceClient github;

    @BeforeEach
    void setUp() {
        when(access.getRepositoryId()).thenReturn(REPO);
        when(access.getNodeId()).thenReturn(NODE_ID);
        doAnswer(invocation -> {
            invocation.getArgument(0, HttpHeaders.class).setBearerAuth(SECRET);
            return null;
        }).when(access).authenticate(any(HttpHeaders.class));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        github = new GitHubSourceClient(tokens, JsonMapper.builder().build(), builder);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void readsExactBranchAndNormalizesShaWithoutLosingRepositoryIdPrecision() {
        graphql().andExpect(jsonPath("$.variables.targetRef").value(REF))
                .andRespond(withSuccess(response("\"ref\":" + branch("Release/v1", REPO,
                        commit("A".repeat(40)))), MediaType.APPLICATION_JSON));

        var result = github.readBranch(access, REF).orElseThrow();
        assertEquals(REPO, result.getQueriedRepositoryId());
        assertEquals(REPO, result.getSourceRepositoryId());
        assertEquals(SHA, result.getSha());
    }

    @Test
    void readsForkHeadShaForTheExactPullRequest() {
        graphql().andExpect(jsonPath("$.variables.prNumber").value(42))
                .andRespond(withSuccess(response("\"pullRequest\":" + pullRequest(42, REPO, FORK, FORK, SHA,
                        commit(SHA))), MediaType.APPLICATION_JSON));

        var result = github.readPullRequest(access, 42).orElseThrow();
        assertEquals(REPO, result.getQueriedRepositoryId());
        assertEquals(FORK, result.getSourceRepositoryId());
        assertEquals(SHA, result.getSha());
    }

    @Test
    void returnsAbsenceForAnExplicitNullRefOnlyOnTheVerifiedRepository() {
        graphql().andRespond(withSuccess(response("\"ref\":null"), MediaType.APPLICATION_JSON));
        assertTrue(github.readBranch(access, REF).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FORBIDDEN", "UNAUTHORIZED", "RATE_LIMITED"})
    void handlesGraphqlPermissionAndRateErrorsEvenWithHttp200(String errorType) {
        String body = "{\"errors\":[{\"type\":\"" + errorType + "\",\"path\":[\"node\",\"ref\"]}],"
                + response("\"ref\":null").substring(1);
        graphql().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(SourceApiException.class, () -> github.readBranch(access, REF));
        if ("RATE_LIMITED".equals(errorType)) {
            verifyNoInteractions(tokens);
        } else {
            verify(tokens).discardAccess(access);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unverifiedBranches")
    void rejectsUnverifiedBranchResponses(String scenario, String body) {
        graphql().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(SourceApiException.class, () -> github.readBranch(access, REF));
    }

    static Stream<Arguments> unverifiedBranches() {
        return Stream.of(
                Arguments.of("GitHub trả tên nhánh khớp một phần", response("\"ref\":" + branch("v1", REPO, commit(SHA)))),
                Arguments.of("Nhánh khác hoa thường", response("\"ref\":" + branch("release/v1", REPO, commit(SHA)))),
                Arguments.of("Nhánh thuộc kho khác", response("\"ref\":" + branch("Release/v1", FORK, commit(SHA)))),
                Arguments.of("Kho ngoài phạm vi", response("\"ref\":null").replaceFirst(Long.toString(REPO), Long.toString(FORK))),
                Arguments.of("Thiếu trường ref", response("\"unrelated\":null")),
                Arguments.of("Không phải commit", response("\"ref\":" + branch("Release/v1", REPO,
                        commit(SHA).replace("\"Commit\"", "\"Tag\"")))),
                Arguments.of("SHA toàn số không", response("\"ref\":" + branch("Release/v1", REPO, commit("0".repeat(40))))),
                Arguments.of("SHA kiểu số JSON", response("\"ref\":" + branch("Release/v1", REPO,
                        commit("1".repeat(40)).replace("\"" + "1".repeat(40) + "\"", "1".repeat(40))))),
                Arguments.of("Thiếu SHA", response("\"ref\":" + branch("Release/v1", REPO, "{\"__typename\":\"Commit\"}"))),
                Arguments.of("JSON hỏng", "{broken"),
                Arguments.of("Phản hồi vượt giới hạn", " ".repeat(256 * 1024 + 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unverifiedPullRequests")
    void rejectsUnverifiedPullRequests(String scenario, String pullRequest) {
        graphql().andRespond(withSuccess(response("\"pullRequest\":" + pullRequest), MediaType.APPLICATION_JSON));
        assertThrows(SourceApiException.class, () -> github.readPullRequest(access, 42));
    }

    static Stream<Arguments> unverifiedPullRequests() {
        return Stream.of(
                Arguments.of("Sai số PR", pullRequest(43, REPO, FORK, FORK, SHA, commit(SHA))),
                Arguments.of("Sai kho gốc", pullRequest(42, FORK, FORK, FORK, SHA, commit(SHA))),
                Arguments.of("Nhánh nguồn thuộc kho khác", pullRequest(42, REPO, FORK, REPO, SHA, commit(SHA))),
                Arguments.of("Hai SHA đầu PR khác nhau", pullRequest(42, REPO, FORK, FORK, SHA, commit(MERGE_SHA))),
                Arguments.of("Nhánh nguồn không đọc được", pullRequest(42, REPO, FORK, FORK, SHA, "null")));
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 401, 403, 404, 429, 500})
    void httpErrorsFailClosedAndHideProviderDetails(int status) {
        graphql().andRespond(withStatus(HttpStatus.valueOf(status)).body(SECRET));
        var failure = assertThrows(SourceApiException.class, () -> github.readBranch(access, REF));
        assertFalse(failure.getMessage().contains(SECRET));
        assertNull(failure.getCause());
        if (status == 401 || status == 403) {
            verify(tokens).discardAccess(access);
        }
    }

    @Test
    void timeoutFailsClosedAndHidesProviderDetails() {
        graphql().andRespond(withException(new SocketTimeoutException(SECRET)));
        var failure = assertThrows(SourceApiException.class, () -> github.readBranch(access, REF));
        assertFalse(failure.getMessage().contains(SECRET));
        assertNull(failure.getCause());
    }

    private ResponseActions graphql() {
        return server.expect(requestTo("https://api.github.com/graphql")).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET))
                .andExpect(jsonPath("$.variables.repoNodeId").value(NODE_ID));
    }

    private static String response(String field) {
        return """
                {"data":{"node":{"__typename":"Repository","fullDatabaseId":"%d",%s}}}
                """.formatted(REPO, field);
    }

    private static String branch(String name, long repository, String target) {
        return """
                {"prefix":"refs/heads/","name":"%s","repository":{"fullDatabaseId":%d},"target":%s}
                """.formatted(name, repository, target);
    }

    private static String pullRequest(int number, long base, long head, long headRef, String headSha, String target) {
        return """
                {"number":%d,"baseRepository":{"fullDatabaseId":%d},"headRepository":{"fullDatabaseId":%d},
                 "headRefOid":"%s","headRef":{"repository":{"fullDatabaseId":%d},"target":%s},
                 "mergeCommit":{"oid":"%s"}}
                """.formatted(number, base, head, headSha, headRef, target, MERGE_SHA);
    }

    private static String commit(String sha) {
        return "{\"__typename\":\"Commit\",\"oid\":\"" + sha + "\"}";
    }
}
