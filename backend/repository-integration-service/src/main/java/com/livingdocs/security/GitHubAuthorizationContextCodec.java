package com.livingdocs.security;

import com.livingdocs.config.GitHubAuthorizationProperties;
import com.livingdocs.exception.GitHubAuthorizationException;
import com.livingdocs.model.GitHubAuthorizationContextBinding;
import com.livingdocs.model.GitHubAuthorizationError;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Standard AES-256-GCM with separate key rings and purpose/context-bound AAD. */
@Component @RequiredArgsConstructor
public class GitHubAuthorizationContextCodec {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private final GitHubAuthorizationProperties properties;
    private final JsonMapper json;

    public Prepared prepare(GitHubAuthorizationContextBinding binding) {
        try {
            String nonce = random(), verifier = random();
            String stateVersion = properties.getStateKeyVersion(), credentialVersion = properties.getCredentialKeyVersion();
            if (MessageDigest.isEqual(key(properties.getStateKeyDirectory(), stateVersion).getEncoded(),
                    key(properties.getCredentialKeyDirectory(), credentialVersion).getEncoded())) throw new IllegalArgumentException();
            byte[] state = json.writeValueAsBytes(Map.of("contextId", binding.getContextId(), "tenantId", binding.getTenantId(),
                    "workspaceId", binding.getWorkspaceId(), "actorIdentityId", binding.getActorIdentityId(),
                    "appId", binding.getAppId(), "appConfigVersion", binding.getAppConfigVersion(),
                    "expiresAt", binding.getExpiresAt().getEpochSecond(), "browserNonceDigest", digest(nonce)));
            String opaque = stateVersion + "." + encrypt(properties.getStateKeyDirectory(), stateVersion,
                    "livingdocs:github:state:" + stateVersion, state);
            if (opaque.length() > 2048) throw new IllegalArgumentException();
            String credential = encrypt(properties.getCredentialKeyDirectory(), credentialVersion,
                    credentialAad(binding, credentialVersion), json.writeValueAsBytes(Map.of("verifier", verifier)));
            return new Prepared(opaque, nonce, URL.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII))), credential, stateVersion, credentialVersion);
        } catch (Exception failure) { throw unavailable(); }
    }

    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw unavailable(); }
    }

    public DecodedState verifyState(String state, Map<String, String> cookies) {
        try {
            if (state == null || state.length() > 2048) throw invalidState();
            String[] envelope = state.split("\\.", -1);
            if (envelope.length != 2 || !envelope[0].matches("[A-Za-z0-9_-]{1,32}")) throw invalidState();
            byte[] payload;
            try { payload = decrypt(properties.getStateKeyDirectory(), envelope[0], "livingdocs:github:state:" + envelope[0], envelope[1]); }
            catch (javax.crypto.AEADBadTagException forged) { throw invalidState(); }
            var node = json.readTree(payload);
            var binding = new GitHubAuthorizationContextBinding(uuid(node.path("contextId")), uuid(node.path("tenantId")),
                    uuid(node.path("workspaceId")), uuid(node.path("actorIdentityId")), node.path("appId").asString(""),
                    node.path("appConfigVersion").asString(""), java.time.Instant.ofEpochSecond(Long.parseLong(node.path("expiresAt").toString())));
            if (!binding.getAppId().matches("[1-9][0-9]{0,18}") || Long.parseLong(binding.getAppId()) <= 0
                    || !binding.getAppConfigVersion().matches("[0-9a-f]{64}")
                    || !java.time.Instant.now().isBefore(binding.getExpiresAt())
                    || binding.getExpiresAt().isAfter(java.time.Instant.now().plusSeconds(900))) throw invalidState();
            String nonce = cookies.get("__Secure-ld-gh-" + binding.getContextId());
            String expected = node.path("browserNonceDigest").asString("");
            if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{43}") || !expected.matches("[0-9a-f]{64}")
                    || !MessageDigest.isEqual(digest(nonce).getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))) throw invalidState();
            return new DecodedState(binding, expected, envelope[0]);
        } catch (GitHubAuthorizationException expected) { throw expected; }
        catch (java.nio.file.NoSuchFileException missingKey) { throw unavailable(); }
        catch (Exception malformed) { throw invalidState(); }
    }

    public tools.jackson.databind.JsonNode openCredential(GitHubAuthorizationContextBinding binding, String version, String ciphertext) {
        try { return json.readTree(decrypt(properties.getCredentialKeyDirectory(), version, credentialAad(binding, version), ciphertext)); }
        catch (Exception failed) { throw new GitHubAuthorizationException(GitHubAuthorizationError.AUTHORIZATION_CONTEXT_UNAVAILABLE); }
    }

    public String sealCredential(GitHubAuthorizationContextBinding binding, String version, Map<String, Object> payload) {
        try { return encrypt(properties.getCredentialKeyDirectory(), version, credentialAad(binding, version), json.writeValueAsBytes(payload)); }
        catch (Exception failed) { throw unavailable(); }
    }

    private static byte[] decrypt(String directory, String version, String aad, String ciphertext) throws Exception {
        if (ciphertext == null || ciphertext.length() > 16384 || !ciphertext.matches("[A-Za-z0-9_-]+")) throw invalidState();
        byte[] encoded = Base64.getUrlDecoder().decode(ciphertext);
        if (encoded.length < 28) throw invalidState();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(directory, version), new GCMParameterSpec(128, encoded, 0, 12));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(encoded, 12, encoded.length - 12);
    }

    private static java.util.UUID uuid(tools.jackson.databind.JsonNode node) {
        String value = node.asString("");
        java.util.UUID id = java.util.UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value) || id.equals(new java.util.UUID(0, 0))) throw invalidState();
        return id;
    }
    private static GitHubAuthorizationException invalidState() { return new GitHubAuthorizationException(GitHubAuthorizationError.OAUTH_STATE_INVALID); }

    @Getter @RequiredArgsConstructor
    public static final class DecodedState {
        private final GitHubAuthorizationContextBinding binding;
        private final String nonceDigest;
        private final String stateKeyVersion;
    }

    private static String random() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return URL.encodeToString(bytes); }

    private static String credentialAad(GitHubAuthorizationContextBinding b, String version) {
        return "livingdocs:github:credential:" + version + ":" + b.getContextId() + ":" + b.getTenantId() + ":"
                + b.getWorkspaceId() + ":" + b.getActorIdentityId() + ":" + b.getAppId() + ":" + b.getAppConfigVersion();
    }

    private static String encrypt(String directory, String version, String aad, byte[] plaintext) throws Exception {
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(directory, version), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext);
        return URL.encodeToString(ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array());
    }

    private static SecretKeySpec key(String directory, String version) throws Exception {
        if (!version.matches("[A-Za-z0-9_-]{1,32}") || directory.isBlank()) throw new IllegalArgumentException();
        Path root = Path.of(directory).toRealPath();
        Path file = root.resolve(version + ".key").toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IllegalArgumentException();
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(129); }
        if (bytes.length > 128) throw new IllegalArgumentException();
        byte[] raw = Base64.getDecoder().decode(new String(bytes, StandardCharsets.US_ASCII).strip());
        if (raw.length != 32) throw new IllegalArgumentException();
        return new SecretKeySpec(raw, "AES");
    }

    private static GitHubAuthorizationException unavailable() {
        return new GitHubAuthorizationException(GitHubAuthorizationError.GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE);
    }

    @Getter @RequiredArgsConstructor
    public static final class Prepared {
        private final String state;
        private final String cookieNonce;
        private final String challenge;
        private final String credentialCiphertext;
        private final String stateKeyVersion;
        private final String credentialKeyVersion;
    }
}
