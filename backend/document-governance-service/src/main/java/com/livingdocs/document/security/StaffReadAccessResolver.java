package com.livingdocs.document.security;

import com.livingdocs.document.client.S01AccessContextClient;
import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.S01AccessContext;
import java.net.URI;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/** API-038 transport is shared; the read policy deliberately does not require a mutation permission. */
@Component
public class StaffReadAccessResolver {
    private final S01AccessContextClient client;
    private final URI endpoint;

    public StaffReadAccessResolver(S01AccessContextClient client,
            @Value("${livingdocs.authorization.access-context-uri:${livingdocs.review-queue.access-context-uri:${livingdocs.review.access-context-uri:}}}") String uri,
            @Value("${livingdocs.authorization.access-context-allow-insecure-http:${livingdocs.review-queue.allow-insecure-http:${livingdocs.review.allow-insecure-http:false}}}") boolean localHttp) {
        this.client = client;
        endpoint = S01AccessContextClient.endpoint(uri, localHttp);
    }

    public S01AccessContext resolve(Jwt jwt) {
        return resolve(jwt, null);
    }

    public S01AccessContext resolve(Jwt jwt, String correlationId) {
        try { return client.resolve(jwt, endpoint, correlationId); }
        catch (S01AuthorityException e) {
            throw switch (e.getKind()) {
                case UNAUTHENTICATED -> new ReviewAuthenticationRequiredException();
                case FORBIDDEN -> new InsufficientReviewScopeException();
                case UNAVAILABLE -> new ReviewAuthorizationUnavailableException();
            };
        }
    }
}
