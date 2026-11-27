package com.livingdocs.document.security;

import com.livingdocs.document.client.S01AccessContextClient;
import com.livingdocs.document.config.ReviewQueueProperties;
import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.S01AccessContext;
import java.net.URI;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** API-038 transport is shared; the read policy deliberately does not require a mutation permission. */
@Component
public class ReviewQueueAccessResolver {
    private final S01AccessContextClient client;
    private final URI endpoint;

    public ReviewQueueAccessResolver(S01AccessContextClient client, ReviewQueueProperties properties) {
        this.client = client;
        endpoint = S01AccessContextClient.endpoint(properties.getAccessContextUri(), properties.isAllowInsecureHttp());
    }

    public S01AccessContext resolve(Jwt jwt) {
        try { return client.resolve(jwt, endpoint); }
        catch (S01AuthorityException e) {
            throw switch (e.getKind()) {
                case UNAUTHENTICATED -> new ReviewAuthenticationRequiredException();
                case FORBIDDEN -> new InsufficientReviewScopeException();
                case UNAVAILABLE -> new ReviewAuthorizationUnavailableException();
            };
        }
    }
}
