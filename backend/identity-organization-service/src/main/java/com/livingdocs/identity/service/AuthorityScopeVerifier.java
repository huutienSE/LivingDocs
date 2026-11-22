package com.livingdocs.identity.service;

import java.util.UUID;

/** Reusable fresh scope lookup. Entry points authenticate actors independently. */
public interface AuthorityScopeVerifier {
    void requirePlatformProvisioning(UUID verifiedActorId);
    void requireTenantOwner(UUID tenantId, UUID verifiedActorId, boolean ownershipChange);
    void requireOwnTechnicalLead(UUID tenantId, UUID workspaceId, UUID workspaceMembershipId,
                                 UUID verifiedActorId);
}
