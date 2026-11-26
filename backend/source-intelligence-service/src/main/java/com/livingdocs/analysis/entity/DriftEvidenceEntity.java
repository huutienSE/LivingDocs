package com.livingdocs.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "intelligence", name = "drift_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriftEvidenceEntity {

	@Id
	@Column(name = "drift_evidence_id", nullable = false)
	private UUID driftEvidenceId;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Column(name = "drift_issue_id", nullable = false)
	private UUID driftIssueId;

	@Column(name = "finding_id", nullable = false)
	private UUID findingId;

	@Column(name = "observation_kind", nullable = false, length = 32)
	private String observationKind;

	@Column(name = "evaluated_target_sha", nullable = false, length = 128)
	private String evaluatedTargetSha;

	@Column(name = "document_version", nullable = false, length = 128)
	private String documentVersion;

	@Column(name = "document_bound_sha", nullable = false, length = 128)
	private String documentBoundSha;

	@Column(name = "content_fingerprint", nullable = false, length = 64)
	private String contentFingerprint;

	@Column(name = "observed_at", nullable = false)
	private Instant observedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public DriftEvidenceEntity(
			UUID driftEvidenceId,
			UUID tenantId,
			UUID driftIssueId,
			UUID findingId,
			String observationKind,
			String evaluatedTargetSha,
			String documentVersion,
			String documentBoundSha,
			String contentFingerprint,
			Instant observedAt,
			Instant now) {
		this.driftEvidenceId = Objects.requireNonNull(driftEvidenceId, "driftEvidenceId");
		this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
		this.driftIssueId = Objects.requireNonNull(driftIssueId, "driftIssueId");
		this.findingId = Objects.requireNonNull(findingId, "findingId");
		this.observationKind = Objects.requireNonNull(observationKind, "observationKind");
		this.evaluatedTargetSha = Objects.requireNonNull(evaluatedTargetSha, "evaluatedTargetSha");
		this.documentVersion = Objects.requireNonNull(documentVersion, "documentVersion");
		this.documentBoundSha = Objects.requireNonNull(documentBoundSha, "documentBoundSha");
		this.contentFingerprint = Objects.requireNonNull(contentFingerprint, "contentFingerprint");
		this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
		this.createdAt = Objects.requireNonNull(now, "now");
	}
}
