package com.livingdocs.document.repository;

import com.livingdocs.document.entity.ReviewQueueEntry;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

/** Immutable view; service owns the transaction and its tenant context. */
public interface ReviewQueueJpaRepository extends JpaRepository<ReviewQueueEntry, UUID>, JpaSpecificationExecutor<ReviewQueueEntry> {
    @Query(value = "SELECT set_config('statement_timeout', :timeout, true)", nativeQuery = true)
    String setStatementTimeout(String timeout);
}
