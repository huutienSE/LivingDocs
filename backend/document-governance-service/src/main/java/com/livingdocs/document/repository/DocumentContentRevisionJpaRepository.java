package com.livingdocs.document.repository;

import com.livingdocs.document.entity.DocumentContentRevisionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentContentRevisionJpaRepository extends JpaRepository<DocumentContentRevisionEntity, UUID> {
    Optional<DocumentContentRevisionEntity> findFirstByTenantIdAndVersionIdOrderByRevisionNoDesc(UUID tenantId, UUID versionId);
}
