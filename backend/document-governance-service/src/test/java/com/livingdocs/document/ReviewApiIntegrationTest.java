package com.livingdocs.document;

import com.livingdocs.document.controller.VersionEtags;
import com.livingdocs.document.model.*;
import com.livingdocs.document.exception.*;
import com.livingdocs.document.service.ReviewSnapshotCodec;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.argThat;
import com.livingdocs.document.dto.request.ReviewRequest;
import com.livingdocs.document.security.ActorContext;
import com.livingdocs.document.service.DocumentReviewService;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.api-docs.enabled=true")
@Import(ReviewSourceValidationTestConfiguration.class)
class ReviewApiIntegrationTest extends PostgresTestSupport {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired jakarta.validation.Validator validator;
    @Autowired DocumentReviewService service;
    @MockitoSpyBean ReviewSnapshotCodec snapshotCodec;
    @Autowired ReviewSourceValidationTestConfiguration.TestValidator sourceValidator;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.livingdocs.document.repository.DocumentVersionRepository versions;
    @Autowired jakarta.persistence.EntityManager entities;
    final HttpClient http = HttpClient.newHttpClient();

    record Fixture(UUID tenant, UUID workspace, UUID repository, UUID document, UUID version, UUID evidence, UUID actor) {
        ActorContext context() {
            return new ActorContext(actor, tenant, Set.of(new ActorContext.WorkspaceGrant(workspace,
                    Set.of("STAFF"), Set.of(repository))));
        }
    }

    static Fixture fixture() throws Exception {
        var f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID collection = UUID.randomUUID();
        execute("INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES (?,?,?,'Review tests')",
                collection, f.tenant, f.workspace);
        execute("INSERT INTO document.documents(document_id,tenant_id,collection_id,repository_connection_id,title,ownership_ref) VALUES (?,?,?,?,'Test document','test')",
                f.document, f.tenant, collection, f.repository);
        execute("INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,review_stage,source_sha,creator_actor_ref) VALUES (?,?,?,1,'IN_REVIEW','STAFF_REVIEW',?,'test-creator')",
                f.version, f.tenant, f.document, "a".repeat(40));
        execute("INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,editor_identity_id) VALUES (?,?,1,'test://original-content',?)",
                f.tenant, f.version, f.actor);
        execute("INSERT INTO document.document_evidence_refs(document_evidence_id,tenant_id,document_version_id,evidence_type,source_ref) VALUES (?,?,?,'SOURCE','test://source')",
                f.evidence, f.tenant, f.version);
        ReviewSourceValidationTestConfiguration.HEADS.put(f.repository, "a".repeat(40));
        ReviewAuthorityTestServer.allow(f);
        return f;
    }

    static void execute(String sql, Object... args) throws Exception {
        try (var connection = admin(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.execute();
        }
    }

    static Object scalar(String sql, Object... args) throws Exception {
        try (var connection = admin(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getObject(1);
            }
        }
    }

    String token(Fixture f) throws Exception { return token(f, Map.of(), KEY, ISSUER, "s04-test", Instant.now().plusSeconds(300)); }

    String token(Fixture f, Map<String, Object> overrides, com.nimbusds.jose.jwk.RSAKey key,
                 String issuer, String audience, Instant expires) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(f.actor.toString()).issuer(issuer).audience(audience)
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(expires));
        overrides.forEach(claims::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    String passBody(Fixture f) {
        return json.writeValueAsString(Map.of("decision", "PASS", "evidenceRefs", List.of(f.evidence)));
    }

    HttpResponse<String> review(Fixture f, String token, String body, String key, String etag) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/document-versions/" + f.version + "/staff-decisions"))
                .header("Content-Type", "application/json").header("X-Correlation-ID", "liv-118-test")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (etag != null) request.header("If-Match", etag);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }

    JsonNode data(HttpResponse<String> response) {
        var envelope = body(response);
        assertThat(envelope.propertyNames()).containsExactlyInAnyOrder("code", "message", "data");
        assertThat(envelope.path("code").asString()).isEqualTo("STAFF_REVIEW_RECORDED");
        assertThat(envelope.path("message").asString()).isEqualTo("Staff review decision recorded");
        return envelope.path("data");
    }

    @Test
    void orm_shouldShareTransactionLocalRlsAndClearTenantAfterCompletion() throws Exception {
        var first = fixture();
        var second = fixture();
        assertThat(transactions).isInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var connectionId = new java.util.concurrent.atomic.AtomicReference<Object>();
        tx.executeWithoutResult(status -> {
            connectionId.set(entities.createNativeQuery("SELECT pg_backend_pid()", Integer.class).getSingleResult());
            versions.setTenant(first.tenant);
            assertThat(entities.find(com.livingdocs.document.entity.DocumentVersionEntity.class, first.version)).isNotNull();
            assertThat(entities.find(com.livingdocs.document.entity.DocumentVersionEntity.class, second.version)).isNull();
        });
        tx.executeWithoutResult(status -> {
            // Prove reuse of the physical pooled connection, not just another clean connection.
            assertThat(entities.createNativeQuery("SELECT pg_backend_pid()", Integer.class).getSingleResult())
                    .isEqualTo(connectionId.get());
            assertThat(entities.find(com.livingdocs.document.entity.DocumentVersionEntity.class, first.version)).isNull();
            versions.setTenant(second.tenant);
            assertThat(entities.find(com.livingdocs.document.entity.DocumentVersionEntity.class, second.version)).isNotNull();
            assertThat(entities.find(com.livingdocs.document.entity.DocumentVersionEntity.class, first.version)).isNull();
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> {
            assertThat(entities.createNativeQuery("SELECT pg_backend_pid()", Integer.class).getSingleResult())
                    .isEqualTo(connectionId.get());
            assertThat(entities.find(com.livingdocs.document.entity.DocumentVersionEntity.class, second.version)).isNull();
        });
        assertThatThrownBy(() -> versions.setTenant(first.tenant))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void orm_shouldRejectStaleManagedVersionAtFlushWithoutAppendingHistory() throws Exception {
        var f = fixture();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            versions.setTenant(f.tenant);
            var before = versions.find(f.version, f.tenant, false).orElseThrow();
            try {
                // An independent writer wins after the ORM snapshot was loaded.
                execute("UPDATE document.document_versions SET revision=revision+1 WHERE document_version_id=?", f.version);
            } catch (Exception e) { throw new RuntimeException(e); }
            versions.update(before, before.reviewed(ReviewAction.PASS));
        })).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?", f.version)).isEqualTo(1L);
        assertThat(scalar("SELECT review_stage FROM document.document_versions WHERE document_version_id=?", f.version)).isEqualTo("STAFF_REVIEW");
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo(0L);
    }

    @Test
    void runtimeOpenApi_shouldMatchReviewedContractAndRequireAuthentication() throws Exception {
        var f = fixture();
        var uri = URI.create("http://127.0.0.1:" + port + "/v3/api-docs");
        assertThat(http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
        var response = http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token(f)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var runtime = body(response);
        var reviewed = json.readTree(json.writeValueAsString(new Yaml().load(
                Files.readString(Path.of("../../contracts/openapi/s04-document-review.yaml")))));
        String path = "/api/v1/document-versions/{versionId}/staff-decisions";
        assertThat(runtime.path("openapi").asString()).startsWith("3.0.");
        assertThat(runtime.path("paths").propertyNames()).containsExactlyInAnyOrder(path, "/api/v1/document-versions/{versionId}/submit");
        var actual = runtime.path("paths").path(path).path("post");
        var expected = reviewed.path("paths").path(path).path("post");
        assertThat(actual.path("operationId").asString()).isEqualTo(expected.path("operationId").asString());
        assertThat(actual.path("responses").propertyNames()).containsExactlyInAnyOrderElementsOf(expected.path("responses").propertyNames());
        assertThat(runtime.path("security").get(0).has("bearerAuth")).isTrue();
        for (String header : List.of("If-Match", "Idempotency-Key")) {
            boolean required = false;
            for (var parameter : actual.path("parameters"))
                if (header.equals(parameter.path("name").asString())) required = parameter.path("required").asBoolean();
            assertThat(required).as(header).isTrue();
        }
        var request = resolveSchema(runtime, actual.path("requestBody").path("content").path("application/json").path("schema"));
        var requestExpected = reviewed.path("components").path("schemas").path("StaffDecisionRequest");
        assertSchemaShape(request, requestExpected);
        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        var refs = request.path("properties").path("evidenceRefs");
        assertThat(refs.path("maxItems").asInt()).isEqualTo(100);
        assertThat(refs.path("uniqueItems").asBoolean()).isTrue();
        assertThat(refs.path("items").path("format").asString()).isEqualTo("uuid");
        var success = actual.path("responses").path("200");
        assertThat(success.path("content").propertyNames()).containsExactly("application/json");
        assertThat(success.path("headers").propertyNames()).containsExactlyInAnyOrder("ETag", "X-Correlation-ID", "Cache-Control");
        var envelope = resolveSchema(runtime, success.path("content").path("application/json").path("schema"));
        assertSchemaShape(envelope, reviewed.path("components").path("schemas").path("StaffDecisionSuccess"));
        var version = resolveSchema(runtime, envelope.path("properties").path("data"));
        assertSchemaShape(version, reviewed.path("components").path("schemas").path("DocumentVersionView"));
        assertThat(version.path("properties").path("revision").path("format").asString()).isEqualTo("int64");
        assertThat(version.path("properties").path("revision").path("minimum").asInt()).isZero();
        assertSchemaShape(runtime.path("components").path("schemas").path("SourceBinding"),
                reviewed.path("components").path("schemas").path("SourceBinding"));
        for (String status : List.of("400", "401", "403", "404", "405", "406", "409", "412", "415", "422", "428", "500", "503")) {
            var error = actual.path("responses").path(status);
            assertThat(error.path("content").propertyNames()).containsExactly("application/problem+json");
            var expectedError = resolveSchema(reviewed, expected.path("responses").path(status));
            assertThat(error.path("headers").propertyNames())
                    .containsExactlyInAnyOrderElementsOf(expectedError.path("headers").propertyNames());
            var problem = resolveSchema(runtime, error.path("content").path("application/problem+json").path("schema"));
            assertSchemaShape(problem, reviewed.path("components").path("schemas").path("Problem"));
        }
        var result = review(f, token(f), passBody(f), "runtime-schema", VersionEtags.format(f.version, 0));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(body(result).propertyNames()).containsExactlyInAnyOrderElementsOf(envelope.path("properties").propertyNames());
        assertThat(data(result).propertyNames()).containsExactlyInAnyOrderElementsOf(version.path("properties").propertyNames());
    }

    private JsonNode resolveSchema(JsonNode document, JsonNode schema) {
        while (schema.has("$ref")) schema = document.at(schema.path("$ref").asString().substring(1));
        return schema;
    }

    private void assertSchemaShape(JsonNode actual, JsonNode expected) {
        assertThat(actual.path("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(expected.path("properties").propertyNames());
        for (var entry : expected.path("properties").properties()) {
            var field = actual.path("properties").path(entry.getKey());
            var baseline = entry.getValue();
            for (String attribute : List.of("type", "format"))
                if (baseline.has(attribute)) assertThat(field.path(attribute).asString()).as(entry.getKey() + "." + attribute)
                        .isEqualTo(baseline.path(attribute).asString());
            assertThat(field.path("nullable").asBoolean()).as(entry.getKey() + ".nullable")
                    .isEqualTo(baseline.path("nullable").asBoolean());
            if (baseline.has("enum")) {
                var values = new HashSet<String>();
                field.path("enum").forEach(value -> { values.add(value.isNull() ? null : value.asString()); });
                var expectedValues = new HashSet<String>();
                baseline.path("enum").forEach(value -> { expectedValues.add(value.isNull() ? null : value.asString()); });
                assertThat(values).as(entry.getKey() + ".enum").isEqualTo(expectedValues);
            }
        }
        var required = new HashSet<String>();
        actual.path("required").forEach(field -> required.add(field.asString()));
        var expectedRequired = new HashSet<String>();
        expected.path("required").forEach(field -> expectedRequired.add(field.asString()));
        assertThat(required).isEqualTo(expectedRequired);
    }

    @Test
    void legacyResponseSnapshot_shouldReplayWithClassDtoAndNoNewSourceCheck() throws Exception {
        var f = fixture();
        String snapshot = """
                {"documentId":"%s","versionId":"%s","state":"IN_REVIEW","reviewStage":"MANAGER_APPROVAL","revision":1,
                "sourceBinding":{"repositoryConnectionId":"%s","targetKind":null,"targetRef":null,"headSha":"%s",
                "comparisonSha":null,"configRevision":null,"capturedAt":null}}
                """.formatted(f.document, f.version, f.repository, "a".repeat(40));
        execute("INSERT INTO document.review_decisions(tenant_id,document_version_id,reviewer_identity_id,decision,round_no,"
                        + "idempotency_key,expected_revision,resulting_revision,resulting_state,resulting_stage,evidence_refs,response_snapshot) "
                        + "VALUES (?,?,?,'PASS',1,'legacy-snapshot',0,1,'IN_REVIEW','MANAGER_APPROVAL',CAST(? AS jsonb),CAST(? AS jsonb))",
                f.tenant, f.version, f.actor, "[\"" + f.evidence + "\"]", snapshot);
        execute("UPDATE document.document_versions SET review_stage='MANAGER_APPROVAL',revision=1 WHERE document_version_id=?", f.version);
        var response = review(f, token(f), passBody(f), "legacy-snapshot", VersionEtags.format(f.version, 0));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(data(response)).isEqualTo(json.readTree(snapshot));
        assertThat(json.readTree((String) scalar("SELECT response_snapshot::text FROM document.review_decisions WHERE document_version_id=?", f.version)))
                .isEqualTo(json.readTree(snapshot));
        assertThat(response.headers().firstValue("ETag")).contains(VersionEtags.format(f.version, 1));
        assertThat(sourceValidator.calls(f.version)).isZero();
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(1L);
    }

    @Test
    void successEnvelope_shouldPersistAndReplayOriginalMessageWithoutNesting() throws Exception {
        var f = fixture();
        var jwt = token(f);
        var etag = VersionEtags.format(f.version, 0);
        var original = review(f, jwt, passBody(f), "envelope-replay", etag);
        assertThat(original.statusCode()).as(original.body()).isEqualTo(200);
        data(original);
        var stored = json.readTree((String) scalar("SELECT response_snapshot::text FROM document.review_decisions WHERE document_version_id=?", f.version));
        assertThat(stored).isEqualTo(body(original));
        // A historical message may differ from today's default after a later deployment.
        // Alter only test data using the fixture's admin role, never the application role.
        execute("UPDATE document.review_decisions SET response_snapshot=jsonb_set(response_snapshot,'{message}',to_jsonb(?::text)) WHERE document_version_id=?",
                "Previously recorded review", f.version);
        var replay = review(f, jwt, passBody(f), "envelope-replay", etag);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(body(replay).path("message").asString()).isEqualTo("Previously recorded review");
        assertThat(body(replay).path("code")).isEqualTo(body(original).path("code"));
        assertThat(body(replay).path("data")).isEqualTo(data(original));
        assertThat(body(replay).path("data").has("data")).isFalse();
        assertThat(replay.headers().firstValue("ETag")).isEqualTo(original.headers().firstValue("ETag"));
        assertThat(sourceValidator.calls(f.version)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo(1L);
    }

    void unchanged(Fixture f) throws Exception {
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?", f.version)).isEqualTo(0L);
        assertThat(scalar("SELECT review_stage FROM document.document_versions WHERE document_version_id=?", f.version)).isEqualTo("STAFF_REVIEW");
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo(0L);
    }

    void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(body(response).path("code").asString()).isEqualTo(code);
        assertThat(body(response).path("instance").asString()).startsWith("urn:uuid:");
        assertThat(body(response).path("correlationId").asString()).isEqualTo("liv-118-test");
    }

    @Test
    void requestConstraints_shouldValidateBeforeMappingToBusinessCommand() throws Exception {
        UUID evidence = UUID.randomUUID();
        var oversized = new ReviewRequest(ReviewAction.PASS, null, Collections.nCopies(101, evidence));
        assertThat(validator.validate(oversized)).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(jakarta.validation.constraints.Size.class));
        var duplicate = new ReviewRequest(ReviewAction.PASS, null, List.of(evidence, evidence));
        assertThat(validator.validate(duplicate)).anySatisfy(violation ->
                assertThat(violation.getConstraintDescriptor().getAnnotation())
                        .isInstanceOf(com.livingdocs.document.validation.UniqueEvidenceRefs.class));
        assertThat(validator.validate(new ReviewRequest(ReviewAction.RETURN_FOR_REVISION, "reason", null))).isEmpty();
        var f = fixture();
        for (String payload : List.of("{\"decision\":\"PASS\",\"evidenceRefs\":[null]}",
                "{\"decision\":\"PASS\",\"evidenceRefs\":[\"not-a-uuid\"]}")) {
            problem(review(f, token(f), payload, "invalid-item", VersionEtags.format(f.version, 0)), 400, "INVALID_REQUEST");
        }
        for (String key : List.of("", "invalid key", "x".repeat(129))) {
            problem(review(f, token(f), passBody(f), key, VersionEtags.format(f.version, 0)), 400, "INVALID_REQUEST");
        }
        unchanged(f);
    }

    @Test
    void transportFailures_shouldKeepHttpStatusAndProblemContractWithoutMutation() throws Exception {
        var f = fixture();
        var endpoint = URI.create("http://127.0.0.1:" + port + "/api/v1/document-versions/" + f.version + "/staff-decisions");
        String[][] cases = {{"POST", "text/plain", "application/json", "415", "UNSUPPORTED_MEDIA_TYPE"},
                {"GET", "application/json", "application/json", "405", "METHOD_NOT_ALLOWED"},
                {"POST", "application/json", "text/plain", "406", "NOT_ACCEPTABLE"}};
        for (var test : cases) {
            var request = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + token(f))
                    .header("Content-Type", test[1]).header("Accept", test[2]).header("X-Correlation-ID", "liv-118-test")
                    .header("If-Match", VersionEtags.format(f.version, 0)).header("Idempotency-Key", "transport-error")
                    .method(test[0], HttpRequest.BodyPublishers.ofString(passBody(f))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            problem(response, Integer.parseInt(test[3]), test[4]);
            if (response.statusCode() == 405) assertThat(response.headers().firstValue("Allow").orElse("")).contains("POST");
            unchanged(f);
        }
        problem(review(f, null, "{}", "unauthenticated", VersionEtags.format(f.version, 0)), 401, "UNAUTHENTICATED");
    }

    @Test
    void correlationId_shouldSurviveInSuccessAndAuditOrBeSafelyReplaced() throws Exception {
        for (String id : List.of("audit.trace_118", "a".repeat(64), "invalid id", "a".repeat(65))) {
            var f = fixture();
            var endpoint = URI.create("http://127.0.0.1:" + port + "/api/v1/document-versions/" + f.version + "/staff-decisions");
            var request = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + token(f))
                    .header("Content-Type", "application/json").header("X-Correlation-ID", id)
                    .header("If-Match", VersionEtags.format(f.version, 0)).header("Idempotency-Key", "correlation")
                    .POST(HttpRequest.BodyPublishers.ofString(passBody(f))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            String actual = response.headers().firstValue("X-Correlation-ID").orElseThrow();
            if (id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) assertThat(actual).isEqualTo(id);
            else assertThatCode(() -> UUID.fromString(actual)).doesNotThrowAnyException();
            assertThat(scalar("SELECT metadata->>'correlationId' FROM document.document_audits WHERE document_version_id=?", f.version))
                    .isEqualTo(actual);
        }
    }

    @Test
    void pass_shouldPersistDecisionAuditAndNewEtag() throws Exception {
        var f = fixture();
        var result = review(f, token(f), passBody(f), "pass", VersionEtags.format(f.version, 0));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(data(result).path("documentId").asString()).isEqualTo(f.document.toString());
        assertThat(data(result).path("sourceBinding").path("repositoryConnectionId").asString()).isEqualTo(f.repository.toString());
        assertThat(data(result).path("sourceBinding").path("headSha").asString()).isEqualTo("a".repeat(40));
        assertThat(data(result).path("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(data(result).path("reviewStage").asString()).isEqualTo("MANAGER_APPROVAL");
        assertThat(result.headers().firstValue("ETag")).contains(VersionEtags.format(f.version, 1));
        assertThat(scalar("SELECT reviewer_identity_id FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(f.actor);
        assertThat(scalar("SELECT metadata->>'previousReviewStage' FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo("STAFF_REVIEW");
        assertThat(scalar("SELECT metadata->>'newReviewStage' FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo("MANAGER_APPROVAL");
        assertThat(scalar("SELECT source_sha FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo("a".repeat(40));
        assertThat(scalar("SELECT metadata->'sourceValidation'->>'validationId' FROM document.document_audits WHERE document_version_id=?", f.version))
                .isEqualTo("test-validation-" + f.version);
        assertThat(scalar("SELECT metadata->'sourceValidation'->>'validatedHeadSha' FROM document.document_audits WHERE document_version_id=?", f.version))
                .isEqualTo("a".repeat(40));
    }

    @Test
    void return_shouldMoveToDraftAndPreserveHistory() throws Exception {
        var f = fixture();
        execute("INSERT INTO document.review_decisions(tenant_id,document_version_id,reviewer_identity_id,decision,round_no) VALUES (?,?,?,'PASS',1)",
                f.tenant, f.version, f.actor);
        var result = review(f, token(f), "{\"decision\":\"RETURN_FOR_REVISION\",\"feedback\":\"Cần sửa nguồn\"}", "return", VersionEtags.format(f.version, 0));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(data(result).path("state").asString()).isEqualTo("DRAFT");
        assertThat(data(result).path("reviewStage").isNull()).isTrue();
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(2L);
        assertThat(scalar("SELECT reason FROM document.review_decisions WHERE document_version_id=? AND round_no=2", f.version)).isEqualTo("Cần sửa nguồn");
        assertThat(scalar("SELECT content_ref FROM document.content_revisions WHERE document_version_id=?", f.version)).isEqualTo("test://original-content");
        assertThat(scalar("SELECT count(*) FROM document.document_evidence_refs WHERE document_version_id=?", f.version)).isEqualTo(1L);
    }

    @Test
    void duplicateRequest_shouldReplayOriginalResponseAndRejectChangedPayload() throws Exception {
        var f = fixture();
        String jwt = token(f), etag = VersionEtags.format(f.version, 0);
        var first = review(f, jwt, passBody(f), "retry", etag);
        var replay = review(f, jwt, passBody(f), "retry", etag);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("ETag")).isEqualTo(first.headers().firstValue("ETag"));
        problem(review(f, jwt, "{\"decision\":\"RETURN_FOR_REVISION\",\"feedback\":\"changed\"}", "retry", etag), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo(1L);
    }

    @Test
    void evidenceBudgetAndDuplicates_shouldRejectBeforeVersionLookup() throws Exception {
        var f = fixture();
        assertThatThrownBy(() -> new StaffDecisionCommand(ReviewAction.PASS, null, Collections.nCopies(101, f.evidence)))
                .isInstanceOfSatisfying(ReviewEvidenceLimitExceededException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ReviewErrorCode.EVIDENCE_LIMIT_EXCEEDED));
        assertThatThrownBy(() -> new StaffDecisionCommand(ReviewAction.PASS, null, List.of(f.evidence, f.evidence)))
                .isInstanceOfSatisfying(DuplicateReviewEvidenceException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ReviewErrorCode.DUPLICATE_EVIDENCE));
        var oversized = json.writeValueAsString(Map.of("decision", "PASS", "evidenceRefs", Collections.nCopies(101, f.evidence)));
        problem(review(f, token(f), oversized, "budget", VersionEtags.format(f.version, 0)), 422, "EVIDENCE_LIMIT_EXCEEDED");
        var repeated = json.writeValueAsString(Map.of("decision", "PASS", "evidenceRefs", List.of(f.evidence, f.evidence)));
        problem(review(f, token(f), repeated, "duplicate", VersionEtags.format(f.version, 0)), 422, "DUPLICATE_EVIDENCE");
        unchanged(f);
    }

    @Test
    void replay_shouldPreserveDocumentAndSourceSnapshot() throws Exception {
        var f = fixture();
        String jwt = token(f), etag = VersionEtags.format(f.version, 0);
        var first = review(f, jwt, passBody(f), "snapshot", etag);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        sourceValidator.overrides.put(f.version, version -> { throw new IllegalStateException("source authority unavailable"); });
        execute("UPDATE document.document_versions SET source_sha=? WHERE document_version_id=?", "b".repeat(40), f.version);
        var retry = review(f, jwt, passBody(f), "snapshot", etag);
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(sourceValidator.calls(f.version)).isEqualTo(1);
    }

    @Test
    void invalidRequests_shouldNeverMutate() throws Exception {
        var f = fixture();
        String jwt = token(f), etag = VersionEtags.format(f.version, 0);
        problem(review(f, jwt, passBody(f), "missing-etag", null), 428, "PRECONDITION_REQUIRED");
        problem(review(f, jwt, passBody(f), null, etag), 400, "INVALID_REQUEST");
        problem(review(f, jwt, passBody(f), "stale", VersionEtags.format(f.version, 2)), 412, "REVISION_CONFLICT");
        problem(review(f, jwt, "{\"decision\":\"RETURN_FOR_REVISION\",\"feedback\":\"  \"}", "blank", etag), 422, "RETURN_REASON_REQUIRED");
        problem(review(f, jwt, "{\"decision\":\"PASS\"}", "no-evidence", etag), 422, "REVIEW_EVIDENCE_REQUIRED");
        problem(review(f, jwt, "{\"decision\":\"REJECT\"}", "unknown", etag), 400, "INVALID_REQUEST");
        problem(review(f, jwt, "{\"decision\":null}", "null", etag), 400, "INVALID_REQUEST");
        problem(review(f, jwt, "{\"decision\":\"PASS\",\"state\":\"APPROVED\"}", "state", etag), 400, "INVALID_REQUEST");
        unchanged(f);
    }

    @Test
    void invalidStateAndForeignEvidence_shouldReject() throws Exception {
        var f = fixture();
        var other = fixture();
        problem(review(f, token(f), passBody(other), "foreign", VersionEtags.format(f.version, 0)), 422, "REVIEW_EVIDENCE_INVALID");
        unchanged(f);
        execute("UPDATE document.document_versions SET state='DRAFT', review_stage=NULL WHERE document_version_id=?", f.version);
        problem(review(f, token(f), passBody(f), "draft", VersionEtags.format(f.version, 0)), 409, "STATE_TRANSITION_FORBIDDEN");
        assertThat(scalar("SELECT state FROM document.document_versions WHERE document_version_id=?", f.version)).isEqualTo("DRAFT");
    }

    @Test
    void wrongTenantWorkspaceRoleRepositoryOrInactiveMembership_shouldReject() throws Exception {
        var f = fixture();
        String etag = VersionEtags.format(f.version, 0);
        var otherTenant = new Fixture(UUID.randomUUID(), f.workspace, f.repository, f.document, f.version, f.evidence, f.actor);
        ReviewAuthorityTestServer.allow(otherTenant);
        problem(review(f, token(otherTenant), passBody(f), "tenant", etag), 404, "DOCUMENT_VERSION_NOT_FOUND");
        for (String kind : List.of("workspace", "role", "permission", "repository", "inactive-workspace", "inactive-tenant")) {
            var grant = ReviewAuthorityTestServer.grant(kind.equals("workspace") ? UUID.randomUUID() : f.workspace,
                    List.of(kind.equals("role") ? "TENANT_OWNER" : "STAFF"),
                    kind.equals("permission") ? List.of("WORKSPACE_VIEW") : List.of("DOCUMENT_STAFF_REVIEW"),
                    List.of(kind.equals("repository") ? UUID.randomUUID() : f.repository));
            ReviewAuthorityTestServer.CONTEXTS.put(f.actor, ReviewAuthorityTestServer.context(f.actor,
                    kind.equals("inactive-tenant") ? null : f.tenant,
                    kind.equals("inactive-workspace") ? List.of() : List.of(grant)));
            problem(review(f, token(f), passBody(f), kind, etag), 403, "REVIEW_FORBIDDEN");
        }
        unchanged(f);
    }

    @Test
    void unsignedWrongSignatureIssuerAudienceExpiredTokens_shouldReject() throws Exception {
        var f = fixture();
        var wrongKey = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate();
        List<String> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add("not-a-jwt");
        invalid.add(token(f, Map.of(), wrongKey, ISSUER, "s04-test", Instant.now().plusSeconds(300)));
        invalid.add(token(f, Map.of(), KEY, "https://wrong-issuer.invalid", "s04-test", Instant.now().plusSeconds(300)));
        invalid.add(token(f, Map.of(), KEY, ISSUER, "another-service", Instant.now().plusSeconds(300)));
        invalid.add(token(f, Map.of("sub", "invalid-identity"), KEY, ISSUER, "s04-test", Instant.now().plusSeconds(300)));
        invalid.add(token(f, Map.of(), KEY, ISSUER, "s04-test", Instant.now().minusSeconds(300)));
        for (String jwt : invalid) problem(review(f, jwt, passBody(f), "auth", VersionEtags.format(f.version, 0)), 401, "UNAUTHENTICATED");
        unchanged(f);
    }

    @Test
    void auditInsertFailure_shouldRollbackEntireReview() throws Exception {
        var f = fixture();
        String suffix = f.version.toString().replace("-", "");
        String function = "document.fail_audit_" + suffix;
        String trigger = "fail_audit_" + suffix;
        execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.document_version_id = '" + f.version
                + "'::uuid THEN RAISE EXCEPTION 'test audit failure'; END IF; RETURN NEW; END $$");
        execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON document.document_audits FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        try {
            problem(review(f, token(f), passBody(f), "audit-fails", VersionEtags.format(f.version, 0)), 500, "PERSISTENCE_ERROR");
            unchanged(f);
        } finally {
            execute("DROP TRIGGER " + trigger + " ON document.document_audits");
            execute("DROP FUNCTION " + function + "()");
        }
        assertThat(review(f, token(f), passBody(f), "audit-fails", VersionEtags.format(f.version, 0)).statusCode()).isEqualTo(200);
    }

    @Test
    void snapshotEncodingFailure_shouldRollbackVersionAndLeaveKeyRetryable() throws Exception {
        var f = fixture();
        var etag = VersionEtags.format(f.version, 0);
        doThrow(new ReviewSnapshotSerializationException(new IllegalStateException("private serializer detail")))
                .when(snapshotCodec).encode(argThat(result -> result.versionId().equals(f.version)));
        try {
            var failed = review(f, token(f), passBody(f), "snapshot-fails", etag);
            problem(failed, 500, "PERSISTENCE_ERROR");
            assertThat(failed.body()).doesNotContain("private serializer detail");
            unchanged(f);
        } finally {
            reset(snapshotCodec);
        }
        var retry = review(f, token(f), passBody(f), "snapshot-fails", etag);
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        data(retry);
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo(1L);
    }

    @Test
    void concurrentReviews_shouldAllowOnlyOneTransition() throws Exception {
        var f = fixture();
        var request = new StaffDecisionCommand(ReviewAction.PASS, null, List.of(f.evidence));
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                String key = "concurrent-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timed out");
                    try { service.review(f.version, request, 0, key, f.context(), "race"); return 200; }
                    catch (ReviewException e) { return e.status(); }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(scalar("SELECT revision FROM document.document_versions WHERE document_version_id=?", f.version)).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo(1L);
    }

    @Test
    void concurrentIdenticalRequests_shouldReplayOnePersistedDecision() throws Exception {
        var f = fixture();
        var request = new StaffDecisionCommand(ReviewAction.PASS, null, List.of(f.evidence));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<StaffReviewResult> call = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.review(f.version, request, 0, "same-key", f.context(), "retry-race");
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo(1L);
    }

    @Test
    void jwtAuthorityClaimsCannotOverrideS01OrCombineRolesAcrossWorkspaces() throws Exception {
        var f = fixture();
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor, ReviewAuthorityTestServer.context(f.actor, f.tenant, List.of(
                ReviewAuthorityTestServer.grant(f.workspace, List.of("MANAGER"), List.of("DOCUMENT_FINAL_APPROVE"), List.of(f.repository)),
                ReviewAuthorityTestServer.grant(UUID.randomUUID(), List.of("STAFF"), List.of("DOCUMENT_STAFF_REVIEW"), List.of(f.repository)))));
        for (Map<String, Object> overrides : List.of(
                Map.<String, Object>of("workspace_grants", "not-a-list"),
                Map.<String, Object>of("tenant_id", "invalid-uuid"),
                Map.<String, Object>of("tenant_id", f.tenant.toString(), "tenant_active", true,
                        "workspace_grants", List.of(Map.of("workspace_id", f.workspace.toString(), "active", true,
                                "roles", List.of("STAFF"), "repository_ids", List.of(f.repository.toString())))),
                Map.<String, Object>of("workspace_grants", List.of(
                        Map.of("workspace_id", f.workspace.toString(), "active", true, "roles", List.of("MANAGER"),
                                "repository_ids", List.of(f.repository.toString())),
                        Map.of("workspace_id", UUID.randomUUID().toString(), "active", true, "roles", List.of("STAFF"),
                                "repository_ids", List.of(f.repository.toString())))))) {
            String jwt = token(f, overrides, KEY, ISSUER, "s04-test", Instant.now().plusSeconds(300));
            problem(review(f, jwt, passBody(f), "claims", VersionEtags.format(f.version, 0)), 403, "REVIEW_FORBIDDEN");
        }
        unchanged(f);
    }

    @Test
    void repositoryFreeDocument_usesWorkspaceStaffScopeWithoutDocumentAcl() throws Exception {
        var f = fixture();
        execute("UPDATE document.documents SET repository_connection_id=NULL WHERE document_id=?", f.document);
        String etag = VersionEtags.format(f.version, 0);
        for (String kind : List.of("workspace", "role", "inactive-workspace", "inactive-tenant")) {
            ReviewAuthorityTestServer.CONTEXTS.put(f.actor, ReviewAuthorityTestServer.context(f.actor,
                    kind.equals("inactive-tenant") ? null : f.tenant, kind.equals("inactive-workspace") ? List.of() : List.of(
                            ReviewAuthorityTestServer.grant(kind.equals("workspace") ? UUID.randomUUID() : f.workspace,
                                    List.of(kind.equals("role") ? "MANAGER" : "STAFF"), List.of("DOCUMENT_STAFF_REVIEW"), List.of()))));
            problem(review(f, token(f), passBody(f), kind, etag),
                    403, "REVIEW_FORBIDDEN");
            unchanged(f);
        }
        var foreignTenant = new Fixture(UUID.randomUUID(), f.workspace, f.repository, f.document, f.version, f.evidence, f.actor);
        ReviewAuthorityTestServer.allow(foreignTenant);
        problem(review(f, token(foreignTenant), passBody(f), "foreign-tenant", etag), 404, "DOCUMENT_VERSION_NOT_FOUND");
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor, ReviewAuthorityTestServer.context(f.actor, f.tenant, List.of(
                ReviewAuthorityTestServer.grant(f.workspace, List.of("STAFF"), List.of("DOCUMENT_STAFF_REVIEW"), List.of()))));
        var result = review(f, token(f), passBody(f), "document", etag);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(sourceValidator.calls(f.version)).isZero();
        assertThat(data(result).path("sourceBinding").isNull()).isTrue();
    }

    @Test
    void eachRequestIncludingReplayMustReloadAuthorityAndFailClosedWithoutWrites() throws Exception {
        var f = fixture();
        String jwt = token(f), etag = VersionEtags.format(f.version, 0);
        for (int status : List.of(401, 403, 500, 503, 302)) {
            ReviewAuthorityTestServer.STATUSES.put(f.actor, status);
            problem(review(f, jwt, passBody(f), "authority", etag), status == 401 ? 401 : status == 403 ? 403 : 503,
                    status == 401 ? "UNAUTHENTICATED" : status == 403 ? "REVIEW_FORBIDDEN" : "AUTHORIZATION_UNAVAILABLE");
            unchanged(f);
        }
        ReviewAuthorityTestServer.STATUSES.remove(f.actor);
        var data = ReviewAuthorityTestServer.context(UUID.randomUUID(), f.tenant, List.of());
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor, data);
        problem(review(f, jwt, passBody(f), "authority", etag), 503, "AUTHORIZATION_UNAVAILABLE");
        unchanged(f);
        ReviewAuthorityTestServer.allow(f);
        var first = review(f, jwt, passBody(f), "authority", etag);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        ReviewAuthorityTestServer.CONTEXTS.put(f.actor, ReviewAuthorityTestServer.context(f.actor, f.tenant, List.of()));
        problem(review(f, jwt, passBody(f), "authority", etag), 403, "REVIEW_FORBIDDEN");
        ReviewAuthorityTestServer.STATUSES.put(f.actor, 503);
        problem(review(f, jwt, passBody(f), "authority", etag), 503, "AUTHORIZATION_UNAVAILABLE");
        ReviewAuthorityTestServer.STATUSES.remove(f.actor);
        ReviewAuthorityTestServer.allow(f);
        assertThat(review(f, jwt, passBody(f), "authority", etag).body()).isEqualTo(first.body());
        assertThat(ReviewAuthorityTestServer.CALLS.get(f.actor)).isEqualTo(10);
        assertThat(scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version)).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version)).isEqualTo(1L);
    }

    @Test
    void changedSourceWithExistingEvidence_shouldBlockPassWithoutConsumingKey() throws Exception {
        var f = fixture();
        String jwt = token(f), etag = VersionEtags.format(f.version, 0);
        ReviewSourceValidationTestConfiguration.HEADS.put(f.repository, "b".repeat(40));
        problem(review(f, jwt, passBody(f), "source-change", etag), 409, "SOURCE_CHANGED_REVALIDATION_REQUIRED");
        unchanged(f);
        ReviewSourceValidationTestConfiguration.HEADS.put(f.repository, "a".repeat(40));
        assertThat(review(f, jwt, passBody(f), "source-change", etag).statusCode()).isEqualTo(200);
    }

    @Test
    void unknownExpiredAndWrongScopeValidation_shouldNotWriteStateDecisionOrAudit() throws Exception {
        var f = fixture();
        String jwt = token(f), etag = VersionEtags.format(f.version, 0);
        sourceValidator.overrides.put(f.version, version -> null);
        problem(review(f, jwt, passBody(f), "missing", etag), 503, "SOURCE_VALIDATION_UNAVAILABLE");
        unchanged(f);
        sourceValidator.overrides.put(f.version, version -> {
            var now = Instant.now();
            return new SourceReviewValidation("expired", f.tenant, f.workspace, f.version, 0, f.repository,
                    "a".repeat(40), now.minusSeconds(60), now.minusSeconds(1));
        });
        problem(review(f, jwt, passBody(f), "expired", etag), 503, "SOURCE_VALIDATION_UNAVAILABLE");
        unchanged(f);
        sourceValidator.overrides.put(f.version, version -> {
            var now = Instant.now();
            return new SourceReviewValidation("wrong-tenant", UUID.randomUUID(), f.workspace, f.version, 0, f.repository,
                    "a".repeat(40), now, now.plusSeconds(60));
        });
        problem(review(f, jwt, passBody(f), "wrong-scope", etag), 503, "SOURCE_VALIDATION_UNAVAILABLE");
        unchanged(f);
        sourceValidator.overrides.put(f.version, version -> { throw new IllegalStateException("upstream secret must not leak"); });
        var unavailable = review(f, jwt, passBody(f), "dependency-failed", etag);
        problem(unavailable, 503, "SOURCE_VALIDATION_UNAVAILABLE");
        assertThat(unavailable.body()).doesNotContain("upstream secret");
        unchanged(f);
        // RETURN does not promote the version and must remain available when source validation is down.
        var returned = review(f, jwt, "{\"decision\":\"RETURN_FOR_REVISION\",\"feedback\":\"Update source\"}", "return", etag);
        assertThat(returned.statusCode()).as(returned.body()).isEqualTo(200);
        assertThat(data(returned).path("state").asString()).isEqualTo("DRAFT");
        assertThat(sourceValidator.calls(f.version)).isEqualTo(4);
    }

    @Test
    void databaseConstraintsRlsAndAppendOnlyPrivileges_shouldHold() throws Exception {
        var versionColumn = com.livingdocs.document.entity.DocumentAuditEntity.class.getDeclaredField("versionId")
                .getAnnotation(jakarta.persistence.Column.class);
        assertThat(versionColumn.nullable()).isFalse();
        assertThat(scalar("SELECT is_nullable FROM information_schema.columns WHERE table_schema='document' AND table_name='document_audits' AND column_name='document_version_id'"))
                .isEqualTo("NO");
        var f = fixture();
        assertThatThrownBy(() -> execute("UPDATE document.document_versions SET review_stage=NULL WHERE document_version_id=?", f.version))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO document.review_decisions(tenant_id,document_version_id,reviewer_identity_id,decision,round_no) VALUES (?,?,?,'RETURN_FOR_REVISION',1)",
                f.tenant, f.version, f.actor)).isInstanceOf(SQLException.class);
        try (var connection = DriverManager.getConnection(databaseUrl(), "ld_s04_app", runtimePassword())) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                try (var rows = sql.executeQuery("SELECT count(*) FROM document.document_versions")) {
                    rows.next(); assertThat(rows.getLong(1)).isZero();
                }
                sql.execute("SELECT set_config('app.tenant_id', '" + f.tenant + "', true)");
                try (var rows = sql.executeQuery("SELECT count(*) FROM document.document_versions")) {
                    rows.next(); assertThat(rows.getLong(1)).isEqualTo(1);
                }
                assertThatThrownBy(() -> sql.execute("INSERT INTO document.document_collections(tenant_id,workspace_id,name) VALUES ('"
                        + UUID.randomUUID() + "','" + f.workspace + "','cross-tenant')")).isInstanceOf(SQLException.class);
                connection.rollback();
                sql.execute("SELECT set_config('app.tenant_id', '" + f.tenant + "', true)");
                connection.commit();
                try (var rows = sql.executeQuery("SELECT count(*) FROM document.document_versions")) {
                    rows.next(); assertThat(rows.getLong(1)).isZero();
                }
                assertThatThrownBy(() -> sql.execute("DELETE FROM document.review_decisions")).isInstanceOf(SQLException.class);
                connection.rollback();
                assertThatThrownBy(() -> sql.execute("UPDATE document.document_audits SET action='rewrite'")).isInstanceOf(SQLException.class);
                connection.rollback();
            }
        }
        assertThat(scalar("SELECT count(*) FROM document.flyway_schema_history WHERE success")).isEqualTo(4L);
        assertThat(scalar("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='document' AND c.relkind='r' AND c.relrowsecurity")).isEqualTo(13L);
    }
}
