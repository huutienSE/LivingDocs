package com.livingdocs.document.repository;

import com.livingdocs.document.entity.ContentRevisionEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRevisionJpaRepository extends JpaRepository<ContentRevisionEntity, UUID> {
    Optional<ContentRevisionEntity> findFirstByTenantIdAndVersionIdOrderByRevisionNoDesc(UUID tenantId, UUID versionId);
}
