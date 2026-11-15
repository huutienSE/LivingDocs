package com.livingdocs.identity;

import com.livingdocs.identity.entity.Identity;
import com.livingdocs.identity.exception.IdentityAlreadyExistsException;
import com.livingdocs.identity.repository.IdentityRepository;
import com.livingdocs.identity.service.RegistrationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
@Testcontainers
class RegistrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("livingdocs")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("s01-test-schema.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_SCHEMA", () -> "identity");
        registry.add("DB_USERNAME", () -> "ld_s01_app");
        registry.add("DB_PASSWORD", () -> "test_app_password");
        registry.add("FLYWAY_USERNAME", POSTGRES::getUsername);
        registry.add("FLYWAY_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired IdentityRepository identities;
    @Autowired RegistrationService registrationService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    @Test
    void registerPersistsHashedIdentityAndWrappedResponse() throws Exception {
        String email = " Person-" + UUID.randomUUID() + "@Example.com";
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, "A-secure-password-123")))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("IDENTITY_REGISTERED"))
                .andExpect(jsonPath("$.message").value("Registration completed"))
                .andExpect(jsonPath("$.data.identityId").isString())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> body = com.jayway.jsonpath.JsonPath.read(response, "$");
        assertThat(body.keySet()).containsExactlyInAnyOrder("code", "message", "data");
        Map<String, Object> data = com.jayway.jsonpath.JsonPath.read(response, "$.data");
        assertThat(data.keySet()).containsExactly("identityId");
        String id = com.jayway.jsonpath.JsonPath.read(response, "$.data.identityId");
        UUID identityId = UUID.fromString(id);
        assertThat(id).isEqualTo(identityId.toString());
        Identity saved = identities.findById(identityId).orElseThrow();
        assertThat(saved.getEmail()).isEqualTo(email.substring(1));
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.passwordHashForVerification()).isNotEqualTo("A-secure-password-123");
        assertThat(passwordEncoder.matches("A-secure-password-123", saved.passwordHashForVerification())).isTrue();
        assertThat(response).doesNotContain("password", "hash", saved.getEmail());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.tenant_memberships", Integer.class)).isZero();
    }

    @Test
    void duplicateEmailReturns409WithoutCreatingSecondIdentity() throws Exception {
        String local = "duplicate-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(local, "A-secure-password-123"))).andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(local.toUpperCase(), "Another-password-123")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.code").value("IDENTITY_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.correlationId").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(local);
        assertThat(countEmail(local)).isEqualTo(1);
    }

    @Test
    void invalidInputsReturn400AndDoNotPersist() throws Exception {
        String email = "invalid-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("not-an-email", "A-secure-password-123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, "é".repeat(40))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(countEmail(email)).isZero();
    }

    @Test
    void unsupportedMediaAndMalformedJsonReturnProblem() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.TEXT_PLAIN).content("invalid"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void concurrentRegistrationsCreateOneIdentity() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.com";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Object> first = pool.submit(() -> registerAfterLatch(email, ready, start));
            Future<Object> second = pool.submit(() -> registerAfterLatch(email.toUpperCase(), ready, start));
            ready.await();
            start.countDown();
            Object a = first.get();
            Object b = second.get();
            assertThat(a instanceof UUID || b instanceof UUID).isTrue();
            assertThat(a instanceof IdentityAlreadyExistsException || b instanceof IdentityAlreadyExistsException).isTrue();
            assertThat(countEmail(email)).isEqualTo(1);
        }
    }

    @Test
    void nonUniqueDatabaseFailureRollsBackAndIsNotReportedAsDuplicate() throws Exception {
        String email = "rollback-" + UUID.randomUUID() + "@example.com";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION identity.reject_registration() RETURNS trigger "
                    + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.email LIKE 'rollback-%' "
                    + "THEN RAISE EXCEPTION 'test database failure'; END IF; RETURN NEW; END $$");
            statement.execute("CREATE TRIGGER reject_registration BEFORE INSERT ON identity.identities "
                    + "FOR EACH ROW EXECUTE FUNCTION identity.reject_registration()");
            try {
                assertThatThrownBy(() -> registrationService.registerLocal(email, "A-secure-password-123"))
                        .isNotInstanceOf(IdentityAlreadyExistsException.class);
                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(email, "A-secure-password-123")))
                        .andExpect(status().isInternalServerError())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
                assertThat(countEmail(email)).isZero();
            } finally {
                statement.execute("DROP TRIGGER reject_registration ON identity.identities");
                statement.execute("DROP FUNCTION identity.reject_registration()");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeSwaggerAndStoredContractDescribeWrapped201() throws Exception {
        String runtime = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runtimeRef = com.jayway.jsonpath.JsonPath.read(runtime,
                "$.paths['/api/v1/auth/register'].post.responses['201'].content['application/json'].schema['$ref']");
        assertThat(runtimeRef).doesNotContain("CandidateObject");
        String runtimeSchemaName = runtimeRef.substring(runtimeRef.lastIndexOf('/') + 1);
        for (String errorStatus : new String[] {"400", "409", "415", "500"}) {
            assertThat((String) com.jayway.jsonpath.JsonPath.read(runtime,
                    "$.paths['/api/v1/auth/register'].post.responses['" + errorStatus
                            + "'].content['application/problem+json'].schema['$ref']"))
                    .isEqualTo("#/components/schemas/Problem");
        }
        assertThat((java.util.List<String>) com.jayway.jsonpath.JsonPath.read(runtime,
                "$.components.schemas.Problem.required"))
                .contains("type", "title", "status", "detail", "instance", "code", "correlationId");

        Path contract = Path.of("..", "..", "contracts", "openapi", "s01-identity.yaml");
        Map<String, Object> yaml;
        try (var reader = Files.newBufferedReader(contract)) {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(200);
            yaml = new Yaml(options).load(reader);
        }
        String contractRef = com.jayway.jsonpath.JsonPath.read(yaml,
                "$.paths['/api/v1/auth/register'].post.responses['201'].content['application/json'].schema['$ref']");
        assertThat(contractRef).isEqualTo("#/components/schemas/RegisterApiResponse");
        Map<String, Object> components = (Map<String, Object>) yaml.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> base = (Map<String, Object>) schemas.get("ApiResponseBase");
        Map<String, Object> baseFields = (Map<String, Object>) base.get("properties");
        Map<String, Object> runtimeResponse = com.jayway.jsonpath.JsonPath.read(runtime,
                "$.components.schemas['" + runtimeSchemaName + "']");
        Map<String, Object> runtimeFields = (Map<String, Object>) runtimeResponse.get("properties");
        assertThat(runtimeFields.keySet()).containsExactlyInAnyOrder("code", "message", "data");
        assertThat((List<String>) runtimeResponse.get("required"))
                .containsExactlyInAnyOrder("code", "message", "data");
        for (String field : List.of("code", "message")) {
            Map<String, Object> storedField = (Map<String, Object>) baseFields.get(field);
            Map<String, Object> runtimeField = (Map<String, Object>) runtimeFields.get(field);
            assertThat(runtimeField.get("type")).isEqualTo("string").isEqualTo(storedField.get("type"));
        }
        Map<String, Object> data = (Map<String, Object>) runtimeFields.get("data");
        assertThat(data.get("$ref")).isEqualTo("#/components/schemas/RegisterResponse");
        Map<String, Object> runtimeData = com.jayway.jsonpath.JsonPath.read(runtime,
                "$.components.schemas.RegisterResponse");
        Map<String, Object> storedData = (Map<String, Object>) schemas.get("RegisterResponse");
        assertThat((List<String>) runtimeData.get("required")).containsExactly("identityId");
        assertThat(runtimeData.get("properties")).isEqualTo(storedData.get("properties"));
        Map<String, Object> runtimeDataFields = (Map<String, Object>) runtimeData.get("properties");
        assertThat(runtimeDataFields).containsExactlyEntriesOf(
                Map.of("identityId", Map.of("type", "string", "format", "uuid")));
        Map<String, Object> storedRequest = (Map<String, Object>) schemas.get("RegisterRequest");
        Map<String, Object> runtimeRequest = com.jayway.jsonpath.JsonPath.read(runtime,
                "$.components.schemas.RegisterRequest");
        assertThat((java.util.List<String>) runtimeRequest.get("required"))
                .containsAll((java.util.List<String>) storedRequest.get("required"));
        Map<String, Object> storedRequestFields = (Map<String, Object>) storedRequest.get("properties");
        Map<String, Object> runtimeRequestFields = (Map<String, Object>) runtimeRequest.get("properties");
        for (String field : new String[] {"email", "password"}) {
            Map<String, Object> storedField = (Map<String, Object>) storedRequestFields.get(field);
            Map<String, Object> runtimeField = (Map<String, Object>) runtimeRequestFields.get(field);
            assertThat(runtimeField.get("format")).isEqualTo(storedField.get("format"));
            for (String limit : new String[] {"minLength", "maxLength"}) {
                if (storedField.containsKey(limit)) {
                    assertThat(runtimeField.get(limit)).isEqualTo(storedField.get(limit));
                }
            }
        }
        Map<String, Object> problem = (Map<String, Object>) schemas.get("Problem");
        assertThat((java.util.List<String>) problem.get("required"))
                .contains("type", "title", "status", "detail", "instance", "code", "correlationId");
    }

    private Object registerAfterLatch(String email, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            return registrationService.registerLocal(email, "A-secure-password-123");
        } catch (Exception ex) {
            return ex;
        }
    }

    private int countEmail(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM identity.identities "
                + "WHERE lower(btrim(email)) = lower(btrim(?))", Integer.class, email);
    }

    private String json(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
