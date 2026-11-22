package com.livingdocs.identity.repository;

import com.livingdocs.identity.entity.RepositoryGrant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositoryGrantRepository extends JpaRepository<RepositoryGrant, UUID> {
    @Query("select g from RepositoryGrant g where g.tenantId = :tenantId and g.workspaceMembershipId in :membershipIds and g.status = 'ACTIVE' and g.verificationStatus = 'VERIFIED'")
    List<RepositoryGrant> findEffective(@Param("tenantId") UUID tenantId,
                                        @Param("membershipIds") Collection<UUID> membershipIds);
}
