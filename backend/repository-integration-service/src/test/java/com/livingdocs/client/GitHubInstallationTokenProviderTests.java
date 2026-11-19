package com.livingdocs.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.livingdocs.config.GitHubAppProperties;
import com.livingdocs.exception.SourceApiException;
import com.livingdocs.model.SourceScope;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class GitHubInstallationTokenProviderTests {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private static final String TOKEN_URL = "https://api.github.com/app/installations/101/access_tokens";
    private static final String REPOSITORIES_URL = "https://api.github.com/installation/repositories?per_page=2";
    private static final String TEST_TOKEN = "ghs_fixture";
    private static final String TOKEN_RESPONSE = """
            {"token":"ghs_fixture","expires_at":"2026-11-15T01:00:00Z",
             "permissions":{"contents":"read","pull_requests":"read","metadata":"read"}}
            """;
    private static final String REPOSITORY_RESPONSE = """
            {"total_count":1,"repositories":[{"id":202,"node_id":"R_fixture"}]}
            """;
    private static KeyPair keyPair;

    @TempDir Path secrets;
    private final JsonMapper json = new JsonMapper();
    private final SourceScope scope = new SourceScope(UUID.randomUUID(), UUID.randomUUID());
    private MockRestServiceServer server;
    private GitHubInstallationTokenProvider provider;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() throws Exception {
        writeKey("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        RestClient.Builder client = RestClient.builder();
        server = MockRestServiceServer.bindTo(client).build();
        provider = new GitHubInstallationTokenProvider(new GitHubAppProperties("Iv1.example", secrets.toString()),
                json, client, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signsAppJwtAndRequestsReadAccessForExactlyOneRepo(boolean pkcs1) throws Exception {
        if (pkcs1) {
            writeKey("RSA PRIVATE KEY", pkcs1FromKeyPair());
        }
        server.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
                .andExpect(content().json("""
                        {"repository_ids":[202],"permissions":{"contents":"read","pull_requests":"read"}}
                        """, JsonCompareMode.STRICT))
                .andExpect(request -> validateAppJwt(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(TOKEN_RESPONSE));
        expectRepositories(REPOSITORY_RESPONSE);

        var access = provider.openRepository(scope, "101", "202", "fixture.pem", "v1:12:17");
        assertEquals(202, access.getRepositoryId());
        assertEquals("R_fixture", access.getNodeId());
        HttpHeaders headers = new HttpHeaders();
        access.authenticate(headers);
        assertEquals("Bearer " + TEST_TOKEN, headers.getFirst(HttpHeaders.AUTHORIZATION));
        assertFalse(access.toString().contains(TEST_TOKEN));
        server.verify();
    }

    @Test
    void rechecksRepoOnCacheHitAndSeparatesTokensByConfigAndTenant() {
        expectToken(TOKEN_RESPONSE);
        expectRepositories(REPOSITORY_RESPONSE);
        expectRepositories(REPOSITORY_RESPONSE);
        expectToken(TOKEN_RESPONSE);
        expectRepositories(REPOSITORY_RESPONSE);
        expectToken(TOKEN_RESPONSE);
        expectRepositories(REPOSITORY_RESPONSE);

        provider.openRepository(scope, "101", "202", "fixture.pem", "v1:12:17");
        provider.openRepository(scope, "101", "202", "fixture.pem", "v1:12:17");
        provider.openRepository(scope, "101", "202", "fixture.pem", "v1:18:17");
        var otherTenant = new SourceScope(UUID.randomUUID(), scope.workspaceId());
        provider.openRepository(otherTenant, "101", "202", "fixture.pem", "v1:18:17");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"total_count\":1,\"repositories\":[{\"id\":203,\"node_id\":\"R_wrong\"}]}",
        "{\"total_count\":2,\"repositories\":[{\"id\":202,\"node_id\":\"R_fixture\"}]}",
        "{\"total_count\":1,\"repositories\":[{\"id\":202}]}"
    })
    void rejectsMissingIdentityOrABroaderRepoGrant(String repositoryResponse) {
        expectToken(TOKEN_RESPONSE);
        expectRepositories(repositoryResponse);
        assertThrows(SourceApiException.class, this::open);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-15T00:01:00Z", "not-a-timestamp"})
    void refusesTokenWithoutEnoughRemainingLifetime(String expiry) {
        expectToken(TOKEN_RESPONSE.replace("2026-11-15T01:00:00Z", expiry));
        assertThrows(SourceApiException.class, this::open);
        server.verify();
    }

    @Test
    void refusesAWritePermissionBeforeListingRepos() {
        expectToken(TOKEN_RESPONSE.replace("\"contents\":\"read\"", "\"contents\":\"write\""));
        assertThrows(SourceApiException.class, this::open);
        server.verify();
    }

    @Test
    void invalidatesCachedTokenAfterGithubDeniesAccessAndSanitizesTheFailure() {
        expectToken(TOKEN_RESPONSE);
        expectRepositories(REPOSITORY_RESPONSE);
        server.expect(requestTo(REPOSITORIES_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN).body(TEST_TOKEN));
        expectToken(TOKEN_RESPONSE);
        expectRepositories(REPOSITORY_RESPONSE);

        open();
        var failure = assertThrows(SourceApiException.class, this::open);
        assertFalse(failure.getMessage().contains(TEST_TOKEN));
        assertNull(failure.getCause());
        open();
        server.verify();
    }

    @Test
    void rejectsRedirectAndDoesNotUseLocation() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.FOUND).location(URI.create("https://example.com/")));
        assertThrows(SourceApiException.class, this::open);
        server.verify();
    }

    @Test
    void refusesAnUnconfiguredKeyAndAnInvalidReferenceBeforeSendingHttp() {
        assertThrows(SourceApiException.class,
                () -> provider.openRepository(scope, "101", "202", "missing.pem", "v1:12:17"));
        assertThrows(SourceApiException.class,
                () -> provider.openRepository(scope, "101", "202", "../fixture.pem", "v1:12:17"));
        server.verify();
    }

    private void open() {
        provider.openRepository(scope, "101", "202", "fixture.pem", "v1:12:17");
    }

    private void expectToken(String body) {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private void expectRepositories(String body) {
        server.expect(requestTo(REPOSITORIES_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TEST_TOKEN))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void validateAppJwt(String authorization) {
        assertTrue(authorization.startsWith("Bearer "));
        String[] parts = authorization.substring(7).split("\\.");
        assertEquals(3, parts.length);
        Base64.Decoder decoder = Base64.getUrlDecoder();
        assertEquals("RS256", json.readTree(decoder.decode(parts[0])).path("alg").asString());
        var claims = json.readTree(decoder.decode(parts[1]));
        assertEquals("Iv1.example", claims.path("iss").asString());
        assertEquals(NOW.minusSeconds(60).getEpochSecond(), claims.path("iat").longValue());
        assertEquals(NOW.plusSeconds(240).getEpochSecond(), claims.path("exp").longValue());
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keyPair.getPublic());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertTrue(verifier.verify(decoder.decode(parts[2])));
        } catch (java.security.GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }

    private void writeKey(String label, byte[] der) throws Exception {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        Files.writeString(secrets.resolve("fixture.pem"),
                "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----\n");
    }

    private static byte[] pkcs1FromKeyPair() {
        ByteBuffer wrapper = ByteBuffer.wrap(readDer(ByteBuffer.wrap(keyPair.getPrivate().getEncoded()), 0x30));
        readDer(wrapper, 0x02);
        readDer(wrapper, 0x30);
        return readDer(wrapper, 0x04);
    }

    private static byte[] readDer(ByteBuffer der, int tag) {
        assertEquals(tag, Byte.toUnsignedInt(der.get()));
        int length = Byte.toUnsignedInt(der.get());
        if ((length & 0x80) != 0) {
            int octets = length & 0x7f;
            length = 0;
            for (int i = 0; i < octets; i++) {
                length = (length << 8) | Byte.toUnsignedInt(der.get());
            }
        }
        byte[] bytes = new byte[length];
        der.get(bytes);
        return bytes;
    }
}
