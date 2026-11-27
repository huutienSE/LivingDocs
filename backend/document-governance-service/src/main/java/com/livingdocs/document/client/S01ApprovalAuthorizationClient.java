package com.livingdocs.document.client;

import com.livingdocs.document.exception.ApprovalAuthorizationUnavailableException;
import com.livingdocs.document.exception.InsufficientApprovalScopeException;
import com.livingdocs.document.exception.ReviewAuthenticationRequiredException;
import com.livingdocs.document.exception.S01AuthorityException;
import com.livingdocs.document.security.ActorContext;
import java.net.URI;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Maps the shared API-038 snapshot to the Manager read use cases (API-122/123).
 * Only grants holding both the MANAGER role and DOCUMENT_FINAL_APPROVE permission are kept.
 * Read APIs use the API-038 snapshot; fresh API-039 evaluation belongs to API-124 (decision).
 */
@Component
public class S01ApprovalAuthorizationClient {
    static final String MANAGER_ROLE = "MANAGER";
    static final String FINAL_APPROVE_PERMISSION = "DOCUMENT_FINAL_APPROVE";

    private final S01AccessContextClient authority;
    private final URI endpoint;

    public S01ApprovalAuthorizationClient(S01AccessContextClient authority,
            // Same S01 API-038 endpoint as Staff Review unless explicitly overridden.
            @Value("${livingdocs.approval.access-context-uri:${livingdocs.review.access-context-uri:}}") String uri,
            @Value("${livingdocs.approval.allow-insecure-http:${livingdocs.review.allow-insecure-http:false}}") boolean localHttp) {
        this.authority = authority;
        this.endpoint = S01AccessContextClient.endpoint(uri, localHttp);
    }

    public ActorContext resolve(Jwt jwt) {
        try {
            var context = authority.resolve(jwt, endpoint);
            return new ActorContext(context.identityId(), context.tenantId(), context.grants().stream()
                    .filter(g -> g.roles().contains(MANAGER_ROLE) && g.permissions().contains(FINAL_APPROVE_PERMISSION))
                    .map(g -> new ActorContext.WorkspaceGrant(g.workspaceId(), g.roles(), g.repositoryIds()))
                    .collect(Collectors.toSet()));
        } catch (S01AuthorityException e) {
            throw switch (e.getKind()) {
                case UNAUTHENTICATED -> new ReviewAuthenticationRequiredException();
                case FORBIDDEN -> new InsufficientApprovalScopeException();
                case UNAVAILABLE -> new ApprovalAuthorizationUnavailableException();
            };
        }
    }
}
