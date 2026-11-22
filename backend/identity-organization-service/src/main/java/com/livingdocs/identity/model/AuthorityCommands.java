package com.livingdocs.identity.model;

import java.util.UUID;

/** Commands contain business targets only; an authenticated actor is supplied separately. */
public final class AuthorityCommands {
    private AuthorityCommands() {}

    public record CreateTenantWithInitialOwner(UUID tenantId, String name, UUID initialOwnerIdentityId) {}
    public record CreateWorkspace(UUID tenantId, UUID workspaceId, String name) {}
    public record AddTenantMember(UUID tenantId, UUID identityId) {}
    public record AddWorkspaceMember(UUID tenantId, UUID workspaceId, UUID identityId) {}
    public record AssignTenantOwner(UUID tenantId, UUID identityId) {}
    public record AssignWorkspaceRole(UUID tenantId, UUID workspaceId, UUID workspaceMembershipId, String roleCode) {}
    public record RequestRepositoryGrant(UUID tenantId, UUID workspaceId, UUID workspaceMembershipId,
                                         UUID repositoryConnectionId, String provenance) {}
    public record RevokeAuthority(UUID tenantId, UUID targetId) {}
}
