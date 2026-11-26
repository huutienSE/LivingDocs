package com.livingdocs.analysis.repository;

import com.livingdocs.analysis.entity.AnalysisFindingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisFindingRepository extends JpaRepository<AnalysisFindingEntity, UUID> {

	@Query("""
			SELECT f FROM AnalysisFindingEntity f
			WHERE f.tenantId = :tenantId
				AND f.jobId = :jobId
				AND f.deterministicFindingKey = :deterministicFindingKey
			""")
	Optional<AnalysisFindingEntity> findScopedByJobAndKey(
			@Param("tenantId") UUID tenantId,
			@Param("jobId") UUID jobId,
			@Param("deterministicFindingKey") String deterministicFindingKey);

	@Query("""
			SELECT f FROM AnalysisFindingEntity f
			WHERE f.tenantId = :tenantId AND f.jobId = :jobId
			ORDER BY f.deterministicFindingKey ASC
			""")
	List<AnalysisFindingEntity> findScopedByJobId(
			@Param("tenantId") UUID tenantId,
			@Param("jobId") UUID jobId);
}
