package com.livingdocs.analysis.repository;

import com.livingdocs.analysis.entity.DriftIssueEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriftIssueRepository extends JpaRepository<DriftIssueEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT i FROM DriftIssueEntity i
			WHERE i.tenantId = :tenantId
				AND i.repositoryConnectionId = :repositoryConnectionId
				AND i.targetKind = :targetKind
				AND i.targetRef = :targetRef
				AND i.issueIdentityKey = :issueIdentityKey
				AND i.state IN ('DETECTED', 'ANALYZED', 'SUGGESTED')
			""")
	Optional<DriftIssueEntity> findOpenForUpdate(
			@Param("tenantId") UUID tenantId,
			@Param("repositoryConnectionId") UUID repositoryConnectionId,
			@Param("targetKind") String targetKind,
			@Param("targetRef") String targetRef,
			@Param("issueIdentityKey") String issueIdentityKey);

	@Query("""
			SELECT i FROM DriftIssueEntity i
			WHERE i.tenantId = :tenantId
				AND i.repositoryConnectionId = :repositoryConnectionId
				AND i.targetKind = :targetKind
				AND i.targetRef = :targetRef
			ORDER BY i.lastObservedAt DESC, i.driftIssueId ASC
			""")
	List<DriftIssueEntity> findByTargetScope(
			@Param("tenantId") UUID tenantId,
			@Param("repositoryConnectionId") UUID repositoryConnectionId,
			@Param("targetKind") String targetKind,
			@Param("targetRef") String targetRef);
}
