package com.livingdocs.document;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL and a test-only signing authority; never connects to a developer's application DB. */
public abstract class PostgresTestSupport {
    static final boolean CI_DATABASE = "true".equals(System.getenv("S04_USE_CI_DATABASE"));
    static final PostgreSQLContainer DATABASE = CI_DATABASE ? null : new PostgreSQLContainer("postgres:17")
            .withDatabaseName("review_tests").withUsername("postgres").withPassword("test-only")
            .withInitScript("db/bootstrap.sql");
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static final String ISSUER;

    static {
        try {
            if (DATABASE != null) DATABASE.start();
            KEY = new RSAKeyGenerator(2048).keyID("review-test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            JWKS.start();
            ISSUER = "http://127.0.0.1:" + JWKS.getAddress().getPort();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> JWKS.stop(0)));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("spring.datasource.url", databaseUrl()),
                Map.entry("spring.datasource.username", "ld_s04_app"),
                Map.entry("spring.datasource.password", runtimePassword()),
                Map.entry("spring.flyway.url", databaseUrl()),
                Map.entry("spring.flyway.user", "ld_s04_migrator"),
                Map.entry("spring.flyway.password", CI_DATABASE ? System.getenv("FLYWAY_PASSWORD") : "test-only"),
                Map.entry("spring.flyway.schemas", "document"),
                Map.entry("spring.flyway.default-schema", "document"),
                Map.entry("spring.flyway.create-schemas", false),
                Map.entry("livingdocs.security.issuer-uri", ISSUER),
                Map.entry("livingdocs.security.jwk-set-uri", ISSUER + "/jwks"),
                Map.entry("livingdocs.security.audience", "s04-test"),
                Map.entry("spring.jackson.deserialization.fail-on-unknown-properties", true));
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        properties().forEach((key, value) -> registry.add(key, () -> value));
    }

    static Connection admin() throws SQLException {
        return DriverManager.getConnection(databaseUrl(), "postgres",
                CI_DATABASE ? System.getenv("S04_TEST_ADMIN_PASSWORD") : "test-only");
    }

    static String databaseUrl() { return CI_DATABASE ? System.getenv("DB_URL") : DATABASE.getJdbcUrl(); }
    static String runtimePassword() { return CI_DATABASE ? System.getenv("DB_PASSWORD") : "test-only"; }
}
