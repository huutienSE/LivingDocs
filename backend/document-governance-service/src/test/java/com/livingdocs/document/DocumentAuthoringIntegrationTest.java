package com.livingdocs.document;

import com.livingdocs.document.client.SourceSnapshotProvider;
import com.livingdocs.document.controller.VersionEtags;
import com.livingdocs.document.entity.DocumentAuditEntity;
import com.livingdocs.document.exception.AuthoringException;
import com.livingdocs.document.model.*;
import com.livingdocs.document.repository.DocumentAuditJpaRepository;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "springdoc.api-docs.enabled=true")
@Import(DocumentAuthoringIntegrationTest.SourceFixtures.class)
class DocumentAuthoringIntegrationTest extends PostgresTestSupport {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Map<UUID, SourceSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    @LocalServerPort int port;
    @MockitoSpyBean DocumentAuditJpaRepository audits;

    @TestConfiguration(proxyBeanMethods = false)
    static class SourceFixtures {
        @Bean @Primary SourceSnapshotProvider testSnapshots() {
            return (id, scope) -> {
                var value = SNAPSHOTS.get(id);
                if (value == null) throw AuthoringException.sourceUnavailable();
                return value;
            };
        }
    }
    @DynamicPropertySource static void authoring(DynamicPropertyRegistry registry) {
        registry.add("livingdocs.submit.access-context-uri", ReviewAuthorityTestServer::uri);
        registry.add("livingdocs.submit.allow-insecure-http", () -> true);
    }
    record Fixture(UUID tenant, UUID workspace, UUID collection, UUID repo, UUID actor, UUID snapshot) {}
    static Fixture fixture() throws Exception {
        var f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        sql("INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES (?,?,?,'Authoring fixture')", f.collection, f.tenant, f.workspace);
        grant(f, "DEVELOPER", true);
        SNAPSHOTS.put(f.snapshot, new SourceSnapshot(f.snapshot, new AuthorizationScope(f.tenant, f.workspace, f.repo),
                "a".repeat(40), "TRACKED_BRANCH", "refs/heads/main", null, "fixture-1", Instant.now().minusSeconds(1),
                List.of(new SourceSnapshot.Evidence("SOURCE", "https://example.test/repo/commit/" + "a".repeat(40), null))));
        return f;
    }
    static void grant(Fixture f, String role, boolean repository) {
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor, ReviewAuthorityTestServer.context(f.actor, f.tenant,
                List.of(ReviewAuthorityTestServer.grant(f.workspace, List.of(role),
                        List.of("WORKSPACE_VIEW", "DOCUMENT_AUTHOR", "DOCUMENT_READ"), repository ? List.of(f.repo) : List.of()))));
    }
    static void sql(String statement, Object... args) throws Exception { ReviewApiIntegrationTest.execute(statement, args); }
    static Object scalar(String statement, Object... args) throws Exception { return ReviewApiIntegrationTest.scalar(statement, args); }
    static String token(Fixture f) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("s04-test").subject(f.actor.toString())
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(KEY));
        return token.serialize();
    }
    HttpResponse<String> call(Fixture f, String method, String path, Object body, String etag) throws Exception {
        return requestAt(port, f, method, path, body, etag);
    }
    static HttpResponse<String> requestAt(int port, Fixture f, String method, String path, Object body, String etag) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token(f)).header("Content-Type", "application/json");
        if (etag != null) request.header("If-Match", etag);
        if (path.endsWith("/submit")) request.header("Idempotency-Key", "authoring-submit");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    static JsonNode expect(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var body = JSON.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(code);
        return body.path("data");
    }
    UUID document(Fixture f) throws Exception {
        var response = call(f, "POST", "/api/v1/workspaces/" + f.workspace + "/documents",
                Map.of("collectionId", f.collection, "repositoryConnectionId", f.repo, "title", "Demo"), null);
        return UUID.fromString(expect(response, 201, "DOCUMENT_CREATED").path("documentId").asString());
    }
    UUID draft(Fixture f, UUID document) throws Exception {
        var response = call(f, "POST", "/api/v1/documents/" + document + "/versions",
                Map.of("content", "# Initial", "sourceSnapshotId", f.snapshot), null);
        var data = expect(response, 201, "DOCUMENT_VERSION_CREATED");
        assertThat(data.path("revision").asLong()).isZero();
        UUID id = UUID.fromString(data.path("versionId").asString());
        assertThat(response.headers().firstValue("ETag")).contains(VersionEtags.format(id, 0));
        return id;
    }
    @Test void completeAuthoringToSubmitAndReplay() throws Exception {
        var f = fixture();
        var doc = document(f);
        var before = expect(call(f, "GET", "/api/v1/documents/" + doc, null, null), 200, "DOCUMENT_LOADED");
        assertThat(before.path("currentVersionId").isNull()).isTrue();
        var version = draft(f, doc);
        var edited = call(f, "PATCH", "/api/v1/document-versions/" + version + "/content", Map.of("content", "# Edited"), VersionEtags.format(version, 0));
        assertThat(expect(edited, 200, "DOCUMENT_CONTENT_UPDATED").path("revision").asLong()).isEqualTo(1);
        assertThat(edited.headers().firstValue("ETag")).contains(VersionEtags.format(version, 1));
        var content = expect(call(f, "GET", "/api/v1/document-versions/" + version, null, null), 200, "DOCUMENT_VERSION_LOADED");
        assertThat(content.path("content").asString()).isEqualTo("# Edited");
        assertThat(content.path("creatorActorRef").asString()).isEqualTo(f.actor.toString());
        var evidence = expect(call(f, "GET", "/api/v1/document-versions/" + version + "/evidence", null, null), 200, "DOCUMENT_EVIDENCE_LOADED");
        assertThat(evidence.path("evidence").size()).isEqualTo(1);
        var submitted = call(f, "POST", "/api/v1/document-versions/" + version + "/submit", Map.of(), VersionEtags.format(version, 1));
        assertThat(expect(submitted, 200, "DOCUMENT_SUBMITTED").path("reviewStage").asString()).isEqualTo("STAFF_REVIEW");
        assertThat(call(f, "POST", "/api/v1/document-versions/" + version + "/submit", Map.of(), VersionEtags.format(version, 1)).body()).isEqualTo(submitted.body());
        var readback = expect(call(f, "GET", "/api/v1/document-versions/" + version, null, null), 200, "DOCUMENT_VERSION_LOADED");
        assertThat(readback.path("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(scalar("SELECT count(*) FROM document.submit_requests WHERE document_version_id=?", version)).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM document.content_revisions WHERE document_version_id=? AND editor_identity_id=?", version, f.actor)).isEqualTo(2L);
        expect(call(f, "PATCH", "/api/v1/document-versions/" + version + "/content", Map.of("content", "forbidden"), VersionEtags.format(version, 2)), 409, "STATE_TRANSITION_FORBIDDEN");
    }
    @Test void paginationAndConcurrentVersionNumbers() throws Exception {
        var f = fixture(); var doc = document(f);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.<Callable<UUID>>of(() -> draft(f, doc), () -> draft(f, doc));
            for (var result : pool.invokeAll(tasks)) assertThat(result.get()).isNotNull();
        }
        var list = expect(call(f, "GET", "/api/v1/documents/" + doc + "/versions?size=1&page=0", null, null), 200, "DOCUMENT_VERSIONS_LOADED");
        assertThat(list.path("totalElements").asLong()).isEqualTo(2);
        assertThat(list.path("items").get(0).path("versionNo").asInt()).isEqualTo(2);
        var canonical = expect(call(f, "GET", "/api/v1/documents/" + doc, null, null), 200, "DOCUMENT_LOADED");
        assertThat(canonical.path("currentVersionId")).isEqualTo(list.path("items").get(0).path("versionId"));
        expect(call(f, "GET", "/api/v1/documents/" + doc + "/versions?size=101", null, null), 400, "INVALID_REQUEST");
    }
    @Test void etagsAndConcurrentEditsDoNotLoseUpdates() throws Exception {
        var f = fixture(); var version = draft(f, document(f)); var path = "/api/v1/document-versions/" + version + "/content";
        expect(call(f, "PATCH", path, Map.of("content", "x"), null), 428, "PRECONDITION_REQUIRED");
        expect(call(f, "PATCH", path, Map.of("content", "x"), "W/" + VersionEtags.format(version, 0)), 400, "INVALID_IF_MATCH");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.<Callable<HttpResponse<String>>>of(
                    () -> call(f, "PATCH", path, Map.of("content", "one"), VersionEtags.format(version, 0)),
                    () -> call(f, "PATCH", path, Map.of("content", "two"), VersionEtags.format(version, 0)));
            var codes = new ArrayList<Integer>();
            for (var result : pool.invokeAll(tasks)) codes.add(result.get().statusCode());
            assertThat(codes).containsExactlyInAnyOrder(200, 412);
        }
        assertThat(scalar("SELECT count(*) FROM document.content_revisions WHERE document_version_id=?", version)).isEqualTo(2L);
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?", version)).isEqualTo(1L);
    }
    @Test void roleGrantAndCrossScopeDenials() throws Exception {
        var f = fixture(); var doc = document(f); var version = draft(f, doc);
        grant(f, "STAFF", true);
        expect(call(f, "PATCH", "/api/v1/document-versions/" + version + "/content", Map.of("content", "x"), VersionEtags.format(version, 0)), 403, "FORBIDDEN_SCOPE");
        expect(call(f, "GET", "/api/v1/documents/" + doc, null, null), 200, "DOCUMENT_LOADED");
        grant(f, "DEVELOPER", false);
        expect(call(f, "GET", "/api/v1/documents/" + doc, null, null), 403, "FORBIDDEN_SCOPE");
        var foreign = fixture();
        expect(call(foreign, "GET", "/api/v1/document-versions/" + version, null, null), 404, "DOCUMENT_RESOURCE_NOT_FOUND");
        expect(call(foreign, "GET", "/api/v1/documents/" + doc, null, null), 404, "DOCUMENT_RESOURCE_NOT_FOUND");
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?", version)).isEqualTo(0L);
    }
    @Test void freshDenialAndOutagePreventWrites() throws Exception {
        var f = fixture(); var doc = document(f);
        ReviewAuthorityTestServer.FRESH_DECISIONS.put(f.actor, "DENY");
        expect(call(f, "POST", "/api/v1/documents/" + doc + "/versions", Map.of("content", "x", "sourceSnapshotId", f.snapshot), null), 403, "FORBIDDEN_SCOPE");
        ReviewAuthorityTestServer.FRESH_DECISIONS.remove(f.actor);
        ReviewAuthorityTestServer.FRESH_STATUSES.put(f.actor, 503);
        expect(call(f, "POST", "/api/v1/documents/" + doc + "/versions", Map.of("content", "x", "sourceSnapshotId", f.snapshot), null), 503, "AUTHORIZATION_UNAVAILABLE");
        assertThat(scalar("SELECT count(*) FROM document.document_versions WHERE document_id=?", doc)).isEqualTo(0L);
    }
    @Test void unknownAndCrossScopedSnapshotsAreRejected() throws Exception {
        var f = fixture(); var doc = document(f);
        expect(call(f, "POST", "/api/v1/documents/" + doc + "/versions", Map.of("content", "x", "sourceSnapshotId", UUID.randomUUID()), null), 503, "SOURCE_SNAPSHOT_UNAVAILABLE");
        var foreign = fixture();
        expect(call(f, "POST", "/api/v1/documents/" + doc + "/versions", Map.of("content", "x", "sourceSnapshotId", foreign.snapshot), null), 503, "SOURCE_SNAPSHOT_UNAVAILABLE");
        assertThat(scalar("SELECT count(*) FROM document.document_versions WHERE document_id=?", doc)).isEqualTo(0L);
    }
    @Test void auditFailureRollsBackCreationAndContent() throws Exception {
        var f = fixture(); var doc = document(f); var version = draft(f, doc);
        doThrow(new DataIntegrityViolationException("fixture failure")).when(audits).saveAndFlush(argThat((DocumentAuditEntity a) ->
                a != null && version.equals(a.getVersionId()) && "DOCUMENT_CONTENT_UPDATED".equals(a.getAction())));
        expect(call(f, "PATCH", "/api/v1/document-versions/" + version + "/content", Map.of("content", "lost"), VersionEtags.format(version, 0)), 500, "PERSISTENCE_ERROR");
        assertThat(scalar("SELECT count(*) FROM document.content_revisions WHERE document_version_id=?", version)).isEqualTo(1L);
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?", version)).isEqualTo(0L);
    }
    @Test void scopeChangeBetweenAuthorizationAndWriteIsRejected() throws Exception {
        var f = fixture(); var doc = document(f);
        ReviewAuthorityTestServer.AFTER_EVALUATION.put(f.actor, () -> {
            try { sql("UPDATE document.documents SET repository_connection_id=? WHERE document_id=?", UUID.randomUUID(), doc); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        expect(call(f, "POST", "/api/v1/documents/" + doc + "/versions", Map.of("content", "x", "sourceSnapshotId", f.snapshot), null), 412, "REVISION_CONFLICT");
        assertThat(scalar("SELECT count(*) FROM document.document_versions WHERE document_id=?", doc)).isEqualTo(0L);
    }
    @Test void emptyMarkdownLegacyReferencesAndBudget() throws Exception {
        var f = fixture(); var version = draft(f, document(f));
        expect(call(f, "PATCH", "/api/v1/document-versions/" + version + "/content", Map.of("content", "x".repeat(100001)), VersionEtags.format(version, 0)), 400, "INVALID_REQUEST");
        var empty = expect(call(f, "PATCH", "/api/v1/document-versions/" + version + "/content", Map.of("content", ""), VersionEtags.format(version, 0)), 200, "DOCUMENT_CONTENT_UPDATED");
        assertThat(empty.path("contentAvailable").asBoolean()).isTrue();
        sql("UPDATE document.content_revisions SET content_markdown=NULL WHERE document_version_id=?", version);
        var legacy = expect(call(f, "GET", "/api/v1/document-versions/" + version, null, null), 200, "DOCUMENT_VERSION_LOADED");
        assertThat(legacy.path("contentAvailable").asBoolean()).isFalse();
        assertThat(legacy.path("content").isNull()).isTrue();
        assertThat(legacy.path("contentRef").asString()).startsWith("urn:livingdocs:content-revision:");
    }
    @Test void openApiMatchesAllSevenRuntimeContracts() throws Exception {
        var f = fixture();
        var response = call(f, "GET", "/v3/api-docs", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/liv164-runtime-openapi.json"), response.body());
        var runtime = JSON.readTree(response.body());
        var options = new org.yaml.snakeyaml.LoaderOptions();
        options.setMaxAliasesForCollections(1000);
        var yaml = new org.yaml.snakeyaml.Yaml(options);
        var contract = JSON.readTree(JSON.writeValueAsString(yaml.load(java.nio.file.Files.readString(java.nio.file.Path.of("../../contracts/openapi/s04-document.yaml")))));
        Set<String> ids = Set.of("api100_post", "api101_get", "api104_get", "api105_post", "api106_get", "api107_patch", "api108_get");
        int verified = 0;
        for (String path : contract.path("paths").propertyNames()) {
            for (String method : contract.path("paths").path(path).propertyNames()) {
                var expected = contract.path("paths").path(path).path(method);
                if (!ids.contains(expected.path("operationId").asString())) continue;
                var actual = runtime.path("paths").path(path).path(method);
                assertThat(actual.path("operationId")).isEqualTo(expected.path("operationId"));
                assertThat(actual.path("responses").propertyNames()).as(path).containsExactlyInAnyOrderElementsOf(expected.path("responses").propertyNames());
                String success = "post".equals(method) ? "201" : "200";
                var body = resolve(runtime, actual.path("responses").path(success).path("content").path("application/json").path("schema"));
                var expectedBody = resolve(contract, expected.path("responses").path(success).path("content").path("application/json").path("schema"));
                assertThat(body.path("properties").propertyNames()).containsExactlyInAnyOrder("code", "message", "data");
                var data = resolve(runtime, body.path("properties").path("data"));
                var expectedData = resolve(contract, expectedBody.path("properties").path("data"));
                assertThat(data.path("properties").propertyNames()).as(path).containsExactlyInAnyOrderElementsOf(expectedData.path("properties").propertyNames());
                if (expected.has("requestBody")) {
                    var request = resolve(runtime, actual.path("requestBody").path("content").path("application/json").path("schema"));
                    var expectedRequest = resolve(contract, expected.path("requestBody").path("content").path("application/json").path("schema"));
                    assertThat(request.path("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(expectedRequest.path("properties").propertyNames());
                    assertThat(request.path("required").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrderElementsOf(expectedRequest.path("required").valueStream().map(JsonNode::asString).toList());
                }
                if (Set.of("api105_post", "api106_get", "api107_patch").contains(expected.path("operationId").asString())) {
                    assertThat(actual.path("responses").path(success).path("headers").has("ETag")).isTrue();
                }
                verified++;
            }
        }
        assertThat(verified).isEqualTo(7);
    }
    private JsonNode resolve(JsonNode spec, JsonNode schema) {
        while (schema.has("$ref")) schema = spec.at(schema.path("$ref").asString().substring(1));
        return schema;
    }

}
