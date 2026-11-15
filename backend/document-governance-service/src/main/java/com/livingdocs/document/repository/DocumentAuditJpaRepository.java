package com.livingdocs.document.repository;

import com.livingdocs.document.entity.DocumentAuditEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentAuditJpaRepository extends JpaRepository<DocumentAuditEntity, UUID> {
}
