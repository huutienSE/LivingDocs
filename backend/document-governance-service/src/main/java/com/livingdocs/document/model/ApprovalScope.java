package com.livingdocs.document.model;

import com.livingdocs.document.exception.InsufficientApprovalScopeException;
import com.livingdocs.document.security.ActorContext;
import java.util.List;
import java.util.UUID;

/**
 * Manager read scope derived from S01 grants that already passed the MANAGER + DOCUMENT_FINAL_APPROVE filter.
 * A version is visible when tenant matches, its workspace is granted, and its repository (if any)
 * is granted in that same workspace. Grants are never combined across workspaces.
 */
public record ApprovalScope(UUID identityId, UUID tenantId, List<UUID> workspaceIds, List<String> workspaceRepositoryPairs) {
    public ApprovalScope {
        workspaceIds = List.copyOf(workspaceIds);
        workspaceRepositoryPairs = List.copyOf(workspaceRepositoryPairs);
    }

    public static ApprovalScope of(ActorContext actor) {
        if (actor.tenantId() == null || actor.grants().isEmpty()) throw new InsufficientApprovalScopeException();
        var workspaces = actor.grants().stream().map(ActorContext.WorkspaceGrant::workspaceId).distinct().sorted().toList();
        var pairs = actor.grants().stream()
                .flatMap(g -> g.repositoryIds().stream().map(r -> pair(g.workspaceId(), r)))
                .distinct().sorted().toList();
        return new ApprovalScope(actor.identityId(), actor.tenantId(), workspaces, pairs);
    }

    public static String pair(UUID workspaceId, UUID repositoryId) {
        return workspaceId + ":" + repositoryId;
    }

    public boolean covers(UUID tenant, UUID workspace, UUID repository) {
        return tenantId.equals(tenant) && workspaceIds.contains(workspace)
                && (repository == null || workspaceRepositoryPairs.contains(pair(workspace, repository)));
    }

    public void require(UUID tenant, UUID workspace, UUID repository) {
        if (!covers(tenant, workspace, repository)) {
            throw new InsufficientApprovalScopeException();
        }
    }
}
