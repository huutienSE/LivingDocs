package com.livingdocs.document;

import com.livingdocs.document.api.controller.VersionEtags;
import com.livingdocs.document.domain.model.*;
import com.livingdocs.document.domain.ReviewException;
import com.livingdocs.document.api.request.ReviewRequest;
import com.livingdocs.document.security.ActorContext;
import com.livingdocs.document.application.service.DocumentReviewService;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.URI;
import java.net.http.*;
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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReviewSourceValidationTestConfiguration.class)
class ReviewApiIntegrationTest extends PostgresTestSupport {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired DocumentReviewService service;
    @Autowired ReviewSourceValidationTestConfiguration.TestValidator sourceValidator;
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
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(expires))
                .claim("tenant_id", f.tenant.toString()).claim("tenant_active", true)
                .claim("workspace_grants", List.of(Map.of("workspace_id", f.workspace.toString(), "active", true,
                        "roles", List.of("STAFF"), "repository_ids", List.of(f.repository.toString()))));
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
    void pass_shouldPersistDecisionAuditAndNewEtag() throws Exception {
        var f = fixture();
        var result = review(f, token(f), passBody(f), "pass", VersionEtags.format(f.version, 0));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(body(result).path("documentId").asString()).isEqualTo(f.document.toString());
        assertThat(body(result).path("sourceBinding").path("repositoryConnectionId").asString()).isEqualTo(f.repository.toString());
        assertThat(body(result).path("sourceBinding").path("headSha").asString()).isEqualTo("a".repeat(40));
        assertThat(body(result).path("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(body(result).path("reviewStage").asString()).isEqualTo("MANAGER_APPROVAL");
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
        assertThat(body(result).path("state").asString()).isEqualTo("DRAFT");
        assertThat(body(result).path("reviewStage").isNull()).isTrue();
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
        assertThatThrownBy(() -> new ReviewRequest(ReviewAction.PASS, null, Collections.nCopies(101, f.evidence)))
                .isInstanceOfSatisfying(ReviewException.class, e -> assertThat(e.code()).isEqualTo("EVIDENCE_LIMIT_EXCEEDED"));
        assertThatThrownBy(() -> new ReviewRequest(ReviewAction.PASS, null, List.of(f.evidence, f.evidence)))
                .isInstanceOfSatisfying(ReviewException.class, e -> assertThat(e.code()).isEqualTo("DUPLICATE_EVIDENCE"));
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
        problem(review(f, token(otherTenant), passBody(f), "tenant", etag), 404, "DOCUMENT_VERSION_NOT_FOUND");
        for (String kind : List.of("workspace", "role", "repository", "inactive-workspace", "inactive-tenant")) {
            var overrides = new HashMap<String, Object>();
            overrides.put("tenant_active", !kind.equals("inactive-tenant"));
            overrides.put("workspace_grants", List.of(Map.of(
                    "workspace_id", (kind.equals("workspace") ? UUID.randomUUID() : f.workspace).toString(),
                    "active", !kind.equals("inactive-workspace"),
                    "roles", List.of(kind.equals("role") ? "TENANT_OWNER" : "STAFF"),
                    "repository_ids", List.of((kind.equals("repository") ? UUID.randomUUID() : f.repository).toString()))));
            String jwt = token(f, overrides, KEY, ISSUER, "s04-test", Instant.now().plusSeconds(300));
            problem(review(f, jwt, passBody(f), kind, etag), 403, "REVIEW_FORBIDDEN");
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
    void concurrentReviews_shouldAllowOnlyOneTransition() throws Exception {
        var f = fixture();
        var request = new ReviewRequest(ReviewAction.PASS, null, List.of(f.evidence));
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
        var request = new ReviewRequest(ReviewAction.PASS, null, List.of(f.evidence));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<com.livingdocs.document.api.response.ReviewResponse> call = () -> {
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
    void malformedClaimsAndStaffRoleFromAnotherWorkspace_shouldNotGrantAccess() throws Exception {
        var f = fixture();
        for (Map<String, Object> overrides : List.of(
                Map.<String, Object>of("workspace_grants", "not-a-list"),
                Map.<String, Object>of("tenant_id", "invalid-uuid"),
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
            var denied = Map.<String, Object>of("tenant_active", !kind.equals("inactive-tenant"), "workspace_grants", List.of(Map.of(
                    "workspace_id", (kind.equals("workspace") ? UUID.randomUUID() : f.workspace).toString(),
                    "active", !kind.equals("inactive-workspace"), "roles", List.of(kind.equals("role") ? "MANAGER" : "STAFF"),
                    "document_ids", List.of(f.document.toString()))));
            problem(review(f, token(f, denied, KEY, ISSUER, "s04-test", Instant.now().plusSeconds(300)), passBody(f), kind, etag),
                    403, "REVIEW_FORBIDDEN");
            unchanged(f);
        }
        var foreignTenant = new Fixture(UUID.randomUUID(), f.workspace, f.repository, f.document, f.version, f.evidence, f.actor);
        problem(review(f, token(foreignTenant), passBody(f), "foreign-tenant", etag), 404, "DOCUMENT_VERSION_NOT_FOUND");
        var overrides = Map.<String, Object>of("workspace_grants", List.of(Map.of(
                "workspace_id", f.workspace.toString(), "active", true, "roles", List.of("STAFF"))));
        var result = review(f, token(f, overrides, KEY, ISSUER, "s04-test", Instant.now().plusSeconds(300)), passBody(f), "document", etag);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(sourceValidator.calls(f.version)).isZero();
        assertThat(body(result).path("sourceBinding").isNull()).isTrue();
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
        assertThat(body(returned).path("state").asString()).isEqualTo("DRAFT");
        assertThat(sourceValidator.calls(f.version)).isEqualTo(4);
    }

    @Test
    void databaseConstraintsRlsAndAppendOnlyPrivileges_shouldHold() throws Exception {
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
        assertThat(scalar("SELECT count(*) FROM document.flyway_schema_history WHERE success")).isEqualTo(3L);
        assertThat(scalar("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='document' AND c.relkind='r' AND c.relrowsecurity")).isEqualTo(12L);
    }
}
