package com.livingdocs.document;

import com.livingdocs.document.api.controller.VersionEtags;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ReviewRestartIntegrationTest extends PostgresTestSupport {
    private ConfigurableApplicationContext start() {
        var options = new HashMap<String, Object>(properties());
        options.put("server.port", 0);
        String[] args = options.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(DocumentGovernanceServiceApplication.class, ReviewSourceValidationTestConfiguration.class).run(args);
    }

    @Test
    void httpDecision_shouldSurviveApplicationRestartWithSameDatabase() throws Exception {
        var http = HttpClient.newHttpClient();
        ReviewApiIntegrationTest.Fixture f;
        String token, payload, originalBody;
        try (var first = start()) {
            f = ReviewApiIntegrationTest.fixture();
            var claims = new JWTClaimsSet.Builder().subject(f.actor().toString()).issuer(ISSUER).audience("s04-test")
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("tenant_id", f.tenant().toString()).claim("tenant_active", true)
                    .claim("workspace_grants", List.of(Map.of("workspace_id", f.workspace().toString(), "active", true,
                            "roles", List.of("STAFF"), "repository_ids", List.of(f.repository().toString())))).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            token = jwt.serialize();
            payload = first.getBean(JsonMapper.class).writeValueAsString(Map.of("decision", "PASS", "evidenceRefs", List.of(f.evidence())));
            int port = ((WebServerApplicationContext) first).getWebServer().getPort();
            var response = http.send(post(port, f.version(), token, payload), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            originalBody = response.body();
        }
        // A new application context, HTTP server, connection pool and Flyway validation over the same PostgreSQL DB.
        try (var restarted = start()) {
            int port = ((WebServerApplicationContext) restarted).getWebServer().getPort();
            var jdbc = restarted.getBean(com.livingdocs.document.infrastructure.persistence.DocumentVersionRepository.class);
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    restarted.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            var persisted = tx.execute(status -> {
                jdbc.setTenant(f.tenant());
                return jdbc.find(f.version(), f.tenant(), false).orElseThrow();
            });
            assertThat(persisted.state().name()).isEqualTo("IN_REVIEW");
            assertThat(persisted.reviewStage().name()).isEqualTo("MANAGER_APPROVAL");
            assertThat(persisted.revision()).isEqualTo(1);
            var retry = http.send(post(port, f.version(), token, payload), HttpResponse.BodyHandlers.ofString());
            assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
            assertThat(retry.body()).isEqualTo(originalBody);
            assertThat(ReviewApiIntegrationTest.scalar("SELECT count(*) FROM document.review_decisions WHERE document_version_id=?", f.version())).isEqualTo(1L);
            assertThat(ReviewApiIntegrationTest.scalar("SELECT count(*) FROM document.document_audits WHERE document_version_id=?", f.version())).isEqualTo(1L);
            assertThat(ReviewApiIntegrationTest.scalar("SELECT metadata->>'newReviewStage' FROM document.document_audits WHERE document_version_id=?", f.version()))
                    .isEqualTo("MANAGER_APPROVAL");
            assertThat(ReviewApiIntegrationTest.scalar("SELECT metadata->'sourceValidation'->>'validationId' FROM document.document_audits WHERE document_version_id=?", f.version()))
                    .isEqualTo("test-validation-" + f.version());
            assertThat(restarted.getBean(ReviewSourceValidationTestConfiguration.TestValidator.class).calls(f.version())).isZero();
        }
    }

    private HttpRequest post(int port, UUID version, String token, String payload) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/document-versions/" + version + "/staff-decisions"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("If-Match", VersionEtags.format(version, 0)).header("Idempotency-Key", "survives-restart")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
    }
}
