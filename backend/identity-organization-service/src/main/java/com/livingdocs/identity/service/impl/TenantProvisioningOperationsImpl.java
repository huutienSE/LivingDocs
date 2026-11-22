package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.entity.Tenant;
import com.livingdocs.identity.entity.TenantMembership;
import com.livingdocs.identity.entity.TenantRoleAssignment;
import com.livingdocs.identity.model.AuthorityCommands.CreateTenantWithInitialOwner;
import com.livingdocs.identity.repository.TenantMembershipRepository;
import com.livingdocs.identity.repository.TenantRepository;
import com.livingdocs.identity.repository.TenantRoleAssignmentRepository;
import com.livingdocs.identity.service.TenantProvisioningOperations;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.livingdocs.identity.service.impl.AuthorityInvariants.require;

@Service
@RequiredArgsConstructor
public class TenantProvisioningOperationsImpl implements TenantProvisioningOperations {
    private final AuthorityInvariants checks;
    private final TenantRepository tenants;
    private final TenantMembershipRepository memberships;
    private final TenantRoleAssignmentRepository roles;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(CreateTenantWithInitialOwner command) {
        checks.activeIdentity(command.initialOwnerIdentityId());
        require(command.name() != null && !command.name().isBlank() && command.name().length() <= 255,
                "Tenant name must have 1..255 characters");
        // The global V2 partial unique index rejects another ACTIVE membership,
        // even if a concurrent transaction in a different tenant races this check.
        tenants.saveAndFlush(new Tenant(command.tenantId(), command.name().trim()));
        TenantMembership member = memberships.saveAndFlush(
                new TenantMembership(command.tenantId(), command.initialOwnerIdentityId()));
        roles.saveAndFlush(new TenantRoleAssignment(command.tenantId(), member.getMembershipId(), "TENANT_OWNER"));
    }
}
