package com.livingdocs.identity.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


//============
// QUẢN LÝ KHÓA
//============

@Component
public class JwtKeyManager {

    private final String privatePath;
    private final String publicPath;
    private final String previousPublicPath;
    private volatile KeyMaterial material;

    public JwtKeyManager(@Value("${livingdocs.security.jwt.private-key-path:}") String privatePath,
            @Value("${livingdocs.security.jwt.public-key-path:}") String publicPath,
            @Value("${livingdocs.security.jwt.previous-public-key-path:}") String previousPublicPath) {
        this.privatePath = privatePath;
        this.publicPath = publicPath;
        this.previousPublicPath = previousPublicPath;
    }

    public RSAKey signingKey() {
        return material().signingKey();
    }

    public String publicJwksJson() {
        return material().publicKeys().toString();
    }

    private KeyMaterial material() {
        KeyMaterial current = material;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (material == null) {
                material = load();
            }
            return material;
        }
    }

    private KeyMaterial load() {
        try {
            if (privatePath.isBlank() || publicPath.isBlank()) {
                throw new IllegalStateException("JWT signing key paths are required");
            }

            KeyFactory factory = KeyFactory.getInstance("RSA");

            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(readPem(privatePath, "PRIVATE KEY")));

            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(readPem(publicPath, "PUBLIC KEY")));

            validateKeyPair(privateKey, publicKey);

            RSAKey signingKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId(publicKey)).build();

            List<RSAKey> keys = new ArrayList<>();

            keys.add(signingKey.toPublicJWK());

            if (!previousPublicPath.isBlank()) {

                RSAPublicKey previous = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(readPem(previousPublicPath, "PUBLIC KEY")));

                if (previous.getModulus().bitLength() < 2048) {
                    throw new IllegalStateException("Previous RSA key is too short");
                }

                String previousId = keyId(previous);

                if (!previousId.equals(signingKey.getKeyID())) {
                    keys.add(new RSAKey.Builder(previous).keyID(previousId).build());
                }
            }

            return new KeyMaterial(signingKey, new JWKSet(new ArrayList<>(keys)));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT key configuration is invalid", ex);
        }
    }

    private record KeyMaterial(RSAKey signingKey, JWKSet publicKeys) {}

    private static byte[] readPem(String path, String type) throws IOException {
        String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        if (!pem.contains(begin) || !pem.contains(end)) {
            throw new IllegalStateException("Expected PEM " + type);
        }
        return Base64.getMimeDecoder().decode(pem.substring(
                pem.indexOf(begin) + begin.length(), pem.indexOf(end)));
    }

    private static String keyId(RSAPublicKey key) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
        return HexFormat.of().formatHex(hash, 0, 16);
    }

    private static void validateKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey) throws Exception {
        if (publicKey.getModulus().bitLength() < 2048
                || !publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalStateException("RSA key pair is invalid or too short");
        }
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign((PrivateKey) privateKey);
        signer.update("livingdocs-key-check".getBytes(StandardCharsets.US_ASCII));
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify((PublicKey) publicKey);
        verifier.update("livingdocs-key-check".getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(signature)) {
            throw new IllegalStateException("RSA key pair mismatch");
        }
    }
}
