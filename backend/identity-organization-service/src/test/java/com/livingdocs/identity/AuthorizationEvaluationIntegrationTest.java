package com.livingdocs.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.identity.security.JwtTokenIssuer;
import com.livingdocs.identity.security.ServiceJwtIssuer;
import com.livingdocs.identity.security.JwtKeyManager;
import com.livingdocs.identity.security.TenantContext;
import com.livingdocs.identity.service.RepositoryBindingClient;
import com.livingdocs.identity.dto.request.ServiceTokenRequest;
import com.livingdocs.identity.exception.GlobalExceptionHandler;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.livingdocs.identity.service.RegistrationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.when;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("db") @Testcontainers
class AuthorizationEvaluationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("livingdocs").withUsername("postgres").withPassword("postgres")
            .withInitScript("s01-test-schema.sql");
    private static final Path[] KEYS = keys();
    private static final Path REGISTRY = registry();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("DB_URL", DB::getJdbcUrl);
        p.add("DB_SCHEMA", () -> "identity");
        p.add("DB_USERNAME", () -> "ld_s01_app");
        p.add("DB_PASSWORD", () -> "test_app_password");
        p.add("FLYWAY_USERNAME", DB::getUsername);
        p.add("FLYWAY_PASSWORD", DB::getPassword);
        p.add("livingdocs.security.jwt.private-key-path", () -> KEYS[0].toString());
        p.add("livingdocs.security.jwt.public-key-path", () -> KEYS[1].toString());
        p.add("livingdocs.security.workloads.registry-path", () -> REGISTRY.toString());
        p.add("livingdocs.security.workloads.allow-insecure-transport", () -> "true");
    }
    @Autowired MockMvc mvc;
    @Autowired JwtTokenIssuer users;
    @Autowired ServiceJwtIssuer serviceTokens;
    @Autowired JwtKeyManager signingKeys;
    @Autowired TenantContext tenantContext;
    @Autowired JdbcTemplate runtimeJdbc;
    @Autowired GlobalExceptionHandler errors;
    @Autowired RegistrationService registration;
    @MockitoBean RepositoryBindingClient binding;
    private final ObjectMapper json = new ObjectMapper();

    @Test void developerConnectAndRevocationAreFreshAndScoped() throws Exception {
        UUID actor = registration.registerLocal("pr2-" + UUID.randomUUID() + "@example.com", "A-secure-password-123");
        UUID tenant = UUID.randomUUID(), workspace = UUID.randomUUID(), other = UUID.randomUUID();
        String hash = new BCryptPasswordEncoder().encode("test-workload-secret");
        Files.writeString(REGISTRY, "[{\"id\":\"s02\",\"secretHash\":\"" + hash
                + "\",\"audiences\":[\"livingdocs-s01-internal\"],\"operations\":[\"authorization:evaluate\"],\"tenantIds\":[\"" + tenant + "\"]},"
                + "{\"id\":\"s04\",\"secretHash\":\"" + hash + "\",\"audiences\":[\"livingdocs-s01-internal\"],\"operations\":[\"authorization:evaluate\"],\"tenantIds\":[\"" + tenant + "\"]}]", StandardCharsets.UTF_8);
        UUID tm = UUID.randomUUID(), wm = UUID.randomUUID();
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), "postgres", "postgres"));
        admin.update("INSERT INTO identity.tenants(tenant_id,name) VALUES (?, 'A')", tenant);
        admin.update("INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES (?,?,?)", tm, tenant, actor);
        admin.update("INSERT INTO identity.workspaces(workspace_id,tenant_id,name) VALUES (?,?,'A')", workspace, tenant);
        admin.update("INSERT INTO identity.workspaces(workspace_id,tenant_id,name) VALUES (?,?,'B')", other, tenant);
        admin.update("INSERT INTO identity.workspace_memberships(workspace_membership_id,tenant_id,workspace_id,tenant_membership_id,identity_id) VALUES (?,?,?,?,?)", wm, tenant, workspace, tm, actor);
        admin.update("INSERT INTO identity.workspace_role_assignments(tenant_id,workspace_id,workspace_membership_id,role_code) VALUES (?,?,?,'DEVELOPER')", tenant, workspace, wm);
        String user = users.issue(actor);
        String assertion = issue(user, tenant, workspace);
        String body = body(tenant, workspace);
        mvc.perform(post("/internal/v1/authorization/evaluate").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + user).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value("ALLOW"));
        admin.update("UPDATE identity.workspace_role_assignments SET status='REVOKED', ended_at=now() WHERE workspace_membership_id=?", wm);
        evaluate(user, assertion, body, "DENY");
        admin.update("UPDATE identity.workspace_role_assignments SET status='ACTIVE', ended_at=NULL WHERE workspace_membership_id=?", wm);
        admin.update("UPDATE identity.workspace_role_assignments SET role_code='STAFF' WHERE workspace_membership_id=?", wm);
        evaluate(user, assertion, body, "DENY");
        admin.update("UPDATE identity.workspace_role_assignments SET role_code='TECHNICAL_LEAD' WHERE workspace_membership_id=?", wm);
        evaluate(user, assertion, body, "ALLOW");
        admin.update("UPDATE identity.workspace_role_assignments SET role_code='DEVELOPER' WHERE workspace_membership_id=?", wm);
        String anotherUser = users.issue(registration.registerLocal("other-" + UUID.randomUUID() + "@example.com", "A-secure-password-123"));
        mvc.perform(post("/internal/v1/authorization/evaluate").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + anotherUser).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/authorization/evaluate").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + assertion).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/authorization/evaluate").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + user).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/auth/service-tokens").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("s02:wrong-secret".getBytes(StandardCharsets.UTF_8)))
                .content("{\"audience\":\"livingdocs-s01-internal\",\"operation\":\"authorization:evaluate\",\"method\":\"POST\",\"path\":\"/internal/v1/authorization/evaluate\",\"tenantId\":\"" + tenant + "\",\"workspaceId\":\"" + workspace + "\",\"action\":\"REPOSITORY_CONNECT\",\"actorTokenSha256\":\"" + "0".repeat(64) + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/authorization/evaluate").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + user).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(body(tenant, other))).andExpect(status().isUnauthorized());
        String documentWithoutRepository = body.replace("REPOSITORY_CONNECT", "DOCUMENT_AUTHOR");
        mvc.perform(post("/internal/v1/authorization/evaluate").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + user).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(documentWithoutRepository)).andExpect(status().isUnauthorized());
        String userHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(user.getBytes(StandardCharsets.US_ASCII)));
        mvc.perform(post("/internal/v1/auth/service-tokens").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("s04:test-workload-secret".getBytes(StandardCharsets.UTF_8)))
                .content("{\"audience\":\"livingdocs-s01-internal\",\"operation\":\"authorization:evaluate\",\"method\":\"POST\",\"path\":\"/internal/v1/authorization/evaluate\",\"tenantId\":\"" + tenant + "\",\"workspaceId\":\"" + workspace + "\",\"action\":\"DOCUMENT_AUTHOR\",\"actorTokenSha256\":\"" + userHash + "\"}"))
                .andExpect(status().isForbidden());
        UUID otherTenant = UUID.randomUUID();
        String otherTenantBody = body(otherTenant, workspace);
        evaluate(user, customAssertion(user, otherTenant, workspace, "urn:livingdocs:s01", "service", Instant.now(), Instant.now().plusSeconds(30)), otherTenantBody, "DENY");
        admin.update("UPDATE identity.workspace_memberships SET status='REVOKED', ended_at=now() WHERE workspace_membership_id=?", wm);
        mvc.perform(post("/internal/v1/authorization/evaluate").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + user).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value("DENY"));
        admin.update("UPDATE identity.workspace_memberships SET status='ACTIVE', ended_at=NULL WHERE workspace_membership_id=?", wm);
        admin.update("UPDATE identity.workspaces SET status='ARCHIVED' WHERE workspace_id=?", workspace);
        evaluate(user, assertion, body, "DENY");
        admin.update("UPDATE identity.workspaces SET status='ACTIVE' WHERE workspace_id=?", workspace);
        admin.update("UPDATE identity.tenant_memberships SET status='REVOKED', ended_at=now() WHERE membership_id=?", tm);
        evaluate(user, assertion, body, "DENY");
        admin.update("UPDATE identity.tenant_memberships SET status='ACTIVE', ended_at=NULL WHERE membership_id=?", tm);
        UUID repository = UUID.randomUUID();
        String repoBody = documentBody(tenant, workspace, repository, "DOCUMENT_AUTHOR");
        String repoAssertion = issue(user, tenant, workspace, repository);
        evaluate(user, repoAssertion, repoBody, "DENY");
        admin.update("INSERT INTO identity.repository_grants(tenant_id,workspace_id,workspace_membership_id,repository_connection_id,provenance,operator_identity_id) VALUES (?,?,?,?,?,?)",
                tenant, workspace, wm, repository, "Requested by a real technical lead", actor);
        evaluate(user, repoAssertion, repoBody, "DENY");
        // Test-only authoritative fixture: production has no PR2 verification mutation.
        admin.update("UPDATE identity.repository_grants SET verification_status='VERIFIED', verification_source='S02', verified_at=now(), verified_binding_revision=1 WHERE repository_connection_id=?", repository);
        when(binding.lookup(tenant, workspace, repository)).thenAnswer(ignored ->
                new RepositoryBindingClient.BindingEvidence(tenant, workspace, repository, "ACTIVE", 1, Instant.now()));
        evaluate(user, repoAssertion, repoBody, "ALLOW");
        String staffBody = documentBody(tenant, workspace, repository, "DOCUMENT_STAFF_REVIEW");
        String finalBody = documentBody(tenant, workspace, repository, "DOCUMENT_FINAL_APPROVE");
        String publishBody = documentBody(tenant, workspace, repository, "DOCUMENT_PUBLISH");
        String staffAssertion = issue(user, tenant, workspace, repository, "DOCUMENT_STAFF_REVIEW");
        String finalAssertion = issue(user, tenant, workspace, repository, "DOCUMENT_FINAL_APPROVE");
        String publishAssertion = issue(user, tenant, workspace, repository, "DOCUMENT_PUBLISH");
        evaluate(user, staffAssertion, staffBody, "DENY");
        evaluate(user, finalAssertion, finalBody, "DENY");
        evaluate(user, publishAssertion, publishBody, "DENY");
        admin.update("UPDATE identity.workspace_role_assignments SET role_code='STAFF' WHERE workspace_membership_id=?", wm);
        evaluate(user, repoAssertion, repoBody, "DENY");
        evaluate(user, staffAssertion, staffBody, "ALLOW");
        evaluate(user, finalAssertion, finalBody, "DENY");
        evaluate(user, publishAssertion, publishBody, "DENY");
        admin.update("UPDATE identity.workspace_role_assignments SET role_code='MANAGER' WHERE workspace_membership_id=?", wm);
        evaluate(user, repoAssertion, repoBody, "DENY");
        evaluate(user, staffAssertion, staffBody, "DENY");
        evaluate(user, finalAssertion, finalBody, "ALLOW");
        Long revisionBeforePublish = admin.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id=?", Long.class, tenant);
        Integer auditCountBeforePublish = admin.queryForObject("SELECT count(*) FROM identity.authorization_audit WHERE tenant_id=?", Integer.class, tenant);
        evaluate(user, publishAssertion, publishBody, "ALLOW");
        assertThat(admin.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id=?", Long.class, tenant)).isEqualTo(revisionBeforePublish);
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.authorization_audit WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(auditCountBeforePublish);
        admin.update("UPDATE identity.workspace_role_assignments SET role_code='DEVELOPER' WHERE workspace_membership_id=?", wm);
        UUID unrelatedRepository = UUID.randomUUID();
        String otherRepoBody = repoBody.replace(repository.toString(), unrelatedRepository.toString());
        evaluate(user, issue(user, tenant, workspace, unrelatedRepository), otherRepoBody, "DENY");
        admin.update("UPDATE identity.repository_grants SET status='REVOKED', ended_at=now() WHERE repository_connection_id=?", repository);
        evaluate(user, repoAssertion, repoBody, "DENY");
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.repository_grants WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(1);
        admin.update("UPDATE identity.tenants SET status='SUSPENDED' WHERE tenant_id=?", tenant);
        evaluate(user, assertion, body, "DENY");
        admin.update("UPDATE identity.tenants SET status='ACTIVE' WHERE tenant_id=?", tenant);
        admin.update("UPDATE identity.identities SET status='DISABLED' WHERE identity_id=?", actor);
        evaluate(user, assertion, body, "DENY");
        admin.update("UPDATE identity.identities SET status='ACTIVE' WHERE identity_id=?", actor);

        var wrongAudience = serviceRequest(tenant, workspace, null, "REPOSITORY_CONNECT");
        wrongAudience.setAudience("livingdocs-s02-internal");
        assertBadAssertion(user, serviceTokens.issue("s02", wrongAudience), body);
        var wrongOperation = serviceRequest(tenant, workspace, null, "REPOSITORY_CONNECT");
        wrongOperation.setOperation("source:resolve");
        assertBadAssertion(user, serviceTokens.issue("s02", wrongOperation), body);
        var wrongPath = serviceRequest(tenant, workspace, null, "REPOSITORY_CONNECT");
        wrongPath.setPath("/internal/v1/source/resolve");
        assertBadAssertion(user, serviceTokens.issue("s02", wrongPath), body);
        var wrongCaller = serviceRequest(tenant, workspace, null, "REPOSITORY_CONNECT");
        assertBadAssertion(user, serviceTokens.issue("s03", wrongCaller), body);
        var wrongMethod = serviceRequest(tenant, workspace, null, "REPOSITORY_CONNECT");
        wrongMethod.setMethod("GET");
        assertBadAssertion(user, serviceTokens.issue("s02", wrongMethod), body);
        assertBadAssertion(user, customAssertion(user, tenant, workspace, "urn:wrong:issuer", "service", Instant.now(), Instant.now().plusSeconds(30)), body);
        assertBadAssertion(user, customAssertion(user, tenant, workspace, "urn:livingdocs:s01", "user", Instant.now(), Instant.now().plusSeconds(30)), body);
        assertBadAssertion(user, customAssertion(user, tenant, workspace, "urn:livingdocs:s01", "service", Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)), body);
    }

    private String customAssertion(String user, UUID tenant, UUID workspace, String tokenIssuer,
                                   String tokenUse, Instant issued, Instant expires) throws Exception {
        String actorHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(user.getBytes(StandardCharsets.US_ASCII)));
        var claims = JwtClaimsSet.builder().issuer(tokenIssuer).subject("workload:s02")
                .audience(List.of("livingdocs-s01-internal")).issuedAt(issued).notBefore(issued)
                .expiresAt(expires).id(UUID.randomUUID().toString())
                .claim("token_use", tokenUse).claim("operation", "authorization:evaluate")
                .claim("http_method", "POST").claim("http_path", "/internal/v1/authorization/evaluate")
                .claim("tenant_id", tenant.toString()).claim("workspace_id", workspace.toString())
                .claim("action", "REPOSITORY_CONNECT").claim("actor_token_sha256", actorHash).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKeys.signingKey())));
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKeys.signingKey().getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test void sourceResolveAndValidateTokensNeedNoHumanJwt() throws Exception {
        UUID tenant = UUID.randomUUID(), workspace = UUID.randomUUID(), repository = UUID.randomUUID();
        String hash = new BCryptPasswordEncoder().encode("test-workload-secret");
        Files.writeString(REGISTRY, "[{\"id\":\"s03\",\"secretHash\":\"" + hash
                + "\",\"audiences\":[\"livingdocs-s02-internal\"],\"operations\":[\"source:resolve\"],\"tenantIds\":[\"" + tenant + "\"]},"
                + "{\"id\":\"s04\",\"secretHash\":\"" + hash
                + "\",\"audiences\":[\"livingdocs-s02-internal\"],\"operations\":[\"source:validate\"],\"tenantIds\":[\"" + tenant + "\"]}]", StandardCharsets.UTF_8);
        String request = "{\"audience\":\"livingdocs-s02-internal\",\"operation\":\"source:resolve\",\"method\":\"POST\",\"path\":\"/internal/v1/source/resolve\",\"tenantId\":\"" + tenant + "\",\"workspaceId\":\"" + workspace + "\",\"repositoryConnectionId\":\"" + repository + "\"}";
        var response = mvc.perform(post("/internal/v1/auth/service-tokens").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("s03:test-workload-secret".getBytes(StandardCharsets.UTF_8)))
                .content(request)).andExpect(status().isOk()).andReturn();
        String token = json.readTree(response.getResponse().getContentAsString()).at("/data/accessToken").asText();
        var claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("workload:s03");
        assertThat(claims.getAudience()).containsExactly("livingdocs-s02-internal");
        assertThat(claims.getStringClaim("operation")).isEqualTo("source:resolve");
        assertThat(claims.getClaim("actor_token_sha256")).isNull();
        var validation = mvc.perform(post("/internal/v1/auth/service-tokens").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("s04:test-workload-secret".getBytes(StandardCharsets.UTF_8)))
                .content(request.replace("source:resolve", "source:validate").replace("/source/resolve", "/source/validate")))
                .andExpect(status().isOk()).andReturn();
        String validationToken = json.readTree(validation.getResponse().getContentAsString()).at("/data/accessToken").asText();
        var validationClaims = SignedJWT.parse(validationToken).getJWTClaimsSet();
        assertThat(validationClaims.getSubject()).isEqualTo("workload:s04");
        assertThat(validationClaims.getStringClaim("operation")).isEqualTo("source:validate");
    }

    @Test void runtimeOpenApiListsInternalContracts() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var api = json.readTree(response.getResponse().getContentAsString());
        assertThat(api.at("/paths/~1api~1v1~1auth~1register/post/operationId").asText()).isEqualTo("api001_post");
        assertThat(api.at("/paths/~1api~1v1~1auth~1login/post/operationId").asText()).isEqualTo("api002_post");
        assertThat(api.at("/paths/~1api~1v1~1users~1me~1access-context/get/operationId").asText()).isEqualTo("api038_get");
        assertThat(api.at("/paths/~1internal~1v1~1auth~1service-tokens/post/operationId").asText())
                .isEqualTo("issueServiceToken");
        assertThat(api.at("/paths/~1internal~1v1~1authorization~1evaluate/post/operationId").asText())
                .isEqualTo("api039_post");
        assertThat(api.at("/paths/~1internal~1v1~1auth~1service-tokens/post/requestBody/content/application~1json/schema").isMissingNode()).isFalse();
        assertThat(api.at("/paths/~1internal~1v1~1authorization~1evaluate/post/requestBody/content/application~1json/schema").isMissingNode()).isFalse();
        assertThat(api.at("/paths/~1internal~1v1~1authorization~1evaluate/post/responses/200").isMissingNode()).isFalse();
        var connectExample = api.at("/paths/~1internal~1v1~1authorization~1evaluate/post/requestBody/content/application~1json/examples/repositoryConnect/value");
        assertThat(connectExample.isMissingNode()).isFalse();
        assertThat(connectExample.toString()).contains("REPOSITORY_CONNECT").doesNotContain("repositoryConnectionId");
        var documentExample = api.at("/paths/~1internal~1v1~1authorization~1evaluate/post/requestBody/content/application~1json/examples/documentPublish/value");
        assertThat(documentExample.toString()).contains("DOCUMENT_PUBLISH", "repositoryConnectionId");
        var sourceExample = api.at("/paths/~1internal~1v1~1auth~1service-tokens/post/requestBody/content/application~1json/examples/s03SourceResolve/value");
        assertThat(sourceExample.toString()).contains("source:resolve", "repositoryConnectionId");
        var evaluateResponses = api.at("/paths/~1internal~1v1~1authorization~1evaluate/post/responses");
        assertThat(evaluateResponses.at("/200/content/application~1json/schema").isMissingNode()).isFalse();
        assertThat(evaluateResponses.at("/200/content/application~1json/examples/allow/value").toString())
                .contains("\"decision\":\"ALLOW\"");
        assertThat(evaluateResponses.at("/200/content/application~1json/examples/deny/value").toString())
                .contains("\"decision\":\"DENY\"");
        for (int status : new int[] {400, 401, 403, 503}) {
            assertThat(evaluateResponses.at("/" + status + "/content/application~1problem+json/example/status").asInt())
                    .isEqualTo(status);
        }
        var tokenResponses = api.at("/paths/~1internal~1v1~1auth~1service-tokens/post/responses");
        assertThat(tokenResponses.at("/200/content/application~1json/schema").isMissingNode()).isFalse();
        assertThat(tokenResponses.at("/200/content/application~1json/example").toString())
                .contains("<signed-service-jwt>");
        for (String schema : new String[] {"LoginResponse", "ServiceTokenResponse"}) {
            assertThat(api.at("/components/schemas/" + schema + "/required").toString())
                    .contains("accessToken", "tokenType", "expiresIn");
        }
        assertThat(api.at("/components/schemas/AccessContextResponse/required").toString())
                .contains("tenantId");
        assertThat(api.at("/components/schemas/AuthorizationEvaluationResponse/required").toString())
                .contains("decision", "action", "tenantId", "workspaceId", "authzRevision", "evaluatedAt", "decisionId");
        var security = api.at("/paths/~1internal~1v1~1authorization~1evaluate/post/security");
        assertThat(security.size()).isEqualTo(1);
        assertThat(security.get(0).has("bearerAuth")).isTrue();
        assertThat(security.get(0).has("serviceAssertion")).isTrue();
    }

    @Test void unavailableAuthorityIs503AndPublicRegistrationContractStays500() {
        var dbFailure = new DataAccessResourceFailureException("test-only database outage");
        var internal = new MockHttpServletRequest("POST", "/internal/v1/authorization/evaluate");
        var registration = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        assertThat(errors.authorityUnavailable(dbFailure, internal).getStatusCode().value()).isEqualTo(503);
        assertThat(errors.authorityUnavailable(dbFailure, registration).getStatusCode().value()).isEqualTo(500);
    }

    @Test void authorityReadUsesOneRepeatableSnapshotUnderConcurrentRevisionChange() throws Exception {
        UUID tenant = UUID.randomUUID();
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), "postgres", "postgres"));
        admin.update("INSERT INTO identity.tenants(tenant_id,name) VALUES (?, 'Snapshot')", tenant);
        Long startingRevision = admin.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id=?", Long.class, tenant);
        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var observed = executor.submit(() -> tenantContext.readInTenant(tenant, () -> {
                Long before = runtimeJdbc.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id=?", Long.class, tenant);
                firstRead.countDown();
                try {
                    if (!continueRead.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Snapshot test timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                Long after = runtimeJdbc.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id=?", Long.class, tenant);
                return List.of(before, after);
            }));
            assertThat(firstRead.await(5, TimeUnit.SECONDS)).isTrue();
            admin.update("UPDATE identity.tenants SET authz_revision=authz_revision+1 WHERE tenant_id=?", tenant);
            continueRead.countDown();
            assertThat(observed.get(5, TimeUnit.SECONDS)).containsExactly(startingRevision, startingRevision);
            assertThat(tenantContext.readInTenant(tenant, () -> runtimeJdbc.queryForObject(
                    "SELECT authz_revision FROM identity.tenants WHERE tenant_id=?", Long.class, tenant))).isEqualTo(startingRevision + 1);
        } finally {
            continueRead.countDown();
        }
    }

    private ServiceTokenRequest serviceRequest(UUID tenant, UUID workspace, UUID repository, String action) {
        var request = new ServiceTokenRequest();
        request.setAudience("livingdocs-s01-internal");
        request.setOperation("authorization:evaluate");
        request.setMethod("POST");
        request.setPath("/internal/v1/authorization/evaluate");
        request.setTenantId(tenant);
        request.setWorkspaceId(workspace);
        request.setRepositoryConnectionId(repository);
        request.setAction(action);
        request.setActorTokenSha256("0".repeat(64));
        return request;
    }
    private void assertBadAssertion(String user, String assertion, String body) throws Exception {
        mvc.perform(post("/internal/v1/authorization/evaluate").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + user).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(body)).andExpect(status().isUnauthorized());
    }

    private String issue(String user, UUID tenant, UUID workspace) throws Exception {
        return issue(user, tenant, workspace, null);
    }
    private String issue(String user, UUID tenant, UUID workspace, UUID repository) throws Exception {
        return issue(user, tenant, workspace, repository,
                repository == null ? "REPOSITORY_CONNECT" : "DOCUMENT_AUTHOR");
    }
    private String issue(String user, UUID tenant, UUID workspace, UUID repository, String action) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(user.getBytes(StandardCharsets.US_ASCII)));
        String body = "{\"audience\":\"livingdocs-s01-internal\",\"operation\":\"authorization:evaluate\",\"method\":\"POST\",\"path\":\"/internal/v1/authorization/evaluate\",\"tenantId\":\"" + tenant + "\",\"workspaceId\":\"" + workspace + "\",\"action\":\"" + action + "\",\"actorTokenSha256\":\"" + hash + "\""
                + (repository == null ? "" : ",\"repositoryConnectionId\":\"" + repository + "\"") + "}";
        var response = mvc.perform(post("/internal/v1/auth/service-tokens").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(((repository == null ? "s02" : "s04") + ":test-workload-secret").getBytes(StandardCharsets.UTF_8)))
                .content(body)).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).at("/data/accessToken").asText();
    }
    private String body(UUID tenant, UUID workspace) {
        return "{\"action\":\"REPOSITORY_CONNECT\",\"tenantId\":\"" + tenant + "\",\"workspaceId\":\"" + workspace + "\"}";
    }
    private String documentBody(UUID tenant, UUID workspace, UUID repository, String action) {
        return "{\"action\":\"" + action + "\",\"tenantId\":\"" + tenant
                + "\",\"workspaceId\":\"" + workspace + "\",\"repositoryConnectionId\":\""
                + repository + "\"}";
    }
    private void evaluate(String user, String assertion, String body, String expected) throws Exception {
        mvc.perform(post("/internal/v1/authorization/evaluate").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + user).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value(expected));
    }
    private static Path registry() {
        try {
            Path path = Files.createTempFile("liv138-workloads-", ".json");
            String hash = new BCryptPasswordEncoder().encode("test-workload-secret");
            Files.writeString(path, "[{\"id\":\"s02\",\"secretHash\":\"" + hash + "\",\"audiences\":[\"livingdocs-s01-internal\"],\"operations\":[\"authorization:evaluate\"],\"tenantIds\":[]}]", StandardCharsets.UTF_8);
            return path;
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    private static Path[] keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            var pair = generator.generateKeyPair();
            Path[] paths = {Files.createTempFile("liv138-pr2-private", ".pem"), Files.createTempFile("liv138-pr2-public", ".pem")};
            Files.writeString(paths[0], pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(paths[1], pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            return paths;
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }
}
