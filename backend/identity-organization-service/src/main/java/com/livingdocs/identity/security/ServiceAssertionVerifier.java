package com.livingdocs.identity.security;

import com.livingdocs.identity.dto.request.AuthorizationEvaluationRequest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/*
 * 1. Kiểm tra Service JWT có đúng do S01 phát hành hay không.
 * 2. Kiểm tra JWT dành cho đúng service đích và API-039.
 * 3. Kiểm tra workload được phép yêu cầu action tương ứng.
 * 4. Kiểm tra action và resource scope khớp với request.
 * 5. Kiểm tra Service JWT gắn với đúng User JWT của An.
 * 6. Kiểm tra thời gian hiệu lực của Service JWT.
 */
@Component
public class ServiceAssertionVerifier {
    private final JwtKeyManager keys; // cung cấp public key s01 để xác minh chữ ký của service jwt
    private final String issuer;

    public ServiceAssertionVerifier(JwtKeyManager keys, @Value("${livingdocs.security.jwt.issuer}") String issuer) {
        this.keys = keys;
        this.issuer = issuer;
    }

    public void verifyForEvaluation(String header, String rawUserAuthorization, AuthorizationEvaluationRequest request) {
        if (header == null || !header.startsWith("Bearer ") || rawUserAuthorization == null || !rawUserAuthorization.startsWith("Bearer "))
            throw new BadJwtException("Missing internal credentials");

        try {
            String raw = header.substring(7); // bỏ qua 7 kí tự đầu "Bearer "
            SignedJWT signed = SignedJWT.parse(raw); // phân tích thàn header payload, chữ ký
            if (!JWSAlgorithm.RS256.equals(signed.getHeader().getAlgorithm()) || signed.getHeader().getKeyID() == null)
                throw new BadJwtException("Invalid service assertion");

            // tìm public key và xác minh chữ ký
            RSAKey key = (RSAKey) JWKSet.parse(keys.publicJwksJson()).getKeyByKeyId(signed.getHeader().getKeyID());
            if (key == null) throw new BadJwtException("Unknown service key");

            //Dùng RS256 xác minh chữ ký, issuer và các điều kiện JWT chuẩn.
            //Nếu thành công: decoder.decode() trả đối tượng Jwt
            // chứa claims để tiếp tục kiểm operation/action/resource scope.
            // Nếu thất bại: ném exception
            var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
            Jwt jwt = decoder.decode(raw);

            //Đối chiếu với actor_token_sha256 trong Service JWT đã được
            // S01 phát hành ở Flow cấp service jwt.
            // Bảo đảm Service JWT cấp cho User JWT của An
            // không thể được sử dụng với User JWT của người khác.
            Instant now = Instant.now();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawUserAuthorization.substring(7).getBytes(StandardCharsets.US_ASCII)));

            boolean valid = "service".equals(jwt.getClaimAsString("token_use"))
                    && List.of("livingdocs-s01-internal").equals(jwt.getAudience())
                    && jwt.getSubject() != null && (jwt.getSubject().equals("workload:s02")
                    && "REPOSITORY_CONNECT".equals(request.getAction())
                    || jwt.getSubject().equals("workload:s04")
                    && !"REPOSITORY_CONNECT".equals(request.getAction()))
                    && "authorization:evaluate".equals(jwt.getClaimAsString("operation"))
                    && "POST".equals(jwt.getClaimAsString("http_method"))
                    && "/internal/v1/authorization/evaluate".equals(jwt.getClaimAsString("http_path"))
                    && request.getAction().equals(jwt.getClaimAsString("action"))
                    && request.getTenantId().toString().equals(jwt.getClaimAsString("tenant_id"))
                    && request.getWorkspaceId().toString().equals(jwt.getClaimAsString("workspace_id"))
                    && java.util.Objects.equals(request.getRepositoryConnectionId() == null ? null
                            : request.getRepositoryConnectionId().toString(), jwt.getClaimAsString("repository_connection_id"))
                    && MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                            jwt.getClaimAsString("actor_token_sha256").getBytes(StandardCharsets.US_ASCII))
                    && jwt.getId() != null && UUID.fromString(jwt.getId()) != null
                    && jwt.getIssuedAt() != null && jwt.getNotBefore() != null && jwt.getExpiresAt() != null
                    && !jwt.getIssuedAt().isAfter(now.plusSeconds(5))
                    && !jwt.getNotBefore().isAfter(now.plusSeconds(5))
                    && jwt.getExpiresAt().isAfter(now)
                    && !jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(60));
            if (!valid) throw new BadJwtException("Invalid service assertion");
        } catch (BadJwtException ex) { throw ex; }
        catch (Exception ex) { throw new BadJwtException("Invalid service assertion", ex); }
    }
}
