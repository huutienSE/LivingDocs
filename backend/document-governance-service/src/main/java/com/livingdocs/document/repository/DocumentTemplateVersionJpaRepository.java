package com.livingdocs.document.repository;

import com.livingdocs.document.entity.DocumentTemplateVersionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTemplateVersionJpaRepository extends JpaRepository<DocumentTemplateVersionEntity, UUID> {
    Optional<DocumentTemplateVersionEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
