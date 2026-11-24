package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.dto.request.AuthorizationEvaluationRequest;
import com.livingdocs.identity.dto.response.AuthorizationEvaluationResponse;
import com.livingdocs.identity.exception.AuthorityException;
import com.livingdocs.identity.repository.*;
import com.livingdocs.identity.security.TenantContext;
import com.livingdocs.identity.service.AuthorizationEvaluationService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class AuthorizationEvaluationServiceImpl implements AuthorizationEvaluationService {
    private static final Set<String> ACTIONS = Set.of("REPOSITORY_CONNECT", "DOCUMENT_AUTHOR",
            "DOCUMENT_STAFF_REVIEW", "DOCUMENT_FINAL_APPROVE", "DOCUMENT_PUBLISH");
    private final TenantContext context;
    private final IdentityRepository identities;
    private final TenantRepository tenants;
    private final TenantMembershipRepository tenantMembers;
    private final WorkspaceMembershipRepository workspaceMembers;
    private final WorkspaceRoleAssignmentRepository workspaceRoles;
    private final RolePermissionRepository permissions;
    private final RepositoryGrantRepository repositoryGrants;

    @Override
    public AuthorizationEvaluationResponse evaluate(UUID actorId, AuthorizationEvaluationRequest request) {
        // BƯỚC 1: Kiểm tra action và scope hợp lệ theo contract API-039.
        // Connect Repository: chưa có Repository Connection -> ID phải null.
        // Document actions: phải chỉ rõ Repository Connection.
        // Sai action/scope -> exception, chưa truy vấn quyền người dùng.
        if (!ACTIONS.contains(request.getAction())
                || ("REPOSITORY_CONNECT".equals(request.getAction())
                        ? request.getRepositoryConnectionId() != null
                        : request.getRepositoryConnectionId() == null)) {
            throw new IllegalArgumentException("Invalid action scope");
        }

        // Xác định Tenant hiện tại theo cơ chế Tenant Context S01.
        UUID tenant;
        try {
            tenant = context.currentTenant(actorId);
        } catch (AuthorityException ex) {
            tenant = null;
        }

        if (tenant == null || !tenant.equals(request.getTenantId()))
            return result(request, null, false);

        //Gọi TenantContext.readInTenant() để kiểm quyền trong phạm vi Tenant
        //Bên trong sẽ thực thi evaluateSnapshot(actorId, request).
        // Kết quả ALLOW/DENY quay về evaluate(), rồi về Controller.
        return context.readInTenant(tenant, () -> evaluateSnapshot(actorId, request));
    }

    private AuthorizationEvaluationResponse evaluateSnapshot(UUID actorId, AuthorizationEvaluationRequest request) {
        //1. Kiểm tra Identity, Tenant và Tenant Membership ACTIVE.
        var identity = identities.findById(actorId).orElse(null);
        var tenant = tenants.findById(request.getTenantId()).orElse(null);
        if (identity == null || !"ACTIVE".equals(identity.getStatus()) || tenant == null
                || !"ACTIVE".equals(tenant.getStatus())
                || tenantMembers.findActive(request.getTenantId(), actorId).isEmpty())
            return result(request, tenant == null ? null : tenant.getAuthzRevision(), false);

        // 2. Kiểm tra Workspace Membership ACTIVE.
        var member = workspaceMembers.findActiveForIdentity(request.getTenantId(), actorId).stream()
                .filter(m -> request.getWorkspaceId().equals(m.getWorkspaceId())).findFirst().orElse(null);
        if (member == null) return result(request, tenant.getAuthzRevision(), false);

        //3. Lấy các Workspace Role ACTIVE của người dùng.
        var roles = workspaceRoles.findActive(request.getTenantId(), Set.of(member.getWorkspaceMembershipId()))
                .stream().map(r -> r.getRoleCode()).collect(java.util.stream.Collectors.toSet());
        //4. Kiểm Role có permission tương ứng với action.
        boolean permission = !roles.isEmpty() && permissions.findForRoles(roles).stream()
                .anyMatch(p -> request.getAction().equals(p.getId().getPermissionCode()));
        //5. Nếu có Repository Connection, kiểm Repository Grant hiệu lực.
        boolean grant = request.getRepositoryConnectionId() == null || repositoryGrants
                .findEffective(request.getTenantId(), Set.of(member.getWorkspaceMembershipId())).stream()
                .anyMatch(g -> request.getWorkspaceId().equals(g.getWorkspaceId())
                        && request.getRepositoryConnectionId().equals(g.getRepositoryConnectionId()));

        // ALLOW = PERMISSTON ^ GRANT
        return result(request, tenant.getAuthzRevision(), permission && grant);
    }

    private AuthorizationEvaluationResponse result(AuthorizationEvaluationRequest request, Long revision, boolean allow) {
        return new AuthorizationEvaluationResponse(allow ? "ALLOW" : "DENY",
                allow ? null : "NOT_AUTHORIZED", request.getAction(), request.getTenantId(),
                request.getWorkspaceId(), request.getRepositoryConnectionId(), revision,
                Instant.now(), UUID.randomUUID());
    }
}
