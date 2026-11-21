package com.livingdocs.identity.repository;

import com.livingdocs.identity.entity.TenantRoleAssignment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface  TenantRoleAssignmentRepository extends JpaRepository<TenantRoleAssignment, UUID> {
    // lay ra danh sách các lan gán role cho 1 membership cụ bên trong 1 tenant cụ thể
    @Query("select a from TenantRoleAssignment a where a.tenantId = :tenantId and a.tenantMembershipId = :membershipId and a.status = 'ACTIVE'")
    List<TenantRoleAssignment> findActive(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);
}
