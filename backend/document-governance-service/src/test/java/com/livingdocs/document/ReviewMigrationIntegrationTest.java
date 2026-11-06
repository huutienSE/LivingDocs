package com.livingdocs.document;

import java.net.URI;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReviewMigrationIntegrationTest extends PostgresTestSupport {
    @Test
    void upgradingExistingV1_shouldPreserveHistoryAndDefaultRevision() throws Exception {
        String database = "review_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + URI.create(databaseUrl().substring(5)).getRawAuthority() + "/" + database;
        String adminPassword = CI_DATABASE ? System.getenv("S04_TEST_ADMIN_PASSWORD") : "test-only";
        try (var connection = DriverManager.getConnection(url, "postgres", adminPassword); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA document AUTHORIZATION ld_s04_migrator");
            var config = Flyway.configure().dataSource(url, "ld_s04_migrator", CI_DATABASE ? System.getenv("FLYWAY_PASSWORD") : "test-only")
                    .schemas("document").defaultSchema("document").createSchemas(false).locations("classpath:db/migration");
            config.target("1").load().migrate();
            UUID tenant = UUID.randomUUID(), workspace = UUID.randomUUID(), document = UUID.randomUUID(),
                    version = UUID.randomUUID(), collection = UUID.randomUUID(), actor = UUID.randomUUID();
            sql.execute("INSERT INTO document.document_collections(collection_id,tenant_id,workspace_id,name) VALUES ('" + collection + "','" + tenant + "','" + workspace + "','legacy')");
            sql.execute("INSERT INTO document.documents(document_id,tenant_id,collection_id,title,ownership_ref) VALUES ('" + document + "','" + tenant + "','" + collection + "','legacy','legacy')");
            sql.execute("INSERT INTO document.document_versions(document_version_id,tenant_id,document_id,version_no,state,review_stage,creator_actor_ref) VALUES ('" + version + "','" + tenant + "','" + document + "',1,'IN_REVIEW','STAFF_REVIEW','legacy')");
            sql.execute("INSERT INTO document.review_decisions(tenant_id,document_version_id,reviewer_identity_id,decision,reason,round_no) VALUES ('" + tenant + "','" + version + "','" + actor + "','RETURN_FOR_REVISION','legacy feedback',1)");
            sql.execute("INSERT INTO document.content_revisions(tenant_id,document_version_id,revision_no,content_ref,editor_identity_id) VALUES ('" + tenant + "','" + version + "',1,'legacy-content','" + actor + "')");
            sql.execute("INSERT INTO document.document_audits(tenant_id,document_id,document_version_id,workspace_id,actor_ref,action,previous_state,new_state) VALUES ('" + tenant + "','" + document + "','" + version + "','" + workspace + "','legacy','legacy-return','IN_REVIEW','DRAFT')");
            assertThat(config.target("2").load().migrate().migrationsExecuted).isEqualTo(1);
            UUID decision = UUID.randomUUID();
            sql.execute("INSERT INTO document.review_decisions(review_decision_id,tenant_id,document_version_id,reviewer_identity_id,decision,round_no,idempotency_key,expected_revision,resulting_revision,resulting_state,resulting_stage) VALUES ('" + decision + "','" + tenant + "','" + version + "','" + actor + "','PASS',2,'v2-key',0,1,'IN_REVIEW','MANAGER_APPROVAL')");
            sql.execute("INSERT INTO document.document_audits(tenant_id,document_id,document_version_id,workspace_id,actor_ref,action,new_state,metadata) VALUES ('" + tenant + "','" + document + "','" + version + "','" + workspace + "','legacy','STAFF_REVIEW_PASS','IN_REVIEW','{\"decisionId\":\"" + decision + "\"}')");
            assertThat(config.target("latest").load().migrate().migrationsExecuted).isEqualTo(1);
            try (var rows = sql.executeQuery("SELECT response_snapshot->>'documentId',response_snapshot->>'revision' FROM document.review_decisions WHERE idempotency_key='v2-key'")) {
                rows.next(); assertThat(rows.getString(1)).isEqualTo(document.toString()); assertThat(rows.getString(2)).isEqualTo("1");
            }
            try (var rows = sql.executeQuery("SELECT revision, state, review_stage FROM document.document_versions")) {
                rows.next();
                assertThat(rows.getLong(1)).isZero();
                assertThat(rows.getString(2)).isEqualTo("IN_REVIEW");
                assertThat(rows.getString(3)).isEqualTo("STAFF_REVIEW");
            }
            try (var rows = sql.executeQuery("SELECT reason, idempotency_key FROM document.review_decisions")) {
                rows.next(); assertThat(rows.getString(1)).isEqualTo("legacy feedback"); assertThat(rows.getString(2)).isNull();
            }
            try (var rows = sql.executeQuery("SELECT content_ref FROM document.content_revisions")) {
                rows.next(); assertThat(rows.getString(1)).isEqualTo("legacy-content");
            }
            try (var rows = sql.executeQuery("SELECT action FROM document.document_audits")) {
                rows.next(); assertThat(rows.getString(1)).isEqualTo("legacy-return");
            }
            config.load().validate();
        }
    }
}
