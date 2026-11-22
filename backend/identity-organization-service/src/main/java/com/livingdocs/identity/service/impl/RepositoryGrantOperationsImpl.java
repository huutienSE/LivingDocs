package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.entity.RepositoryGrant;
import com.livingdocs.identity.entity.WorkspaceMembership;
import com.livingdocs.identity.model.AuthorityCommands.RequestRepositoryGrant;
import com.livingdocs.identity.model.AuthorityCommands.RevokeAuthority;
import com.livingdocs.identity.repository.RepositoryGrantRepository;
import com.livingdocs.identity.repository.WorkspaceRoleAssignmentRepository;
import com.livingdocs.identity.service.RepositoryGrantOperations;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.livingdocs.identity.service.impl.AuthorityInvariants.require;
import static com.livingdocs.identity.service.impl.AuthorityInvariants.required;

@Service
@RequiredArgsConstructor
public class RepositoryGrantOperationsImpl implements RepositoryGrantOperations {
    private final AuthorityInvariants checks;
    private final WorkspaceRoleAssignmentRepository roles;
    private final RepositoryGrantRepository grants;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID requestPending(RequestRepositoryGrant command, UUID actorIdentityId) {
        WorkspaceMembership member = checks.activeWorkspaceMember(command.tenantId(),
                command.workspaceId(), command.workspaceMembershipId());
        require(member.getIdentityId().equals(actorIdentityId),
                "Technical Lead may request only for own membership");
        require(roles.findActive(command.tenantId(), java.util.Set.of(member.getWorkspaceMembershipId()))
                .stream().anyMatch(role -> "TECHNICAL_LEAD".equals(role.getRoleCode())),
                "Active TECHNICAL_LEAD role required");
        required(command.repositoryConnectionId(), "repositoryConnectionId");
        require(command.provenance() != null && command.provenance().length() >= 10
                && command.provenance().length() <= 2000, "provenance must have 10..2000 characters");
        // S02 has not verified this binding. PENDING is never an effective grant.
        return grants.saveAndFlush(new RepositoryGrant(command.tenantId(), command.workspaceId(),
                member.getWorkspaceMembershipId(), command.repositoryConnectionId(), actorIdentityId,
                command.provenance())).getGrantId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(RevokeAuthority command) {
        RepositoryGrant grant = grants.findById(required(command.targetId(), "targetId"))
                .orElseThrow(() -> new IllegalArgumentException("Repository grant not found"));
        require(command.tenantId().equals(grant.getTenantId()), "Repository grant scope mismatch");
        grant.revoke();
        grants.saveAndFlush(grant);
    }
}
