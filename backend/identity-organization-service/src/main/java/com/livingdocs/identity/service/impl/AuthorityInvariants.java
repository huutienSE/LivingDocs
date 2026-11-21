package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.entity.Tenant;
import com.livingdocs.identity.entity.Workspace;
import com.livingdocs.identity.entity.WorkspaceMembership;
import com.livingdocs.identity.repository.RoleCatalogRepository;
import com.livingdocs.identity.repository.TenantMembershipRepository;
import com.livingdocs.identity.repository.TenantRepository;
import com.livingdocs.identity.repository.WorkspaceMembershipRepository;
import com.livingdocs.identity.repository.WorkspaceRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Shared domain checks. Callers establish a verified tenant context first. */
@Component
@RequiredArgsConstructor
public class AuthorityInvariants {
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final TenantMembershipRepository memberships;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMembershipRepository workspaceMemberships;
    private final RoleCatalogRepository roles;

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static UUID required(UUID value, String name) {
        require(value != null, name + " is required");
        return value;
    }

    public void activeIdentity(UUID identityId) {
        require(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM identity.identities WHERE identity_id = ? AND status = 'ACTIVE')",
                Boolean.class, required(identityId, "identityId"))), "Active identity required");
    }

    public Tenant activeTenant(UUID tenantId) {
        Tenant tenant = tenants.findById(required(tenantId, "tenantId"))
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        require("ACTIVE".equals(tenant.getStatus()), "Tenant must be ACTIVE");
        return tenant;
    }

    /** Serialize all ownership changes on the same tenant before counting owners. */
    public void lockTenant(UUID tenantId) {
        var ids = jdbc.queryForList("SELECT tenant_id FROM identity.tenants WHERE tenant_id = ? FOR UPDATE",
                UUID.class, required(tenantId, "tenantId"));
        require(ids.size() == 1, "Tenant not found");
    }

    public int activeOwnerCount(UUID tenantId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM identity.tenant_role_assignments a
                JOIN identity.tenant_memberships m ON m.tenant_id = a.tenant_id
                    AND m.membership_id = a.tenant_membership_id
                WHERE a.tenant_id = ? AND m.status = 'ACTIVE' AND a.status = 'ACTIVE'
                    AND a.role_code = 'TENANT_OWNER'
                """, Integer.class, tenantId);
    }

    public Workspace activeWorkspace(UUID tenantId, UUID workspaceId) {
        Workspace workspace = workspaces.findById(required(workspaceId, "workspaceId"))
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found"));
        require(tenantId.equals(workspace.getTenantId()) && "ACTIVE".equals(workspace.getStatus()),
                "Workspace must be active in tenant");
        return workspace;
    }

    public WorkspaceMembership activeWorkspaceMember(UUID tenantId, UUID workspaceId, UUID memberId) {
        activeWorkspace(tenantId, workspaceId);
        WorkspaceMembership member = workspaceMemberships.findById(required(memberId, "workspaceMembershipId"))
                .orElseThrow(() -> new IllegalArgumentException("Workspace membership not found"));
        require(tenantId.equals(member.getTenantId()) && workspaceId.equals(member.getWorkspaceId())
                && "ACTIVE".equals(member.getStatus()), "Active workspace membership required");
        require(memberships.findActive(tenantId, member.getIdentityId())
                .map(parent -> parent.getMembershipId().equals(member.getTenantMembershipId()))
                .orElse(false), "Active parent tenant membership required");
        return member;
    }

    public void roleScope(String roleCode, String scope) {
        require(roleCode != null && roles.findById(roleCode)
                .map(role -> scope.equals(role.getScopeType())).orElse(false),
                "Role code is not in the approved scope catalog");
    }
}
