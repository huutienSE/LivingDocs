package com.livingdocs.document.client;

import com.livingdocs.document.model.SubmitActorContext;
import com.livingdocs.document.exception.*;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Maps the shared API-038 snapshot to the submit use case; wire validation stays shared. */
@Component
public class S01SubmitAuthorizationClient {
    private final S01AccessContextClient authority;
    private final URI endpoint;
    public S01SubmitAuthorizationClient(S01AccessContextClient authority,
            @Value("${livingdocs.submit.access-context-uri:}") String uri,
            @Value("${livingdocs.submit.allow-insecure-http:false}") boolean localHttp) {
        this.authority = authority;
        this.endpoint = S01AccessContextClient.endpoint(uri, localHttp);
    }
    public SubmitActorContext resolve(Jwt jwt) {
        try {
            var c = authority.resolve(jwt, endpoint);
            return new SubmitActorContext(c.identityId(), c.tenantId(), c.grants().stream()
                    .map(g -> new SubmitActorContext.Grant(g.workspaceId(), g.roles(), g.permissions(), g.repositoryIds())).toList());
        } catch (S01AuthorityException e) {
            throw switch (e.getKind()) {
                case UNAUTHENTICATED -> new SubmitException("UNAUTHENTICATED", org.springframework.http.HttpStatus.UNAUTHORIZED, "A valid S01 token is required.");
                case FORBIDDEN -> SubmitException.forbidden();
                case UNAVAILABLE -> SubmitException.unavailable();
            };
        }
    }
}
