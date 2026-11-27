package com.livingdocs.client;

import com.livingdocs.config.GitHubAppProperties;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Shared App signing only; callers retain their own token narrowing and trust boundaries. */
@Component
@EnableConfigurationProperties(GitHubAppProperties.class)
public final class GitHubAppJwtSigner {
    private static final int MAX_KEY_BYTES = 16 * 1024;
    private static final Pattern KEY_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Base64.Encoder JWT_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final GitHubAppProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    @Autowired public GitHubAppJwtSigner(GitHubAppProperties properties, ObjectMapper json) { this(properties, json, Clock.systemUTC()); }
    GitHubAppJwtSigner(GitHubAppProperties properties, ObjectMapper json, Clock clock) { this.properties=properties; this.json=json; this.clock=clock; }
    public String sign(String credentialRef) {
        if (credentialRef == null || !KEY_REFERENCE.matcher(credentialRef).matches()) throw unavailable();
        String clientId = properties.getClientId();
        String keyDirectory = properties.getKeyDirectory();
        if (clientId == null || clientId.isBlank() || keyDirectory == null || keyDirectory.isBlank()) {
            throw unavailable();
        }
        try {
            Path root = Path.of(keyDirectory).toRealPath();
            Path keyFile = root.resolve(credentialRef).toRealPath();
            if (!keyFile.startsWith(root) || !Files.isRegularFile(keyFile)) {
                throw unavailable();
            }
            Instant now = clock.instant();
            byte[] header = json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT"));
            byte[] claims = json.writeValueAsBytes(Map.of("iss", clientId, "iat", now.minusSeconds(60).getEpochSecond(),
                    "exp", now.plusSeconds(240).getEpochSecond()));
            String signingInput = JWT_ENCODER.encodeToString(header) + "." + JWT_ENCODER.encodeToString(claims);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(readPrivateKey(keyFile));
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + JWT_ENCODER.encodeToString(signer.sign());
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | JacksonException failure) {
            throw unavailable();
        }
    }

    private static PrivateKey readPrivateKey(Path keyFile) throws IOException, GeneralSecurityException {
        try (var input = Files.newInputStream(keyFile)) {
            byte[] bytes = input.readNBytes(MAX_KEY_BYTES + 1);
            if (bytes.length > MAX_KEY_BYTES) {
                throw unavailable();
            }
            String pem = new String(bytes, StandardCharsets.US_ASCII).strip();
            boolean pkcs1 = pem.startsWith("-----BEGIN RSA PRIVATE KEY-----");
            String label = pkcs1 ? "RSA PRIVATE KEY" : "PRIVATE KEY";
            String begin = "-----BEGIN " + label + "-----";
            String end = "-----END " + label + "-----";
            if (!pem.startsWith(begin) || !pem.endsWith(end)) {
                throw unavailable();
            }
            String encoded = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1 ? wrapPkcs1(der) : der));
        }
    }

    private static byte[] wrapPkcs1(byte[] rsaKey) {
        // PKCS#8 PrivateKeyInfo: phiên bản 0, định danh rsaEncryption, rồi OCTET STRING chứa PKCS#1.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(HexFormat.of().parseHex("020100300d06092a864886f70d0101010500"));
        writeDer(body, 0x04, rsaKey);
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        writeDer(key, 0x30, body.toByteArray());
        return key.toByteArray();
    }

    private static void writeDer(ByteArrayOutputStream output, int tag, byte[] value) {
        int length = value.length;
        output.write(tag);
        if (length < 128) {
            output.write(length);
        } else if (length < 256) {
            output.write(0x81);
            output.write(length);
        } else {
            output.write(0x82);
            output.write(length >>> 8);
            output.write(length);
        }
        output.writeBytes(value);
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("GitHub App signing configuration is unavailable"); }
}
