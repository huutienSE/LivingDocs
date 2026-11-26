package com.livingdocs.analysis.repository;

import com.livingdocs.analysis.entity.DriftEvidenceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriftEvidenceRepository extends JpaRepository<DriftEvidenceEntity, UUID> {

	@Query("""
			SELECT e FROM DriftEvidenceEntity e
			WHERE e.tenantId = :tenantId
				AND e.driftIssueId = :driftIssueId
				AND e.findingId = :findingId
				AND e.observationKind = :observationKind
			""")
	Optional<DriftEvidenceEntity> findExistingObservation(
			@Param("tenantId") UUID tenantId,
			@Param("driftIssueId") UUID driftIssueId,
			@Param("findingId") UUID findingId,
			@Param("observationKind") String observationKind);

	@Query("""
			SELECT e FROM DriftEvidenceEntity e
			WHERE e.tenantId = :tenantId AND e.driftIssueId = :driftIssueId
			ORDER BY e.observedAt DESC, e.driftEvidenceId ASC
			""")
	List<DriftEvidenceEntity> findScopedByIssueId(
			@Param("tenantId") UUID tenantId,
			@Param("driftIssueId") UUID driftIssueId);
}
