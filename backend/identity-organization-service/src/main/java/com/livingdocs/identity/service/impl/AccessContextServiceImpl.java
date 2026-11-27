package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.dto.response.AccessContextResponse;
import com.livingdocs.identity.entity.RepositoryGrant;
import com.livingdocs.identity.entity.RolePermission;
import com.livingdocs.identity.entity.WorkspaceMembership;
import com.livingdocs.identity.entity.WorkspaceRoleAssignment;
import com.livingdocs.identity.exception.AuthorityException;
import com.livingdocs.identity.repository.RepositoryGrantRepository;
import com.livingdocs.identity.repository.RolePermissionRepository;
import com.livingdocs.identity.repository.TenantMembershipRepository;
import com.livingdocs.identity.repository.TenantRepository;
import com.livingdocs.identity.repository.TenantRoleAssignmentRepository;
import com.livingdocs.identity.repository.WorkspaceMembershipRepository;
import com.livingdocs.identity.repository.WorkspaceRoleAssignmentRepository;
import com.livingdocs.identity.security.TenantContext;
import com.livingdocs.identity.service.AccessContextService;
import com.livingdocs.identity.service.RepositoryBindingClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccessContextServiceImpl implements AccessContextService {
    private final TenantContext context;
    private final TenantRepository tenants;
    private final TenantMembershipRepository tenantMemberships;
    private final TenantRoleAssignmentRepository tenantRoles;
    private final WorkspaceMembershipRepository workspaceMemberships;
    private final WorkspaceRoleAssignmentRepository workspaceRoles;
    private final RepositoryGrantRepository repositoryGrants;
    private final RolePermissionRepository rolePermissions;
    private final RepositoryBindingClient bindings;

    //
    //Xác định Tenant hiện hành của Identity B thông qua TenantContext.
    //Nếu B không có Tenant hợp lệ, trả Access Context rỗng.
    //Nếu có Tenant, thực hiện load() trong Tenant Context.
    @Override
    public AccessContextResponse current(UUID verifiedIdentityId) {
        UUID tenantId = context.currentTenant(verifiedIdentityId);
        if (tenantId == null) {
            return new AccessContextResponse(verifiedIdentityId, null, 0, Instant.now(),
                    List.of(), List.of(), List.of());
        }
        Loaded loaded = context.readInTenant(tenantId, () -> load(verifiedIdentityId, tenantId));
        List<AccessContextResponse.WorkspaceGrant> workspaces = new ArrayList<>();
        for (var workspace : loaded.response().getWorkspaceGrants()) {
            List<UUID> effectiveIds = new ArrayList<>();
            List<AccessContextResponse.RepositoryGrantStatus> states = new ArrayList<>();
            for (RepositoryGrant grant : loaded.grants()) {
                if (!workspace.getWorkspaceId().equals(grant.getWorkspaceId())) continue;
                boolean effective = false;
                String reason = "GRANT_NOT_VERIFIED";
                if (grant.isEffectiveCandidate()) {
                    reason = "BINDING_UNVERIFIED";
                    try {
                        var evidence = bindings.lookup(tenantId, workspace.getWorkspaceId(),
                                grant.getRepositoryConnectionId());
                        effective = evidence != null && evidence.activeFor(tenantId, workspace.getWorkspaceId(),
                                grant.getRepositoryConnectionId(), grant.getVerifiedBindingRevision())
                                && evidence.revision() == grant.getVerifiedBindingRevision();
                        if (effective) reason = null;
                    } catch (RepositoryBindingClient.Unavailable ignored) {
                        // API-038 is a UI snapshot. Missing S02 evidence never contributes access.
                    }
                }
                if (effective) effectiveIds.add(grant.getRepositoryConnectionId());
                states.add(new AccessContextResponse.RepositoryGrantStatus(grant.getGrantId(),
                        grant.getRepositoryConnectionId(), grant.getStatus(), grant.getVerificationStatus(),
                        effective, reason));
            }
            List<String> visiblePermissions = effectiveIds.isEmpty()
                    ? workspace.getPermissions().stream().filter(p -> !p.startsWith("DOCUMENT_")).toList()
                    : workspace.getPermissions();
            workspaces.add(new AccessContextResponse.WorkspaceGrant(workspace.getWorkspaceId(),
                    workspace.getRoles(), visiblePermissions, effectiveIds.stream().distinct().toList(), states));
        }
        AccessContextResponse base = loaded.response();
        Long latestRevision = context.readInTenant(tenantId, () ->
                tenants.findById(tenantId).map(t -> t.getAuthzRevision()).orElse(null));
        if (!java.util.Objects.equals(base.getAuthzRevision(), latestRevision)) {
            workspaces = workspaces.stream().map(w -> new AccessContextResponse.WorkspaceGrant(
                    w.getWorkspaceId(), w.getRoles(),
                    w.getPermissions().stream().filter(p -> !p.startsWith("DOCUMENT_")).toList(), List.of(),
                    w.getRepositoryGrantStatuses().stream().map(s ->
                            new AccessContextResponse.RepositoryGrantStatus(s.getGrantId(),
                                    s.getRepositoryConnectionId(), s.getStatus(), s.getVerificationStatus(),
                                    false, "BINDING_UNVERIFIED")).toList())).toList();
        }
        return new AccessContextResponse(base.getIdentityId(), base.getTenantId(), base.getAuthzRevision(),
                base.getEvaluatedAt(), base.getTenantRoles(), base.getTenantPermissions(), workspaces);
    }

    private Loaded load(UUID identityId, UUID tenantId) {

        // kiem tra tenant ACTIVE,
        // INACTIVE --> THROW AuthorityException
        var tenant = tenants.findById(tenantId).orElseThrow(() -> new AuthorityException("TENANT_INACTIVE"));

        if (!"ACTIVE".equals(tenant.getStatus())) {
            throw new AuthorityException("TENANT_INACTIVE");
        }

        // kiem tra membership phải active
        var membership = tenantMemberships.findActive(tenantId, identityId)
                .orElseThrow(() -> new AuthorityException("TENANT_MEMBERSHIP_REQUIRED"));

        // lay ra danh sách các lan gan role của một membership trong một tenant cụ thể xong map -> chỉ lấy danh sach các role code, sắp xếp
        //Từ một tư cách thành viên cấp tenant (membership) trong tenantId, lấy các role assignment đang ACTIVE,
        // rút ra danh sách các mã role (roleCode) không trùng lặp và sắp xếp theo thứ tự tăng dần.
        List<String> tenantRoleCodes = tenantRoles.findActive(tenantId, membership.getMembershipId()).stream()
                .map(a -> a.getRoleCode()).distinct().sorted().toList();

        // lọc ra tưf các danh sách role code --> danh sách các role permissioon và không trùng
        //ví dụ TENANT_ADMIN vừa có TENANT_MEMBER và cả hai role đều chứa chung quyền tenant:read, quyền đó chỉ xuất hiện 1 lần duy nhất.
        Set<String> tenantPermissions = new TreeSet<>();
        if (!tenantRoleCodes.isEmpty()) {
            rolePermissions.findForRoles(tenantRoleCodes).forEach(permission ->
                    tenantPermissions.add(permission.getId().getPermissionCode()));
        }


        List<WorkspaceMembership> active = workspaceMemberships.findActiveForIdentity(tenantId, identityId);
        if (active.isEmpty()) {
            return new Loaded(new AccessContextResponse(identityId, tenantId, tenant.getAuthzRevision(), Instant.now(),
                    tenantRoleCodes, List.copyOf(tenantPermissions), List.of()), List.of());
        }
        Set<UUID> memberIds = active.stream().map(WorkspaceMembership::getWorkspaceMembershipId)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, Set<String>> rolesByMember = new HashMap<>();
        for (WorkspaceRoleAssignment assignment : workspaceRoles.findActive(tenantId, memberIds)) {
            rolesByMember.computeIfAbsent(assignment.getWorkspaceMembershipId(), ignored -> new TreeSet<>())
                    .add(assignment.getRoleCode());
        }
        Set<String> allRoles = new TreeSet<>();
        rolesByMember.values().forEach(allRoles::addAll);
        Map<String, Set<String>> permissionsByRole = new HashMap<>();
        if (!allRoles.isEmpty()) {
            for (RolePermission permission : rolePermissions.findForRoles(allRoles)) {
                permissionsByRole.computeIfAbsent(permission.getId().getRoleCode(), ignored -> new TreeSet<>())
                        .add(permission.getId().getPermissionCode());
            }
        }
        List<RepositoryGrant> candidateGrants = repositoryGrants.findForMembers(tenantId, memberIds);
        List<AccessContextResponse.WorkspaceGrant> grants = new ArrayList<>();
        for (WorkspaceMembership member : active) {
            UUID memberId = member.getWorkspaceMembershipId();
            Set<String> roles = rolesByMember.getOrDefault(memberId, Set.of());
            Set<String> permissions = new TreeSet<>();
            roles.forEach(role -> permissions.addAll(permissionsByRole.getOrDefault(role, Set.of())));
            if (!permissions.contains("WORKSPACE_VIEW")) {
                continue;
            }
            grants.add(new AccessContextResponse.WorkspaceGrant(member.getWorkspaceId(),
                    List.copyOf(roles), List.copyOf(permissions),
                    List.of()));
        }
        grants.sort(Comparator.comparing(g -> g.getWorkspaceId().toString()));
        return new Loaded(new AccessContextResponse(identityId, tenantId, tenant.getAuthzRevision(), Instant.now(),
                tenantRoleCodes, List.copyOf(tenantPermissions), List.copyOf(grants)), candidateGrants);
    }

    private record Loaded(AccessContextResponse response, List<RepositoryGrant> grants) {}
}
