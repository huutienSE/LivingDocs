package com.livingdocs.identity;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

class RepositoryGrantUpgradeMigrationTest {
    @Test void v7PreservesLegacyGrantsWithoutTreatingOldVerificationAsBindingEvidence() {
        try (PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17")
                .withDatabaseName("livingdocs").withUsername("postgres").withPassword("postgres")
                .withInitScript("s01-test-schema.sql")) {
            db.start();
            JdbcTemplate bootstrap = new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), "postgres", "postgres"));
            bootstrap.execute("CREATE ROLE ld_s01_migrator LOGIN PASSWORD 'test_migrator_password'");
            bootstrap.execute("GRANT CONNECT ON DATABASE livingdocs TO ld_s01_migrator");
            bootstrap.execute("ALTER SCHEMA identity OWNER TO ld_s01_migrator");
            flyway(db, "6").migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    db.getJdbcUrl(), "postgres", "postgres"));
            UUID tenant = UUID.randomUUID(), identity = UUID.randomUUID(), tm = UUID.randomUUID();
            UUID workspace = UUID.randomUUID(), wm = UUID.randomUUID();
            UUID pending = UUID.randomUUID(), verified = UUID.randomUUID(), revoked = UUID.randomUUID();
            jdbc.update("INSERT INTO identity.identities(identity_id,email,status) VALUES (?,?,'ACTIVE')",
                    identity, identity + "@example.com");
            jdbc.update("INSERT INTO identity.tenants(tenant_id,name) VALUES (?,'Upgrade')", tenant);
            jdbc.update("INSERT INTO identity.tenant_memberships(membership_id,tenant_id,identity_id) VALUES (?,?,?)", tm, tenant, identity);
            jdbc.update("INSERT INTO identity.workspaces(workspace_id,tenant_id,name) VALUES (?,?,'Upgrade')", workspace, tenant);
            jdbc.update("INSERT INTO identity.workspace_memberships(workspace_membership_id,tenant_id,workspace_id,tenant_membership_id,identity_id) VALUES (?,?,?,?,?)", wm, tenant, workspace, tm, identity);
            for (UUID id : new UUID[]{pending, verified, revoked})
                jdbc.update("INSERT INTO identity.repository_grants(grant_id,tenant_id,workspace_id,workspace_membership_id,repository_connection_id,provenance,operator_identity_id) VALUES (?,?,?,?,?,'Existing grant before V7',?)",
                        id, tenant, workspace, wm, UUID.randomUUID(), identity);
            jdbc.update("UPDATE identity.repository_grants SET verification_status='VERIFIED',verification_source='S02',verified_at=now() WHERE grant_id=?", verified);
            jdbc.update("UPDATE identity.repository_grants SET status='REVOKED',ended_at=now() WHERE grant_id=?", revoked);
            Long beforeRevision = jdbc.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id=?", Long.class, tenant);
            flyway(db, "7").migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.repository_grants WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT verification_status FROM identity.repository_grants WHERE grant_id=?", String.class, pending)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT verification_status FROM identity.repository_grants WHERE grant_id=?", String.class, verified)).isEqualTo("VERIFIED");
            assertThat(jdbc.queryForObject("SELECT status FROM identity.repository_grants WHERE grant_id=?", String.class, revoked)).isEqualTo("REVOKED");
            assertThat(jdbc.queryForObject("SELECT verified_binding_revision FROM identity.repository_grants WHERE grant_id=?", Long.class, verified)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.repository_grants WHERE tenant_id=? AND status='ACTIVE' AND verification_status='VERIFIED' AND verification_source='S02' AND verified_binding_revision IS NOT NULL", Integer.class, tenant)).isZero();
            assertThat(jdbc.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id=?", Long.class, tenant)).isEqualTo(beforeRevision);
            flyway(db, "7").migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.flyway_schema_history WHERE success", Integer.class)).isEqualTo(7);
        }
    }

    private Flyway flyway(PostgreSQLContainer<?> db, String target) {
        return Flyway.configure().dataSource(db.getJdbcUrl(), "ld_s01_migrator", "test_migrator_password")
                .schemas("identity").locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target)).load();
    }
}
