package com.livingdocs.identity.security;

import com.livingdocs.identity.dto.request.ServiceTokenRequest;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public class ServiceJwtIssuer {
    private final JwtKeyManager keys;
    private final String issuer;

    public ServiceJwtIssuer(JwtKeyManager keys, @Value("${livingdocs.security.jwt.issuer}") String issuer) {
        this.keys = keys;
        this.issuer = issuer;
    }

    // ĐÓNG GÓI THÔNG TIN ĐƯỢC CẤP PHÉP CÀO JWT
    // GIỚI HẠN QUYỀN SỬ DỤNG
    // KÝ JWT BẰNG PRIVATEKEY CỦA S01, CÁC SERVICE CÓ THÊ RDUNGF PUBLIC KEY CỦA S01 ĐẺ XÁC THỰC
    public String issue(String workloadId, ServiceTokenRequest request) {
        Instant now = Instant.now();
        var builder = JwtClaimsSet.builder().issuer(issuer).subject("workload:" + workloadId)
                .audience(List.of(request.getAudience())).issuedAt(now).notBefore(now)
                .expiresAt(now.plusSeconds(60)).id(UUID.randomUUID().toString())
                .claim("token_use", "service").claim("operation", request.getOperation())
                .claim("http_method", request.getMethod()).claim("http_path", request.getPath())
                .claim("tenant_id", request.getTenantId().toString());
        if (request.getWorkspaceId() != null) builder.claim("workspace_id", request.getWorkspaceId().toString());
        if (request.getRepositoryConnectionId() != null) builder.claim("repository_connection_id", request.getRepositoryConnectionId().toString());
        if (request.getAction() != null) builder.claim("action", request.getAction());
        if (request.getActorTokenSha256() != null) builder.claim("actor_token_sha256", request.getActorTokenSha256());

        // NỘI DUNG ĐƯỢC XÁC NHẬN TRONG TOKEN
        var claims = builder.build();

        //  //keys.signingKey(): Khóa riêng S01 dùng để ký.
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));

        //RS256: Thuật toán ký số.
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.signingKey().getKeyID()).build();

        //encode(): Tạo JWT đã ký để chuyển cho service gọi.
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
