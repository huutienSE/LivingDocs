package com.livingdocs.document.client;

import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.DocumentVersion;
import com.livingdocs.document.model.AuthorizationDecision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Use-case adapter over shared S01 Service JWT/API-039 transport. */
@Component
public class S01FreshReviewAuthorizationClient {
    private final S01FreshAuthorizationClient client;
    @Autowired
    public S01FreshReviewAuthorizationClient(S01FreshAuthorizationClient client) { this.client = client; }
    public S01FreshReviewAuthorizationClient(JsonMapper json, String tokenUri, String evaluationUri, String secret, boolean localHttp) {
        this(new S01FreshAuthorizationClient(json, tokenUri, evaluationUri, secret, localHttp));
    }
    public AuthorizationDecision requireAllowed(DocumentVersion version, String token, String correlation) {
        try { return client.requireAllowed(version, token, correlation, "DOCUMENT_STAFF_REVIEW"); }
        catch (S01AuthorityException e) {
            throw switch (e.getKind()) {
                case UNAUTHENTICATED -> new ReviewAuthenticationRequiredException();
                case FORBIDDEN -> new InsufficientReviewScopeException();
                case UNAVAILABLE -> new ReviewAuthorizationUnavailableException();
            };
        }
    }
}
