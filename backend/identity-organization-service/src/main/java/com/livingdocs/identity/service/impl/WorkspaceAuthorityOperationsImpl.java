package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.entity.TenantMembership;
import com.livingdocs.identity.entity.Workspace;
import com.livingdocs.identity.entity.WorkspaceMembership;
import com.livingdocs.identity.entity.WorkspaceRoleAssignment;
import com.livingdocs.identity.model.AuthorityCommands.AddWorkspaceMember;
import com.livingdocs.identity.model.AuthorityCommands.AssignWorkspaceRole;
import com.livingdocs.identity.model.AuthorityCommands.CreateWorkspace;
import com.livingdocs.identity.model.AuthorityCommands.RevokeAuthority;
import com.livingdocs.identity.repository.TenantMembershipRepository;
import com.livingdocs.identity.repository.WorkspaceMembershipRepository;
import com.livingdocs.identity.repository.WorkspaceRepository;
import com.livingdocs.identity.repository.WorkspaceRoleAssignmentRepository;
import com.livingdocs.identity.service.WorkspaceAuthorityOperations;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.livingdocs.identity.service.impl.AuthorityInvariants.require;
import static com.livingdocs.identity.service.impl.AuthorityInvariants.required;

@Service
@RequiredArgsConstructor
public class WorkspaceAuthorityOperationsImpl implements WorkspaceAuthorityOperations {
    private final AuthorityInvariants checks;
    private final TenantMembershipRepository tenantMembers;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMembershipRepository members;
    private final WorkspaceRoleAssignmentRepository roles;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(CreateWorkspace command) {
        checks.activeTenant(command.tenantId());
        required(command.workspaceId(), "workspaceId");
        require(command.name() != null && !command.name().isBlank() && command.name().length() <= 255,
                "Workspace name must have 1..255 characters");
        workspaces.saveAndFlush(new Workspace(command.tenantId(), command.workspaceId(), command.name().trim()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID addMember(AddWorkspaceMember command) {
        checks.activeWorkspace(command.tenantId(), command.workspaceId());
        TenantMembership tenantMember = tenantMembers.findActive(command.tenantId(),
                required(command.identityId(), "identityId"))
                .orElseThrow(() -> new IllegalArgumentException("Active tenant membership required"));
        return members.saveAndFlush(new WorkspaceMembership(command.tenantId(), command.workspaceId(),
                tenantMember.getMembershipId(), command.identityId())).getWorkspaceMembershipId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID assignRole(AssignWorkspaceRole command) {
        checks.roleScope(command.roleCode(), "WORKSPACE");
        WorkspaceMembership member = checks.activeWorkspaceMember(command.tenantId(),
                command.workspaceId(), command.workspaceMembershipId());
        return roles.saveAndFlush(new WorkspaceRoleAssignment(command.tenantId(), command.workspaceId(),
                member.getWorkspaceMembershipId(), command.roleCode())).getAssignmentId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeMember(RevokeAuthority command) {
        WorkspaceMembership member = members.findById(required(command.targetId(), "targetId"))
                .orElseThrow(() -> new IllegalArgumentException("Workspace membership not found"));
        require(command.tenantId().equals(member.getTenantId()), "Workspace membership scope mismatch");
        member.revoke();
        members.saveAndFlush(member);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeRole(RevokeAuthority command) {
        WorkspaceRoleAssignment role = roles.findById(required(command.targetId(), "targetId"))
                .orElseThrow(() -> new IllegalArgumentException("Workspace role not found"));
        require(command.tenantId().equals(role.getTenantId()), "Workspace role scope mismatch");
        role.revoke();
        roles.saveAndFlush(role);
    }
}
