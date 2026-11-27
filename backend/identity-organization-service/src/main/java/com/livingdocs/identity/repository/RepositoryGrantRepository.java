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
    @Query("select g from RepositoryGrant g where g.tenantId = :tenantId and g.workspaceMembershipId in :membershipIds and g.status = 'ACTIVE' and g.verificationStatus = 'VERIFIED' and g.verificationSource = 'S02' and g.verifiedBindingRevision is not null")
    List<RepositoryGrant> findEffective(@Param("tenantId") UUID tenantId,
                                        @Param("membershipIds") Collection<UUID> membershipIds);

    @Query("select g from RepositoryGrant g where g.tenantId = :tenantId and g.workspaceId = :workspaceId and g.workspaceMembershipId = :membershipId and g.repositoryConnectionId = :connectionId and g.status = 'ACTIVE'")
    Optional<RepositoryGrant> findActive(@Param("tenantId") UUID tenantId,
             @Param("workspaceId") UUID workspaceId, @Param("membershipId") UUID membershipId,
             @Param("connectionId") UUID connectionId);

    @Query("select g from RepositoryGrant g where g.tenantId = :tenantId and g.workspaceId = :workspaceId order by g.grantId")
    List<RepositoryGrant> findScoped(@Param("tenantId") UUID tenantId, @Param("workspaceId") UUID workspaceId);

    @Query("select g from RepositoryGrant g where g.tenantId = :tenantId and g.workspaceMembershipId in :membershipIds and g.status = 'ACTIVE'")
    List<RepositoryGrant> findForMembers(@Param("tenantId") UUID tenantId,
                                         @Param("membershipIds") Collection<UUID> membershipIds);
}
