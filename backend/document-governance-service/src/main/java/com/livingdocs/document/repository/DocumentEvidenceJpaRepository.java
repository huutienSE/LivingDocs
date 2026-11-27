package com.livingdocs.document.repository;

import com.livingdocs.document.entity.DocumentEvidenceEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentEvidenceJpaRepository extends JpaRepository<DocumentEvidenceEntity, UUID> {
    List<DocumentEvidenceEntity> findByTenantIdAndVersionIdOrderById(UUID tenantId, UUID versionId);
    @Query("""
            select e.id from DocumentEvidenceEntity e
            where e.tenantId = :tenant and e.versionId = :version
              and (length(trim(e.sourceRef)) > 0 or length(trim(e.artifactRef)) > 0)
            order by e.id
            """)
    List<UUID> findUsableIds(@Param("tenant") UUID tenant, @Param("version") UUID version);
}
