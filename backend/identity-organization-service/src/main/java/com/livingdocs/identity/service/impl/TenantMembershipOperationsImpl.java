package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.entity.TenantMembership;
import com.livingdocs.identity.entity.TenantRoleAssignment;
import com.livingdocs.identity.model.AuthorityCommands.AddTenantMember;
import com.livingdocs.identity.model.AuthorityCommands.AssignTenantOwner;
import com.livingdocs.identity.model.AuthorityCommands.RevokeAuthority;
import com.livingdocs.identity.repository.TenantMembershipRepository;
import com.livingdocs.identity.repository.TenantRoleAssignmentRepository;
import com.livingdocs.identity.service.TenantMembershipOperations;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.livingdocs.identity.service.impl.AuthorityInvariants.require;
import static com.livingdocs.identity.service.impl.AuthorityInvariants.required;

@Service
@RequiredArgsConstructor
public class TenantMembershipOperationsImpl implements TenantMembershipOperations {
    private final AuthorityInvariants checks;
    private final TenantMembershipRepository members;
    private final TenantRoleAssignmentRepository roles;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID add(AddTenantMember command) {
        checks.activeTenant(command.tenantId());
        checks.activeIdentity(command.identityId());
        require(members.findActive(command.tenantId(), command.identityId()).isEmpty(),
                "Active membership already exists");
        return members.saveAndFlush(new TenantMembership(command.tenantId(), command.identityId())).getMembershipId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID assignOwner(AssignTenantOwner command) {
        checks.lockTenant(command.tenantId());
        checks.activeTenant(command.tenantId());
        TenantMembership member = members.findActive(command.tenantId(), required(command.identityId(), "identityId"))
                .orElseThrow(() -> new IllegalArgumentException("Active tenant membership required"));
        return roles.saveAndFlush(new TenantRoleAssignment(command.tenantId(), member.getMembershipId(),
                "TENANT_OWNER")).getAssignmentId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeMember(RevokeAuthority command, UUID actorIdentityId) {
        checks.lockTenant(command.tenantId());
        TenantMembership member = members.findById(required(command.targetId(), "targetId"))
                .orElseThrow(() -> new IllegalArgumentException("Tenant membership not found"));
        require(command.tenantId().equals(member.getTenantId()), "Tenant membership scope mismatch");
        require(!actorIdentityId.equals(member.getIdentityId()), "Actor cannot revoke own membership");
        if (roles.findActive(command.tenantId(), member.getMembershipId()).stream()
                .anyMatch(role -> "TENANT_OWNER".equals(role.getRoleCode()))) {
            require(checks.activeOwnerCount(command.tenantId()) > 1, "Cannot revoke last Tenant Owner");
        }
        member.revoke();
        members.saveAndFlush(member);
        // Child rows remain historical, but every effective lookup must require an
        // ACTIVE parent. This is tested via API-038 and the future fresh evaluator.
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeOwner(RevokeAuthority command) {
        checks.lockTenant(command.tenantId());
        TenantRoleAssignment role = roles.findById(required(command.targetId(), "targetId"))
                .orElseThrow(() -> new IllegalArgumentException("Tenant role not found"));
        require(command.tenantId().equals(role.getTenantId()) && "TENANT_OWNER".equals(role.getRoleCode()),
                "Tenant Owner assignment required");
        require(checks.activeOwnerCount(command.tenantId()) > 1, "Cannot revoke last Tenant Owner");
        role.revoke();
        roles.saveAndFlush(role);
    }
}
