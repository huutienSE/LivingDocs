package com.livingdocs.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "intelligence", name = "analysis_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJobEntity {

	@Id
	@Column(name = "job_id", nullable = false)
	private UUID jobId;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Column(name = "workspace_id", nullable = false)
	private UUID workspaceId;

	@Column(name = "repository_connection_id", nullable = false)
	private UUID repositoryConnectionId;

	@Column(name = "external_job_key", nullable = false, length = 160)
	private String externalJobKey;

	@Column(name = "target_kind", nullable = false, length = 32)
	private String targetKind;

	@Column(name = "target_ref", nullable = false, length = 512)
	private String targetRef;

	@Column(name = "comparison_intent", nullable = false, length = 32)
	private String comparisonIntent;

	@Column(name = "comparison_before_sha", length = 128)
	private String comparisonBeforeSha;

	@Column(name = "authoritative_target_sha", nullable = false, length = 128)
	private String authoritativeTargetSha;

	@Column(name = "scope_manifest_id", length = 128)
	private String scopeManifestId;

	@Column(name = "config_revision", length = 255)
	private String configRevision;

	@Column(name = "declared_scope_completeness", nullable = false, length = 32)
	private String declaredScopeCompleteness;

	@Column(name = "whitespace_no_impact_enabled", nullable = false)
	private boolean whitespaceNoImpactEnabled;

	@Column(name = "status", nullable = false, length = 32)
	private String status;

	@Column(name = "current_attempt_id", nullable = false, length = 128)
	private String currentAttemptId;

	@Column(name = "lease_expires_at", nullable = false)
	private Instant leaseExpiresAt;

	@Column(name = "result_disposition", nullable = false, length = 48)
	private String resultDisposition;

	@Column(name = "processing_status", length = 32)
	private String processingStatus;

	@Column(name = "overall_outcome", length = 32)
	private String overallOutcome;

	@Column(name = "evidence_status", length = 32)
	private String evidenceStatus;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "coverage_summary", nullable = false, columnDefinition = "jsonb")
	private String coverageSummary;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "safe_diagnostics", nullable = false, columnDefinition = "jsonb")
	private String safeDiagnostics;

	@Column(name = "applied_attempt_id", length = 128)
	private String appliedAttemptId;

	@Column(name = "applied_source_revision")
	private Long appliedSourceRevision;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private Long rowVersion;

	public AnalysisJobEntity(
			UUID jobId,
			UUID tenantId,
			UUID workspaceId,
			UUID repositoryConnectionId,
			String externalJobKey,
			String targetKind,
			String targetRef,
			String comparisonIntent,
			String comparisonBeforeSha,
			String authoritativeTargetSha,
			String scopeManifestId,
			String configRevision,
			String declaredScopeCompleteness,
			boolean whitespaceNoImpactEnabled,
			String currentAttemptId,
			Instant leaseExpiresAt,
			Instant now) {
		this.jobId = Objects.requireNonNull(jobId, "jobId");
		this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
		this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
		this.repositoryConnectionId = Objects.requireNonNull(repositoryConnectionId, "repositoryConnectionId");
		this.externalJobKey = Objects.requireNonNull(externalJobKey, "externalJobKey");
		this.targetKind = Objects.requireNonNull(targetKind, "targetKind");
		this.targetRef = Objects.requireNonNull(targetRef, "targetRef");
		this.comparisonIntent = Objects.requireNonNull(comparisonIntent, "comparisonIntent");
		this.comparisonBeforeSha = comparisonBeforeSha;
		this.authoritativeTargetSha = Objects.requireNonNull(authoritativeTargetSha, "authoritativeTargetSha");
		this.scopeManifestId = scopeManifestId;
		this.configRevision = configRevision;
		this.declaredScopeCompleteness = Objects.requireNonNull(declaredScopeCompleteness, "declaredScopeCompleteness");
		this.whitespaceNoImpactEnabled = whitespaceNoImpactEnabled;
		this.status = "CLAIMED";
		this.currentAttemptId = Objects.requireNonNull(currentAttemptId, "currentAttemptId");
		this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
		this.resultDisposition = "PENDING";
		this.coverageSummary = "{}";
		this.safeDiagnostics = "[]";
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}

	public boolean isFinalized() {
		return "APPLIED_CURRENT".equals(resultDisposition) || "SUPPRESSED_STALE".equals(resultDisposition);
	}

	public boolean hasActiveLeaseFor(String attemptId, Instant now) {
		return Objects.equals(this.currentAttemptId, attemptId) && this.leaseExpiresAt.isAfter(now);
	}

	public void claimRetryAttempt(String newAttemptId, Instant newLeaseExpiresAt, Instant now) {
		if (isFinalized()) {
			throw new IllegalStateException("Cannot reclaim finalized job " + jobId);
		}
		this.currentAttemptId = Objects.requireNonNull(newAttemptId, "newAttemptId");
		this.leaseExpiresAt = Objects.requireNonNull(newLeaseExpiresAt, "newLeaseExpiresAt");
		this.status = "CLAIMED";
		this.updatedAt = Objects.requireNonNull(now, "now");
	}

	public void markAppliedCurrent(
			String attemptId,
			long sourceRevision,
			String processingStatus,
			String overallOutcome,
			String evidenceStatus,
			String coverageSummaryJson,
			String safeDiagnosticsJson,
			Instant now) {
		this.status = "COMPLETED";
		this.resultDisposition = "APPLIED_CURRENT";
		this.appliedAttemptId = Objects.requireNonNull(attemptId, "attemptId");
		this.appliedSourceRevision = sourceRevision;
		this.processingStatus = processingStatus;
		this.overallOutcome = overallOutcome;
		this.evidenceStatus = Objects.requireNonNull(evidenceStatus, "evidenceStatus");
		this.coverageSummary = Objects.requireNonNull(coverageSummaryJson, "coverageSummaryJson");
		this.safeDiagnostics = Objects.requireNonNull(safeDiagnosticsJson, "safeDiagnosticsJson");
		this.updatedAt = Objects.requireNonNull(now, "now");
	}

	public void markSuppressedStale(
			String attemptId,
			long observedSourceRevision,
			String processingStatus,
			String overallOutcome,
			String evidenceStatus,
			String coverageSummaryJson,
			String safeDiagnosticsJson,
			Instant now) {
		this.status = "SUPPRESSED";
		this.resultDisposition = "SUPPRESSED_STALE";
		this.appliedAttemptId = Objects.requireNonNull(attemptId, "attemptId");
		this.appliedSourceRevision = observedSourceRevision;
		this.processingStatus = processingStatus;
		this.overallOutcome = overallOutcome;
		this.evidenceStatus = Objects.requireNonNull(evidenceStatus, "evidenceStatus");
		this.coverageSummary = Objects.requireNonNull(coverageSummaryJson, "coverageSummaryJson");
		this.safeDiagnostics = Objects.requireNonNull(safeDiagnosticsJson, "safeDiagnosticsJson");
		this.updatedAt = Objects.requireNonNull(now, "now");
	}

	public void markDeferredUnknownCurrentness(
			String processingStatus,
			String overallOutcome,
			String evidenceStatus,
			String coverageSummaryJson,
			String safeDiagnosticsJson,
			Instant now) {
		this.status = "RETRY_PENDING";
		this.resultDisposition = "DEFERRED_UNKNOWN_CURRENTNESS";
		this.processingStatus = processingStatus;
		this.overallOutcome = overallOutcome;
		this.evidenceStatus = Objects.requireNonNull(evidenceStatus, "evidenceStatus");
		this.coverageSummary = Objects.requireNonNull(coverageSummaryJson, "coverageSummaryJson");
		this.safeDiagnostics = Objects.requireNonNull(safeDiagnosticsJson, "safeDiagnosticsJson");
		this.updatedAt = Objects.requireNonNull(now, "now");
	}
}
