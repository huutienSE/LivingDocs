package com.livingdocs.identity.repository;

import com.livingdocs.identity.entity.WorkspaceMembership;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, UUID> {
    //
    @Query(value = """
        SELECT wm.* FROM identity.workspace_memberships wm
        JOIN identity.tenant_memberships tm ON tm.tenant_id = wm.tenant_id
            AND tm.membership_id = wm.tenant_membership_id
        JOIN identity.workspaces w ON w.tenant_id = wm.tenant_id
            AND w.workspace_id = wm.workspace_id
        WHERE wm.tenant_id = :tenantId AND wm.identity_id = :identityId
            AND wm.status = 'ACTIVE' AND tm.status = 'ACTIVE' AND w.status = 'ACTIVE'
        ORDER BY wm.workspace_id
        """, nativeQuery = true)
    List<WorkspaceMembership> findActiveForIdentity(@Param("tenantId") UUID tenantId, @Param("identityId") UUID identityId);
}
