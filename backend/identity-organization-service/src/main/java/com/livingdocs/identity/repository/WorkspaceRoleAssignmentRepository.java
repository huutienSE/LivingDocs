package com.livingdocs.identity.repository;

import com.livingdocs.identity.entity.WorkspaceRoleAssignment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceRoleAssignmentRepository extends JpaRepository<WorkspaceRoleAssignment, UUID> {
    @Query("select a from WorkspaceRoleAssignment a where a.tenantId = :tenantId and a.workspaceMembershipId in :membershipIds and a.status = 'ACTIVE'")
    List<WorkspaceRoleAssignment> findActive(@Param("tenantId") UUID tenantId, @Param("membershipIds") Collection<UUID> membershipIds);
}
