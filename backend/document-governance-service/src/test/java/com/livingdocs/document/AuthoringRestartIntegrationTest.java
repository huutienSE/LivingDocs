package com.livingdocs.document;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;

class AuthoringRestartIntegrationTest extends PostgresTestSupport {
    private ConfigurableApplicationContext start() {
        var options = new HashMap<String, Object>(properties());
        options.put("server.port", 0);
        options.put("livingdocs.submit.access-context-uri", ReviewAuthorityTestServer.uri());
        options.put("livingdocs.submit.allow-insecure-http", true);
        return new SpringApplicationBuilder(DocumentGovernanceServiceApplication.class, DocumentAuthoringIntegrationTest.SourceFixtures.class)
                .run(options.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }
    @Test void apiCreatedContentAndEvidenceSurviveNewServerAndPool() throws Exception {
        DocumentAuthoringIntegrationTest.Fixture fixture;
        String version;
        try (var app = start()) {
            fixture = DocumentAuthoringIntegrationTest.fixture();
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            var created = DocumentAuthoringIntegrationTest.requestAt(port, fixture, "POST", "/api/v1/workspaces/" + fixture.workspace() + "/documents",
                    Map.of("collectionId", fixture.collection(), "repositoryConnectionId", fixture.repo(), "title", "Restart"), null);
            String document = DocumentAuthoringIntegrationTest.expect(created, 201, "DOCUMENT_CREATED").path("documentId").asString();
            var draft = DocumentAuthoringIntegrationTest.requestAt(port, fixture, "POST", "/api/v1/documents/" + document + "/versions",
                    Map.of("content", "# Survives restart", "sourceSnapshotId", fixture.snapshot()), null);
            version = DocumentAuthoringIntegrationTest.expect(draft, 201, "DOCUMENT_VERSION_CREATED").path("versionId").asString();
        }
        try (var app = start()) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            var response = DocumentAuthoringIntegrationTest.requestAt(port, fixture, "GET", "/api/v1/document-versions/" + version, null, null);
            var persisted = DocumentAuthoringIntegrationTest.expect(response, 200, "DOCUMENT_VERSION_LOADED");
            assertThat(persisted.path("content").asString()).isEqualTo("# Survives restart");
            var evidence = DocumentAuthoringIntegrationTest.requestAt(port, fixture, "GET", "/api/v1/document-versions/" + version + "/evidence", null, null);
            assertThat(DocumentAuthoringIntegrationTest.expect(evidence, 200, "DOCUMENT_EVIDENCE_LOADED").path("evidence").size()).isEqualTo(1);
        }
    }
}
