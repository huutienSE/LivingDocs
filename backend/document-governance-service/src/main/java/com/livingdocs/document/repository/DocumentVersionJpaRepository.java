package com.livingdocs.document.repository;

import com.livingdocs.document.entity.DocumentVersionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Used within the adapter's mandatory tenant transaction, after acquiring scope locks. */
public interface DocumentVersionJpaRepository extends JpaRepository<DocumentVersionEntity, UUID> {
}
