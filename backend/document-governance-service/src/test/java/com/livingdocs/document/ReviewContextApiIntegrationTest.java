package com.livingdocs.document;

import com.livingdocs.document.controller.VersionEtags;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.livingdocs.document.ReviewApiIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL/RLS; test-only issuer/API-038/039/source adapters. Live S01 evidence is separate. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true", "spring.datasource.hikari.maximum-pool-size=1"})
@Import(ReviewSourceValidationTestConfiguration.class)
class ReviewContextApiIntegrationTest extends PostgresTestSupport {
    @LocalServerPort int port;
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource static void authority(DynamicPropertyRegistry registry) {
        registry.add("spring.config.import", () -> "");
        registry.add("livingdocs.authorization.access-context-uri", ReviewAuthorityTestServer::uri);
        registry.add("livingdocs.authorization.access-context-allow-insecure-http", () -> true);
        registry.add("livingdocs.authorization.service-token-uri", () -> ReviewAuthorityTestServer.base() + "/internal/v1/auth/service-tokens");
        registry.add("livingdocs.authorization.authorization-uri", () -> ReviewAuthorityTestServer.base() + "/internal/v1/authorization/evaluate");
        registry.add("livingdocs.authorization.workload-secret-path", () -> ReviewAuthorityTestServer.SECRET_PATH);
        registry.add("livingdocs.authorization.allow-insecure-http", () -> true);
    }

    private Fixture readable() throws Exception {
        var f = fixture();
        grant(f, f.tenant(), f.workspace(), List.of("STAFF"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW"), List.of(f.repository()));
        return f;
    }

    private void grant(Fixture f, UUID tenant, UUID workspace, List<String> roles, List<String> permissions, List<UUID> repos) {
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor(), ReviewAuthorityTestServer.context(f.actor(), tenant,
                List.of(ReviewAuthorityTestServer.grant(workspace, roles, permissions, repos))));
    }

    private String token(Fixture f) throws Exception { return token(f, ISSUER, "s04-test", Instant.now().plusSeconds(300)); }
    private String token(Fixture f, String issuer, String audience, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(f.actor().toString()).issuer(issuer).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(expiry)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY)); return jwt.serialize();
    }

    private HttpResponse<String> get(UUID version, String token) throws Exception {
        return send("/api/v1/document-versions/" + version + "/review-context", token, Map.of());
    }
    private HttpResponse<String> send(String path, String token, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("X-Correlation-ID", "liv145-test");
        if (token != null) request.header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode data(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var body = json.readTree(response.body()); assertThat(body.path("code").asString()).isEqualTo("REVIEW_CONTEXT_LOADED");
        return body.path("data");
    }
    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        var body = json.readTree(response.body()); assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("correlationId").asString()).isEqualTo("liv145-test");
        if (status == 401) assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @Test void loadsCanonicalContentTemplateBaselineAndOnlyVersionEvidenceWithoutWrites() throws Exception {
        var f = readable(); var foreign = readable();
        UUID template = UUID.randomUUID(), tv = UUID.randomUUID(), baseline = UUID.randomUUID(), newer = UUID.randomUUID();
        execute("INSERT INTO document.document_templates(template_id,tenant_id,scope_type,scope_ref,name,document_type) VALUES (?,?,'WORKSPACE',?,'Design template','SDD')", template, f.tenant(), f.workspace());
        execute("INSERT INTO document.template_versions(template_version_id,tenant_id,template_id,version_no,structure_ref,formatting_rules) VALUES (?,?,?,1,'artifact://template','{\"headings\":[\"Overview\"]}'::jsonb)", tv, f.tenant(), template);
        execute("UPDATE document.document_versions SET version_no=3,template_version_id=? WHERE document_version_id=?", tv, f.version());
        execute("UPDATE document.content_revisions SET content_text='# Original',content_format='MARKDOWN' WHERE document_version_id=?", f.version());
        execute("INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,content_text,content_format,editor_identity_id) VALUES (?,?,2,'artifact://current','# Current','MARKDOWN',?)", f.tenant(), f.version(), f.actor());
        execute("INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,creator_actor_ref) VALUES (?,?,?,2,'PUBLISHED','test'),(?,?,?,4,'APPROVED','test')", baseline, f.tenant(), f.document(), newer, f.tenant(), f.document());
        execute("INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,content_text,content_format,editor_identity_id) VALUES (?,?,1,'artifact://baseline','Published baseline','TEXT',?)", f.tenant(), baseline, f.actor());
        UUID diff = UUID.randomUUID();
        execute("INSERT INTO document.document_evidence_refs(document_evidence_id,tenant_id,document_version_id,evidence_type,source_ref) VALUES (?,?,?,'DIFF','source://pinned-diff')", diff, f.tenant(), f.version());
        var response = get(f.version(), token(f)); var context = data(response);
        assertRepresentationEtag(response, f.version(), 0);
        assertThat(context.path("content").asString()).isEqualTo("# Current");
        assertThat(context.path("contentRef").asString()).isEqualTo("artifact://current");
        assertThat(context.path("contentRevisionNo").asInt()).isEqualTo(2);
        assertThat(context.path("template").path("templateVersionId").asString()).isEqualTo(tv.toString());
        assertThat(context.path("template").path("formattingRules").path("headings").get(0).asString()).isEqualTo("Overview");
        assertThat(context.path("baseline").path("versionId").asString()).isEqualTo(baseline.toString());
        assertThat(context.path("baseline").path("content").asString()).isEqualTo("Published baseline");
        var refs = new HashSet<String>(); context.path("evidence").forEach(e -> refs.add(e.path("ref").asString()));
        assertThat(refs).containsExactlyInAnyOrder(f.evidence().toString(), diff.toString()).doesNotContain(foreign.evidence().toString());
        assertThat(context.path("reviewRound").asInt()).isZero();
        assertThat(ReviewAuthorityTestServer.FRESH_CALLS.getOrDefault(f.actor(), 0)).isZero();
        assertThat(ReviewAuthorityTestServer.CORRELATIONS.get(f.actor())).isEqualTo("liv145-test");
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?", f.version())).isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version())).isEqualTo(0L);
    }

    @Test void legacyRefsAreNotInventedContentAndMissingOptionalDataIsNull() throws Exception {
        var f = readable(); var context = data(get(f.version(), token(f)));
        assertThat(context.path("content").isNull()).isTrue(); assertThat(context.path("contentFormat").isNull()).isTrue();
        assertThat(context.path("contentRef").asString()).isEqualTo("test://original-content");
        assertThat(context.path("template").isNull()).isTrue(); assertThat(context.path("baseline").isNull()).isTrue();
        assertThat(context.path("submittedAt").isNull()).isTrue(); assertThat(context.path("submittedBy").isNull()).isTrue();
    }

    @Test void foreignScopeAndMissingResourceAreIndistinguishable() throws Exception {
        var f = readable(); String jwt = token(f);
        problem(get(UUID.randomUUID(), jwt), 404, "DOCUMENT_VERSION_NOT_FOUND");
        for (var scope : List.of(new UUID[]{UUID.randomUUID(), f.workspace(), f.repository()},
                new UUID[]{f.tenant(), UUID.randomUUID(), f.repository()}, new UUID[]{f.tenant(), f.workspace(), UUID.randomUUID()})) {
            grant(f, scope[0], scope[1], List.of("STAFF"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW"), List.of(scope[2]));
            problem(get(f.version(), jwt), 404, "DOCUMENT_VERSION_NOT_FOUND");
        }
        grant(f, f.tenant(), f.workspace(), List.of("STAFF"), List.of("DOCUMENT_STAFF_REVIEW"), List.of(f.repository()));
        problem(get(f.version(), jwt), 403, "REVIEW_FORBIDDEN");
        grant(f, f.tenant(), f.workspace(), List.of("DEVELOPER"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW"), List.of(f.repository()));
        problem(get(f.version(), jwt), 403, "REVIEW_FORBIDDEN");
    }

    @Test void selectedTemplateInAnotherWorkspaceIsNotLeaked() throws Exception {
        var f = readable(); UUID template = UUID.randomUUID(), tv = UUID.randomUUID();
        execute("INSERT INTO document.document_templates(template_id,tenant_id,scope_type,scope_ref,name,document_type) VALUES (?,?,'WORKSPACE',?,'Hidden template','SDD')", template, f.tenant(), UUID.randomUUID());
        execute("INSERT INTO document.template_versions(template_version_id,tenant_id,template_id,version_no,structure_ref) VALUES (?,?,?,1,'secret://template')", tv, f.tenant(), template);
        execute("UPDATE document.document_versions SET template_version_id=? WHERE document_version_id=?", tv, f.version());
        var response = get(f.version(), token(f)); problem(response, 404, "DOCUMENT_VERSION_NOT_FOUND");
        assertThat(response.body()).doesNotContain("Hidden template", "secret://template");
        execute("UPDATE document.document_templates SET scope_type='TENANT',scope_ref=? WHERE template_id=?", f.tenant(), template);
        assertThat(data(get(f.version(), token(f))).path("template").path("templateId").asString()).isEqualTo(template.toString());
    }

    @Test void jwtAndAuthorityErrorsAreFailClosed() throws Exception {
        var f = readable(); problem(get(f.version(), null), 401, "UNAUTHENTICATED");
        problem(get(f.version(), token(f) + "x"), 401, "UNAUTHENTICATED");
        problem(get(f.version(), token(f, ISSUER, "other", Instant.now().plusSeconds(300))), 401, "UNAUTHENTICATED");
        problem(get(f.version(), token(f, "other", "s04-test", Instant.now().plusSeconds(300))), 401, "UNAUTHENTICATED");
        problem(get(f.version(), token(f, ISSUER, "s04-test", Instant.now().minusSeconds(120))), 401, "UNAUTHENTICATED");
        for (int status : List.of(401, 403, 503)) {
            ReviewAuthorityTestServer.STATUSES.put(f.actor(), status);
            problem(get(f.version(), token(f)), status, status == 401 ? "UNAUTHENTICATED" : status == 403 ? "REVIEW_FORBIDDEN" : "AUTHORIZATION_UNAVAILABLE");
        }
        ReviewAuthorityTestServer.STATUSES.remove(f.actor());
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor(), Map.of("identityId", UUID.randomUUID()));
        problem(get(f.version(), token(f)), 503, "AUTHORIZATION_UNAVAILABLE");
    }

    @Test void contextEtagWorksForDecisionAndChangesWithVersionRevision() throws Exception {
        var f = readable();
        grant(f, f.tenant(), f.workspace(), List.of("STAFF"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW", "DOCUMENT_STAFF_REVIEW"), List.of(f.repository()));
        String jwt = token(f), original = get(f.version(), jwt).headers().firstValue("ETag").orElseThrow();
        var first = decide(f, jwt, original, "context-review"); assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var refreshed = get(f.version(), jwt); var context = data(refreshed);
        assertThat(context.path("version").path("state").asString()).isEqualTo("DRAFT");
        assertThat(context.path("reviewRound").asInt()).isEqualTo(1);
        assertRepresentationEtag(refreshed, f.version(), 1);
        assertThat(refreshed.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(original);
        problem(decide(f, jwt, original, "stale-review"), 412, "REVISION_CONFLICT");
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version())).isEqualTo(1L);
    }

    private HttpResponse<String> decide(Fixture f, String jwt, String etag, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/document-versions/" + f.version() + "/staff-decisions"))
                .header("Authorization", "Bearer " + jwt).header("X-Correlation-ID", "liv145-test").header("Content-Type", "application/json")
                .header("If-Match", etag).header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString("{\"decision\":\"RETURN_FOR_REVISION\",\"feedback\":\"Context ETag verification\"}")).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void conditionalReadStillChecksScopeAndDoesNotUseFresh() throws Exception {
        var f = readable(); String jwt = token(f); String path = "/api/v1/document-versions/" + f.version() + "/review-context";
        String etag = get(f.version(), jwt).headers().firstValue("ETag").orElseThrow();
        var response = send(path, jwt, Map.of("If-None-Match", etag));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(304); assertThat(response.body()).isEmpty();
        assertThat(send(path, jwt, Map.of("If-None-Match", "W/" + etag)).statusCode()).isEqualTo(304);
        assertThat(send(path, jwt, Map.of("If-None-Match", "\"unrelated\", " + etag)).statusCode()).isEqualTo(304);
        assertThat(send(path, jwt, Map.of("If-None-Match", VersionEtags.format(f.version(), 0))).statusCode()).isEqualTo(200);
        grant(f, f.tenant(), UUID.randomUUID(), List.of("STAFF"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW"), List.of(f.repository()));
        problem(send(path, jwt, Map.of("If-None-Match", etag)), 404, "DOCUMENT_VERSION_NOT_FOUND");
        assertThat(ReviewAuthorityTestServer.FRESH_CALLS.getOrDefault(f.actor(), 0)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"documentTitle", "templateName", "templateRules", "baselineContent", "evidence", "candidateContent"})
    void metadataChangeInvalidatesContextTagWithoutChangingCandidateRevision(String change) throws Exception {
        var f = readable(); UUID template = UUID.randomUUID(), tv = UUID.randomUUID(), baseline = UUID.randomUUID();
        execute("INSERT INTO document.document_templates(template_id,tenant_id,scope_type,scope_ref,name,document_type) VALUES (?,?,'WORKSPACE',?,'Original template','SDD')", template, f.tenant(), f.workspace());
        execute("INSERT INTO document.template_versions(template_version_id,tenant_id,template_id,version_no,structure_ref) VALUES (?,?,?,1,'artifact://template')", tv, f.tenant(), template);
        execute("UPDATE document.document_versions SET version_no=3,template_version_id=? WHERE document_version_id=?", tv, f.version());
        execute("INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,creator_actor_ref) VALUES (?,?,?,2,'PUBLISHED','test')", baseline, f.tenant(), f.document());
        execute("INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,content_text,content_format,editor_identity_id) VALUES (?,?,1,'artifact://baseline','Original baseline','TEXT',?)", f.tenant(), baseline, f.actor());
        String jwt = token(f), path = "/api/v1/document-versions/" + f.version() + "/review-context";
        var initial = get(f.version(), jwt); data(initial); assertRepresentationEtag(initial, f.version(), 0);
        String original = initial.headers().firstValue("ETag").orElseThrow();
        switch (change) {
            case "documentTitle" -> execute("UPDATE document.documents SET title='Renamed document' WHERE document_id=?", f.document());
            case "templateName" -> execute("UPDATE document.document_templates SET name='Renamed template' WHERE template_id=?", template);
            case "templateRules" -> execute("UPDATE document.template_versions SET formatting_rules='{\"headings\":[\"Changed\"]}'::jsonb WHERE template_version_id=?", tv);
            case "baselineContent" -> execute("UPDATE document.content_revisions SET content_text='Changed baseline' WHERE document_version_id=?", baseline);
            case "evidence" -> execute("INSERT INTO document.document_evidence_refs(document_evidence_id,tenant_id,document_version_id,evidence_type,source_ref) VALUES (?,?,?,'DIFF','source://new-diff')", UUID.randomUUID(), f.tenant(), f.version());
            case "candidateContent" -> execute("UPDATE document.content_revisions SET content_text='Changed candidate',content_format='TEXT' WHERE document_version_id=?", f.version());
            default -> throw new AssertionError(change);
        }
        var refreshed = send(path, jwt, Map.of("If-None-Match", original)); data(refreshed);
        assertRepresentationEtag(refreshed, f.version(), 0);
        assertThat(refreshed.body()).isNotEqualTo(initial.body());
        String current = refreshed.headers().firstValue("ETag").orElseThrow();
        assertThat(current).isNotEqualTo(original);
        var unchanged = send(path, jwt, Map.of("If-None-Match", current));
        assertThat(unchanged.statusCode()).as(unchanged.body()).isEqualTo(304);
        assertThat(unchanged.body()).isEmpty();
        assertThat(unchanged.headers().firstValue("ETag")).contains(current);
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?", f.version())).isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version())).isEqualTo(0L);
    }

    @Test void wildcardConditionalReadMatchesOnlyAfterAuthorizationAndResourceChecks() throws Exception {
        var f = readable(); String jwt = token(f), path = "/api/v1/document-versions/" + f.version() + "/review-context";
        var response = send(path, jwt, Map.of("If-None-Match", "*"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(304);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("ETag")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        problem(send(path, null, Map.of("If-None-Match", "*")), 401, "UNAUTHENTICATED");
        problem(send("/api/v1/document-versions/" + UUID.randomUUID() + "/review-context", jwt, Map.of("If-None-Match", "*")), 404, "DOCUMENT_VERSION_NOT_FOUND");
        grant(f, f.tenant(), UUID.randomUUID(), List.of("STAFF"), List.of("DOCUMENT_READ", "WORKSPACE_VIEW"), List.of(f.repository()));
        problem(send(path, jwt, Map.of("If-None-Match", "*")), 404, "DOCUMENT_VERSION_NOT_FOUND");
        assertThat(ReviewAuthorityTestServer.FRESH_CALLS.getOrDefault(f.actor(), 0)).isZero();
    }

    private void assertRepresentationEtag(HttpResponse<String> response, UUID version, long revision) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body().getBytes(StandardCharsets.UTF_8)));
        assertThat(response.headers().firstValue("ETag")).contains("\"" + version + ":revision-" + revision + ":context-" + hash + "\"");
    }

    @Test void pooledConnectionDoesNotCarryAnotherTenantContext() throws Exception {
        var a = readable(); var b = readable();
        for (var f : List.of(a, b, a))
            assertThat(data(get(f.version(), token(f))).path("version").path("versionId").asString()).isEqualTo(f.version().toString());
    }

    @Test void queryTimeoutIsSanitizedAndTheNextReadRecovers() throws Exception {
        var f = readable();
        try (var lock = admin()) {
            lock.setAutoCommit(false);
            try (var sql = lock.createStatement()) {
                sql.execute("LOCK TABLE document.content_revisions IN ACCESS EXCLUSIVE MODE");
                var response = get(f.version(), token(f)); problem(response, 500, "PERSISTENCE_ERROR");
                assertThat(response.body()).doesNotContain("statement timeout", "SELECT", "tenant_id");
            } finally { lock.rollback(); }
        }
        data(get(f.version(), token(f)));
    }

    @Test void runtimeOpenApiMatchesCanonicalContextShapesAndHeaders() throws Exception {
        var f = readable();
        var response = send("/v3/api-docs", token(f), Map.of()); assertThat(response.statusCode()).isEqualTo(200);
        var actual = json.readTree(response.body());
        var options = new org.yaml.snakeyaml.LoaderOptions(); options.setMaxAliasesForCollections(1000);
        var expected = json.readTree(json.writeValueAsString(new org.yaml.snakeyaml.Yaml(options)
                .load(java.nio.file.Files.readString(java.nio.file.Path.of("../../contracts/openapi/s04-document.yaml")))));
        String path = "/api/v1/document-versions/{versionId}/review-context";
        var operation = actual.path("paths").path(path).path("get"); var contract = expected.path("paths").path(path).path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("api116_get");
        assertThat(operation.path("responses").propertyNames()).containsExactlyInAnyOrderElementsOf(contract.path("responses").propertyNames());
        var parameters = new HashSet<String>(); operation.path("parameters").forEach(p -> parameters.add(p.path("name").asString()));
        assertThat(parameters).containsExactlyInAnyOrder("versionId", "If-None-Match");
        for (var parameter : operation.path("parameters")) if ("versionId".equals(parameter.path("name").asString())) {
            assertThat(parameter.path("required").asBoolean()).isTrue();
            assertThat(parameter.path("schema").path("format").asString()).isEqualTo("uuid");
        }
        for (String status : List.of("200", "304", "401", "405"))
            assertThat(operation.path("responses").path(status).path("headers").propertyNames()).containsExactlyInAnyOrderElementsOf(contract.path("responses").path(status).path("headers").propertyNames());
        assertThat(operation.path("responses").path("304").path("content").isMissingNode()).isTrue();
        var envelope = resolve(actual, operation.path("responses").path("200").path("content").path("application/json").path("schema"));
        shape(actual, envelope, expected.path("components").path("schemas").path("ReviewContextApiResponse"));
        var view = resolve(actual, envelope.path("properties").path("data"));
        shape(actual, view, expected.path("components").path("schemas").path("ReviewContextView"));
        for (String field : List.of("template", "baseline"))
            shape(actual, resolve(actual, view.path("properties").path(field)), expected.path("components").path("schemas").path(field.equals("template") ? "ReviewContextTemplate" : "ReviewContextBaseline"));
        shape(actual, resolve(actual, view.path("properties").path("evidence").path("items")), expected.path("components").path("schemas").path("ReviewContextEvidence"));
        for (String status : List.of("400", "401", "403", "404", "405", "406", "500", "503"))
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
            var field = resolve(document, actual.path("properties").path(property.getKey())); var baseline = property.getValue();
            for (String attribute : List.of("type", "format", "minimum", "nullable")) if (baseline.has(attribute))
                assertThat(field.path(attribute)).as(property.getKey() + "." + attribute).isEqualTo(baseline.path(attribute));
            if (baseline.has("enum")) {
                var values = new HashSet<String>(); field.path("enum").forEach(n -> values.add(n.isNull() ? null : n.asString()));
                var expectedValues = new HashSet<String>(); baseline.path("enum").forEach(n -> expectedValues.add(n.isNull() ? null : n.asString()));
                assertThat(values).as(property.getKey()).isEqualTo(expectedValues);
            }
        }
    }

    @Test void requestAndContentConstraintsRejectInvalidInputs() throws Exception {
        var f = readable(); String jwt = token(f);
        problem(send("/api/v1/document-versions/not-a-uuid/review-context", jwt, Map.of()), 400, "INVALID_REQUEST");
        problem(send("/api/v1/document-versions/" + f.version() + "/review-context?evidenceRef=" + UUID.randomUUID(), jwt, Map.of()), 400, "INVALID_REQUEST");
        assertThatThrownBy(() -> execute("UPDATE document.content_revisions SET content_text='real body',content_format=NULL WHERE document_version_id=?", f.version()))
                .isInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> execute("UPDATE document.content_revisions SET content_text='real body',content_format='HTML' WHERE document_version_id=?", f.version()))
                .isInstanceOf(java.sql.SQLException.class);
    }
}
