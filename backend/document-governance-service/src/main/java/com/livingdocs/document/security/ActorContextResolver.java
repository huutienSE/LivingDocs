package com.livingdocs.document.security;

import com.livingdocs.document.domain.ReviewException;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Adapter for the documented scoped JWT contract; only invoked after signature validation. */
@Component
public class ActorContextResolver {
    public ActorContext resolve(Jwt jwt) {
        if (jwt == null) throw new ReviewException(401, "UNAUTHENTICATED", "A verified access token is required.");
        try {
            if (!Boolean.TRUE.equals(jwt.getClaims().get("tenant_active"))) throw ReviewException.forbidden();
            var grants = new HashSet<ActorContext.WorkspaceGrant>();
            Object rawGrants = jwt.getClaims().get("workspace_grants");
            if (!(rawGrants instanceof List<?> list)) throw ReviewException.forbidden();
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> grant)) throw ReviewException.forbidden();
                if (!Boolean.TRUE.equals(grant.get("active"))) continue;
                grants.add(new ActorContext.WorkspaceGrant(UUID.fromString((String) grant.get("workspace_id")),
                        strings(grant.get("roles")), uuids(grant.get("repository_ids"))));
            }
            return new ActorContext(UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("tenant_id")), grants);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            throw ReviewException.forbidden();
        }
    }

    private Set<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) throw ReviewException.forbidden();
        var values = new HashSet<String>();
        for (Object value : list) {
            if (!(value instanceof String s)) throw ReviewException.forbidden();
            values.add(s);
        }
        return values;
    }

    private Set<UUID> uuids(Object raw) {
        if (raw == null) return Set.of();
        var ids = new HashSet<UUID>();
        strings(raw).forEach(value -> ids.add(UUID.fromString(value)));
        return ids;
    }
}
