package com.livingdocs.identity.provisioning.impl;

import com.livingdocs.identity.model.AuthorityCommands.*;
import com.livingdocs.identity.model.VerifiedActor;
import com.livingdocs.identity.provisioning.ProvisioningManifest;
import com.livingdocs.identity.provisioning.ProvisioningService;
import com.livingdocs.identity.service.*;
import com.livingdocs.identity.service.impl.AuthorityInvariants;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.livingdocs.identity.service.impl.AuthorityInvariants.require;
import static com.livingdocs.identity.service.impl.AuthorityInvariants.required;

/** CLI-only orchestration: bind actor, authorize, deduplicate, run one transaction. */
@Service
@RequiredArgsConstructor
public class ProvisioningServiceImpl implements ProvisioningService {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final AuthorityInvariants checks;
    private final AuthorityScopeVerifier scopeVerifier;
    private final TenantProvisioningOperations tenants;
    private final TenantMembershipOperations members;
    private final WorkspaceAuthorityOperations workspaces;
    private final RepositoryGrantOperations grants;


    // 	Kiểm tra dữ liệu cơ bản và mở transaction
    @Override
    public String apply(ProvisioningManifest m, String digest) {
        require(m != null && m.getOperationId() != null && m.getTenantId() != null, "operationId and tenantId are required");
        require(m.getType() != null && !m.getType().isBlank(), "type is required");
        require(m.getReason() != null && m.getReason().length() >= 8
                && m.getReason().length() <= 500, "reason must have 8..500 characters");
        require(digest != null && digest.matches("[0-9a-f]{64}"), "Invalid manifest digest");

        //TransactionTemplate mở transaction rồi gọi applyInTransaction().
        return new TransactionTemplate(transactions).execute(status -> applyInTransaction(m, digest));
    }

    // Điều phối toàn bộ operation: xác thực, kiểm quyền, idempotency, gọi nghiệp vụ
    private String applyInTransaction(ProvisioningManifest m, String digest) {
        // xác thực operator
        VerifiedActor actor = authenticatedOperator();
        require(m.getOperatorIdentityId() == null || actor.identityId().equals(m.getOperatorIdentityId()),
                "Manifest operator identity does not match authenticated DB principal");

        // kiem tra quyen theo nghiep vu
        UUID tenant = m.getTenantId();
        if ("CREATE_TENANT".equals(m.getType())) {
            scopeVerifier.requirePlatformProvisioning(actor.identityId());
        } else if ("REQUEST_REPOSITORY_GRANT".equals(m.getType())) {
            scopeVerifier.requireOwnTechnicalLead(tenant, m.getWorkspaceId(), m.getWorkspaceMembershipId(), actor.identityId());
        } else {
            boolean ownerChange = "ASSIGN_TENANT_ROLE".equals(m.getType())
                    || "REVOKE_TENANT_ROLE".equals(m.getType())
                    || "REVOKE_TENANT_MEMBER".equals(m.getType());
            scopeVerifier.requireTenantOwner(tenant, actor.identityId(), ownerChange);
        }

        // thiet lạp postgre context
        setLocal("app.tenant_id", tenant.toString());
        setLocal("app.actor_identity_id", actor.identityId().toString());
        setLocal("app.change_reason", m.getReason());

        // the hien tinh luy dang Idempotency, chong chay lap
        int inserted = jdbc.update("""
                INSERT INTO identity.provisioning_operations
                    (operation_id, tenant_id, request_sha256, operator_identity_id)
                VALUES (?, ?, ?, ?) ON CONFLICT (operation_id) DO NOTHING
                """, m.getOperationId(), tenant, digest, actor.identityId());
        if (inserted == 0) {
            List<String> matching = jdbc.queryForList("""
                    SELECT request_sha256 FROM identity.provisioning_operations
                    WHERE operation_id = ? AND tenant_id = ? AND operator_identity_id = ?
                    """, String.class, m.getOperationId(), tenant, actor.identityId());
            require(matching.size() == 1 && digest.equals(matching.getFirst().trim()),
                    "Operation ID already used with a different manifest, tenant or actor");
            return "ALREADY_APPLIED";
        }


        RevokeAuthority revoke = new RevokeAuthority(tenant, m.getTargetId());

        // Dispatch đến Domain Operations
        return switch (m.getType()) {
            case "CREATE_TENANT" -> {
                require("TENANT_OWNER".equals(m.getRoleCode()), "Initial Owner role required");
                tenants.create(new CreateTenantWithInitialOwner(tenant, m.getName(),
                        required(m.getIdentityId(), "initialOwnerIdentityId")));
                yield "APPLIED";
            }
            case "CREATE_WORKSPACE" -> {
                workspaces.create(new CreateWorkspace(tenant, m.getWorkspaceId(), m.getName()));
                yield "APPLIED";
            }
            case "ADD_TENANT_MEMBER" -> "APPLIED membershipId=" + members.add(
                    new AddTenantMember(tenant, m.getIdentityId()));
            case "ADD_WORKSPACE_MEMBER" -> "APPLIED workspaceMembershipId=" + workspaces.addMember(
                    new AddWorkspaceMember(tenant, m.getWorkspaceId(), m.getIdentityId()));
            case "ASSIGN_TENANT_ROLE" -> {
                require("TENANT_OWNER".equals(m.getRoleCode()), "Only Tenant Owner role is supported");
                yield "APPLIED assignmentId=" + members.assignOwner(new AssignTenantOwner(tenant, m.getIdentityId()));
            }
            case "ASSIGN_WORKSPACE_ROLE" -> "APPLIED assignmentId=" + workspaces.assignRole(
                    new AssignWorkspaceRole(tenant, m.getWorkspaceId(), m.getWorkspaceMembershipId(), m.getRoleCode()));
            case "REQUEST_REPOSITORY_GRANT" -> "APPLIED grantId=" + grants.requestPending(
                    new RequestRepositoryGrant(tenant, m.getWorkspaceId(), m.getWorkspaceMembershipId(),
                            m.getRepositoryConnectionId(), m.getProvenance()), actor.identityId());
            case "REVOKE_TENANT_MEMBER" -> { members.revokeMember(revoke, actor.identityId()); yield "APPLIED"; }
            case "REVOKE_WORKSPACE_MEMBER" -> { workspaces.revokeMember(revoke); yield "APPLIED"; }
            case "REVOKE_TENANT_ROLE" -> { members.revokeOwner(revoke); yield "APPLIED"; }
            case "REVOKE_WORKSPACE_ROLE" -> { workspaces.revokeRole(revoke); yield "APPLIED"; }
            case "REVOKE_REPOSITORY_GRANT" -> throw new IllegalArgumentException(
                    "Repository grant revoke policy is not approved");
            default -> throw new IllegalArgumentException("Unknown provisioning type");
        };
    }

    // Xác định người thực sự chạy CLI dựa vào PostgreSQL login
    private VerifiedActor authenticatedOperator() {
        // xác định ai đang đăng nhập vào db
        String login = jdbc.queryForObject("SELECT session_user", String.class);

        // kiểm tra login có thuộc nhóm db role dành cho provisioning không
        require(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_has_role(session_user, 'ld_s01_provisioner', 'member')", Boolean.class)),
                "Operator database group role required");

        // tím identity đã được ánh xạ với DB login
        List<UUID> identities = jdbc.queryForList("""
                SELECT identity_id FROM identity.operator_principals
                WHERE db_login_name = session_user AND status = 'ACTIVE'
                """, UUID.class);

        require(identities.size() == 1, "Authenticated operator mapping required");

        UUID identityId = identities.getFirst();
        checks.activeIdentity(identityId);
        return new VerifiedActor(identityId, "OPERATOR_DB_LOGIN", login);
    }

    // Đặt các biến PostgreSQL chỉ có hiệu lực trong transaction hiện tại
    private void setLocal(String key, String value) {
        jdbc.queryForObject("SELECT set_config(?, ?, true)", String.class, key, value);
    }
}
