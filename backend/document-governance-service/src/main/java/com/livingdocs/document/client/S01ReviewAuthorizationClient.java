package com.livingdocs.document.client;

import com.livingdocs.document.security.ActorContext;
import com.livingdocs.document.exception.*;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Maps the shared API-038 snapshot to the review use case; wire validation stays shared. */
@Component
public class S01ReviewAuthorizationClient {
    private final S01AccessContextClient authority;
    private final URI endpoint;
    public S01ReviewAuthorizationClient(S01AccessContextClient authority,
            @Value("${livingdocs.review.access-context-uri:}") String uri,
            @Value("${livingdocs.review.allow-insecure-http:false}") boolean localHttp) {
        this.authority = authority;
        this.endpoint = S01AccessContextClient.endpoint(uri, localHttp);
    }
    public ActorContext resolve(Jwt jwt) {
        try {
            var c = authority.resolve(jwt, endpoint);
            return new ActorContext(c.identityId(), c.tenantId(), c.grants().stream()
                    .filter(g -> g.roles().contains("STAFF") && g.permissions().contains("DOCUMENT_STAFF_REVIEW"))
                    .map(g -> new ActorContext.WorkspaceGrant(g.workspaceId(), g.roles(), g.repositoryIds()))
                    .collect(java.util.stream.Collectors.toSet()));
        } catch (S01AuthorityException e) {
            throw switch (e.getKind()) {
                case UNAUTHENTICATED -> new ReviewAuthenticationRequiredException();
                case FORBIDDEN -> new InsufficientReviewScopeException();
                case UNAVAILABLE -> new ReviewAuthorizationUnavailableException();
            };
        }
    }
}
