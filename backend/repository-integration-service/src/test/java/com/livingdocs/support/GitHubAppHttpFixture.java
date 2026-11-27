package com.livingdocs.support;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.atomic.*;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Controlled App boundary; distinct from the S01 user signing key. Never used as live evidence. */
public final class GitHubAppHttpFixture {
    public static final String COMPLETE = "{\"metadata\":\"read\",\"contents\":\"write\",\"pull_requests\":\"write\",\"checks\":\"write\"}";
    public final AtomicReference<String> permissions = new AtomicReference<>(COMPLETE);
    public final AtomicReference<String> suspension = new AtomicReference<>("null");
    public final AtomicInteger probes = new AtomicInteger();
    public final AtomicInteger status = new AtomicInteger(200);
    private final Path keyDirectory;
    public GitHubAppHttpFixture(Path root) {
        try {
            keyDirectory=Files.createDirectory(root.resolve("app"));
            var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            var pair=generator.generateKeyPair();
            Files.writeString(keyDirectory.resolve("fixture.pem"),"-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n");
        } catch (Exception failed) { throw new IllegalStateException(failed); }
    }
    public void configure(DynamicPropertyRegistry properties) {
        properties.add("livingdocs.source.github.client-id",()->"Iv1.fixture151");
        properties.add("livingdocs.source.github.key-directory",()->keyDirectory.toString());
    }
    public void handle(HttpExchange exchange) throws IOException {
        String auth=exchange.getRequestHeaders().getFirst("Authorization");
        if (auth==null || !auth.startsWith("Bearer ey") || auth.split("\\.").length!=3) throw new IOException("Expected App JWT");
        boolean app=exchange.getRequestURI().getPath().equals("/app");
        if (!app) probes.incrementAndGet();
        String body=app ? "{\"id\":151,\"slug\":\"livingdocs-test\",\"client_id\":\"Iv1.fixture151\",\"name\":\"LivingDocs Test\",\"permissions\":"+COMPLETE+",\"events\":[\"push\",\"pull_request\"]}"
                : "{\"id\":15110,\"app_id\":151,\"app_slug\":\"livingdocs-test\",\"account\":{\"id\":15120,\"login\":\"fixture-owner\"},\"repository_selection\":\"selected\",\"suspended_at\":"+suspension.get()+",\"permissions\":"+permissions.get()+"}";
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(app ? 200 : status.get(),bytes.length);
        try(var output=exchange.getResponseBody()) { output.write(bytes); }
    }
}
