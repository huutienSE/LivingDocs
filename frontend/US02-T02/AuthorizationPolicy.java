package com.livingdocs.identity.authz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure decision logic (no I/O) for "may this actor perform this sensitive action in this workspace RIGHT NOW?".
 *
 * Rules (LD-DEC-025 §3, LD-DEC-027 §4, LD-DEC-029 §4):
 *  1. The role must be an ACTIVE Workspace Role of the actor IN THAT workspace. Roles in other workspaces never count.
 *  2. Tenant Owner / Platform Administrator / Technical Lead never grant Manager actions by themselves.
 *  3. Cross-tenant => the workspace is not visible => FORBIDDEN_SCOPE (no information about other tenants).
 *  4. A role the actor held before but no longer has => PERMISSION_REVOKED (not FORBIDDEN_SCOPE).
 * This class does NOT enforce No Self Final Approval: that depends on the document's author/editor history, owned by S04.
 */
public final class AuthorizationPolicy {

    private AuthorizationPolicy() {
    }

    public record Decision(boolean allowed, ReasonCode reason, List<String> effectiveRoles) {
    }

    public static Decision decide(AuthorizationAction action, ActorFacts facts) {
        String required = action.requiredRole();

        if (!facts.workspaceActive()) {
            return deny(ReasonCode.FORBIDDEN_SCOPE);
        }
        if (!facts.identityActive() || !facts.activeTenantMembership() || !facts.activeWorkspaceMembership()) {
            return deny(heldBefore(facts, required) ? ReasonCode.PERMISSION_REVOKED : ReasonCode.FORBIDDEN_SCOPE);
        }
        if (facts.activeRoles().contains(required)) {
            List<String> roles = new ArrayList<>(facts.activeRoles());
            Collections.sort(roles);
            return new Decision(true, null, roles);
        }
        return deny(facts.formerRoles().contains(required) ? ReasonCode.PERMISSION_REVOKED : ReasonCode.FORBIDDEN_SCOPE);
    }

    private static boolean heldBefore(ActorFacts facts, String role) {
        Set<String> all = new HashSet<>(facts.formerRoles());
        all.addAll(facts.activeRoles());
        return all.contains(role);
    }

    private static Decision deny(ReasonCode reason) {
        return new Decision(false, reason, null);
    }
}
