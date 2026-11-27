package com.livingdocs.document.repository;

import com.livingdocs.document.entity.DocumentVersionEntity;
import com.livingdocs.document.model.DocumentState;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Used within the adapter's mandatory tenant transaction, after acquiring scope locks. */
public interface DocumentVersionJpaRepository extends JpaRepository<DocumentVersionEntity, UUID> {
    Optional<DocumentVersionEntity> findFirstByTenantIdAndDocumentIdAndVersionNoLessThanAndStateInOrderByVersionNoDesc(
            UUID tenantId, UUID documentId, int versionNo, Collection<DocumentState> states);
}
