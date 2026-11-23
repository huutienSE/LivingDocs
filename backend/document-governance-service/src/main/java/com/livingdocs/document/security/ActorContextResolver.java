package com.livingdocs.document.security;

import com.livingdocs.document.client.S01ReviewAuthorizationClient;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Looks up S01 authority before the review write transaction. */
@Component
@RequiredArgsConstructor
public class ActorContextResolver {
    private final S01ReviewAuthorizationClient authority;

    public ActorContext resolve(Jwt jwt) {
        return authority.resolve(jwt);
    }
}
