package com.livingdocs.document.client;

import com.livingdocs.document.model.*;
import com.livingdocs.document.exception.AuthoringException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Set;

@Component
public class S01DocumentAuthorizationClient {
    private final S01AccessContextClient client;
    private final URI endpoint;

    public S01DocumentAuthorizationClient(S01AccessContextClient client,
            @Value("${livingdocs.submit.access-context-uri:}") String uri,
            @Value("${livingdocs.submit.allow-insecure-http:false}") boolean localHttp) {
        this.client = client;
        this.endpoint = S01AccessContextClient.endpoint(uri, localHttp);
    }
    public S01AccessContext resolve(Jwt jwt) { return client.resolve(jwt, endpoint); }

    public void require(S01AccessContext actor, AuthorizationScope scope, boolean author) {
        Set<String> permissions = author ? Set.of("WORKSPACE_VIEW", "DOCUMENT_AUTHOR")
                : Set.of("WORKSPACE_VIEW", "DOCUMENT_READ");
        boolean allowed = actor.tenantId().equals(scope.tenantId()) && actor.grants().stream().anyMatch(g ->
                g.workspaceId().equals(scope.workspaceId()) && g.permissions().containsAll(permissions)
                && (!author || g.roles().contains("DEVELOPER"))
                && (scope.repositoryConnectionId() == null || g.repositoryIds().contains(scope.repositoryConnectionId())));
        if (!allowed) throw AuthoringException.forbidden();
    }
}
