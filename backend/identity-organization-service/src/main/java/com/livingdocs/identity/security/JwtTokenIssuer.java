package com.livingdocs.identity.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

//========
// PHÁT HÀNH JWT
//========
@Component
public class JwtTokenIssuer {

    public static final long EXPIRES_IN_SECONDS = 900;

    private final JwtKeyManager keys;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    @Autowired
    public JwtTokenIssuer(JwtKeyManager keys,
            @Value("${livingdocs.security.jwt.issuer}") String issuer,
            @Value("${livingdocs.security.jwt.audience}") String audience) {
        this(keys, issuer, audience, Clock.systemUTC());
    }

    JwtTokenIssuer(JwtKeyManager keys, String issuer, String audience, Clock clock) {
        this.keys = keys;
        this.issuer = issuer;
        this.audience = audience;
        this.clock = clock;
    }

    public String issue(UUID identityId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).audience(List.of(audience)).subject(identityId.toString())
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(EXPIRES_IN_SECONDS)).build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.signingKey().getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
