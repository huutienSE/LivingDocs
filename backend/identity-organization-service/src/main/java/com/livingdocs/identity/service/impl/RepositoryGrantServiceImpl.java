package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.dto.response.RepositoryGrantResponse;
import com.livingdocs.identity.entity.RepositoryGrant;
import com.livingdocs.identity.exception.AuthorityException;
import com.livingdocs.identity.exception.GrantConflictException;
import com.livingdocs.identity.model.AuthorityCommands.RequestRepositoryGrant;
import com.livingdocs.identity.model.AuthorityCommands.RevokeAuthority;
import com.livingdocs.identity.repository.HttpAuthorityOperationRepository;
import com.livingdocs.identity.repository.RepositoryGrantRepository;
import com.livingdocs.identity.repository.TenantMembershipRepository;
import com.livingdocs.identity.repository.TenantRoleAssignmentRepository;
import com.livingdocs.identity.security.AuthorityWriteContext;
import com.livingdocs.identity.security.TenantContext;
import com.livingdocs.identity.service.AuthorityScopeVerifier;
import com.livingdocs.identity.service.RepositoryBindingClient;
import com.livingdocs.identity.service.RepositoryGrantOperations;
import com.livingdocs.identity.service.RepositoryGrantService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

@Service @RequiredArgsConstructor
public class RepositoryGrantServiceImpl implements RepositoryGrantService {
    private final TenantContext context;
    private final AuthorityWriteContext writer;
    private final AuthorityScopeVerifier scope;
    private final AuthorityInvariants checks;
    private final TenantMembershipRepository tenantMembers;
    private final TenantRoleAssignmentRepository tenantRoles;
    private final RepositoryGrantRepository grants;
    private final RepositoryGrantOperations operations;
    private final HttpAuthorityOperationRepository idempotency;
    private final RepositoryBindingClient bindings;

    @Override
    public RepositoryGrantResponse request(UUID actor, UUID tenant, UUID workspace, UUID member,
            UUID connection, String provenance, UUID idempotencyKey) {

        // kiem tra tenant của actor có còn activev không, và có khớp với tenant trong request không
        requireTenant(actor, tenant);
        //kiểm khóa định danh để kiểm tra req trùng
        if (idempotencyKey == null) throw new IllegalArgumentException("Idempotency-Key required");

        // dựng yêu cầu (command)
        RequestRepositoryGrant command = new RequestRepositoryGrant(tenant, workspace, member,
                connection, provenance);

        // sẽ được băm thành SHA-256 để xác định yêu cầu có giống một yêu cầu trước đó hay không.
        //Chuỗi đại diện nội dung request để kiểm tra retry/trùng yêu cầu.
        String payload = "request|" + tenant + "|" + workspace + "|" + member + "|" + connection + "|" + provenance;
        UUID grantId;
        try {
            // Mở transaction ghi:
            // kiểm quyền trước → chuyển DB writer role → xử lý ghi → commit/rollback.
            grantId = writer.asHuman(tenant, actor, "HTTP repository grant request",
                () -> { // // Khối kiểm tra quyền: Kiểm người gửi có quyền yêu cầu Grant và người nhận có hợp lệ không.
                    if (owner(tenant, actor)) scope.requireTenantOwner(tenant, actor, false);
                    else {
                        // Technical Lead chỉ được tự yêu cầu cho chính mình.
                        try { scope.requireOwnTechnicalLead(tenant, workspace, member, actor); }
                        catch (IllegalArgumentException ex) { throw new AuthorityException("GRANT_SCOPE_DENIED"); }
                    }
                    // Người nhận phải thuộc đúng Workspace và còn ACTIVE.
                    checks.activeWorkspaceMember(tenant, workspace, member);
                }, () -> { //tạo grant: Kiểm request trùng, tạo Grant ACTIVE/PENDING và lưu kết quả.
                    // Cùng key + cùng request đã hoàn thành → lấy Grant cũ.
                    UUID prior = idempotency.claimOrResult(idempotencyKey, tenant, actor, payload);
                    if (prior != null) return prior;

                    // Đã có Grant ACTIVE cho thành viên/repository → từ chối tạo trùng.
                    if (grants.findActive(tenant, workspace, member, connection).isPresent())
                        throw new GrantConflictException("Active grant already exists");

                    // Chọn nhánh Owner hoặc Technical Lead để tạo ACTIVE/PENDING.
                    UUID created = owner(tenant, actor)
                            ? operations.requestPendingForOwner(command, actor)
                            : operations.requestPending(command, actor);

                    // Lưu Grant ID để lần retry sau trả đúng kết quả cũ.
                    idempotency.finish(idempotencyKey, created);
                    return created;
                });
        } catch (DataIntegrityViolationException ex) {
            // Nếu hai request đồng thời cùng tạo Grant, DB unique index
            // chặn bản ghi trùng → chuyển thành lỗi nghiệp vụ 409.
            if (ex.getMostSpecificCause().getMessage() != null
                    && ex.getMostSpecificCause().getMessage().contains("uq_active_repository_grant"))
                throw new GrantConflictException("Active grant already exists");
            throw ex;
        }
        // Đọc lại Grant và trả thông tin ACTIVE/PENDING, effective=false.
        return get(actor, tenant, workspace, grantId);
    }

    @Override
    public List<RepositoryGrantResponse> list(UUID actor, UUID tenant, UUID workspace) {
        requireTenant(actor, tenant);
        List<RepositoryGrant> scoped = context.readInTenant(tenant, () -> {
            checks.activeWorkspace(tenant, workspace);
            boolean isOwner = owner(tenant, actor);
            return grants.findScoped(tenant, workspace).stream()
                    .filter(g -> isOwner || recipient(g, actor)).toList();
        });
        return scoped.stream().map(this::describe).toList();
    }

    @Override
    public RepositoryGrantResponse get(UUID actor, UUID tenant, UUID workspace, UUID grantId) {
        requireTenant(actor, tenant);
        RepositoryGrant grant = context.readInTenant(tenant, () -> authorizedGrant(actor, tenant, workspace, grantId));
        return describe(grant);
    }

    @Override
    public RepositoryGrantResponse verify(UUID actor, UUID tenant, UUID workspace, UUID grantId) {
        // tương tự kiểm tenant giống ở req
        requireTenant(actor, tenant);

        // KHỐI 1: Đọc Grant, kiểm Minh là Owner,
        // cấm tự xác minh, kiểm Grant và người nhận còn hợp lệ.
        RepositoryGrant before = context.readInTenant(tenant, () -> {
            //1. Kiểm người xác minh có role Tenant Owner ACTIVE không.
            if (!owner(tenant, actor)) throw new AuthorityException("GRANT_SCOPE_DENIED");

            // 2. Kiểm tra đầy đủ quyền Tenant Owner và Tenant còn hợp lệ.
            scope.requireTenantOwner(tenant, actor, false);
            // 3. Lấy Grant từ DB, kiểm thuộc đúng Tenant và Workspace.
            RepositoryGrant g = scopedGrant(tenant, workspace, grantId);
            // 4. Kiểm người nhận Grant vẫn thuộc Workspace và còn ACTIVE.
            checks.activeWorkspaceMember(tenant, workspace, g.getWorkspaceMembershipId());

            // 5. Người tạo yêu cầu không được tự xác minh yêu cầu của mình.
            //actor là ngừoi tạo yêu cầu, g là người nhận
            if (actor.equals(g.getOperatorIdentityId())) throw new AuthorityException("SELF_VERIFICATION_DENIED");

            // 6. Chỉ cho tiếp tục nếu Grant chưa bị revoke hoặc reject.
            if (!"ACTIVE".equals(g.getStatus()) || "REJECTED".equals(g.getVerificationStatus()))
                throw new GrantConflictException("Grant cannot be verified");
            // 7. Trả bản Grant vừa kiểm để dùng trong bước gọi S02.
            return g;
        });

        // KHỐI 2: Gọi S02 để xác minh kết nối repository.
        // Thực hiện ngoài write transaction để không giữ transaction trong lúc chờ HTTP response.
        RepositoryBindingClient.BindingEvidence evidence = bindings.lookup(tenant, workspace,
                before.getRepositoryConnectionId());
        // Đúng Tenant, Workspace, Connection, ACTIVE,
        // revision hợp lệ và thời gian xác minh còn mới.
        if (evidence == null || !evidence.activeFor(tenant, workspace,
                before.getRepositoryConnectionId(), 1))
            throw new GrantConflictException("Repository binding is not active");

        // KHỐI 3: Mở write transaction.
        // Lambda 1: kiểm lại quyền và Grant có bị thay đổi không.
        // Lambda 2: cập nhật Grant thành VERIFIED nếu đủ điều kiện.
        writer.asHuman(tenant, actor, "HTTP repository grant verification", () -> {
            if (!owner(tenant, actor)) throw new AuthorityException("GRANT_SCOPE_DENIED");
            scope.requireTenantOwner(tenant, actor, false);
            RepositoryGrant current = scopedGrant(tenant, workspace, grantId);
            checks.activeWorkspaceMember(tenant, workspace, current.getWorkspaceMembershipId());
            if (actor.equals(current.getOperatorIdentityId())) throw new AuthorityException("SELF_VERIFICATION_DENIED");
            if (!before.getRowVersion().equals(current.getRowVersion()))
                throw new GrantConflictException("Grant changed during binding lookup");
        }, () -> {
            RepositoryGrant current = scopedGrant(tenant, workspace, grantId);
            if ("VERIFIED".equals(current.getVerificationStatus())) {
                if (!current.isEffectiveCandidate()
                        || !evidence.activeFor(tenant, workspace, current.getRepositoryConnectionId(),
                                current.getVerifiedBindingRevision())
                        || current.getVerifiedBindingRevision() != evidence.revision())
                    throw new GrantConflictException("Binding revision changed; request a new grant");
                return null;
            }
            if (!"ACTIVE".equals(current.getStatus()) || !"PENDING".equals(current.getVerificationStatus()))
                throw new GrantConflictException("Grant cannot be verified");
            operations.verify(tenant, grantId, evidence.revision());
            return null;
        });
        return get(actor, tenant, workspace, grantId);
    }

    @Override
    public RepositoryGrantResponse revoke(UUID actor, UUID tenant, UUID workspace, UUID grantId) {
        requireTenant(actor, tenant);
        writer.asHuman(tenant, actor, "HTTP repository grant revocation", () -> {
            RepositoryGrant grant = scopedGrant(tenant, workspace, grantId);
            if (owner(tenant, actor)) scope.requireTenantOwner(tenant, actor, false);
            else if (!recipient(grant, actor)) throw new AuthorityException("GRANT_SCOPE_DENIED");
        }, () -> {
            RepositoryGrant grant = scopedGrant(tenant, workspace, grantId);
            if ("ACTIVE".equals(grant.getStatus())) operations.revoke(new RevokeAuthority(tenant, grantId));
            return null;
        });
        return get(actor, tenant, workspace, grantId);
    }

    private RepositoryGrantResponse describe(RepositoryGrant grant) {
        if (!"ACTIVE".equals(grant.getStatus()))
            return new RepositoryGrantResponse(grant, false, "GRANT_REVOKED");
        if (!grant.isEffectiveCandidate())
            return new RepositoryGrantResponse(grant, false, "GRANT_NOT_VERIFIED");
        try {
            var evidence = bindings.lookup(grant.getTenantId(), grant.getWorkspaceId(),
                    grant.getRepositoryConnectionId());
            boolean effective = evidence != null && evidence.activeFor(grant.getTenantId(),
                    grant.getWorkspaceId(), grant.getRepositoryConnectionId(), grant.getVerifiedBindingRevision())
                    && evidence.revision() == grant.getVerifiedBindingRevision();
            if (effective) effective = context.readInTenant(grant.getTenantId(), () ->
                    grants.findById(grant.getGrantId()).map(now -> now.isEffectiveCandidate()
                            && now.getRowVersion().equals(grant.getRowVersion())
                            && recipientStillActive(now)).orElse(false));
            return new RepositoryGrantResponse(grant, effective,
                    effective ? null : "BINDING_UNVERIFIED");
        } catch (RepositoryBindingClient.Unavailable ex) {
            return new RepositoryGrantResponse(grant, false, "BINDING_UNVERIFIED");
        }
    }

    private RepositoryGrant authorizedGrant(UUID actor, UUID tenant, UUID workspace, UUID grantId) {
        RepositoryGrant grant = scopedGrant(tenant, workspace, grantId);
        if (!owner(tenant, actor) && !recipient(grant, actor)) throw new AuthorityException("GRANT_SCOPE_DENIED");
        return grant;
    }

    private RepositoryGrant scopedGrant(UUID tenant, UUID workspace, UUID grantId) {
        RepositoryGrant grant = grants.findById(grantId)
                .orElseThrow(() -> new IllegalArgumentException("Repository grant not found"));
        if (!tenant.equals(grant.getTenantId()) || !workspace.equals(grant.getWorkspaceId()))
            throw new AuthorityException("GRANT_SCOPE_DENIED");
        return grant;
    }

    private boolean recipient(RepositoryGrant grant, UUID actor) {
        try {
            return actor.equals(checks.activeWorkspaceMember(grant.getTenantId(),
                    grant.getWorkspaceId(), grant.getWorkspaceMembershipId()).getIdentityId());
        } catch (IllegalArgumentException ex) { return false; }
    }

    private boolean recipientStillActive(RepositoryGrant grant) {
        try {
            checks.activeWorkspaceMember(grant.getTenantId(), grant.getWorkspaceId(),
                    grant.getWorkspaceMembershipId());
            return true;
        } catch (IllegalArgumentException ex) { return false; }
    }

    // xem tenant membershp có đang active, role hiện tại có đang active
    private boolean owner(UUID tenant, UUID actor) {
        return tenantMembers.findActive(tenant, actor).map(member ->
                tenantRoles.findActive(tenant, member.getMembershipId()).stream()
                        .anyMatch(role -> "TENANT_OWNER".equals(role.getRoleCode()))).orElse(false);
    }

    private void requireTenant(UUID actor, UUID tenant) {
        UUID actual = context.currentTenant(actor);
        if (actual == null || !actual.equals(tenant)) throw new AuthorityException("TENANT_MEMBERSHIP_REQUIRED");
    }
}
