package com.livingdocs.gateway;

import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.*;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Actual TCP response starts immediately then stalls: headers alone must not satisfy the route deadline. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "JWT_ISSUER_URI=urn:livingdocs:s01","JWT_AUDIENCE=livingdocs-api","management.server.port=0"})
class GitHubAuthorizationGatewayDeadlineTest {
    private static final DisposableServer UPSTREAM=HttpServer.create().host("127.0.0.1").port(0)
            .handle((request,response)->response.header("Content-Type","application/json")
                    .sendString(Flux.concat(Mono.just("{\"data\":"),Mono.delay(Duration.ofSeconds(16)).map(t->"{}}"))))
            .bindNow();
    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry properties) {
        properties.add("S02_SERVICE_URI",()->"http://127.0.0.1:"+UPSTREAM.port());
    }
    @LocalServerPort int port;
    @MockitoBean ReactiveJwtDecoder decoder;
    @AfterAll static void stop(){UPSTREAM.disposeNow();}
    @Test void aCommittedPartialBodyIsAbortedWithinTwelveSecondsInsteadOfCompletingAsSuccess() {
        Instant now=Instant.now();
        when(decoder.decode("deadline-fixture")).thenReturn(Mono.just(Jwt.withTokenValue("deadline-fixture")
                .header("alg","RS256").subject("fixture").issuedAt(now).expiresAt(now.plusSeconds(60)).build()));
        long started=System.nanoTime();
        assertThrows(RuntimeException.class,()->HttpClient.create().headers(headers->headers.set("Authorization","Bearer deadline-fixture"))
                .get().uri("http://127.0.0.1:"+port+"/api/v1/integrations/github/authorization?workspaceId=fixture")
                .responseSingle((response,body)->{
                    assertEquals(200,response.status().code(),"Upstream headers arrived before the body stalled");
                    assertEquals("no-store",response.responseHeaders().get("Cache-Control"));
                    return body.asString();
                }).block(Duration.ofSeconds(15)));
        assertTrue(System.nanoTime()-started<Duration.ofMillis(14000).toNanos(),"Whole body must stop on the 12s route deadline");
    }
}
