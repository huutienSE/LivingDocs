package com.livingdocs.client;

import com.livingdocs.config.GitHubAppProperties;
import com.livingdocs.exception.SourceApiException;
import com.livingdocs.exception.SourceApiException.UnavailableReason;
import com.livingdocs.model.SourceScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Sử dụng định danh từ kết nối đã kiểm tra phạm vi trong S02. */
@Component
@EnableConfigurationProperties(GitHubAppProperties.class)
public final class GitHubInstallationTokenProvider {
    private static final int MAX_KEY_BYTES = 16 * 1024;
    private static final int CACHE_CAPACITY = 128;
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);
    private static final Pattern KEY_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern BEARER = Pattern.compile("[A-Za-z0-9._~+/-]+=*");
    private static final Map<String, String> PERMISSIONS = Map.of("contents", "read", "pull_requests", "read");
    private static final Base64.Encoder JWT_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final GitHubAppProperties properties;
    private final ObjectMapper json;
    private final RestClient client;
    private final Clock clock;
    private final GitHubAppJwtSigner appSigner;
    private final Map<TokenKey, InstallationToken> tokens = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public GitHubInstallationTokenProvider(GitHubAppProperties properties, ObjectMapper json) {
        this(properties, json, GitHubHttp.clientBuilder(), Clock.systemUTC());
    }

    GitHubInstallationTokenProvider(GitHubAppProperties properties, ObjectMapper json,
                                    RestClient.Builder builder, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.appSigner = new GitHubAppJwtSigner(properties, json, clock);
        this.client = builder.baseUrl(GitHubHttp.BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2026-03-10")
                .defaultHeader(HttpHeaders.USER_AGENT, "LivingDocs-S02")
                .build();
    }

    public RepositoryAccess openRepository(SourceScope scope, String installationRef, String externalRepoId,
                                           String credentialRef, String configRevision) {
        if (credentialRef == null || !KEY_REFERENCE.matcher(credentialRef).matches()
                || configRevision == null || configRevision.isBlank() || configRevision.length() > 255) {
            throw unavailable();
        }
        long installationId = parseId(installationRef);
        long repositoryId = parseId(externalRepoId);
        TokenKey key = new TokenKey(scope.tenantId(), installationId, repositoryId, credentialRef, configRevision);
        InstallationToken cached = cachedToken(key);
        InstallationToken token = cached != null ? cached : requestToken(installationId, repositoryId, credentialRef);
        try {
            JsonNode result = readResponse(client.get().uri("/installation/repositories?per_page=2")
                    .headers(headers -> headers.setBearerAuth(token.value)), 200);
            JsonNode repositories = result.path("repositories");
            if (!hasId(result.path("total_count"), 1) || !repositories.isArray() || repositories.size() != 1
                    || !hasId(repositories.path(0).path("id"), repositoryId)) {
                throw unavailable();
            }
            String nodeId = requiredText(repositories.path(0).path("node_id"));
            if (!usable(token)) {
                throw unavailable();
            }
            synchronized (tokens) {
                tokens.put(key, token);
                if (tokens.size() > CACHE_CAPACITY) {
                    tokens.remove(tokens.keySet().iterator().next());
                }
            }
            return new RepositoryAccess(nodeId, key, token);
        } catch (SourceApiException failure) {
            discardToken(key, token);
            throw failure;
        }
    }

    void discardAccess(RepositoryAccess access) {
        discardToken(access.key, access.token);
    }

    private void discardToken(TokenKey key, InstallationToken token) {
        synchronized (tokens) {
            tokens.remove(key, token);
        }
    }

    private InstallationToken cachedToken(TokenKey key) {
        synchronized (tokens) {
            InstallationToken token = tokens.get(key);
            if (token != null && !usable(token)) {
                tokens.remove(key);
                return null;
            }
            return token;
        }
    }

    private boolean usable(InstallationToken token) {
        return clock.instant().plus(EXPIRY_MARGIN).isBefore(token.expiresAt);
    }

    private InstallationToken requestToken(long installationId, long repositoryId, String credentialRef) {
        String appJwt = signAppJwt(credentialRef);
        JsonNode response = readResponse(client.post().uri("/app/installations/{id}/access_tokens", installationId)
                .headers(headers -> headers.setBearerAuth(appJwt))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repository_ids", List.of(repositoryId), "permissions", PERMISSIONS)), 201);
        JsonNode permissions = response.path("permissions");
        if (!permissions.isObject() || !"read".equals(permissions.path("contents").asString(""))
                || !"read".equals(permissions.path("pull_requests").asString(""))
                || permissions.properties().stream().anyMatch(entry ->
                        (!PERMISSIONS.containsKey(entry.getKey()) && !"metadata".equals(entry.getKey()))
                                || !"read".equals(entry.getValue().asString("")))) {
            throw unavailable();
        }
        String value = requiredText(response.path("token"));
        if (!BEARER.matcher(value).matches()) {
            throw unavailable();
        }
        try {
            InstallationToken token = new InstallationToken(value, Instant.parse(requiredText(response.path("expires_at"))));
            if (!usable(token)) {
                throw unavailable();
            }
            return token;
        } catch (DateTimeParseException failure) {
            throw unavailable();
        }
    }

    private JsonNode readResponse(RestClient.RequestHeadersSpec<?> request, int expectedStatus) {
        try {
            return request.exchange((outgoing, incoming) -> {
                if (incoming.getStatusCode().value() != expectedStatus) {
                    throw unavailable();
                }
                return GitHubHttp.readBody(incoming, json);
            });
        } catch (RestClientException | JacksonException failure) {
            // Không truyền lỗi thô của HTTP client hoặc GitHub sang luồng nghiệp vụ.
            throw unavailable();
        }
    }

    private String signAppJwt(String credentialRef) {
        try { return appSigner.sign(credentialRef); }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    private static long parseId(String id) {
        if (id == null || !POSITIVE_ID.matcher(id).matches()) {
            throw unavailable();
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException failure) {
            throw unavailable();
        }
    }

    private static boolean hasId(JsonNode node, long expected) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() == expected;
    }

    private static String requiredText(JsonNode node) {
        if (!node.isString() || node.asString().isBlank()) {
            throw unavailable();
        }
        return node.asString();
    }

    private static SourceApiException unavailable() {
        return SourceApiException.unavailable(UnavailableReason.GITHUB_ACCESS_UNVERIFIED);
    }

    @EqualsAndHashCode
    @RequiredArgsConstructor
    private static final class TokenKey {
        private final UUID tenantId;
        private final long installationId;
        private final long repositoryId;
        private final String credentialRef;
        private final String configRevision;
    }

    @RequiredArgsConstructor
    private static final class InstallationToken {
        private final String value;
        private final Instant expiresAt;
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class RepositoryAccess {
        @Getter private final String nodeId;
        private final TokenKey key;
        private final InstallationToken token;

        public long getRepositoryId() {
            return key.repositoryId;
        }

        void authenticate(HttpHeaders headers) {
            headers.setBearerAuth(token.value);
        }
    }
}
