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
@Table(schema = "intelligence", name = "analysis_findings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisFindingEntity {

	@Id
	@Column(name = "finding_id", nullable = false)
	private UUID findingId;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Column(name = "job_id", nullable = false)
	private UUID jobId;

	@Column(name = "drift_issue_id")
	private UUID driftIssueId;

	@Column(name = "deterministic_finding_key", nullable = false, length = 80)
	private String deterministicFindingKey;

	@Column(name = "unit_disposition", nullable = false, length = 32)
	private String unitDisposition;

	@Column(name = "impact_outcome", nullable = false, length = 32)
	private String impactOutcome;

	@Column(name = "drift_classification", length = 32)
	private String driftClassification;

	@Column(name = "severity_status", nullable = false, length = 32)
	private String severityStatus;

	@Column(name = "severity", length = 32)
	private String severity;

	@Column(name = "semantic_confidence")
	private Double semanticConfidence;

	@Column(name = "issue_admission", nullable = false, length = 48)
	private String issueAdmission;

	@Column(name = "primary_reason", nullable = false, length = 96)
	private String primaryReason;

	@Column(name = "admission_reason", nullable = false, length = 96)
	private String admissionReason;

	@Column(name = "evidence_status", nullable = false, length = 32)
	private String evidenceStatus;

	@Column(name = "evidence_schema_version", nullable = false, length = 32)
	private String evidenceSchemaVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence_payload", nullable = false, columnDefinition = "jsonb")
	private String evidencePayload;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private Long rowVersion;

	public AnalysisFindingEntity(
			UUID findingId,
			UUID tenantId,
			UUID jobId,
			String deterministicFindingKey,
			String unitDisposition,
			String impactOutcome,
			String driftClassification,
			String severityStatus,
			String severity,
			Double semanticConfidence,
			String issueAdmission,
			String primaryReason,
			String admissionReason,
			String evidenceStatus,
			String evidenceSchemaVersion,
			String evidencePayloadJson,
			Instant now) {
		this.findingId = Objects.requireNonNull(findingId, "findingId");
		this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
		this.jobId = Objects.requireNonNull(jobId, "jobId");
		this.deterministicFindingKey = Objects.requireNonNull(deterministicFindingKey, "deterministicFindingKey");
		this.unitDisposition = Objects.requireNonNull(unitDisposition, "unitDisposition");
		this.impactOutcome = Objects.requireNonNull(impactOutcome, "impactOutcome");
		this.driftClassification = driftClassification;
		this.severityStatus = Objects.requireNonNull(severityStatus, "severityStatus");
		this.severity = severity;
		this.semanticConfidence = semanticConfidence;
		this.issueAdmission = Objects.requireNonNull(issueAdmission, "issueAdmission");
		this.primaryReason = Objects.requireNonNull(primaryReason, "primaryReason");
		this.admissionReason = Objects.requireNonNull(admissionReason, "admissionReason");
		this.evidenceStatus = Objects.requireNonNull(evidenceStatus, "evidenceStatus");
		this.evidenceSchemaVersion = Objects.requireNonNull(evidenceSchemaVersion, "evidenceSchemaVersion");
		this.evidencePayload = Objects.requireNonNull(evidencePayloadJson, "evidencePayloadJson");
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}

	public void refreshFromRetry(
			String unitDisposition,
			String impactOutcome,
			String driftClassification,
			String severityStatus,
			String severity,
			Double semanticConfidence,
			String issueAdmission,
			String primaryReason,
			String admissionReason,
			String evidenceStatus,
			String evidenceSchemaVersion,
			String evidencePayloadJson,
			Instant now) {
		this.unitDisposition = Objects.requireNonNull(unitDisposition, "unitDisposition");
		this.impactOutcome = Objects.requireNonNull(impactOutcome, "impactOutcome");
		this.driftClassification = driftClassification;
		this.severityStatus = Objects.requireNonNull(severityStatus, "severityStatus");
		this.severity = severity;
		this.semanticConfidence = semanticConfidence;
		this.issueAdmission = Objects.requireNonNull(issueAdmission, "issueAdmission");
		this.primaryReason = Objects.requireNonNull(primaryReason, "primaryReason");
		this.admissionReason = Objects.requireNonNull(admissionReason, "admissionReason");
		this.evidenceStatus = Objects.requireNonNull(evidenceStatus, "evidenceStatus");
		this.evidenceSchemaVersion = Objects.requireNonNull(evidenceSchemaVersion, "evidenceSchemaVersion");
		this.evidencePayload = Objects.requireNonNull(evidencePayloadJson, "evidencePayloadJson");
		this.updatedAt = Objects.requireNonNull(now, "now");
	}

	public void linkToIssue(UUID driftIssueId, Instant now) {
		if (this.driftIssueId != null && !this.driftIssueId.equals(driftIssueId)) {
			throw new IllegalStateException("Finding " + findingId + " already linked to issue " + this.driftIssueId);
		}
		this.driftIssueId = Objects.requireNonNull(driftIssueId, "driftIssueId");
		this.updatedAt = Objects.requireNonNull(now, "now");
	}
}
