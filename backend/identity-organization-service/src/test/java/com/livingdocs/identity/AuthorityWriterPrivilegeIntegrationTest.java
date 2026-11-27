package com.livingdocs.identity;

import com.livingdocs.identity.entity.RepositoryGrant;
import com.livingdocs.identity.repository.RepositoryGrantRepository;
import com.livingdocs.identity.security.AuthorityWriteContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("db") @Testcontainers
class AuthorityWriterPrivilegeIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("livingdocs").withUsername("postgres").withPassword("postgres")
            .withInitScript("s01-test-schema.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("DB_URL", DB::getJdbcUrl);
        p.add("DB_SCHEMA", () -> "identity");
        p.add("DB_USERNAME", () -> "ld_s01_app");
        p.add("DB_PASSWORD", () -> "test_app_password");
        p.add("FLYWAY_USERNAME", DB::getUsername);
        p.add("FLYWAY_PASSWORD", DB::getPassword);
    }
    @Autowired JdbcTemplate runtime;
    @Autowired RepositoryGrantRepository grants;
    @Autowired AuthorityWriteContext writer;

    @Test void writerRoleIsScopedTransactionalAndJpaUsesTheSameConnection() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), "postgres", "postgres"));
        UUID actor = UUID.randomUUID(), tenant = UUID.randomUUID(), workspace = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID member = UUID.randomUUID(), tenantMember = UUID.randomUUID(), connection = UUID.randomUUID();
        admin.update("INSERT INTO identity.identities(identity_id,email,status) VALUES (?,?,'ACTIVE')",
                actor, actor + "@example.com");
        admin.update("INSERT INTO identity.tenants(tenant_id,name) VALUES (?,'Writer test')", tenant);
        admin.update("INSERT INTO identity.tenants(tenant_id,name) VALUES (?,'Other tenant')", otherTenant);
        admin.update("INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES (?,?,?)",
                tenantMember, tenant, actor);
        admin.update("INSERT INTO identity.workspaces(workspace_id,tenant_id,name) VALUES (?,?,'Writer test')", workspace, tenant);
        admin.update("INSERT INTO identity.workspace_memberships(workspace_membership_id,tenant_id,workspace_id,tenant_membership_id,identity_id) VALUES (?,?,?,?,?)",
                member, tenant, workspace, tenantMember, actor);

        assertThat(runtime.queryForObject("SELECT session_user", String.class)).isEqualTo("ld_s01_app");
        assertThat(runtime.queryForObject("SELECT has_table_privilege('ld_s01_app','identity.repository_grants','INSERT')", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_table_privilege('ld_s01_http_writer','identity.repository_grants','INSERT')", Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("SELECT has_table_privilege('ld_s01_http_writer','identity.workspace_memberships','UPDATE')", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT rolbypassrls OR rolsuper OR rolcreaterole FROM pg_roles WHERE rolname='ld_s01_http_writer'", Boolean.class)).isFalse();
        assertThatThrownBy(() -> runtime.update("INSERT INTO identity.repository_grants(tenant_id,workspace_id,workspace_membership_id,repository_connection_id,provenance,operator_identity_id) VALUES (?,?,?,?,?,?)",
                tenant, workspace, member, connection, "Direct app write denied", actor)).isInstanceOf(RuntimeException.class);

        UUID grant = writer.asHuman(tenant, actor, "Approved writer boundary test", () -> {}, () -> {
            assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("ld_s01_http_writer");
            assertThat(runtime.queryForObject("SELECT count(*) FROM identity.tenants WHERE tenant_id=?", Integer.class,
                    otherTenant)).isZero();
            return grants.saveAndFlush(new RepositoryGrant(tenant, workspace, member, connection, actor,
                    "Pending authoritative S02 binding")).getGrantId();
        });
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.repository_grants WHERE grant_id=?", Integer.class, grant)).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT actor_identity_id FROM identity.authorization_audit WHERE target_id=?", UUID.class, grant)).isEqualTo(actor);
        assertThat(admin.queryForObject("SELECT actor_kind FROM identity.authorization_audit WHERE target_id=?", String.class, grant)).isEqualTo("USER");
        writer.asHuman(tenant, actor, "Verify narrow update privilege", () -> {}, () -> {
            RepositoryGrant loaded = grants.findById(grant).orElseThrow();
            loaded.verifyFromS02(7);
            grants.saveAndFlush(loaded);
            return null;
        });
        assertThat(admin.queryForObject("SELECT verified_binding_revision FROM identity.repository_grants WHERE grant_id=?", Long.class, grant)).isEqualTo(7);
        writer.asHuman(tenant, actor, "Revoke narrow update privilege", () -> {}, () -> {
            RepositoryGrant loaded = grants.findById(grant).orElseThrow();
            loaded.revoke();
            grants.saveAndFlush(loaded);
            return null;
        });
        assertThat(admin.queryForObject("SELECT status FROM identity.repository_grants WHERE grant_id=?", String.class, grant)).isEqualTo("REVOKED");
        assertThatThrownBy(() -> writer.asHuman(tenant, actor, "Deny cross tenant writer", () -> {}, () ->
                grants.saveAndFlush(new RepositoryGrant(UUID.randomUUID(), workspace, member, UUID.randomUUID(), actor,
                        "Pending authoritative S02 binding")))).isInstanceOf(RuntimeException.class);
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("ld_s01_app");
        assertThat(runtime.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class)).isNullOrEmpty();

        long revision = admin.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id=?", Long.class, tenant);
        assertThatThrownBy(() -> writer.asHuman(tenant, actor, "Rollback writer boundary test", () -> {}, () -> {
            grants.saveAndFlush(new RepositoryGrant(tenant, workspace, member, UUID.randomUUID(), actor,
                    "Pending authoritative S02 binding"));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(admin.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id=?", Long.class, tenant)).isEqualTo(revision);
    }
}
