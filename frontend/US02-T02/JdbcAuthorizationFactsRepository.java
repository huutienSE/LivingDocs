package com.livingdocs.identity.authz;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.stereotype.Component;

/**
 * Reads authorization facts with plain JDBC in ONE short, read-only, REPEATABLE READ transaction, so the facts are a
 * consistent snapshot of the database at call time (never cached: LD-API-001 "fresh role").
 *
 * Tenant isolation is enforced by PostgreSQL RLS (V2/V3 policies use app.tenant_id), set from the resource scope
 * inside the transaction. Every query ALSO filters by tenant_id explicitly (defence in depth).
 *
 * Requires the connection to use the runtime role (ld_s01_app), NOT the table owner: owners bypass RLS.
 */
@Component
public class JdbcAuthorizationFactsRepository implements AuthorizationFactsRepository {

    private static final String SQL_IDENTITY = "SELECT status FROM identity.identities WHERE identity_id = ?";

    private static final String SQL_TENANT_MEMBERSHIP =
            "SELECT status FROM identity.tenant_memberships WHERE identity_id = ? AND tenant_id = ?";

    private static final String SQL_WORKSPACE =
            "SELECT status, archived_at FROM identity.workspaces WHERE workspace_id = ? AND tenant_id = ?";

    // One row per (workspace membership x role assignment) of the actor in this workspace, any status.
    private static final String SQL_ROLES = """
            SELECT tm.status AS tm_status, wm.status AS wm_status, wm.revision AS revision,
                   ra.role_code AS role_code, ra.revoked_at AS revoked_at
              FROM identity.tenant_memberships tm
              JOIN identity.workspace_memberships wm
                ON wm.tenant_id = tm.tenant_id
               AND wm.tenant_membership_id = tm.membership_id
               AND wm.workspace_id = ?
              LEFT JOIN identity.workspace_role_assignments ra
                ON ra.tenant_id = wm.tenant_id
               AND ra.workspace_membership_id = wm.workspace_membership_id
             WHERE tm.identity_id = ? AND tm.tenant_id = ?
            """;

    private final DataSource dataSource;

    public JdbcAuthorizationFactsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public ActorFacts load(UUID actorIdentityId, ResourceScope scope) {
        try (Connection c = dataSource.getConnection()) {
            boolean oldAutoCommit = c.getAutoCommit();
            boolean oldReadOnly = c.isReadOnly();
            int oldIsolation = c.getTransactionIsolation();
            try {
                c.setAutoCommit(false);
                c.setReadOnly(true);
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                ActorFacts facts = read(c, actorIdentityId, scope);
                c.commit();
                return facts;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // original failure is what matters
                }
                throw e;
            } finally {
                // never hand a connection with altered state back to the pool
                c.setReadOnly(oldReadOnly);
                c.setTransactionIsolation(oldIsolation);
                c.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException | RuntimeException e) {
            // Fail closed: the caller turns this into 503 DEPENDENCY_UNAVAILABLE. Never leak SQL details.
            throw new AuthorizationUnavailableException("authorization facts unavailable", e);
        }
    }

    private ActorFacts read(Connection c, UUID actor, ResourceScope scope) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, scope.tenantId().toString());
            ps.executeQuery().close();
        }

        boolean identityActive = false;
        try (PreparedStatement ps = c.prepareStatement(SQL_IDENTITY)) {
            ps.setObject(1, actor);
            try (ResultSet rs = ps.executeQuery()) {
                identityActive = rs.next() && "ACTIVE".equals(rs.getString("status"));
            }
        }

        boolean activeTenantMembership = false;
        try (PreparedStatement ps = c.prepareStatement(SQL_TENANT_MEMBERSHIP)) {
            ps.setObject(1, actor);
            ps.setObject(2, scope.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    activeTenantMembership |= "ACTIVE".equals(rs.getString("status"));
                }
            }
        }

        boolean workspaceActive = false;
        try (PreparedStatement ps = c.prepareStatement(SQL_WORKSPACE)) {
            ps.setObject(1, scope.workspaceId());
            ps.setObject(2, scope.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                workspaceActive = rs.next() && "ACTIVE".equals(rs.getString("status")) && rs.getTimestamp("archived_at") == null;
            }
        }

        boolean activeWorkspaceMembership = false;
        Long revision = null;
        Set<String> activeRoles = new HashSet<>();
        Set<String> formerRoles = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(SQL_ROLES)) {
            ps.setObject(1, scope.workspaceId());
            ps.setObject(2, actor);
            ps.setObject(3, scope.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean membershipActive = "ACTIVE".equals(rs.getString("tm_status")) && "ACTIVE".equals(rs.getString("wm_status"));
                    String role = rs.getString("role_code");
                    boolean roleActive = role != null && rs.getTimestamp("revoked_at") == null;
                    if (membershipActive) {
                        activeWorkspaceMembership = true;
                        revision = rs.getLong("revision");
                        if (roleActive) {
                            activeRoles.add(role);
                        } else if (role != null) {
                            formerRoles.add(role);
                        }
                    } else if (role != null) {
                        formerRoles.add(role);
                    }
                }
            }
        }
        return new ActorFacts(identityActive, activeTenantMembership, workspaceActive, activeWorkspaceMembership,
                activeRoles, formerRoles, revision);
    }
}
