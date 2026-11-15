package com.livingdocs.client;

import com.livingdocs.config.GitHubAppProperties;
import com.livingdocs.exception.SourceUnavailableException;
import com.livingdocs.model.SourceScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
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
    private final Map<TokenKey, InstallationToken> tokens = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public GitHubInstallationTokenProvider(GitHubAppProperties properties, ObjectMapper json) {
        this(properties, json, httpClientBuilder(), Clock.systemUTC());
    }

    GitHubInstallationTokenProvider(GitHubAppProperties properties, ObjectMapper json,
                                    RestClient.Builder builder, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.client = builder.baseUrl("https://api.github.com")
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
            return new RepositoryAccess(repositoryId, nodeId, token.value);
        } catch (SourceUnavailableException failure) {
            synchronized (tokens) {
                tokens.remove(key, token);
            }
            throw failure;
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
                byte[] body = incoming.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw unavailable();
                }
                return json.readTree(body);
            });
        } catch (RestClientException | JacksonException failure) {
            // Không truyền lỗi thô của HTTP client hoặc GitHub sang luồng nghiệp vụ.
            throw unavailable();
        }
    }

    private String signAppJwt(String credentialRef) {
        String clientId = properties.getClientId();
        String keyDirectory = properties.getKeyDirectory();
        if (clientId == null || clientId.isBlank() || keyDirectory == null || keyDirectory.isBlank()) {
            throw unavailable();
        }
        try {
            Path root = Path.of(keyDirectory).toRealPath();
            Path keyFile = root.resolve(credentialRef).toRealPath();
            if (!keyFile.startsWith(root) || !Files.isRegularFile(keyFile)) {
                throw unavailable();
            }
            Instant now = clock.instant();
            byte[] header = json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT"));
            byte[] claims = json.writeValueAsBytes(Map.of("iss", clientId, "iat", now.minusSeconds(60).getEpochSecond(),
                    "exp", now.plusSeconds(240).getEpochSecond()));
            String signingInput = JWT_ENCODER.encodeToString(header) + "." + JWT_ENCODER.encodeToString(claims);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(readPrivateKey(keyFile));
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + JWT_ENCODER.encodeToString(signer.sign());
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | JacksonException failure) {
            throw unavailable();
        }
    }

    private static PrivateKey readPrivateKey(Path keyFile) throws IOException, GeneralSecurityException {
        try (var input = Files.newInputStream(keyFile)) {
            byte[] bytes = input.readNBytes(MAX_KEY_BYTES + 1);
            if (bytes.length > MAX_KEY_BYTES) {
                throw unavailable();
            }
            String pem = new String(bytes, StandardCharsets.US_ASCII).strip();
            boolean pkcs1 = pem.startsWith("-----BEGIN RSA PRIVATE KEY-----");
            String label = pkcs1 ? "RSA PRIVATE KEY" : "PRIVATE KEY";
            String begin = "-----BEGIN " + label + "-----";
            String end = "-----END " + label + "-----";
            if (!pem.startsWith(begin) || !pem.endsWith(end)) {
                throw unavailable();
            }
            String encoded = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1 ? wrapPkcs1(der) : der));
        }
    }

    private static byte[] wrapPkcs1(byte[] rsaKey) {
        // PKCS#8 PrivateKeyInfo: phiên bản 0, định danh rsaEncryption, rồi OCTET STRING chứa PKCS#1.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(HexFormat.of().parseHex("020100300d06092a864886f70d0101010500"));
        writeDer(body, 0x04, rsaKey);
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        writeDer(key, 0x30, body.toByteArray());
        return key.toByteArray();
    }

    private static void writeDer(ByteArrayOutputStream output, int tag, byte[] value) {
        int length = value.length;
        output.write(tag);
        if (length < 128) {
            output.write(length);
        } else if (length < 256) {
            output.write(0x81);
            output.write(length);
        } else {
            output.write(0x82);
            output.write(length >>> 8);
            output.write(length);
        }
        output.writeBytes(value);
    }

    private static RestClient.Builder httpClientBuilder() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().requestFactory(requests);
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

    private static SourceUnavailableException unavailable() {
        return new SourceUnavailableException("Không xác minh được quyền truy cập kho GitHub.");
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
        @Getter private final long repositoryId;
        @Getter private final String nodeId;
        private final String token;

        void authenticate(HttpHeaders headers) {
            headers.setBearerAuth(token);
        }
    }
}
