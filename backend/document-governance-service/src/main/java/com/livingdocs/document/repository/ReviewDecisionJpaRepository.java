package com.livingdocs.document.repository;

import com.livingdocs.document.entity.ReviewDecisionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReviewDecisionJpaRepository extends JpaRepository<ReviewDecisionEntity, UUID> {
    Optional<ReviewDecisionEntity> findByTenantIdAndVersionIdAndReviewerIdAndIdempotencyKey(
            UUID tenantId, UUID versionId, UUID reviewerId, String idempotencyKey);

    @Query("SELECT COALESCE(MAX(d.roundNo), 0) + 1 FROM ReviewDecisionEntity d WHERE d.tenantId = :tenant AND d.versionId = :version")
    int nextRound(UUID tenant, UUID version);

    @Query("SELECT d.responseSnapshot FROM ReviewDecisionEntity d WHERE d.tenantId = :tenant AND d.id = :id")
    String responseSnapshot(UUID tenant, UUID id);
}
