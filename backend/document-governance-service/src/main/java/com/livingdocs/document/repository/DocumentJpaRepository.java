package com.livingdocs.document.repository;

import com.livingdocs.document.entity.DocumentEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentJpaRepository extends JpaRepository<DocumentEntity, UUID> {
    Optional<DocumentEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
