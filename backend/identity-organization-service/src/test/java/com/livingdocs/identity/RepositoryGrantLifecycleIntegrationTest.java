package com.livingdocs.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.identity.security.JwtKeyManager;
import com.livingdocs.identity.security.JwtTokenIssuer;
import com.livingdocs.identity.security.ServiceJwtIssuer;
import com.livingdocs.identity.dto.request.ServiceTokenRequest;
import com.livingdocs.identity.service.RegistrationService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controlled S02 HTTP receiver, not a real cross-service E2E test. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("db") @Testcontainers
class RepositoryGrantLifecycleIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("livingdocs").withUsername("postgres").withPassword("postgres")
            .withInitScript("s01-test-schema.sql");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path[] KEYS = keys();
    private static final AtomicReference<String> S02_STATE = new AtomicReference<>("ACTIVE");
    private static volatile UUID expectedTenant, expectedWorkspace, expectedConnection;
    private static volatile JwtKeyManager keyManager;
    private static volatile CountDownLatch lookupStarted, releaseLookup;
    private static final HttpServer S02 = receiver();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("DB_URL", DB::getJdbcUrl); p.add("DB_SCHEMA", () -> "identity");
        p.add("DB_USERNAME", () -> "ld_s01_app"); p.add("DB_PASSWORD", () -> "test_app_password");
        p.add("FLYWAY_USERNAME", DB::getUsername); p.add("FLYWAY_PASSWORD", DB::getPassword);
        p.add("livingdocs.security.jwt.private-key-path", () -> KEYS[0].toString());
        p.add("livingdocs.security.jwt.public-key-path", () -> KEYS[1].toString());
        p.add("livingdocs.s02.binding.base-url", () -> "http://127.0.0.1:" + S02.getAddress().getPort());
        p.add("livingdocs.s02.binding.allow-http", () -> "true");
    }
    @Autowired MockMvc mvc;
    @Autowired RegistrationService registration;
    @Autowired JwtTokenIssuer tokens;
    @Autowired JwtKeyManager keys;
    @Autowired ServiceJwtIssuer serviceTokens;

    @BeforeEach void prepare() { keyManager = keys; S02_STATE.set("ACTIVE"); }
    @AfterAll static void close() { S02.stop(0); }

    @Test void requestVerifyRevokeAndBindingFailuresAreFailClosed() throws Exception {
        UUID lead = registration.registerLocal("lead-" + UUID.randomUUID() + "@example.com", "A-secure-password-123");
        UUID owner = registration.registerLocal("owner-" + UUID.randomUUID() + "@example.com", "A-secure-password-123");
        UUID tenant = UUID.randomUUID(), workspace = UUID.randomUUID(), member = UUID.randomUUID();
        UUID connection = UUID.randomUUID(), tmLead = UUID.randomUUID(), tmOwner = UUID.randomUUID();
        expectedTenant = tenant; expectedWorkspace = workspace; expectedConnection = connection;
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), "postgres", "postgres"));
        admin.update("INSERT INTO identity.tenants(tenant_id,name) VALUES (?,'Grant test')", tenant);
        admin.update("INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES (?,?,?)", tmLead, tenant, lead);
        admin.update("INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES (?,?,?)", tmOwner, tenant, owner);
        admin.update("INSERT INTO identity.tenant_role_assignments(tenant_id,tenant_membership_id,role_code) VALUES (?,?,'TENANT_OWNER')", tenant, tmOwner);
        admin.update("INSERT INTO identity.workspaces(workspace_id,tenant_id,name) VALUES (?,?,'Grant test')", workspace, tenant);
        admin.update("INSERT INTO identity.workspace_memberships(workspace_membership_id,tenant_id,workspace_id,tenant_membership_id,identity_id) VALUES (?,?,?,?,?)", member, tenant, workspace, tmLead, lead);
        admin.update("INSERT INTO identity.workspace_role_assignments(tenant_id,workspace_id,workspace_membership_id,role_code) VALUES (?,?,?,'TECHNICAL_LEAD')", tenant, workspace, member);
        admin.update("INSERT INTO identity.workspace_role_assignments(tenant_id,workspace_id,workspace_membership_id,role_code) VALUES (?,?,?,'DEVELOPER')", tenant, workspace, member);
        String path = "/api/v1/tenants/" + tenant + "/workspaces/" + workspace + "/repository-grants";
        String payload = "{\"workspaceMembershipId\":\"" + member + "\",\"repositoryConnectionId\":\"" + connection
                + "\",\"provenance\":\"Requested for controlled S02 test\"}";
        UUID key = UUID.randomUUID();
        String leadJwt = tokens.issue(lead), ownerJwt = tokens.issue(owner);
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + leadJwt)
                .content(payload)).andExpect(status().isBadRequest());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        String created = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + leadJwt).header("Idempotency-Key", key).content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.effective").value(false)).andReturn().getResponse().getContentAsString();
        UUID grant = UUID.fromString(JSON.readTree(created).path("data").path("grantId").asText());
        String evaluation = "{\"action\":\"DOCUMENT_AUTHOR\",\"tenantId\":\"" + tenant + "\",\"workspaceId\":\""
                + workspace + "\",\"repositoryConnectionId\":\"" + connection + "\"}";
        ServiceTokenRequest request = new ServiceTokenRequest();
        request.setAudience("livingdocs-s01-internal"); request.setOperation("authorization:evaluate");
        request.setMethod("POST"); request.setPath("/internal/v1/authorization/evaluate");
        request.setTenantId(tenant); request.setWorkspaceId(workspace); request.setRepositoryConnectionId(connection);
        request.setAction("DOCUMENT_AUTHOR");
        request.setActorTokenSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(leadJwt.getBytes(StandardCharsets.US_ASCII))));
        String assertion = serviceTokens.issue("s04", request);
        mvc.perform(post("/internal/v1/authorization/evaluate").secure(true).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + leadJwt).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(evaluation)).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value("DENY"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + leadJwt).header("Idempotency-Key", key).content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.grantId").value(grant.toString()));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + leadJwt).header("Idempotency-Key", key)
                .content(payload.replace("controlled S02 test", "different S02 test")))
                .andExpect(status().isConflict());
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.repository_grants WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(1);
        mvc.perform(post(path + "/" + grant + "/verify").header("Authorization", "Bearer " + leadJwt))
                .andExpect(status().isForbidden());
        S02_STATE.set("WRONG_SCOPE");
        mvc.perform(post(path + "/" + grant + "/verify").header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isConflict());
        S02_STATE.set("UNAVAILABLE");
        mvc.perform(post(path + "/" + grant + "/verify").header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isServiceUnavailable());
        S02_STATE.set("SLOW");
        mvc.perform(post(path + "/" + grant + "/verify").header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isServiceUnavailable());
        S02_STATE.set("ACTIVE");
        mvc.perform(post(path + "/" + grant + "/verify").header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.effective").value(true));
        mvc.perform(post("/internal/v1/authorization/evaluate").secure(true).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + leadJwt).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(evaluation)).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value("ALLOW"));
        S02_STATE.set("WRONG_SCOPE");
        mvc.perform(post("/internal/v1/authorization/evaluate").secure(true).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + leadJwt).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(evaluation)).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value("DENY"));
        S02_STATE.set("REVISION_2");
        mvc.perform(post("/internal/v1/authorization/evaluate").secure(true).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + leadJwt).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(evaluation)).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value("DENY"));
        assertThat(admin.queryForObject("SELECT verified_binding_revision FROM identity.repository_grants WHERE grant_id=?", Long.class, grant)).isEqualTo(1L);
        assertThat(admin.queryForObject("SELECT actor_identity_id FROM identity.authorization_audit WHERE target_id=? ORDER BY authz_revision DESC LIMIT 1", UUID.class, grant)).isEqualTo(owner);
        S02_STATE.set("UNAVAILABLE");
        mvc.perform(post("/internal/v1/authorization/evaluate").secure(true).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + leadJwt).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(evaluation)).andExpect(status().isServiceUnavailable());
        mvc.perform(get(path + "/" + grant).header("Authorization", "Bearer " + leadJwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.effective").value(false))
                .andExpect(jsonPath("$.data.effectiveReason").value("BINDING_UNVERIFIED"));
        mvc.perform(get("/api/v1/users/me/access-context").header("Authorization", "Bearer " + leadJwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workspaceGrants[0].repositoryIds").isEmpty())
                .andExpect(jsonPath("$.data.workspaceGrants[0].repositoryGrantStatuses[0].effectiveReason").value("BINDING_UNVERIFIED"));
        mvc.perform(post(path + "/" + grant + "/revoke").header("Authorization", "Bearer " + leadJwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REVOKED"));
        mvc.perform(post("/internal/v1/authorization/evaluate").secure(true).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + leadJwt).header("X-LD-Service-Assertion", "Bearer " + assertion)
                .content(evaluation)).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision").value("DENY"));
        assertThat(admin.queryForObject("SELECT status FROM identity.repository_grants WHERE grant_id=?", String.class, grant)).isEqualTo("REVOKED");

        S02_STATE.set("ACTIVE");
        String raced = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + leadJwt)
                        .header("Idempotency-Key", UUID.randomUUID()).content(payload))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID racedGrant = UUID.fromString(JSON.readTree(raced).path("data").path("grantId").asText());
        lookupStarted = new CountDownLatch(1); releaseLookup = new CountDownLatch(1);
        S02_STATE.set("BLOCK");
        CompletableFuture<Integer> verification = CompletableFuture.supplyAsync(() -> {
            try { return mvc.perform(post(path + "/" + racedGrant + "/verify")
                    .header("Authorization", "Bearer " + ownerJwt)).andReturn().getResponse().getStatus(); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        });
        assertThat(lookupStarted.await(4, TimeUnit.SECONDS)).isTrue();
        mvc.perform(post(path + "/" + racedGrant + "/revoke").header("Authorization", "Bearer " + leadJwt))
                .andExpect(status().isOk());
        releaseLookup.countDown();
        assertThat(verification.get(4, TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(admin.queryForObject("SELECT verification_status FROM identity.repository_grants WHERE grant_id=?", String.class,
                racedGrant)).isEqualTo("PENDING");

        UUID secondConnection = UUID.randomUUID();
        String ownerRequested = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + ownerJwt)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .content(payload.replace(connection.toString(), secondConnection.toString())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.verificationStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        UUID ownerGrant = UUID.fromString(JSON.readTree(ownerRequested).path("data").path("grantId").asText());
        mvc.perform(post(path + "/" + ownerGrant + "/verify").header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "/" + ownerGrant).header("Authorization", "Bearer " + leadJwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.effective").value(false));
        mvc.perform(get(path + "/" + ownerGrant).header("Authorization", "Bearer " + tokens.issue(
                registration.registerLocal("outsider-" + UUID.randomUUID() + "@example.com", "A-secure-password-123"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenants/" + tenant + "/workspaces/" + UUID.randomUUID()
                + "/repository-grants/" + ownerGrant).header("Authorization", "Bearer " + leadJwt))
                .andExpect(status().isForbidden());
    }

    @Test void runtimeOpenApiExposesAllFiveGrantOperations() throws Exception {
        String runtime = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode paths = JSON.readTree(runtime).path("paths");
        String base = "/api/v1/tenants/{tenantId}/workspaces/{workspaceId}/repository-grants";
        assertThat(paths.path(base).path("post").path("operationId").asText()).isEqualTo("liv162_grant_request");
        assertThat(paths.path(base).path("get").path("operationId").asText()).isEqualTo("liv162_grant_list");
        assertThat(paths.path(base + "/{grantId}").path("get").path("operationId").asText()).isEqualTo("liv162_grant_get");
        assertThat(paths.path(base + "/{grantId}/verify").path("post").path("operationId").asText()).isEqualTo("liv162_grant_verify");
        assertThat(paths.path(base + "/{grantId}/revoke").path("post").path("operationId").asText()).isEqualTo("liv162_grant_revoke");
        String grantRequired = JSON.readTree(runtime).path("components").path("schemas")
                .path("RepositoryGrantResponse").path("required").toString();
        assertThat(grantRequired).contains("grantId", "tenantId", "workspaceId",
                "workspaceMembershipId", "repositoryConnectionId", "status", "verificationStatus", "effective");
    }

    private static HttpServer receiver() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/repository-bindings/verify", exchange -> {
                int status = 200;
                try {
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    SignedJWT jwt = SignedJWT.parse(authorization.substring(7));
                    RSAKey key = (RSAKey) JWKSet.parse(keyManager.publicJwksJson()).getKeyByKeyId(jwt.getHeader().getKeyID());
                    JsonNode body = JSON.readTree(exchange.getRequestBody());
                    if (!"POST".equals(exchange.getRequestMethod()) || !JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                            || key == null || !jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))
                            || !"workload:s01".equals(jwt.getJWTClaimsSet().getSubject())
                            || !jwt.getJWTClaimsSet().getAudience().contains("livingdocs-s02-internal")
                            || !"repository-binding:verify".equals(jwt.getJWTClaimsSet().getStringClaim("operation"))
                            || !expectedTenant.toString().equals(body.path("tenantId").asText())
                            || !expectedConnection.toString().equals(body.path("repositoryConnectionId").asText())) status = 401;
                } catch (Exception ex) { status = 401; }
                if (status == 200 && "UNAVAILABLE".equals(S02_STATE.get())) status = 503;
                if (status == 200 && "SLOW".equals(S02_STATE.get())) {
                    try { Thread.sleep(1800); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); status = 503; }
                }
                if (status == 200 && "BLOCK".equals(S02_STATE.get())) {
                    lookupStarted.countDown();
                    try { releaseLookup.await(4, TimeUnit.SECONDS); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); status = 503; }
                }
                String tenant = "WRONG_SCOPE".equals(S02_STATE.get()) ? UUID.randomUUID().toString() : expectedTenant.toString();
                int revision = "REVISION_2".equals(S02_STATE.get()) ? 2 : 1;
                String response = "{\"data\":{\"tenantId\":\"" + tenant + "\",\"workspaceId\":\"" + expectedWorkspace
                        + "\",\"repositoryConnectionId\":\"" + expectedConnection + "\",\"status\":\"ACTIVE\",\"bindingRevision\":" + revision + ",\"observedAt\":\"" + Instant.now() + "\"}}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start(); return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static Path[] keys() {
        try {
            var pair = KeyPairGenerator.getInstance("RSA"); pair.initialize(2048);
            var generated = pair.generateKeyPair();
            Path privatePath = Files.createTempFile("liv162-private-", ".pem");
            Path publicPath = Files.createTempFile("liv162-public-", ".pem");
            Files.writeString(privatePath, "-----BEGIN PRIVATE KEY-----\n" +
                    java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(generated.getPrivate().getEncoded()) +
                    "\n-----END PRIVATE KEY-----\n");
            Files.writeString(publicPath, "-----BEGIN PUBLIC KEY-----\n" +
                    java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(generated.getPublic().getEncoded()) +
                    "\n-----END PUBLIC KEY-----\n");
            return new Path[]{privatePath, publicPath};
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
