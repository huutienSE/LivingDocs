package com.livingdocs.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.livingdocs.client.GitHubInstallationTokenProvider;
import com.livingdocs.client.GitHubInstallationTokenProvider.RepositoryAccess;
import com.livingdocs.client.GitHubSourceClient;
import com.livingdocs.client.GitHubSourceClient.CommitObservation;
import com.livingdocs.entity.GithubConnectionEntity;
import com.livingdocs.exception.SourceApiException;
import com.livingdocs.exception.SourceApiException.UnavailableReason;
import com.livingdocs.entity.RepositoryConnectionEntity;
import com.livingdocs.entity.TrackedBranchEntity;
import com.livingdocs.model.SourceScope;
import com.livingdocs.security.SourceServiceAuthentication;
import com.livingdocs.security.SourceServiceIdentityMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.persistence.EntityManager;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.json.JsonMapper;

/** HTTP thật; PostgreSQL test riêng, không dùng DB hoặc GitHub của người dùng. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"livingdocs.source.lookup.github-ready-statuses=CONNECTED", "livingdocs.source.http.allow-insecure-transport=true"})
@ActiveProfiles("db")
@Import(SourceHttpTests.TestTrust.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql:.*")
class SourceHttpTests {
    @LocalServerPort
    private int port;
    private static final String A = "a".repeat(40);
    private static final String ISSUER = "urn:livingdocs:liv122-test-only";
    private static final KeyPair KEY = keyPair();
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private Flyway flyway;
    @Autowired private JsonMapper json;
    @MockitoBean private GitHubInstallationTokenProvider tokens;
    @MockitoBean private GitHubSourceClient github;
    private final List<UUID> tenants = new ArrayList<>();

    @Test
    void missingServiceIdentityIsRejectedBeforeParsingTheRequest() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/v1/source/validate"))
                .header("Content-Type", "application/json")
                .header("X-Correlation-ID", "liv122-http-test")
                .POST(HttpRequest.BodyPublishers.ofString("invalid-json")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("liv122-http-test", response.headers().firstValue("X-Correlation-ID").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertTrue(response.body().contains("\"code\":\"INVALID_SERVICE_IDENTITY\""));
    }

    @Test
    void callerCanResolveAndValidateTheSameSourceThroughHttp() throws Exception {
        UUID[] fixture = fixture();
        String token = token(fixture, List.of("source:resolve", "source:validate"));
        var resolved = send("resolve", body(fixture[2], null), token);
        assertEquals(200, resolved.statusCode(), resolved.body());
        var snapshot = json.readTree(resolved.body());
        assertEquals("SOURCE_RESOLVED", snapshot.path("code").asString());
        assertEquals(A, snapshot.at("/data/resolvedSha").asString());
        assertEquals("TRACKED_BRANCH", snapshot.at("/data/targetKind").asString());
        assertEquals(fixture[0].toString(), snapshot.at("/data/tenantId").asString());
        var validated = send("validate", body(fixture[2], snapshot.at("/data/configRevision").asString()), token);
        assertEquals(200, validated.statusCode(), validated.body());
        var receipt = json.readTree(validated.body());
        assertEquals("SOURCE_VALIDATED", receipt.path("code").asString());
        assertEquals("PASS", receipt.at("/data/validationStatus").asString());
        assertEquals("CURRENT", receipt.at("/data/reason").asString());
        assertEquals(A, receipt.at("/data/validatedHeadSha").asString());
        assertEquals("1", receipt.at("/data/sourceRevision").asString());
        assertNotNull(UUID.fromString(receipt.at("/data/validationId").asString()));
        assertTrue(Instant.parse(receipt.at("/data/expiresAt").asString())
                .isAfter(Instant.parse(receipt.at("/data/checkedAt").asString())));
        assertFalse(validated.body().contains("credential"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsOversizedBodiesIncludingChunkedRequests(boolean chunked) throws Exception {
        UUID[] scope = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        byte[] payload = (" ".repeat(16_384) + body(scope[2], null)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(payload))
                : HttpRequest.BodyPublishers.ofByteArray(payload);
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/v1/source/resolve"))
                .header("Authorization", "Bearer " + token(scope, List.of("source:resolve")))
                .header("Content-Type", "application/json").POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode(), response.body());
        assertEquals("PAYLOAD_TOO_LARGE", json.readTree(response.body()).path("code").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "unknown", "number", "trailing", "null", "enum", "uuid", "zero-sha", "ref"})
    void rejectsRequestsThatDoNotMatchTheWireContract(String invalid) throws Exception {
        UUID[] scope = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        String valid = body(scope[2], null);
        String payload = switch (invalid) {
            case "duplicate" -> valid.replace("\"targetKind\":", "\"targetKind\":\"PR\",\"targetKind\":");
            case "unknown" -> valid.replace("}", ",\"tenantId\":\"" + scope[0] + "\"}");
            case "number" -> valid.replace("\"refs/heads/main\"", "123");
            case "trailing" -> valid + " {}";
            case "null" -> valid.replace("\"refs/heads/main\"", "null");
            case "enum" -> valid.replace("TRACKED_BRANCH", "BRANCH");
            case "uuid" -> valid.replace(scope[2].toString(), "1-1-1-1-1");
            case "zero-sha" -> valid.replace("}", ",\"explicitSha\":\"" + "0".repeat(40) + "\"}");
            case "ref" -> valid.replace("refs/heads/main", "refs/heads/../main");
            default -> throw new IllegalArgumentException();
        };
        var response = send("resolve", payload, token(scope, List.of("source:resolve")));
        assertEquals(400, response.statusCode(), invalid + ": " + response.body());
        assertEquals("INVALID_REQUEST", json.readTree(response.body()).path("code").asString());
    }

    @Test
    void operationPermissionIsCheckedBeforeJsonAndResourceScopeBeforeLookup() throws Exception {
        UUID[] scope = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        var deniedAction = send("validate", "invalid-json", token(scope, List.of("source:resolve")));
        assertEquals(403, deniedAction.statusCode());
        assertEquals("FORBIDDEN_SCOPE", json.readTree(deniedAction.body()).path("code").asString());
        var deniedResource = send("resolve", body(UUID.randomUUID(), null), token(scope, List.of("source:resolve")));
        assertEquals(403, deniedResource.statusCode());
        assertEquals("FORBIDDEN_SCOPE", json.readTree(deniedResource.body()).path("code").asString());
        verifyNoInteractions(tokens, github);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "issuer", "audience", "signature", "user", "workload"})
    void invalidServiceIdentitiesAreRejected(String invalid) throws Exception {
        UUID[] scope = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        String bearer = token(scope, List.of("source:resolve"), builder -> {
            switch (invalid) {
                case "expired" -> builder.expirationTime(Date.from(Instant.now().minusSeconds(120)));
                case "issuer" -> builder.issuer("urn:untrusted");
                case "audience" -> builder.audience("another-service");
                case "user" -> builder.claim("test_identity", "user");
                case "workload" -> builder.subject("untrusted-workload");
                default -> { }
            }
        });
        if (invalid.equals("signature")) {
            int start = bearer.lastIndexOf('.') + 1;
            bearer = bearer.substring(0, start) + (bearer.charAt(start) == 'A' ? 'B' : 'A') + bearer.substring(start + 1);
        }
        var response = send("resolve", body(scope[2], null), bearer);
        assertEquals(401, response.statusCode(), invalid + ": " + response.body());
        assertEquals("INVALID_SERVICE_IDENTITY", json.readTree(response.body()).path("code").asString());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        verifyNoInteractions(tokens, github);
    }

    @Test
    void wrongTenantOrWorkspaceDoesNotRevealTheConnection() throws Exception {
        UUID[] actual = fixture();
        for (UUID[] wrong : List.of(new UUID[]{UUID.randomUUID(), actual[1], actual[2]},
                new UUID[]{actual[0], UUID.randomUUID(), actual[2]})) {
            var response = send("resolve", body(actual[2], null), token(wrong, List.of("source:resolve")));
            assertEquals(404, response.statusCode(), response.body());
            assertEquals("UNKNOWN_CONNECTION", json.readTree(response.body()).path("code").asString());
        }
        verifyNoInteractions(github);
    }

    @Test
    void staleConfigurationAndShaAndUnavailableProviderHaveDistinctErrors() throws Exception {
        UUID[] scope = fixture();
        String bearer = token(scope, List.of("source:resolve", "source:validate"));
        var resolved = send("resolve", body(scope[2], null), bearer);
        assertEquals(200, resolved.statusCode(), resolved.body());
        String config = json.readTree(resolved.body()).at("/data/configRevision").asString();
        assertProblem(send("validate", body(scope[2], "v1:0:0"), bearer), 409, "CONFIG_CHANGED");
        assertProblem(send("validate", body(scope[2], config).replace(A, "b".repeat(40)), bearer), 409, "STALE_SHA");
        when(github.readBranch(any(), anyString())).thenThrow(SourceApiException.unavailable(UnavailableReason.GITHUB_RESPONSE_TOO_LARGE));
        var unavailable = send("resolve", body(scope[2], null), bearer);
        assertProblem(unavailable, 503, "SOURCE_UNAVAILABLE");
        assertEquals("Source cannot currently be verified", json.readTree(unavailable.body()).path("detail").asString());
        assertFalse(unavailable.body().contains("GitHub response exceeds the maximum allowed size"));
        doThrow(new IllegalStateException("SECRET-INTERNAL-DETAIL")).when(github).readBranch(any(), anyString());
        var internal = send("resolve", body(scope[2], null), bearer);
        assertProblem(internal, 500, "INTERNAL_ERROR");
        assertFalse(internal.body().contains("SECRET-INTERNAL-DETAIL"));
    }

    @Test
    void unexpectedFailureBeforeMvcUsesTheSameProblemContract() throws Exception {
        UUID[] scope = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        String bearer = token(scope, List.of("source:resolve"), builder ->
                builder.claim("test_reject_with_internal_failure", true));

        var response = send("resolve", body(scope[2], null), bearer);

        assertProblem(response, 500, "INTERNAL_ERROR");
        assertEquals("Source operation could not be completed", json.readTree(response.body()).path("detail").asString());
        assertFalse(response.body().contains("TEST-AUTH-ADAPTER-FAILURE"));
        verifyNoInteractions(tokens, github);
    }

    @Test
    void methodAndMediaTypeFailuresUseProblemDetails() throws Exception {
        UUID[] scope = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        String bearer = token(scope, List.of("source:resolve"));
        var method = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/v1/source/resolve"))
                .header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(method, 405, "METHOD_NOT_ALLOWED");
        assertEquals("POST", method.headers().firstValue("Allow").orElseThrow());
        var media = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/v1/source/resolve"))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body(scope[2], null))).build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(media, 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void runtimeOpenApiMatchesThePublishedSourceHttpContract() throws Exception {
        UUID[] scope = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .header("Authorization", "Bearer " + token(scope, List.of("source:resolve"))).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var runtime = json.readTree(response.body());
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("contracts/openapi/s02-repository.yaml"))) root = root.getParent();
        assertNotNull(root, "Repository contract must be available for conformance test");
        java.nio.file.Files.writeString(root.resolve("backend/repository-integration-service/target/liv122-http-runtime-openapi.json"), response.body());
        var yamlOptions = new org.yaml.snakeyaml.LoaderOptions();
        yamlOptions.setMaxAliasesForCollections(1000); // Trusted repository spec reuses its shared Problem response.
        var declared = json.valueToTree(new org.yaml.snakeyaml.Yaml(yamlOptions).load(
                java.nio.file.Files.readString(root.resolve("contracts/openapi/s02-repository.yaml"))));
        for (String operation : List.of("resolve", "validate")) {
            String path = "/internal/v1/source/" + operation;
            var actual = runtime.path("paths").path(path).path("post");
            var expected = declared.path("paths").path(path).path("post");
            assertEquals(expected.path("operationId"), actual.path("operationId"));
            assertEquals(expected.path("requestBody").path("required"), actual.path("requestBody").path("required"));
            assertEquals(expected.path("security"), actual.path("security"));
            for (String code : List.of("200", "400", "401", "403", "404", "405", "406", "409", "413", "415", "500", "503")) {
                assertEquals(expected.path("responses").path(code).path("content"), actual.path("responses").path(code).path("content"), operation + ": " + code);
                assertEquals(expected.path("responses").path(code).path("headers"), actual.path("responses").path(code).path("headers"), operation + ": headers " + code);
            }
        }
        for (String name : List.of("SourceResolveRequest", "SourceValidationRequest", "SourceSnapshotView", "SourceValidationReceipt",
                "SuccessResponseSourceSnapshotView", "SuccessResponseSourceValidationReceipt", "Problem")) {
            var actual = runtime.path("components").path("schemas").path(name);
            var expected = declared.path("components").path("schemas").path(name);
            var actualFields = new TreeSet<String>(); actual.path("properties").propertyNames().forEach(actualFields::add);
            var expectedFields = new TreeSet<String>(); expected.path("properties").propertyNames().forEach(expectedFields::add);
            assertEquals(expectedFields, actualFields, name + ": fields");
            var actualRequired = new TreeSet<String>(); actual.path("required").forEach(value -> actualRequired.add(value.asString()));
            var expectedRequired = new TreeSet<String>(); expected.path("required").forEach(value -> expectedRequired.add(value.asString()));
            assertEquals(expectedRequired, actualRequired, name + ": required");
        }
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        var problem = json.readTree(response.body());
        assertEquals(code, problem.path("code").asString());
        assertEquals(status, problem.path("status").asInt());
        assertTrue(problem.path("instance").asString().startsWith("urn:uuid:"));
        assertEquals(response.headers().firstValue("X-Correlation-ID").orElseThrow(), problem.path("correlationId").asString());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }

    private HttpResponse<String> send(String operation, String body, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/v1/source/" + operation))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .header("X-Correlation-ID", "liv122-http-test")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String body(UUID connection, String config) {
        return "{\"repositoryConnectionId\":\"" + connection + "\",\"targetKind\":\"TRACKED_BRANCH\","
                + "\"targetRef\":\"refs/heads/main\""
                + (config == null ? "" : ",\"boundHeadSha\":\"" + A + "\",\"configRevision\":\"" + config + "\"") + "}";
    }

    private UUID[] fixture() {
        UUID tenant = UUID.randomUUID(), workspace = UUID.randomUUID();
        tenants.add(tenant);
        UUID connection = new TransactionTemplate(transactions).execute(status -> {
            entities.createNativeQuery("select set_config('app.tenant_id', :tenant, true)")
                    .setParameter("tenant", tenant.toString()).getSingleResult();
            var installation = new GithubConnectionEntity(tenant, "42", "test-only.pem", "CONNECTED", null);
            entities.persist(installation);
            var repository = new RepositoryConnectionEntity(tenant, workspace, installation.getGithubConnectionId(),
                    "100", "main", "ACTIVE");
            entities.persist(repository);
            entities.persist(new TrackedBranchEntity(tenant, repository.getRepositoryConnectionId(), "refs/heads/main"));
            return repository.getRepositoryConnectionId();
        });
        var access = mock(RepositoryAccess.class);
        when(tokens.openRepository(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(access);
        var commit = mock(CommitObservation.class);
        when(commit.getQueriedRepositoryId()).thenReturn(100L);
        when(commit.getSourceRepositoryId()).thenReturn(100L);
        when(commit.getSha()).thenReturn(A);
        when(github.readBranch(any(), eq("refs/heads/main"))).thenReturn(Optional.of(commit));
        return new UUID[]{tenant, workspace, connection};
    }

    private static String token(UUID[] fixture, List<String> permissions) throws Exception {
        return token(fixture, permissions, builder -> { });
    }

    private static String token(UUID[] fixture, List<String> permissions, java.util.function.Consumer<JWTClaimsSet.Builder> customize) throws Exception {
        var builder = new JWTClaimsSet.Builder().issuer(ISSUER).audience("s02-test")
                .subject("test-s04").issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .claim("test_identity", "service").claim("test_tenant", fixture[0].toString())
                .claim("test_workspace", fixture[1].toString()).claim("test_repository", fixture[2].toString())
                .claim("test_permissions", permissions);
        customize.accept(builder);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEY.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair keyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestTrust {
        @Bean @org.springframework.context.annotation.Primary JwtDecoder testJwtDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY.getPublic()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER),
                    new JwtClaimValidator<List<String>>("aud", audience -> audience != null && audience.contains("s02-test"))));
            return decoder;
        }

        @Bean @org.springframework.context.annotation.Primary SourceServiceIdentityMapper testIdentities() {
            // Claim names below belong exclusively to the test issuer, not a production contract.
            return jwt -> {
                if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("test_reject_with_internal_failure"))) {
                    throw new IllegalStateException("TEST-AUTH-ADAPTER-FAILURE");
                }
                if (!"service".equals(jwt.getClaimAsString("test_identity")) || !"test-s04".equals(jwt.getSubject())) {
                    throw new BadCredentialsException("Not a test workload");
                }
                return new SourceServiceAuthentication(jwt.getSubject(), new SourceScope(
                        UUID.fromString(jwt.getClaimAsString("test_tenant")),
                        UUID.fromString(jwt.getClaimAsString("test_workspace"))),
                        UUID.fromString(jwt.getClaimAsString("test_repository")),
                        jwt.getClaimAsStringList("test_permissions").stream().map(SimpleGrantedAuthority::new).toList());
            };
        }
    }

    @AfterEach
    void cleanTestFixtures() throws Exception {
        try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            for (UUID tenant : tenants) {
                try (var scope = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                    scope.setString(1, tenant.toString()); scope.execute();
                }
                for (String table : List.of("source_target_heads", "tracked_branches", "repository_connections", "github_connections")) {
                    try (var statement = connection.prepareStatement("delete from repository." + table + " where tenant_id = ?")) {
                        statement.setObject(1, tenant); statement.executeUpdate();
                    }
                }
                connection.commit();
            }
        }
    }
}
