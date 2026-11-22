package com.livingdocs.identity.repository;

import com.livingdocs.identity.entity.PlatformRoleAssignment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformRoleAssignmentRepository extends JpaRepository<PlatformRoleAssignment, UUID> {
    @Query("select a from PlatformRoleAssignment a where a.identityId = :identityId and a.status = 'ACTIVE'")
    List<PlatformRoleAssignment> findActive(@Param("identityId") UUID identityId);
}
