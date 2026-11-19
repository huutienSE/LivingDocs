package com.livingdocs.client;

import com.livingdocs.client.GitHubInstallationTokenProvider.RepositoryAccess;
import com.livingdocs.exception.SourceApiException;
import com.livingdocs.exception.SourceApiException.UnavailableReason;
import com.livingdocs.model.TargetKind;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Đọc đối tượng Git từ kho đã xác thực và kiểm chứng danh tính trong từng phản hồi. */
@Component
public final class GitHubSourceClient {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubSourceClient.class);
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final String ZERO_SHA = "0".repeat(40);
    private static final String BRANCH_PREFIX = "refs/heads/";
    private static final String TRACKED_BRANCH = """
            query ReadTrackedBranch($repoNodeId: ID!, $targetRef: String!) {
              node(id: $repoNodeId) {
                __typename
                ... on Repository {
                  fullDatabaseId
                  ref(qualifiedName: $targetRef) {
                    prefix
                    name
                    repository { fullDatabaseId }
                    target {
                      __typename
                      oid
                    }
                  }
                }
              }
            }
            """;
    private static final String PULL_REQUEST = """
            query ReadPullRequestHead($repoNodeId: ID!, $prNumber: Int!) {
              node(id: $repoNodeId) {
                __typename
                ... on Repository {
                  fullDatabaseId
                  pullRequest(number: $prNumber) {
                    number
                    baseRepository { fullDatabaseId }
                    headRepository { fullDatabaseId }
                    headRefOid
                    headRef {
                      repository { fullDatabaseId }
                      target {
                        __typename
                        oid
                      }
                    }
                  }
                }
              }
            }
            """;
    private final GitHubInstallationTokenProvider tokens;
    private final ObjectMapper json;
    private final RestClient client;

    @Autowired
    public GitHubSourceClient(GitHubInstallationTokenProvider tokens, ObjectMapper json) {
        this(tokens, json, GitHubHttp.clientBuilder());
    }

    GitHubSourceClient(GitHubInstallationTokenProvider tokens, ObjectMapper json, RestClient.Builder builder) {
        this.tokens = tokens;
        this.json = json;
        this.client = builder.baseUrl(GitHubHttp.BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "LivingDocs-S02")
                .build();
    }

    public Optional<CommitObservation> readBranch(RepositoryAccess access, String ref) {
        if (!TargetKind.BRANCH.isValidRef(ref)) {
            throw unavailable();
        }
        JsonNode branch = readRepository(access, TRACKED_BRANCH, Map.of(
                "repoNodeId", access.getNodeId(), "targetRef", ref)).path("ref");
        if (branch.isNull()) {
            return Optional.empty();
        }
        if (!BRANCH_PREFIX.equals(text(branch.path("prefix")))
                || !ref.equals(BRANCH_PREFIX + text(branch.path("name")))
                || repositoryId(branch.path("repository")) != access.getRepositoryId()) {
            throw unavailable();
        }
        return Optional.of(readCommit(branch.path("target"), access.getRepositoryId(), access.getRepositoryId()));
    }

    public Optional<CommitObservation> readPullRequest(RepositoryAccess access, int number) {
        if (number <= 0) {
            throw unavailable();
        }
        JsonNode pullRequest = readRepository(access, PULL_REQUEST, Map.of(
                "repoNodeId", access.getNodeId(), "prNumber", number)).path("pullRequest");
        if (pullRequest.isNull()) {
            return Optional.empty();
        }
        JsonNode actualNumber = pullRequest.path("number");
        if (!actualNumber.isIntegralNumber() || !actualNumber.canConvertToInt() || actualNumber.asInt() != number
                || repositoryId(pullRequest.path("baseRepository")) != access.getRepositoryId()) {
            throw unavailable();
        }
        long sourceId = repositoryId(pullRequest.path("headRepository"));
        JsonNode head = pullRequest.path("headRef");
        if (repositoryId(head.path("repository")) != sourceId) {
            throw unavailable();
        }
        CommitObservation commit = readCommit(head.path("target"), access.getRepositoryId(), sourceId);
        if (!commit.getSha().equals(normalizeSha(text(pullRequest.path("headRefOid"))))) {
            throw unavailable();
        }
        return Optional.of(commit);
    }

    private JsonNode readRepository(RepositoryAccess access, String query, Map<String, ?> variables) {
        JsonNode response;
        try {
            response = client.post().uri("/graphql").headers(access::authenticate)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("query", query, "variables", variables))
                    .exchange((outgoing, incoming) -> {
                        int status = incoming.getStatusCode().value();
                        if (status != 200) {
                            boolean rateLimited = (status == 403 || status == 429)
                                    && reportRateLimit(incoming.getHeaders(), status == 429);
                            if (status == 401 || (status == 403 && !rateLimited)) {
                                tokens.discardAccess(access);
                            }
                            throw unavailable();
                        }
                        JsonNode body = GitHubHttp.readBody(incoming, json);
                        if (body == null || !body.isObject()) {
                            throw unavailable();
                        }
                        JsonNode errors = body.path("errors");
                        if (!errors.isMissingNode() && (!errors.isArray() || !errors.isEmpty())) {
                            boolean rateLimited = reportRateLimit(incoming.getHeaders(), hasErrorType(errors, "RATE_LIMITED"));
                            if (!rateLimited && (hasErrorType(errors, "FORBIDDEN") || hasErrorType(errors, "UNAUTHORIZED"))) {
                                tokens.discardAccess(access);
                            }
                            // Truy vấn chỉ chọn trường cần kiểm. Dữ liệu đi kèm lỗi không chứng minh được ref vắng mặt.
                            throw unavailable();
                        }
                        return body;
                    });
        } catch (RestClientException | JacksonException failure) {
            throw unavailable();
        }
        JsonNode repository = response.path("data").path("node");
        if (!"Repository".equals(text(repository.path("__typename")))
                || repositoryId(repository) != access.getRepositoryId()) {
            throw unavailable();
        }
        return repository;
    }

    private static boolean hasErrorType(JsonNode errors, String type) {
        if (errors.isArray()) {
            for (JsonNode error : errors) {
                if (type.equals(text(error.path("type")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean reportRateLimit(HttpHeaders headers, boolean rateLimitReported) {
        String retryAfter = secondsHeader(headers.getFirst("Retry-After"));
        boolean exhausted = "0".equals(headers.getFirst("X-RateLimit-Remaining"));
        if (rateLimitReported || exhausted || retryAfter != null) {
            String reset = secondsHeader(headers.getFirst("X-RateLimit-Reset"));
            LOG.warn("GitHub rate limit reached, retryAfterSeconds={}, rateLimitResetEpochSeconds={}", retryAfter, reset);
            return true;
        }
        return false;
    }

    private static String secondsHeader(String value) {
        return value != null && value.matches("[0-9]{1,12}") ? value : null;
    }

    private static long repositoryId(JsonNode repository) {
        JsonNode id = repository.path("fullDatabaseId");
        if (id.isIntegralNumber() && id.canConvertToLong() && id.asLong() > 0) {
            return id.asLong();
        }
        if (id.isString() && ID.matcher(id.asString()).matches()) {
            try {
                return Long.parseLong(id.asString());
            } catch (NumberFormatException failure) {
                throw unavailable();
            }
        }
        throw unavailable();
    }

    private static CommitObservation readCommit(JsonNode object, long queriedRepositoryId, long sourceRepositoryId) {
        if (!"Commit".equals(text(object.path("__typename")))) {
            throw unavailable();
        }
        String sha = normalizeSha(text(object.path("oid")));
        return new CommitObservation(queriedRepositoryId, sourceRepositoryId, sha);
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : "";
    }

    private static String normalizeSha(String sha) {
        if (sha == null || !SHA.matcher(sha).matches() || ZERO_SHA.equals(sha)) {
            throw unavailable();
        }
        return sha.toLowerCase(Locale.ROOT);
    }

    private static SourceApiException unavailable() {
        return SourceApiException.unavailable(UnavailableReason.GITHUB_SOURCE_UNVERIFIED);
    }

    /** Quan sát từ GitHub trước khi PostgreSQL cấp phiên bản nguồn. */
    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class CommitObservation {
        private final long queriedRepositoryId;
        private final long sourceRepositoryId;
        private final String sha;
    }
}
