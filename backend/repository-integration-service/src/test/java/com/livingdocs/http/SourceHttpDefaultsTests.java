package com.livingdocs.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("db")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql:.*")
class SourceHttpDefaultsTests {
    @LocalServerPort private int port;
    @Autowired private MockMvc mvc;

    @Test
    void plaintextAndForwardedHeadersCannotOpenTheInternalApi() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/v1/source/resolve"))
                .header("X-Forwarded-Proto", "https").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode(), response.body());
        assertTrue(response.body().contains("SOURCE_UNAVAILABLE"));
        assertTrue(response.headers().firstValue("Location").isEmpty());
    }

    @Test
    void secureRequestWithoutIdentityIsUnauthorized() throws Exception {
        mvc.perform(post("/internal/v1/source/validate").secure(true).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("INVALID_SERVICE_IDENTITY"));
    }

    @Test
    void unconfiguredProductionTokenTrustNeverGrantsAccess() throws Exception {
        mvc.perform(post("/internal/v1/source/resolve").secure(true).header("Authorization", "Bearer test-token")
                        .header("X-Tenant-ID", "forged").header("X-User-Role", "ADMIN")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
    }
}
