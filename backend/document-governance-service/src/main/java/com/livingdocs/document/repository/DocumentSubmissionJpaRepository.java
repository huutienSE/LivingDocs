package com.livingdocs.document.repository;
import com.livingdocs.document.entity.DocumentSubmissionEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentSubmissionJpaRepository extends JpaRepository<DocumentSubmissionEntity, UUID> {
    Optional<DocumentSubmissionEntity> findFirstByTenantIdAndVersionIdOrderByResultingRevisionDesc(UUID tenantId, UUID versionId);
    Optional<DocumentSubmissionEntity> findByTenantIdAndVersionIdAndActorIdAndKey(UUID tenant, UUID version, UUID actor, String key);
}
