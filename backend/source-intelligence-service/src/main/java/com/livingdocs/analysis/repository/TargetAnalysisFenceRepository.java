package com.livingdocs.analysis.repository;

import com.livingdocs.analysis.entity.TargetAnalysisFenceEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TargetAnalysisFenceRepository extends JpaRepository<TargetAnalysisFenceEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT f FROM TargetAnalysisFenceEntity f
			WHERE f.tenantId = :tenantId
				AND f.repositoryConnectionId = :repositoryConnectionId
				AND f.targetKind = :targetKind
				AND f.targetRef = :targetRef
			""")
	Optional<TargetAnalysisFenceEntity> findForUpdateByTarget(
			@Param("tenantId") UUID tenantId,
			@Param("repositoryConnectionId") UUID repositoryConnectionId,
			@Param("targetKind") String targetKind,
			@Param("targetRef") String targetRef);

	@Query("""
			SELECT f FROM TargetAnalysisFenceEntity f
			WHERE f.tenantId = :tenantId
				AND f.repositoryConnectionId = :repositoryConnectionId
				AND f.targetKind = :targetKind
				AND f.targetRef = :targetRef
			""")
	Optional<TargetAnalysisFenceEntity> findScopedByTarget(
			@Param("tenantId") UUID tenantId,
			@Param("repositoryConnectionId") UUID repositoryConnectionId,
			@Param("targetKind") String targetKind,
			@Param("targetRef") String targetRef);
}
