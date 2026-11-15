package com.livingdocs.identity;

import com.livingdocs.identity.security.JwtKeyManager;
import com.livingdocs.identity.security.JwtTokenIssuer;
import com.livingdocs.identity.controller.AuthController;
import com.livingdocs.identity.exception.GlobalExceptionHandler;
import com.livingdocs.identity.exception.InvalidCredentialsException;
import com.livingdocs.identity.security.RedisLoginRateLimiter.LoginSecurityUnavailableException;
import com.livingdocs.identity.service.LoginService;
import com.livingdocs.identity.service.RegistrationService;
import com.nimbusds.jose.jwk.JWKSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwtIssuerTest {

    @Test
    void issuedTokenVerifiesWithPublishedPublicKeyAndHasOnlyTrustedClaims() throws Exception {
        KeyPair pair = keyPair();
        JwtKeyManager keys = manager(pair);
        UUID identityId = UUID.randomUUID();
        String token = new JwtTokenIssuer(keys, "urn:livingdocs:s01", "livingdocs-api").issue(identityId);

        JWKSet jwks = JWKSet.parse(keys.publicJwksJson());
        assertThat(jwks.getKeys()).hasSize(1);
        assertThat(jwks.getKeys().getFirst().isPrivate()).isFalse();
        assertThat(jwks.getKeys().getFirst().getKeyID()).isNotBlank();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey) pair.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        Jwt jwt = decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(identityId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("urn:livingdocs:s01");
        assertThat(jwt.getAudience()).containsExactly("livingdocs-api");
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getNotBefore()).isNotNull();
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(900));
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsKey("kid");
        assertThat(jwt.getClaims()).doesNotContainKeys("tenant_id", "roles", "permissions");

        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void wrongOrShortKeyFailsClosed() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        Path privatePath = pem("PRIVATE KEY", first.getPrivate().getEncoded());
        Path wrongPublicPath = pem("PUBLIC KEY", second.getPublic().getEncoded());
        assertThatThrownBy(() -> new JwtKeyManager(privatePath.toString(), wrongPublicPath.toString(), "")
                .signingKey()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rotationPublishesCurrentAndPreviousPublicKeysOnly() throws Exception {
        KeyPair current = keyPair();
        KeyPair previous = keyPair();
        JwtKeyManager keys = new JwtKeyManager(
                pem("PRIVATE KEY", current.getPrivate().getEncoded()).toString(),
                pem("PUBLIC KEY", current.getPublic().getEncoded()).toString(),
                pem("PUBLIC KEY", previous.getPublic().getEncoded()).toString());
        JWKSet jwks = JWKSet.parse(keys.publicJwksJson());
        assertThat(jwks.getKeys()).hasSize(2).allMatch(key -> !key.isPrivate());
        assertThat(jwks.getKeys().get(0).getKeyID()).isNotEqualTo(jwks.getKeys().get(1).getKeyID());
    }

    @Test
    void loginHttpContractHasNoStoreAndGeneric401WithoutDatabase() throws Exception {
        LoginService login = mock(LoginService.class);
        UUID id = UUID.randomUUID();
        when(login.verifyCredentials("person@example.com", "correct-password"))
                .thenReturn(id);
        when(login.verifyCredentials("person@example.com", "wrong-password"))
                .thenThrow(new InvalidCredentialsException());
        when(login.verifyCredentials("person@example.com", "security-down"))
                .thenThrow(new LoginSecurityUnavailableException());
        JwtTokenIssuer issuer = new JwtTokenIssuer(manager(keyPair()),
                "urn:livingdocs:s01", "livingdocs-api");
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(
                        mock(RegistrationService.class), login, issuer))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator).build();

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"person@example.com\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("LOGIN_SUCCEEDED"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"person@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"person@example.com\",\"password\":\"security-down\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("LOGIN_SECURITY_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static JwtKeyManager manager(KeyPair pair) throws Exception {
        return new JwtKeyManager(pem("PRIVATE KEY", pair.getPrivate().getEncoded()).toString(),
                pem("PUBLIC KEY", pair.getPublic().getEncoded()).toString(), "");
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static Path pem(String type, byte[] encoded) throws Exception {
        Path path = Files.createTempFile("liv137-jwt-", ".pem");
        String text = "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
        Files.writeString(path, text, StandardCharsets.US_ASCII);
        path.toFile().deleteOnExit();
        return path;
    }
}
