package com.livingdocs.identity;

import com.livingdocs.identity.exception.InvalidCredentialsException;
import com.livingdocs.identity.exception.LoginRateLimitedException;
import com.livingdocs.identity.service.LoginService;
import com.livingdocs.identity.service.RegistrationService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
@Testcontainers
class LoginIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("livingdocs")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("s01-test-schema.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine")
            .withExposedPorts(6379);

    private static final Path PEPPER = temporaryPepper();
    private static final KeyPaths KEYS = temporaryKeys();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_SCHEMA", () -> "identity");
        registry.add("DB_USERNAME", () -> "ld_s01_app");
        registry.add("DB_PASSWORD", () -> "test_app_password");
        registry.add("FLYWAY_USERNAME", POSTGRES::getUsername);
        registry.add("FLYWAY_PASSWORD", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("livingdocs.security.login.rate-limit.pepper-path", PEPPER::toString);
        registry.add("livingdocs.security.jwt.private-key-path", () -> KEYS.privatePath().toString());
        registry.add("livingdocs.security.jwt.public-key-path", () -> KEYS.publicPath().toString());
    }

    @Autowired LoginService login;
    @Autowired RegistrationService registration;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @MockitoSpyBean PasswordEncoder passwords;

    @Test
    void realCredentialWorksWithoutMembership() {
        String email = "login-" + UUID.randomUUID() + "@example.com";
        UUID id = registration.registerLocal(email, "A-secure-password-123");

        assertThat(login.verifyCredentials(" " + email.toUpperCase() + " ", "A-secure-password-123"))
                .isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.tenant_memberships", Integer.class))
                .isZero();
    }

    @Test
    void wrongAndUnknownCredentialsHaveTheSamePublicFailureAndBothRunBcrypt() {
        String email = "login-" + UUID.randomUUID() + "@example.com";
        registration.registerLocal(email, "A-secure-password-123");
        clearInvocations(passwords);

        assertThatThrownBy(() -> login.verifyCredentials(email, "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
        verify(passwords).matches(eq("wrong-password"), anyString());

        clearInvocations(passwords);
        assertThatThrownBy(() -> login.verifyCredentials("unknown-" + UUID.randomUUID() + "@example.com", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
        verify(passwords).matches(eq("wrong-password"), anyString());
    }

    @Test
    void inactiveIdentityCannotLogIn() throws Exception {
        String email = "inactive-" + UUID.randomUUID() + "@example.com";
        UUID id = registration.registerLocal(email, "A-secure-password-123");
        jdbc.update("UPDATE identity.identities SET status = 'INACTIVE' WHERE identity_id = ?", id);

        assertThatThrownBy(() -> login.verifyCredentials(email, "A-secure-password-123"))
                .isInstanceOf(InvalidCredentialsException.class);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(json(email, "A-secure-password-123")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void identifierBudgetAppliesToUnknownAccountsWithoutRevealingTheirExistence() {
        String email = "limited-" + UUID.randomUUID() + "@example.com";
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThatThrownBy(() -> login.verifyCredentials(email, "wrong-password"))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThatThrownBy(() -> login.verifyCredentials(email, "wrong-password"))
                .isInstanceOf(LoginRateLimitedException.class)
                .satisfies(ex -> assertThat(((LoginRateLimitedException) ex).getRetryAfterSeconds())
                        .isGreaterThan(0));
    }

    @Test
    void httpLoginIssuesAccessOnlyAndReturnsSafeProblems() throws Exception {
        String email = "http-login-" + UUID.randomUUID() + "@example.com";
        registration.registerLocal(email, "A-secure-password-123");
        String valid = json(email, "A-secure-password-123");
        String token = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("LOGIN_SUCCEEDED"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.data.accessToken");
        assertThat(token.split("\\.")).hasSize(3);
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());

        for (String rejectedEmail : new String[] {email, "unknown-" + UUID.randomUUID() + "@example.com"}) {
            String body = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(json(rejectedEmail, "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(rejectedEmail, "wrong-password", token);
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedLoginRequestsReturnSafeProblemWithoutToken() throws Exception {
        String[] invalidJson = {
                "{}",
                "{\"email\":\"\",\"password\":\"x\"}",
                "{\"email\":\"not-an-email\",\"password\":\"x\"}",
                "{\"email\":\"person@example.com\"}",
                json("person@example.com", "x".repeat(65)),
                json("person@example.com", "😀".repeat(19)),
                "{\"email\":\"person@example.com\",\"password\":"
        };
        for (String body : invalidJson) {
            String response = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("accessToken", "person@example.com");
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.TEXT_PLAIN).content("email=person@example.com"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void httpLoginLimitReturns429AndRetryAfter() throws Exception {
        String email = "limited-http-" + UUID.randomUUID() + "@example.com";
        for (int attempt = 0; attempt < 10; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(json(email, "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(json(email, "wrong-password")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }

    @Test
    void runtimeSwaggerListsLoginSuccessAndFailureStatuses() throws Exception {
        String base = "$.paths['/api/v1/auth/login'].post.responses";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(base + "['200']").exists())
                .andExpect(jsonPath(base + "['400']").exists())
                .andExpect(jsonPath(base + "['401']").exists())
                .andExpect(jsonPath(base + "['415']").exists())
                .andExpect(jsonPath(base + "['429'].headers['Retry-After']").exists())
                .andExpect(jsonPath(base + "['500']").exists())
                .andExpect(jsonPath(base + "['503']").exists());
    }

    @Test
    void unimplementedCandidateRouteRequiresJwtAndReturns404ForAuthenticatedIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        String email = "candidate-me-" + UUID.randomUUID() + "@example.com";
        registration.registerLocal(email, "A-secure-password-123");
        String token = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, "A-secure-password-123")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.accessToken");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private static String json(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static KeyPaths temporaryKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new KeyPaths(pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                    pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static Path pem(String type, byte[] encoded) throws IOException {
        Path path = Files.createTempFile("liv137-http-jwt-", ".pem");
        String content = "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
        Files.writeString(path, content, StandardCharsets.US_ASCII);
        path.toFile().deleteOnExit();
        return path;
    }

    private record KeyPaths(Path privatePath, Path publicPath) {}

    private static Path temporaryPepper() {
        try {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            Path path = Files.createTempFile("liv137-login-pepper-", ".bin");
            Files.write(path, secret);
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
