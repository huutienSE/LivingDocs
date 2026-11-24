package com.livingdocs.identity;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogRevisionMigrationTest {

    @Test
    void v6AuditsAndInvalidatesEachExistingTenantExactlyOnce() {
        try (PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17")
                .withDatabaseName("livingdocs")
                .withUsername("postgres")
                .withPassword("postgres")
                .withInitScript("s01-test-schema.sql")) {
            db.start();
            Flyway throughV5 = flyway(db, "5");
            throughV5.migrate();

            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            jdbc.update("INSERT INTO identity.tenants(tenant_id, name) VALUES (?, 'First'), (?, 'Second')",
                    first, second);
            Long firstRevision = revision(jdbc, first);
            Long secondRevision = revision(jdbc, second);

            Flyway throughV6 = flyway(db, "6");
            throughV6.migrate();

            assertThat(revision(jdbc, first)).isEqualTo(firstRevision + 1);
            assertThat(revision(jdbc, second)).isEqualTo(secondRevision + 1);
            for (UUID tenant : new UUID[]{first, second}) {
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM identity.authorization_audit
                         WHERE tenant_id = ? AND action = 'CATALOG_CHANGED'
                           AND target_table = 'tenants' AND target_id = ?
                           AND authz_revision = (SELECT authz_revision FROM identity.tenants WHERE tenant_id = ?)
                        """, Integer.class, tenant, tenant, tenant)).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM identity.flyway_schema_history WHERE success", Integer.class)).isEqualTo(6);

            throughV6.migrate();
            assertThat(revision(jdbc, first)).isEqualTo(firstRevision + 1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM identity.authorization_audit WHERE action='CATALOG_CHANGED'",
                    Integer.class)).isEqualTo(2);
        }
    }

    private Flyway flyway(PostgreSQLContainer<?> db, String target) {
        return Flyway.configure()
                .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .schemas("identity")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private Long revision(JdbcTemplate jdbc, UUID tenant) {
        return jdbc.queryForObject("SELECT authz_revision FROM identity.tenants WHERE tenant_id = ?",
                Long.class, tenant);
    }
}
