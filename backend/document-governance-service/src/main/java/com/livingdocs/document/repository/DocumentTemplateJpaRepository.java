package com.livingdocs.document.repository;

import com.livingdocs.document.entity.DocumentTemplateEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTemplateJpaRepository extends JpaRepository<DocumentTemplateEntity, UUID> {
    Optional<DocumentTemplateEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
