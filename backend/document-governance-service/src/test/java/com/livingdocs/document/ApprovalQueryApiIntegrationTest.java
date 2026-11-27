package com.livingdocs.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.livingdocs.document.controller.VersionEtags;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API-122/123 over HTTP against real PostgreSQL (Flyway + RLS), a real JWT signature check and the S01 API-038 stub.
 * Every scenario uses its own random tenant so tests stay independent on a shared database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true", "livingdocs.approval.queue.max-page-size=50"})
@Import(ReviewSourceValidationTestConfiguration.class)
class ApprovalQueryApiIntegrationTest extends PostgresTestSupport {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    final HttpClient http = HttpClient.newHttpClient();

    /** One tenant with a Manager granted on (workspace, repository). */
    record World(UUID tenant, UUID workspace, UUID repository, UUID collection, UUID manager) {}

    World world() throws Exception {
        var w = new World(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        collection(w.tenant, w.collection, w.workspace);
        grantManager(w.manager, w.tenant, w.workspace, List.of(w.repository));
        return w;
    }

    static void collection(UUID tenant, UUID collection, UUID workspace) throws Exception {
        ReviewApiIntegrationTest.execute("INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES (?,?,?,'Approval tests')",
                collection, tenant, workspace);
    }

    static void grantManager(UUID identity, UUID tenant, UUID workspace, List<UUID> repos) {
        ReviewAuthorityTestServer.CONTEXTS.put(identity, ReviewAuthorityTestServer.context(identity, tenant, List.of(
                ReviewAuthorityTestServer.grant(workspace, List.of("MANAGER"),
                        List.of("WORKSPACE_VIEW", "DOCUMENT_READ", "DOCUMENT_FINAL_APPROVE", "DOCUMENT_PUBLISH"), repos))));
    }

    /** Inserts document + version (+ optional Staff PASS at passedAt) and returns the version id. */
    static UUID version(UUID tenant, UUID collection, UUID repository, String title, String state, String stage,
                        Instant passedAt) throws Exception {
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        ReviewApiIntegrationTest.execute("INSERT INTO document.documents(document_id,tenant_id,collection_id,repository_connection_id,title,ownership_ref) VALUES (?,?,?,?,?,'test')",
                document, tenant, collection, repository, title);
        ReviewApiIntegrationTest.execute("INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,review_stage,source_sha,creator_actor_ref,revision) VALUES (?,?,?,1,?,?,?,'test-creator',2)",
                version, tenant, document, state, stage, repository == null ? null : "b".repeat(40));
        if (passedAt != null) {
            ReviewApiIntegrationTest.execute("INSERT INTO document.review_decisions(tenant_id,document_version_id,reviewer_identity_id,decision,decided_at,round_no) VALUES (?,?,?,'PASS',?,1)",
                    tenant, version, UUID.randomUUID(), Timestamp.from(passedAt));
        }
        return version;
    }

    String token(UUID subject) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject.toString()).issuer(ISSUER).audience("s04-test")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    HttpResponse<String> get(String path, UUID actor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json").header("X-Correlation-ID", "approval-test").GET();
        if (actor != null) request.header("Authorization", "Bearer " + token(actor));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode data(HttpResponse<String> response, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var envelope = json.readTree(response.body());
        assertThat(envelope.propertyNames()).containsExactlyInAnyOrder("code", "message", "data");
        assertThat(envelope.path("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return envelope.path("data");
    }

    void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        var problem = json.readTree(response.body());
        assertThat(problem.path("code").asString()).isEqualTo(code);
        assertThat(problem.path("correlationId").asString()).isEqualTo("approval-test");
        assertThat(response.body()).doesNotContain("SELECT").doesNotContain("document_versions");
    }

    List<String> ids(JsonNode items) {
        var result = new ArrayList<String>();
        items.forEach(i -> result.add(i.path("versionId").asString()));
        return result;
    }

    // ---------------------------------------------------------------- API-122 approval queue

    @Test
    void queue_listsOnlyManagerApprovalVersionsInsideScope_oldestPassFirst() throws Exception {
        var w = world();
        Instant now = Instant.now();
        UUID newer = version(w.tenant, w.collection, w.repository, "Newer", "IN_REVIEW", "MANAGER_APPROVAL", now.minusSeconds(60));
        UUID older = version(w.tenant, w.collection, w.repository, "Older", "IN_REVIEW", "MANAGER_APPROVAL", now.minusSeconds(3600));
        UUID repoFree = version(w.tenant, w.collection, null, "Repository free", "IN_REVIEW", "MANAGER_APPROVAL", now.minusSeconds(30));
        // Excluded: still at Staff Review, already approved, ungranted repository, other workspace, other tenant.
        version(w.tenant, w.collection, w.repository, "Staff", "IN_REVIEW", "STAFF_REVIEW", null);
        version(w.tenant, w.collection, w.repository, "Approved", "APPROVED", null, now.minusSeconds(7200));
        version(w.tenant, w.collection, UUID.randomUUID(), "Other repo", "IN_REVIEW", "MANAGER_APPROVAL", now.minusSeconds(10));
        UUID otherCollection = UUID.randomUUID();
        collection(w.tenant, otherCollection, UUID.randomUUID());
        version(w.tenant, otherCollection, w.repository, "Other workspace", "IN_REVIEW", "MANAGER_APPROVAL", now.minusSeconds(10));
        UUID otherTenant = UUID.randomUUID();
        UUID otherTenantCollection = UUID.randomUUID();
        collection(otherTenant, otherTenantCollection, w.workspace);
        version(otherTenant, otherTenantCollection, w.repository, "Other tenant", "IN_REVIEW", "MANAGER_APPROVAL", now.minusSeconds(10));

        var data = data(get("/api/v1/approval-queue", w.manager), "APPROVAL_QUEUE_LOADED");

        assertThat(ids(data.path("items"))).containsExactly(older.toString(), newer.toString(), repoFree.toString());
        assertThat(data.path("totalElements").asLong()).isEqualTo(3);
        assertThat(data.path("totalPages").asInt()).isEqualTo(1);
        assertThat(data.path("page").asInt()).isZero();
        assertThat(data.path("size").asInt()).isEqualTo(20);
        var first = data.path("items").get(0);
        assertThat(first.path("documentTitle").asString()).isEqualTo("Older");
        assertThat(first.path("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(first.path("reviewStage").asString()).isEqualTo("MANAGER_APPROVAL");
        assertThat(first.path("workspaceId").asString()).isEqualTo(w.workspace.toString());
        assertThat(first.path("repositoryConnectionId").asString()).isEqualTo(w.repository.toString());
        assertThat(first.path("revision").asLong()).isEqualTo(2);
        assertThat(first.path("staffPassedAt").isNull()).isFalse();
        assertThat(data.path("items").get(2).path("repositoryConnectionId").isNull()).isTrue();
    }

    @Test
    void queue_paginatesAndBoundaryValues() throws Exception {
        var w = world();
        Instant now = Instant.now();
        UUID a = version(w.tenant, w.collection, w.repository, "A", "IN_REVIEW", "MANAGER_APPROVAL", now.minusSeconds(300));
        UUID b = version(w.tenant, w.collection, w.repository, "B", "IN_REVIEW", "MANAGER_APPROVAL", now.minusSeconds(200));
        UUID c = version(w.tenant, w.collection, w.repository, "C", "IN_REVIEW", "MANAGER_APPROVAL", now.minusSeconds(100));

        var page1 = data(get("/api/v1/approval-queue?page=1&size=2", w.manager), "APPROVAL_QUEUE_LOADED");
        assertThat(ids(page1.path("items"))).containsExactly(c.toString());
        assertThat(page1.path("totalPages").asInt()).isEqualTo(2);
        var page0 = data(get("/api/v1/approval-queue?page=0&size=2", w.manager), "APPROVAL_QUEUE_LOADED");
        assertThat(ids(page0.path("items"))).containsExactly(a.toString(), b.toString());
        var beyond = data(get("/api/v1/approval-queue?page=9&size=2", w.manager), "APPROVAL_QUEUE_LOADED");
        assertThat(beyond.path("items").size()).isZero();
        assertThat(beyond.path("totalElements").asLong()).isEqualTo(3);
        // BVA: size min 1 and configured max 50 are accepted; just outside is rejected.
        data(get("/api/v1/approval-queue?size=1", w.manager), "APPROVAL_QUEUE_LOADED");
        data(get("/api/v1/approval-queue?size=50", w.manager), "APPROVAL_QUEUE_LOADED");
        problem(get("/api/v1/approval-queue?size=0", w.manager), 400, "INVALID_REQUEST");
        problem(get("/api/v1/approval-queue?size=51", w.manager), 400, "INVALID_REQUEST");
        problem(get("/api/v1/approval-queue?page=-1", w.manager), 400, "INVALID_REQUEST");
        problem(get("/api/v1/approval-queue?page=abc", w.manager), 400, "INVALID_REQUEST");
    }

    @Test
    void queue_emptyForManagerWithNothingWaiting() throws Exception {
        var w = world();
        var data = data(get("/api/v1/approval-queue", w.manager), "APPROVAL_QUEUE_LOADED");
        assertThat(data.path("items").size()).isZero();
        assertThat(data.path("totalElements").asLong()).isZero();
        assertThat(data.path("totalPages").asInt()).isZero();
    }

    @Test
    void queue_rejectsMissingTokenNonManagerAndUnavailableAuthority() throws Exception {
        var w = world();
        version(w.tenant, w.collection, w.repository, "Waiting", "IN_REVIEW", "MANAGER_APPROVAL", Instant.now());
        var unauthenticated = get("/api/v1/approval-queue", null);
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        assertThat(unauthenticated.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer");

        UUID staff = UUID.randomUUID();
        ReviewAuthorityTestServer.CONTEXTS.put(staff, ReviewAuthorityTestServer.context(staff, w.tenant, List.of(
                ReviewAuthorityTestServer.grant(w.workspace, List.of("STAFF"), List.of("DOCUMENT_STAFF_REVIEW"), List.of(w.repository)))));
        problem(get("/api/v1/approval-queue", staff), 403, "APPROVAL_FORBIDDEN");

        // MANAGER role without DOCUMENT_FINAL_APPROVE is not enough.
        UUID managerWithoutPermission = UUID.randomUUID();
        ReviewAuthorityTestServer.CONTEXTS.put(managerWithoutPermission, ReviewAuthorityTestServer.context(managerWithoutPermission,
                w.tenant, List.of(ReviewAuthorityTestServer.grant(w.workspace, List.of("MANAGER"), List.of("DOCUMENT_READ"), List.of(w.repository)))));
        problem(get("/api/v1/approval-queue", managerWithoutPermission), 403, "APPROVAL_FORBIDDEN");

        UUID down = UUID.randomUUID();
        grantManager(down, w.tenant, w.workspace, List.of(w.repository));
        ReviewAuthorityTestServer.STATUSES.put(down, 503);
        problem(get("/api/v1/approval-queue", down), 503, "AUTHORIZATION_UNAVAILABLE");

        UUID revoked = UUID.randomUUID();
        ReviewAuthorityTestServer.STATUSES.put(revoked, 403);
        problem(get("/api/v1/approval-queue", revoked), 403, "APPROVAL_FORBIDDEN");
    }

    // ---------------------------------------------------------------- API-123 approval context

    @Test
    void context_returnsVersionHistoryEvidenceAndEtag() throws Exception {
        var w = world();
        Instant passed = Instant.now().minusSeconds(120);
        UUID version = version(w.tenant, w.collection, w.repository, "Payment API", "IN_REVIEW", "MANAGER_APPROVAL", passed);
        UUID editor = UUID.randomUUID();
        ReviewApiIntegrationTest.execute("INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,editor_identity_id) VALUES (?,?,1,'test://v1',?)",
                w.tenant, version, editor);
        ReviewApiIntegrationTest.execute("INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,editor_identity_id) VALUES (?,?,2,'test://v2',?)",
                w.tenant, version, editor);
        UUID evidence = UUID.randomUUID();
        ReviewApiIntegrationTest.execute("INSERT INTO document.document_evidence_refs(document_evidence_id,tenant_id,document_version_id,evidence_type,source_ref) VALUES (?,?,?,'SOURCE','test://source')",
                evidence, w.tenant, version);
        ReviewApiIntegrationTest.execute("INSERT INTO document.approval_decisions(tenant_id,document_version_id,approver_identity_id,decision,reason,round_no) VALUES (?,?,?,'RETURN_TO_STAFF','Need clearer error table',1)",
                w.tenant, version, UUID.randomUUID());

        var response = get("/api/v1/document-versions/" + version + "/approval-context", w.manager);
        var data = data(response, "APPROVAL_CONTEXT_LOADED");

        assertThat(response.headers().firstValue("ETag").orElse("")).isEqualTo(VersionEtags.format(version, 2));
        assertThat(data.path("awaitingManagerDecision").asBoolean()).isTrue();
        assertThat(data.path("documentTitle").asString()).isEqualTo("Payment API");
        assertThat(data.path("versionNo").asInt()).isEqualTo(1);
        assertThat(data.path("workspaceId").asString()).isEqualTo(w.workspace.toString());
        var v = data.path("version");
        assertThat(v.path("versionId").asString()).isEqualTo(version.toString());
        assertThat(v.path("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(v.path("reviewStage").asString()).isEqualTo("MANAGER_APPROVAL");
        assertThat(v.path("revision").asLong()).isEqualTo(2);
        assertThat(v.path("sourceBinding").path("repositoryConnectionId").asString()).isEqualTo(w.repository.toString());
        assertThat(v.path("sourceBinding").path("headSha").asString()).isEqualTo("b".repeat(40));
        assertThat(data.path("currentContent").path("revisionNo").asInt()).isEqualTo(2);
        assertThat(data.path("currentContent").path("contentRef").asString()).isEqualTo("test://v2");
        assertThat(data.path("currentContent").path("editorIdentityId").asString()).isEqualTo(editor.toString());
        assertThat(data.path("staffDecisions").size()).isEqualTo(1);
        assertThat(data.path("staffDecisions").get(0).path("decision").asString()).isEqualTo("PASS");
        assertThat(data.path("approvalDecisions").get(0).path("decision").asString()).isEqualTo("RETURN_TO_STAFF");
        assertThat(data.path("approvalDecisions").get(0).path("reason").asString()).isEqualTo("Need clearer error table");
        assertThat(data.path("evidence").get(0).path("evidenceId").asString()).isEqualTo(evidence.toString());
        assertThat(data.path("evidence").get(0).path("evidenceType").asString()).isEqualTo("SOURCE");
    }

    @Test
    void context_ofVersionNotAtManagerStage_isReadableButNotAwaiting() throws Exception {
        var w = world();
        UUID staffStage = version(w.tenant, w.collection, w.repository, "Staff", "IN_REVIEW", "STAFF_REVIEW", null);
        UUID approved = version(w.tenant, w.collection, null, "Approved", "APPROVED", null, Instant.now());
        var staff = data(get("/api/v1/document-versions/" + staffStage + "/approval-context", w.manager), "APPROVAL_CONTEXT_LOADED");
        assertThat(staff.path("awaitingManagerDecision").asBoolean()).isFalse();
        assertThat(staff.path("currentContent").isNull()).isTrue();
        assertThat(staff.path("staffDecisions").size()).isZero();
        var done = data(get("/api/v1/document-versions/" + approved + "/approval-context", w.manager), "APPROVAL_CONTEXT_LOADED");
        assertThat(done.path("awaitingManagerDecision").asBoolean()).isFalse();
        assertThat(done.path("version").path("reviewStage").isNull()).isTrue();
        assertThat(done.path("version").path("sourceBinding").isNull()).isTrue();
    }

    @Test
    void context_deniesOutsideScopeAndUnknownVersions() throws Exception {
        var w = world();
        // Same tenant, other workspace → 403; ungranted repository → 403.
        UUID otherCollection = UUID.randomUUID();
        collection(w.tenant, otherCollection, UUID.randomUUID());
        UUID otherWorkspace = version(w.tenant, otherCollection, w.repository, "WS", "IN_REVIEW", "MANAGER_APPROVAL", Instant.now());
        UUID otherRepo = version(w.tenant, w.collection, UUID.randomUUID(), "Repo", "IN_REVIEW", "MANAGER_APPROVAL", Instant.now());
        problem(get("/api/v1/document-versions/" + otherWorkspace + "/approval-context", w.manager), 403, "APPROVAL_FORBIDDEN");
        problem(get("/api/v1/document-versions/" + otherRepo + "/approval-context", w.manager), 403, "APPROVAL_FORBIDDEN");
        // Other tenant (even with the same workspace id) → 404, no existence leak; unknown id → 404.
        UUID otherTenant = UUID.randomUUID();
        UUID otherTenantCollection = UUID.randomUUID();
        collection(otherTenant, otherTenantCollection, w.workspace);
        UUID crossTenant = version(otherTenant, otherTenantCollection, w.repository, "Tenant", "IN_REVIEW", "MANAGER_APPROVAL", Instant.now());
        problem(get("/api/v1/document-versions/" + crossTenant + "/approval-context", w.manager), 404, "DOCUMENT_VERSION_NOT_FOUND");
        problem(get("/api/v1/document-versions/" + UUID.randomUUID() + "/approval-context", w.manager), 404, "DOCUMENT_VERSION_NOT_FOUND");
        problem(get("/api/v1/document-versions/not-a-uuid/approval-context", w.manager), 400, "INVALID_REQUEST");
        assertThat(get("/api/v1/document-versions/" + otherRepo + "/approval-context", null).statusCode()).isEqualTo(401);
    }

    @Test
    void readsNeverMutateStateDecisionsOrAudit() throws Exception {
        var w = world();
        UUID version = version(w.tenant, w.collection, w.repository, "Read only", "IN_REVIEW", "MANAGER_APPROVAL", Instant.now());
        String before = snapshot(version);
        data(get("/api/v1/approval-queue", w.manager), "APPROVAL_QUEUE_LOADED");
        data(get("/api/v1/document-versions/" + version + "/approval-context", w.manager), "APPROVAL_CONTEXT_LOADED");
        UUID outsider = UUID.randomUUID();
        grantManager(outsider, w.tenant, UUID.randomUUID(), List.of());
        get("/api/v1/document-versions/" + version + "/approval-context", outsider);
        assertThat(snapshot(version)).isEqualTo(before);
    }

    String snapshot(UUID version) throws Exception {
        return ReviewApiIntegrationTest.scalar("""
                SELECT v.state || '|' || coalesce(v.review_stage,'') || '|' || v.revision
                  || '|' || (SELECT count(*) FROM document.review_decisions r WHERE r.document_version_id = v.document_version_id)
                  || '|' || (SELECT count(*) FROM document.approval_decisions a WHERE a.document_version_id = v.document_version_id)
                  || '|' || (SELECT count(*) FROM document.document_audits a WHERE a.document_version_id = v.document_version_id)
                FROM document.document_versions v WHERE v.document_version_id = ?""", version).toString();
    }

    // ---------------------------------------------------------------- Contract parity

    @Test
    void runtimeOpenApi_matchesStaticContractForApprovalReads() throws Exception {
        var w = world();
        var runtime = json.readTree(get("/v3/api-docs", w.manager).body());
        // API-113 precedent: implemented S04 operations are described with x-api-id in s04-document.yaml.
        // The inventory spec reuses many YAML aliases (*id001); it is a trusted repository file.
        var options = new LoaderOptions();
        options.setMaxAliasesForCollections(1000);
        var reviewed = json.readTree(json.writeValueAsString(new Yaml(options).load(
                Files.readString(Path.of("../../contracts/openapi/s04-document.yaml")))));
        for (var op : List.of(Map.entry("/api/v1/approval-queue", "getApprovalQueue"),
                Map.entry("/api/v1/document-versions/{versionId}/approval-context", "getApprovalContext"))) {
            var actual = runtime.path("paths").path(op.getKey()).path("get");
            var expected = reviewed.path("paths").path(op.getKey()).path("get");
            assertThat(actual.path("operationId").asString()).as(op.getKey()).isEqualTo(op.getValue());
            assertThat(expected.path("operationId").asString()).as(op.getKey()).isEqualTo(op.getValue());
            assertThat(actual.path("responses").propertyNames())
                    .containsExactlyInAnyOrderElementsOf(expected.path("responses").propertyNames());
        }
        for (String schema : List.of("ApprovalQueueView", "ApprovalQueueItem", "ApprovalContextView",
                "ApprovalContent", "ApprovalStaffDecision", "ApprovalManagerDecision", "ApprovalEvidence")) {
            var actual = runtime.path("components").path("schemas").path(schema);
            var expected = reviewed.path("components").path("schemas").path(schema);
            assertThat(actual.path("properties").propertyNames()).as(schema)
                    .containsExactlyInAnyOrderElementsOf(expected.path("properties").propertyNames());
            assertThat(actual.path("required").valueStream().map(JsonNode::asString).toList()).as(schema)
                    .containsExactlyInAnyOrderElementsOf(expected.path("required").valueStream().map(JsonNode::asString).toList());
        }
    }
}
