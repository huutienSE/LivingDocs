package com.livingdocs.analysis.repository;

import com.livingdocs.analysis.entity.AnalysisJobEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJobEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT j FROM AnalysisJobEntity j
			WHERE j.tenantId = :tenantId AND j.externalJobKey = :externalJobKey
			""")
	Optional<AnalysisJobEntity> findForUpdateByExternalKey(
			@Param("tenantId") UUID tenantId,
			@Param("externalJobKey") String externalJobKey);

	@Query("""
			SELECT j FROM AnalysisJobEntity j
			WHERE j.tenantId = :tenantId AND j.externalJobKey = :externalJobKey
			""")
	Optional<AnalysisJobEntity> findScopedByExternalKey(
			@Param("tenantId") UUID tenantId,
			@Param("externalJobKey") String externalJobKey);
}
