package com.livingdocs.config;

import com.livingdocs.client.GitHubAuthorizationIo;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Cryptographic receiver seam with real HTTP JWKS and exact PR2/PR3 claims; not S01 E2E. */
class S01WorkloadTrustTests {
    private HttpServer server;
    private RSAKey key;
    private GitHubAuthorizationIo io;
    private org.springframework.security.oauth2.jwt.JwtDecoder decoder;
    private final S01WorkloadTrustConfiguration trust=new S01WorkloadTrustConfiguration();
    private final UUID tenant=UUID.randomUUID(),workspace=UUID.randomUUID(),repository=UUID.randomUUID();
    @BeforeEach void prepare() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);var pair=generator.generateKeyPair();
        key=new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey(pair.getPrivate()).keyID("fixture-s01").build();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/jwks",exchange->{
            byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });server.start();io=new GitHubAuthorizationIo();
        decoder=trust.s01WorkloadDecoder("http://127.0.0.1:"+server.getAddress().getPort()+"/jwks",true,io);
    }
    @AfterEach void close(){server.stop(0);io.close();}
    @Test void acceptsTheRealS01UrnIssuerAndExactBindingScope() throws Exception {
        var identity=trust.s01WorkloadIdentityMapper().authenticate(decoder.decode(token("valid")));
        assertEquals(tenant,identity.authorize(repository).tenantId());
        assertEquals(workspace,identity.authorize(repository).workspaceId());
        assertEquals("workload:s01",identity.getName());
        assertThrows(com.livingdocs.exception.SourceApiException.class,()->identity.authorize(UUID.randomUUID()));
    }
    @ParameterizedTest @ValueSource(strings={"issuer","audience","multi-audience","expired","long-lived","token-use","kid"})
    void invalidTrustCannotBecomeAServiceIdentity(String fault) throws Exception {
        String raw=token(fault);assertThrows(org.springframework.security.oauth2.jwt.JwtException.class,()->decoder.decode(raw));
    }
    @ParameterizedTest @ValueSource(strings={"subject","path","method","operation","scope","action"})
    void signedButWrongReceiverClaimsAreRejected(String fault) throws Exception {
        var jwt=decoder.decode(token(fault));
        assertThrows(org.springframework.security.oauth2.server.resource.InvalidBearerTokenException.class,
                ()->trust.s01WorkloadIdentityMapper().authenticate(jwt));
    }
    private String token(String fault) throws Exception {
        Instant now=Instant.now();
        var claims=new JWTClaimsSet.Builder().issuer(fault.equals("issuer") ? "https://other.test" : "urn:livingdocs:s01")
                .audience(fault.equals("multi-audience") ? List.of("livingdocs-s02-internal","livingdocs-api")
                        : List.of(fault.equals("audience") ? "livingdocs-api" : "livingdocs-s02-internal"))
                .subject(fault.equals("subject") ? "workload:s04" : "workload:s01")
                .issueTime(Date.from(fault.equals("expired") ? now.minusSeconds(120) : now)).notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(fault.equals("expired") ? now.minusSeconds(60) : now.plusSeconds(fault.equals("long-lived") ? 900 : 60)))
                .jwtID(UUID.randomUUID().toString()).claim("token_use",fault.equals("token-use") ? "user" : "service")
                .claim("operation",fault.equals("operation") ? "source:validate" : "repository-binding:verify")
                .claim("http_method",fault.equals("method") ? "GET" : "POST")
                .claim("http_path",fault.equals("path") ? "/internal/v1/source/resolve" : "/internal/v1/repository-bindings/verify")
                .claim("tenant_id",fault.equals("scope") ? "not-a-uuid" : tenant.toString())
                .claim("workspace_id",workspace.toString()).claim("repository_connection_id",repository.toString());
        if(fault.equals("action"))claims.claim("action","DOCUMENT_AUTHOR");
        var header=new JWSHeader.Builder(JWSAlgorithm.RS256);if(!fault.equals("kid"))header.keyID("fixture-s01");
        var jwt=new SignedJWT(header.build(),claims.build());jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
}
