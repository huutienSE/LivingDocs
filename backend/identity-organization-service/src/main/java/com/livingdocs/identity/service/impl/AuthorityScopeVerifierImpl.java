package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.service.AuthorityScopeVerifier;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.livingdocs.identity.service.impl.AuthorityInvariants.require;

@Service
@RequiredArgsConstructor
public class AuthorityScopeVerifierImpl implements AuthorityScopeVerifier {
    private final JdbcTemplate jdbc;
    private final AuthorityInvariants checks;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requirePlatformProvisioning(UUID actor) {
        setLocal("app.identity_id", actor.toString());
        for (String permission : List.of("PLATFORM_TENANT_PROVISION", "PLATFORM_INITIAL_OWNER_ASSIGN")) {
            Boolean allowed = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM identity.platform_role_assignments a
                      JOIN identity.role_permissions p ON p.role_code = a.role_code
                      WHERE a.identity_id = ? AND a.status = 'ACTIVE' AND p.permission_code = ?)
                    """, Boolean.class, actor, permission);
            require(Boolean.TRUE.equals(allowed), "Active Platform permission required: " + permission);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireTenantOwner(UUID tenant, UUID actor, boolean ownershipChange) {
        requireOwnTenant(tenant, actor);
        if (ownershipChange) checks.lockTenant(tenant);
        Boolean owner = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM identity.tenant_role_assignments a
                  JOIN identity.tenant_memberships m ON m.tenant_id = a.tenant_id
                    AND m.membership_id = a.tenant_membership_id
                  WHERE a.tenant_id = ? AND m.identity_id = ? AND m.status = 'ACTIVE'
                    AND a.status = 'ACTIVE' AND a.role_code = 'TENANT_OWNER')
                """, Boolean.class, tenant, actor);
        require(Boolean.TRUE.equals(owner), "Active Tenant Owner assignment required");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireOwnTechnicalLead(UUID tenant, UUID workspace, UUID memberId, UUID actor) {
        requireOwnTenant(tenant, actor);
        var member = checks.activeWorkspaceMember(tenant, workspace, memberId);
        require(actor.equals(member.getIdentityId()), "Technical Lead may request only for own membership");
        Boolean lead = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM identity.workspace_role_assignments
                  WHERE tenant_id = ? AND workspace_membership_id = ?
                    AND role_code = 'TECHNICAL_LEAD' AND status = 'ACTIVE')
                """, Boolean.class, tenant, memberId);
        require(Boolean.TRUE.equals(lead), "Active TECHNICAL_LEAD role required");
    }

    private void requireOwnTenant(UUID tenant, UUID actor) {
        setLocal("app.identity_id", actor.toString());
        List<UUID> own = jdbc.queryForList("""
                SELECT tenant_id FROM identity.tenant_memberships
                WHERE identity_id = ? AND status = 'ACTIVE'
                """, UUID.class, actor);
        require(own.size() == 1 && tenant.equals(own.getFirst()),
                "Active tenant membership required in target tenant");
        setLocal("app.tenant_id", tenant.toString());
        checks.activeTenant(tenant);
    }

    private void setLocal(String key, String value) {
        jdbc.queryForObject("SELECT set_config(?, ?, true)", String.class, key, value);
    }
}
