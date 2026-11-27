package com.livingdocs.security;

import com.livingdocs.exception.SourceApiError;
import com.livingdocs.exception.SourceApiException;
import com.livingdocs.model.SourceScope;
import java.util.Collection;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/** Phạm vi chỉ được dựng bởi adapter hợp đồng danh tính đã xác minh. */
public final class SourceServiceAuthentication extends AbstractAuthenticationToken {
    private final String workload;
    private final SourceScope scope;
    private final UUID repositoryConnectionId;

    public SourceServiceAuthentication(String workload, SourceScope scope, UUID repositoryConnectionId,
                                       Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.workload = java.util.Objects.requireNonNull(workload);
        this.scope = java.util.Objects.requireNonNull(scope);
        this.repositoryConnectionId = java.util.Objects.requireNonNull(repositoryConnectionId);
        super.setAuthenticated(true);
    }

    public SourceScope authorize(UUID requestedConnection) {
        if (!repositoryConnectionId.equals(requestedConnection)) {
            throw new SourceApiException(SourceApiError.FORBIDDEN_SCOPE);
        }
        return scope;
    }

    @Override public Object getCredentials() { return ""; }
    @Override public Object getPrincipal() { return workload; }
    @Override public String getName() { return workload; }
}
