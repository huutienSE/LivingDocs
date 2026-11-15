package com.livingdocs.gateway.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayJwtTrustTest {

    private HttpServer server;
    private KeyPair pair;
    private ReactiveJwtDecoder decoder;
    private final AtomicInteger jwksRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
        String jwks = new JWKSet(new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .keyID("current").build()).toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            jwksRequests.incrementAndGet();
            byte[] bytes = jwks.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        decoder = new GatewayJwtTrustConfig().jwtDecoder(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json",
                "urn:livingdocs:s01", "livingdocs-api");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void verifiesRealJwksSignatureAndIdentitySubject() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(decoder.decode(token("current", "urn:livingdocs:s01", "livingdocs-api",
                id.toString(), Instant.now().minusSeconds(1), Instant.now().plusSeconds(900))).block()
                .getSubject()).isEqualTo(id.toString());
    }

    @Test
    void rejectsWrongIssuerAudienceUnknownKidAndFutureNbf() throws Exception {
        assertRejected(token("current", "urn:other", "livingdocs-api", UUID.randomUUID().toString(),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900)));
        assertRejected(token("current", "urn:livingdocs:s01", "other", UUID.randomUUID().toString(),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900)));
        assertRejected(token("unknown", "urn:livingdocs:s01", "livingdocs-api", UUID.randomUUID().toString(),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900)));
        assertRejected(token("current", "urn:livingdocs:s01", "livingdocs-api", UUID.randomUUID().toString(),
                Instant.now().plusSeconds(120), Instant.now().plusSeconds(900)));
    }

    @Test
    void rejectsExpiredTamperedAndMissingJwks() throws Exception {
        String expired = token("current", "urn:livingdocs:s01", "livingdocs-api", UUID.randomUUID().toString(),
                Instant.now().minusSeconds(900), Instant.now().minusSeconds(60));
        assertRejected(expired);
        String valid = token("current", "urn:livingdocs:s01", "livingdocs-api", UUID.randomUUID().toString(),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900));
        assertRejected(valid.substring(0, valid.length() - 2) + "xx");
        server.stop(0);
        ReactiveJwtDecoder unavailable = new GatewayJwtTrustConfig().jwtDecoder(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json",
                "urn:livingdocs:s01", "livingdocs-api");
        assertThatThrownBy(() -> unavailable.decode(valid).block())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void cachedKnownKeyStillVerifiesWhileUnknownKidFailsClosedWhenJwksStops() throws Exception {
        String first = token("current", "urn:livingdocs:s01", "livingdocs-api", UUID.randomUUID().toString(),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900));
        decoder.decode(first).block();
        server.stop(0);
        String second = token("current", "urn:livingdocs:s01", "livingdocs-api", UUID.randomUUID().toString(),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900));
        assertThat(decoder.decode(second).block()).isNotNull();
        String unknown = token("unknown", "urn:livingdocs:s01", "livingdocs-api", UUID.randomUUID().toString(),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(900));
        assertRejected(unknown);
    }

    @Test
    void rejectsAlgorithmSubstitution() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("urn:livingdocs:s01").audience(List.of("livingdocs-api"))
                .subject(UUID.randomUUID().toString())
                .issueTime(Date.from(Instant.now()))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(1)))
                .expirationTime(Date.from(Instant.now().plusSeconds(900))).build();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .keyID("current").build(), claims);
        forged.sign(new MACSigner(new byte[32]));
        assertRejected(forged.serialize());
    }

    @Test
    void enforcesThirtySecondClockSkew() throws Exception {
        String withinSkew = token("current", "urn:livingdocs:s01", "livingdocs-api",
                UUID.randomUUID().toString(), Instant.now().minusSeconds(60),
                Instant.now().minusSeconds(20));
        assertThat(decoder.decode(withinSkew).block()).isNotNull();
        String outsideSkew = token("current", "urn:livingdocs:s01", "livingdocs-api",
                UUID.randomUUID().toString(), Instant.now().minusSeconds(60),
                Instant.now().minusSeconds(45));
        assertRejected(outsideSkew);
    }

    @Test
    void unknownKidDoesNotTriggerUnboundedRefetches() throws Exception {
        decoder.decode(token("current", "urn:livingdocs:s01", "livingdocs-api",
                UUID.randomUUID().toString(), Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(900))).block();
        int afterPrime = jwksRequests.get();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertRejected(token("unknown", "urn:livingdocs:s01", "livingdocs-api",
                    UUID.randomUUID().toString(), Instant.now().minusSeconds(1),
                    Instant.now().plusSeconds(900)));
        }
        assertThat(jwksRequests.get() - afterPrime).isLessThanOrEqualTo(1);
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token).block()).isInstanceOf(RuntimeException.class);
    }

    private String token(String kid, String issuer, String audience, String subject,
            Instant notBefore, Instant expiration) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).audience(List.of(audience)).subject(subject)
                .issueTime(Date.from(notBefore)).notBeforeTime(Date.from(notBefore))
                .expirationTime(Date.from(expiration)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
        return jwt.serialize();
    }
}
