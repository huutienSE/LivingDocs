package com.livingdocs.document.client;

import com.livingdocs.document.exception.*;
import com.livingdocs.document.model.DocumentVersion;
import com.livingdocs.document.model.ReviewAuthorizationDecision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Use-case adapter over shared S01 Service JWT/API-039 transport. */
@Component
public class S01FreshSubmitAuthorizationClient {
    private final S01FreshAuthorizationClient client;
    @Autowired
    public S01FreshSubmitAuthorizationClient(S01FreshAuthorizationClient client) { this.client = client; }
    public S01FreshSubmitAuthorizationClient(JsonMapper json, String tokenUri, String evaluationUri, String secret, boolean localHttp) {
        this(new S01FreshAuthorizationClient(json, tokenUri, evaluationUri, secret, localHttp));
    }
    public ReviewAuthorizationDecision requireAllowed(DocumentVersion version, String token, String correlation) {
        try { return client.requireAllowed(version, token, correlation, "DOCUMENT_AUTHOR"); }
        catch (S01AuthorityException e) {
            throw switch (e.getKind()) {
                case UNAUTHENTICATED -> new SubmitException("UNAUTHENTICATED", org.springframework.http.HttpStatus.UNAUTHORIZED, "A valid S01 token is required.");
                case FORBIDDEN -> SubmitException.forbidden();
                case UNAVAILABLE -> SubmitException.unavailable();
            };
        }
    }
}
