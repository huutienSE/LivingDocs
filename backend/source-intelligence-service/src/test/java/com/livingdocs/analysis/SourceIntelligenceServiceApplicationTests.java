package com.livingdocs.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers
class SourceIntelligenceServiceApplicationTests {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
			.withDatabaseName("livingdocs")
			.withUsername("postgres")
			.withPassword("postgres")
			.withInitScript("s03-test-schema.sql");

	@DynamicPropertySource
	static void isolatedDatabase(DynamicPropertyRegistry registry) {
		registry.add("DB_URL", POSTGRES::getJdbcUrl);
		registry.add("DB_SCHEMA", () -> "intelligence");
		registry.add("DB_USERNAME", () -> "ld_s03_app");
		registry.add("DB_PASSWORD", () -> "test_app_password");
		registry.add("FLYWAY_USERNAME", POSTGRES::getUsername);
		registry.add("FLYWAY_PASSWORD", POSTGRES::getPassword);
	}

	@Test
	void contextLoads() {
	}
}
