package com.livingdocs.identity;

import com.livingdocs.identity.security.JwtTokenIssuer;
import com.livingdocs.identity.provisioning.ProvisioningManifest;
import com.livingdocs.identity.provisioning.ProvisioningService;
import com.livingdocs.identity.security.JwtKeyManager;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.livingdocs.identity.service.RegistrationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
@Testcontainers
class AccessContextIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("livingdocs").withUsername("postgres").withPassword("postgres")
            .withInitScript("s01-test-schema.sql");
    private static final KeyPaths KEYS = keys();

    @Autowired ProvisioningService provisioning;

    @Test
    void publicRuntimeDbRoleCannotExecuteOperatorProvisioning() {
        ProvisioningManifest m = new ProvisioningManifest();
        m.setOperationId(UUID.randomUUID());
        m.setTenantId(UUID.randomUUID());
        m.setType("CREATE_TENANT");
        m.setReason("Unauthorized runtime role must not provision");
        assertThatThrownBy(() -> provisioning.apply(m, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Operator database group role required");
    }

    @Test
    void platformAdministratorHasNoImplicitTenantOrDocumentPermissions() throws Exception {
        UUID platformAdmin = identity();
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                "ld_bootstrap_test", "test_bootstrap_password"))
                .queryForObject("SELECT identity.bootstrap_platform_admin(?, ?)", UUID.class,
                platformAdmin, "Approved platform bootstrap for isolated test");
        mvc.perform(get("/api/v1/users/me/access-context")
                        .header("Authorization", "Bearer " + tokens.issue(platformAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.tenantPermissions").isEmpty())
                .andExpect(jsonPath("$.data.workspaceGrants").isEmpty());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_SCHEMA", () -> "identity");
        registry.add("DB_USERNAME", () -> "ld_s01_app");
        registry.add("DB_PASSWORD", () -> "test_app_password");
        registry.add("FLYWAY_USERNAME", POSTGRES::getUsername);
        registry.add("FLYWAY_PASSWORD", POSTGRES::getPassword);
        registry.add("livingdocs.security.jwt.private-key-path", () -> KEYS.privatePath().toString());
        registry.add("livingdocs.security.jwt.public-key-path", () -> KEYS.publicPath().toString());
    }

    @Autowired MockMvc mvc;
    @Autowired RegistrationService registration;
    @Autowired JwtTokenIssuer tokens;
    @Autowired JwtKeyManager keys;
    @Autowired JdbcTemplate runtime;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void verifiedIdentityHasNoAuthorityUntilExplicitProvisioning() throws Exception {
        UUID identity = identity();
        String jwt = tokens.issue(identity);
        mvc.perform(get("/api/v1/users/me/access-context").header("Authorization", "Bearer " + jwt)
                        .header("X-Identity-ID", UUID.randomUUID()).header("X-Tenant-ID", UUID.randomUUID())
                        .header("X-Roles", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_LOADED"))
                .andExpect(jsonPath("$.data.identityId").value(identity.toString()))
                .andExpect(jsonPath("$.data.tenantId").value((Object) null))
                .andExpect(jsonPath("$.data.tenantRoles").isEmpty())
                .andExpect(jsonPath("$.data.workspaceGrants").isEmpty());
        assertThat(runtime.queryForObject("SELECT count(*) FROM identity.tenant_memberships", Integer.class))
                .isZero();
    }

    @Test
    void multiRoleIsScopedPendingRepositoryExcludedAndRevocationIsFresh() throws Exception {
        UUID identity = identity();
        UUID tenant = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID tenantMember = UUID.randomUUID();
        UUID firstMember = UUID.randomUUID();
        UUID secondMember = UUID.randomUUID();
        UUID repository = UUID.randomUUID();
        JdbcTemplate admin = admin();
        admin.update("INSERT INTO identity.tenants(tenant_id, name) VALUES (?, 'Tenant A')", tenant);
        admin.update("INSERT INTO identity.tenant_memberships(membership_id, tenant_id, identity_id) VALUES (?, ?, ?)",
                tenantMember, tenant, identity);
        admin.update("INSERT INTO identity.workspaces(workspace_id, tenant_id, name) VALUES (?, ?, 'First')", first, tenant);
        admin.update("INSERT INTO identity.workspaces(workspace_id, tenant_id, name) VALUES (?, ?, 'Second')", second, tenant);
        admin.update("INSERT INTO identity.workspace_memberships(workspace_membership_id, tenant_id, workspace_id, tenant_membership_id, identity_id) VALUES (?, ?, ?, ?, ?)",
                firstMember, tenant, first, tenantMember, identity);
        admin.update("INSERT INTO identity.workspace_memberships(workspace_membership_id, tenant_id, workspace_id, tenant_membership_id, identity_id) VALUES (?, ?, ?, ?, ?)",
                secondMember, tenant, second, tenantMember, identity);
        admin.update("INSERT INTO identity.workspace_role_assignments(tenant_id, workspace_id, workspace_membership_id, role_code) VALUES (?, ?, ?, 'STAFF')",
                tenant, first, firstMember);
        admin.update("INSERT INTO identity.workspace_role_assignments(tenant_id, workspace_id, workspace_membership_id, role_code) VALUES (?, ?, ?, 'MANAGER')",
                tenant, first, firstMember);
        admin.update("INSERT INTO identity.workspace_role_assignments(tenant_id, workspace_id, workspace_membership_id, role_code) VALUES (?, ?, ?, 'DEVELOPER')",
                tenant, second, secondMember);
        admin.update("INSERT INTO identity.repository_grants(tenant_id, workspace_id, workspace_membership_id, repository_connection_id, provenance, operator_identity_id) VALUES (?, ?, ?, ?, 'Pending S02 binding', ?)",
                tenant, first, firstMember, repository, identity);
        String jwt = tokens.issue(identity);
        String body = mvc.perform(get("/api/v1/users/me/access-context")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value(tenant.toString()))
                .andExpect(jsonPath("$.data.workspaceGrants.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("DOCUMENT_STAFF_REVIEW", "DOCUMENT_FINAL_APPROVE", "DOCUMENT_AUTHOR");
        assertThat(body).doesNotContain(repository.toString());
        var grants = com.jayway.jsonpath.JsonPath.read(body, "$.data.workspaceGrants");
        String firstPermissions = com.jayway.jsonpath.JsonPath.read(body,
                "$.data.workspaceGrants[?(@.workspaceId=='" + first + "')].permissions").toString();
        String secondPermissions = com.jayway.jsonpath.JsonPath.read(body,
                "$.data.workspaceGrants[?(@.workspaceId=='" + second + "')].permissions").toString();
        assertThat(firstPermissions).contains("DOCUMENT_STAFF_REVIEW", "DOCUMENT_FINAL_APPROVE")
                .doesNotContain("DOCUMENT_AUTHOR");
        assertThat(secondPermissions).contains("DOCUMENT_AUTHOR")
                .doesNotContain("DOCUMENT_FINAL_APPROVE", "DOCUMENT_STAFF_REVIEW");
        long before = admin.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id = ?", Long.class, tenant);
        admin.update("UPDATE identity.tenant_memberships SET status = 'REVOKED', ended_at = now() WHERE membership_id = ?", tenantMember);
        long after = admin.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id = ?", Long.class, tenant);
        assertThat(after).isGreaterThan(before);
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.authorization_audit WHERE tenant_id = ? AND authz_revision = ?", Integer.class, tenant, after)).isEqualTo(1);
        mvc.perform(get("/api/v1/users/me/access-context")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value((Object) null))
                .andExpect(jsonPath("$.data.workspaceGrants").isEmpty());
    }

    @Test
    void realRuntimeRoleDeniesMissingAndCrossTenantContextAfterPoolReuse() throws Exception {
        UUID identity = identity();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID workspaceA = UUID.randomUUID();
        UUID workspaceB = UUID.randomUUID();
        JdbcTemplate admin = admin();
        admin.update("INSERT INTO identity.tenants(tenant_id, name) VALUES (?, 'A')", tenantA);
        admin.update("INSERT INTO identity.tenants(tenant_id, name) VALUES (?, 'B')", tenantB);
        admin.update("INSERT INTO identity.workspaces(workspace_id, tenant_id, name) VALUES (?, ?, 'A')", workspaceA, tenantA);
        admin.update("INSERT INTO identity.workspaces(workspace_id, tenant_id, name) VALUES (?, ?, 'B')", workspaceB, tenantB);
        admin.update("INSERT INTO identity.tenant_memberships(tenant_id, identity_id) VALUES (?, ?)", tenantA, identity);
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("ld_s01_app");
        assertThat(runtime.queryForObject("SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'identity.tenants'::regclass", String.class))
                .isNotEqualTo("ld_s01_app");
        assertThat(runtime.queryForObject("SELECT has_table_privilege(current_user, 'identity.tenants', 'INSERT')", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_table_privilege(current_user, 'identity.tenant_memberships', 'DELETE')", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT count(*) FROM identity.tenants", Integer.class)).isZero();
        mvc.perform(get("/api/v1/users/me/access-context")
                .header("Authorization", "Bearer " + tokens.issue(identity))).andExpect(status().isOk());
        assertThat(runtime.queryForObject("SELECT count(*) FROM identity.tenants", Integer.class)).isZero();
        new TransactionTemplate(transactions).execute(status -> {
            runtime.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantA.toString());
            assertThat(runtime.queryForObject("SELECT count(*) FROM identity.tenants WHERE tenant_id = ?", Integer.class, tenantB)).isZero();
            assertThat(runtime.queryForObject("SELECT count(*) FROM identity.tenants WHERE tenant_id = ?", Integer.class, tenantA)).isEqualTo(1);
            assertThat(runtime.queryForObject("SELECT count(*) FROM identity.workspaces WHERE workspace_id = ?", Integer.class, workspaceB)).isZero();
            assertThat(runtime.queryForObject("SELECT count(*) FROM identity.workspaces WHERE workspace_id = ?", Integer.class, workspaceA)).isEqualTo(1);
            return null;
        });
        assertThat(runtime.queryForObject("SELECT count(*) FROM identity.tenants", Integer.class)).isZero();
    }

    @Test
    void workspaceMembershipWithoutCatalogRoleDoesNotExposeNavigation() throws Exception {
        UUID identity = identity();
        UUID tenant = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        JdbcTemplate admin = admin();
        admin.update("INSERT INTO identity.tenants(tenant_id, name) VALUES (?, 'No role')", tenant);
        admin.update("INSERT INTO identity.tenant_memberships(membership_id, tenant_id, identity_id) VALUES (?, ?, ?)",
                member, tenant, identity);
        admin.update("INSERT INTO identity.workspaces(workspace_id, tenant_id, name) VALUES (?, ?, 'Hidden')", workspace, tenant);
        admin.update("INSERT INTO identity.workspace_memberships(tenant_id, workspace_id, tenant_membership_id, identity_id) VALUES (?, ?, ?, ?)",
                tenant, workspace, member, identity);
        mvc.perform(get("/api/v1/users/me/access-context")
                        .header("Authorization", "Bearer " + tokens.issue(identity)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value(tenant.toString()))
                .andExpect(jsonPath("$.data.workspaceGrants").isEmpty());
    }

    @Test
    void missingAndInvalidJwtReturnStableProblem() throws Exception {
        mvc.perform(get("/api/v1/users/me/access-context"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/users/me/access-context").header("Authorization", "Bearer forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        UUID identity = identity();
        var key = keys.signingKey();
        SignedJWT expired = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID()).build(), new JWTClaimsSet.Builder()
                .issuer("urn:livingdocs:s01").audience("livingdocs-api")
                .subject(identity.toString())
                .issueTime(Date.from(java.time.Instant.now().minusSeconds(1200)))
                .notBeforeTime(Date.from(java.time.Instant.now().minusSeconds(1200)))
                .expirationTime(Date.from(java.time.Instant.now().minusSeconds(300)))
                .build());
        expired.sign(new RSASSASigner(key.toRSAPrivateKey()));
        mvc.perform(get("/api/v1/users/me/access-context")
                        .header("Authorization", "Bearer " + expired.serialize()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void inactiveIdentityIsDeniedWithValidJwtAndStableProblem() throws Exception {
        UUID identity = identity();
        String jwt = tokens.issue(identity);
        admin().update("UPDATE identity.identities SET status = 'INACTIVE' WHERE identity_id = ?", identity);
        mvc.perform(get("/api/v1/users/me/access-context")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_INACTIVE"))
                .andExpect(jsonPath("$.correlationId").isString());
    }

    @Test
    void runtimeOpenApiAndSwaggerUiExposeActualSecuredController() throws Exception {
        String path = "$.paths['/api/v1/users/me/access-context'].get";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(path + ".security[0].bearerAuth").isArray())
                .andExpect(jsonPath(path + ".responses['200']").exists())
                .andExpect(jsonPath(path + ".responses['200'].content['application/json'].example.code")
                        .value("ACCESS_CONTEXT_LOADED"))
                .andExpect(jsonPath(path + ".responses['200'].content['application/json'].example.data.workspaceGrants[0].roles[0]")
                        .value("MANAGER"))
                .andExpect(jsonPath(path + ".responses['401']").exists())
                .andExpect(jsonPath(path + ".responses['403']").exists())
                .andExpect(jsonPath(path + ".responses['500']").exists());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    private UUID identity() {
        return registration.registerLocal("access-" + UUID.randomUUID() + "@example.com", "A-secure-password-123");
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static KeyPaths keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new KeyPaths(pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                    pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static Path pem(String type, byte[] encoded) throws Exception {
        Path file = Files.createTempFile("liv138-jwt-", ".pem");
        Files.writeString(file, "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n", StandardCharsets.US_ASCII);
        file.toFile().deleteOnExit();
        return file;
    }

    private record KeyPaths(Path privatePath, Path publicPath) {}
}
