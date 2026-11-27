package com.livingdocs.document;

import com.livingdocs.document.dto.request.ReviewQueueQuery;
import com.livingdocs.document.repository.ReviewQueueJpaRepository;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.livingdocs.document.ReviewApiIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL/RLS + signed test JWT/API-038 stub; real-S01 evidence is recorded separately. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true", "spring.datasource.hikari.maximum-pool-size=1"})
@Import(ReviewSourceValidationTestConfiguration.class)
class ReviewQueueApiIntegrationTest extends PostgresTestSupport {
    @LocalServerPort int port;
    @Autowired ReviewQueueJpaRepository repository;
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource static void queueProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.config.import", () -> "");
        registry.add("livingdocs.authorization.service-token-uri", () -> ReviewAuthorityTestServer.base() + "/internal/v1/auth/service-tokens");
        registry.add("livingdocs.authorization.authorization-uri", () -> ReviewAuthorityTestServer.base() + "/internal/v1/authorization/evaluate");
        registry.add("livingdocs.authorization.workload-secret-path", () -> ReviewAuthorityTestServer.SECRET_PATH);
        registry.add("livingdocs.authorization.allow-insecure-http", () -> true);
        registry.add("livingdocs.submit.access-context-uri", ReviewAuthorityTestServer::uri);
        registry.add("livingdocs.submit.allow-insecure-http", () -> true);
    }

    private Fixture readable() throws Exception {
        var f = fixture();
        allow(f, List.of("STAFF"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW"), List.of(f.repository()));
        return f;
    }

    private void allow(Fixture f, List<String> roles, List<String> permissions, List<UUID> repositories) {
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor(), ReviewAuthorityTestServer.context(f.actor(), f.tenant(),
                List.of(ReviewAuthorityTestServer.grant(f.workspace(), roles, permissions, repositories))));
    }

    private String token(Fixture f, String issuer, String audience, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(f.actor().toString()).issuer(issuer).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(expiry)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private String token(Fixture f) throws Exception { return token(f, ISSUER, "s04-test", Instant.now().plusSeconds(300)); }

    private HttpResponse<String> get(String query, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/review-queue" + query))
                .header("X-Correlation-ID", "liv144-queue-test");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("liv144-queue-test");
        var body = json.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo("REVIEW_QUEUE_LOADED");
        return body.path("data");
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        var body = json.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("correlationId").asString()).isEqualTo("liv144-queue-test");
        assertThat(body.path("status").asInt()).isEqualTo(status);
        if (status == 401) assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @Test void readDoesNotRequireMutationPermissionOrCallFreshAuthority() throws Exception {
        var f = readable();
        var result = data(get("", token(f)));
        assertThat(result.path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("size").asInt()).isEqualTo(20);
        var item = result.path("items").get(0);
        assertThat(item.path("versionId").asString()).isEqualTo(f.version().toString());
        assertThat(item.path("submittedAt").isNull()).isTrue();
        assertThat(item.path("driftSeverity").isNull()).isTrue();
        assertThat(ReviewAuthorityTestServer.FRESH_CALLS.getOrDefault(f.actor(), 0)).isZero();
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?", f.version())).isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version())).isEqualTo(0L);
    }

    @Test void roleAndReadPermissionsAreBothRequired() throws Exception {
        var f = readable();
        for (var roles : List.of(List.of("DEVELOPER"), List.of("MANAGER"), List.of("TECHNICAL_LEAD"), List.<String>of())) {
            allow(f, roles, List.of("DOCUMENT_READ", "WORKSPACE_VIEW"), List.of(f.repository()));
            problem(get("", token(f)), 403, "REVIEW_FORBIDDEN");
        }
        for (var permissions : List.of(List.of("DOCUMENT_READ"), List.of("WORKSPACE_VIEW"), List.of("DOCUMENT_STAFF_REVIEW"))) {
            allow(f, List.of("STAFF"), permissions, List.of(f.repository()));
            problem(get("", token(f)), 403, "REVIEW_FORBIDDEN");
        }
    }

    @Test void allScopePredicatesApplyBeforePaginationAndCount() throws Exception {
        var f = readable();
        var other = fixture();
        // Same tenant but another workspace with a repository granted only in the original workspace.
        UUID collection = UUID.randomUUID(), document = UUID.randomUUID(), version = UUID.randomUUID();
        execute("INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES (?,?,?,'hidden')", collection, f.tenant(), other.workspace());
        execute("INSERT INTO document.documents(document_id,tenant_id,collection_id,repository_connection_id,title,ownership_ref) VALUES (?,?,?,?,'hidden','test')", document, f.tenant(), collection, f.repository());
        execute("INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,review_stage,creator_actor_ref) VALUES (?,?,?,1,'IN_REVIEW','STAFF_REVIEW','test')", version, f.tenant(), document);
        var result = data(get("?size=1", token(f)));
        assertThat(result.path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("versionId").asString()).isEqualTo(f.version().toString());
        assertThat(data(get("?size=1&page=1", token(f))).path("items").isEmpty()).isTrue();
        problem(get("?workspaceId=" + other.workspace(), token(f)), 403, "REVIEW_FORBIDDEN");
        problem(get("?repositoryConnectionId=" + other.repository(), token(f)), 403, "REVIEW_FORBIDDEN");
        // Another workspace grant must not cross-multiply repository IDs between grants.
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor(), ReviewAuthorityTestServer.context(f.actor(), f.tenant(), List.of(
                ReviewAuthorityTestServer.grant(f.workspace(), List.of("STAFF"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW"), List.of(f.repository())),
                ReviewAuthorityTestServer.grant(other.workspace(), List.of("STAFF"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW"), List.of(other.repository())))));
        assertThat(data(get("?size=1", token(f))).path("totalElements").asLong()).isEqualTo(1);
        allow(f, List.of("STAFF"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW"), List.of());
        assertThat(data(get("", token(f))).path("totalElements").asLong()).isZero();
        execute("UPDATE document.documents SET repository_connection_id=NULL WHERE document_id=?", f.document());
        assertThat(data(get("", token(f))).path("totalElements").asLong()).isEqualTo(1);
    }

    @Test void filtersAndStableSortUseStoredMetadataAndLatestSubmission() throws Exception {
        var f = readable();
        UUID template = UUID.randomUUID(), templateVersion = UUID.randomUUID(), second = UUID.randomUUID();
        execute("INSERT INTO document.document_templates(template_id,tenant_id,scope_type,scope_ref,name,document_type) VALUES (?,?,'WORKSPACE',?,'T','SDD')", template, f.tenant(), f.workspace());
        execute("INSERT INTO document.template_versions(template_version_id,tenant_id,template_id,version_no,structure_ref) VALUES (?,?,?,1,'test://structure')", templateVersion, f.tenant(), template);
        execute("UPDATE document.document_versions SET template_version_id=?,drift_severity='LOW' WHERE document_version_id=?", templateVersion, f.version());
        execute("INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,review_stage,creator_actor_ref,drift_severity) VALUES (?,?,?,2,'IN_REVIEW','STAFF_REVIEW','test','CRITICAL')", second, f.tenant(), f.document());
        receipt(f, f.version(), 1, "2026-11-20T00:00:00Z");
        receipt(f, f.version(), 3, "2026-11-22T00:00:00Z");
        receipt(f, second, 1, "2026-11-21T00:00:00Z");
        var result = data(get("", token(f)));
        assertThat(result.path("totalElements").asLong()).isEqualTo(2);
        assertThat(result.path("items").get(0).path("submittedAt").asString()).isEqualTo("2026-11-22T00:00:00Z");
        String filter = "?workspaceId=" + f.workspace() + "&repositoryConnectionId=" + f.repository() + "&templateId=" + template
                + "&driftSeverity=LOW&submittedFrom=2026-11-22T00:00:00Z&submittedTo=2026-11-22T00:00:00Z";
        assertThat(data(get(filter, token(f))).path("totalElements").asLong()).isEqualTo(1);
        for (var sort : ReviewQueueQuery.SortField.values()) for (String direction : List.of("ASC", "DESC")) {
            String query = "?size=1&sort=" + sort + "&direction=" + direction;
            var page = data(get(query, token(f)));
            assertThat(page.path("totalElements").asLong()).isEqualTo(2);
            assertThat(data(get(query, token(f)))).isEqualTo(page);
            assertThat(data(get(query + "&page=1", token(f))).path("items").get(0).path("versionId"))
                    .isNotEqualTo(page.path("items").get(0).path("versionId"));
        }
        assertThat(data(get("?sort=DRIFT_SEVERITY", token(f))).path("items").get(0).path("versionId").asString()).isEqualTo(second.toString());
        assertThat(data(get("?templateId=" + UUID.randomUUID(), token(f))).path("totalElements").asLong()).isZero();
    }

    private void receipt(Fixture f, UUID version, long revision, String time) throws Exception {
        execute("INSERT INTO document.submit_requests(submission_id,tenant_id,document_version_id,actor_identity_id,idempotency_key,expected_revision,resulting_revision,response_snapshot,submitted_at) VALUES (?,?,?,?,?,?,?, '{}'::jsonb,?::timestamptz)",
                UUID.randomUUID(), f.tenant(), version, f.actor(), "queue-" + revision, revision - 1, revision, time);
    }

    @Test void submitAddsCandidateAndPassOrReturnRemovesIt() throws Exception {
        for (String action : List.of("PASS", "RETURN_FOR_REVISION")) {
            var f = readable();
            execute("UPDATE document.document_versions SET state='DRAFT',review_stage=NULL,source_target_kind='TRACKED_BRANCH',source_target_ref='refs/heads/main',source_config_revision='1',source_captured_at=now() WHERE document_version_id=?", f.version());
            allow(f, List.of("DEVELOPER", "STAFF"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW", "DOCUMENT_AUTHOR", "DOCUMENT_STAFF_REVIEW"), List.of(f.repository()));
            String jwt = token(f);
            assertThat(data(get("", jwt)).path("totalElements").asLong()).isZero();
            assertThat(post(f, jwt, "submit", "{}", 0).statusCode()).isEqualTo(200);
            var queued = data(get("", jwt));
            assertThat(queued.path("totalElements").asLong()).isEqualTo(1);
            assertThat(queued.path("items").get(0).path("submittedBy").asString()).isEqualTo(f.actor().toString());
            var decision = post(f, jwt, "staff-decisions", json.writeValueAsString(Map.of("decision", action,
                    "feedback", "Review feedback", "evidenceRefs", List.of(f.evidence()))), 1);
            assertThat(decision.statusCode()).as(decision.body()).isEqualTo(200);
            assertThat(data(get("", jwt)).path("totalElements").asLong()).isZero();
            assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version())).isEqualTo(2L);
        }
    }

    private HttpResponse<String> post(Fixture f, String jwt, String action, String body, long revision) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/document-versions/" + f.version() + "/" + action))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .header("If-Match", "\"" + f.version() + ":revision-" + revision + "\"").header("Idempotency-Key", "queue-" + revision)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void invalidAndAmbiguousQueriesAreRejected() throws Exception {
        var f = readable();
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=2147483647", "?sort=title",
                "?direction=sideways", "?repositoryConnectionId=bad", "?driftSeverity=UNKNOWN", "?submittedFrom=bad",
                "?submittedFrom=2026-11-23T00:00:00Z&submittedTo=2026-11-22T00:00:00Z", "?tenantId=" + f.tenant(), "?page=0&page=1", "?repositoryConnectionId="))
            problem(get(query, token(f)), 400, "INVALID_REQUEST");
    }

    @Test void jwtAndAuthorityFailuresAreFailClosed() throws Exception {
        var f = readable();
        problem(get("", null), 401, "UNAUTHENTICATED");
        problem(get("", token(f, ISSUER, "wrong", Instant.now().plusSeconds(300))), 401, "UNAUTHENTICATED");
        problem(get("", token(f, "wrong", "s04-test", Instant.now().plusSeconds(300))), 401, "UNAUTHENTICATED");
        problem(get("", token(f, ISSUER, "s04-test", Instant.now().minusSeconds(120))), 401, "UNAUTHENTICATED");
        for (int status : List.of(401, 403, 503)) {
            ReviewAuthorityTestServer.STATUSES.put(f.actor(), status);
            problem(get("", token(f)), status, status == 401 ? "UNAUTHENTICATED" : status == 403 ? "REVIEW_FORBIDDEN" : "AUTHORIZATION_UNAVAILABLE");
        }
        ReviewAuthorityTestServer.STATUSES.remove(f.actor());
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor(), Map.of("identityId", UUID.randomUUID()));
        problem(get("", token(f)), 503, "AUTHORIZATION_UNAVAILABLE");
    }

    @Test void queryTimeoutReturnsSanitizedErrorAndNextRequestRecovers() throws Exception {
        var f = readable();
        try (var lock = admin()) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) {
                statement.execute("LOCK TABLE document.documents IN ACCESS EXCLUSIVE MODE");
                var response = get("", token(f));
                problem(response, 500, "PERSISTENCE_ERROR");
                assertThat(response.body()).doesNotContain("statement timeout", "SELECT", "tenant_id");
            } finally { lock.rollback(); }
        }
        assertThat(data(get("", token(f))).path("totalElements").asLong()).isEqualTo(1);
    }

    @Test void unsupportedMethodAndMediaTypeHaveProblemResponses() throws Exception {
        var f = readable();
        var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/review-queue");
        var post = http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token(f))
                .header("X-Correlation-ID", "liv144-queue-test").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        problem(post, 405, "METHOD_NOT_ALLOWED");
        assertThat(post.headers().firstValue("Allow").orElseThrow()).contains("GET");
        var unacceptable = http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token(f))
                .header("X-Correlation-ID", "liv144-queue-test").header("Accept", "text/plain").GET().build(), HttpResponse.BodyHandlers.ofString());
        problem(unacceptable, 406, "NOT_ACCEPTABLE");
    }

    @Test void invokerViewAndReusedConnectionCannotLeakTenant() throws Exception {
        var first = readable(); var second = readable();
        for (var f : List.of(first, second, first))
            assertThat(data(get("", token(f))).path("items").get(0).path("versionId").asString()).isEqualTo(f.version().toString());
        try (var connection = DriverManager.getConnection(databaseUrl(), "ld_s04_app", runtimePassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM document.staff_review_queue")) { rows.next(); assertThat(rows.getLong(1)).isZero(); }
                statement.execute("SELECT set_config('app.tenant_id','" + first.tenant() + "',true)");
                try (var rows = statement.executeQuery("SELECT count(*) FROM document.staff_review_queue")) { rows.next(); assertThat(rows.getLong(1)).isEqualTo(1); }
                connection.commit();
                try (var rows = statement.executeQuery("SELECT count(*) FROM document.staff_review_queue")) { rows.next(); assertThat(rows.getLong(1)).isZero(); }
            }
        }
    }

    @Test void runtimeOpenApiMatchesTheQueueContract() throws Exception {
        var f = readable();
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .header("Authorization", "Bearer " + token(f)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var actual = json.readTree(response.body());
        var options = new org.yaml.snakeyaml.LoaderOptions(); options.setMaxAliasesForCollections(1000);
        var expected = json.readTree(json.writeValueAsString(new org.yaml.snakeyaml.Yaml(options)
                .load(java.nio.file.Files.readString(java.nio.file.Path.of("../../contracts/openapi/s04-document.yaml")))));
        var operation = actual.path("paths").path("/api/v1/review-queue").path("get");
        var contract = expected.path("paths").path("/api/v1/review-queue").path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("api115_get");
        assertThat(operation.path("responses").propertyNames()).containsExactlyInAnyOrderElementsOf(contract.path("responses").propertyNames());
        var parameterNames = new HashSet<String>();
        for (var parameter : operation.path("parameters")) parameterNames.add(parameter.path("name").asString());
        assertThat(parameterNames).containsExactlyInAnyOrder("page", "size", "workspaceId", "repositoryConnectionId", "templateId", "driftSeverity", "submittedFrom", "submittedTo", "sort", "direction");
        var envelope = resolve(actual, operation.path("responses").path("200").path("content").path("application/json").path("schema"));
        shape(actual, envelope, expected.path("components").path("schemas").path("ReviewQueueApiResponse"));
        var view = resolve(actual, envelope.path("properties").path("data"));
        shape(actual, view, expected.path("components").path("schemas").path("ReviewQueueView"));
        var item = resolve(actual, view.path("properties").path("items").path("items"));
        shape(actual, item, expected.path("components").path("schemas").path("ReviewQueueItem"));
        for (String status : List.of("400", "401", "403", "405", "406", "500", "503"))
            assertThat(operation.path("responses").path(status).path("content").propertyNames()).containsExactly("application/problem+json");
    }

    private JsonNode resolve(JsonNode document, JsonNode schema) {
        while (schema.has("$ref")) schema = document.at(schema.path("$ref").asString().substring(1));
        return schema;
    }

    private void shape(JsonNode document, JsonNode actual, JsonNode expected) {
        assertThat(actual.path("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(expected.path("properties").propertyNames());
        var required = new HashSet<String>(); actual.path("required").forEach(n -> required.add(n.asString()));
        var expectedRequired = new HashSet<String>(); expected.path("required").forEach(n -> expectedRequired.add(n.asString()));
        assertThat(required).isEqualTo(expectedRequired);
        for (var property : expected.path("properties").properties()) {
            var field = resolve(document, actual.path("properties").path(property.getKey()));
            var baseline = property.getValue();
            for (String attr : List.of("type", "format", "nullable", "minimum")) if (baseline.has(attr))
                assertThat(field.path(attr)).as(property.getKey() + "." + attr).isEqualTo(baseline.path(attr));
            if (baseline.has("enum")) {
                var values = new HashSet<String>(); field.path("enum").forEach(n -> values.add(n.isNull() ? null : n.asString()));
                var expectedValues = new HashSet<String>(); baseline.path("enum").forEach(n -> expectedValues.add(n.isNull() ? null : n.asString()));
                assertThat(values).as(property.getKey()).isEqualTo(expectedValues);
            }
        }
    }
}
