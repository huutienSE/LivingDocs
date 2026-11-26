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

@Entity
@Table(schema = "intelligence", name = "drift_issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriftIssueEntity {

	@Id
	@Column(name = "drift_issue_id", nullable = false)
	private UUID driftIssueId;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Column(name = "workspace_id", nullable = false)
	private UUID workspaceId;

	@Column(name = "repository_connection_id", nullable = false)
	private UUID repositoryConnectionId;

	@Column(name = "target_kind", nullable = false, length = 32)
	private String targetKind;

	@Column(name = "target_ref", nullable = false, length = 512)
	private String targetRef;

	@Column(name = "document_id", nullable = false)
	private UUID documentId;

	@Column(name = "document_version", nullable = false, length = 128)
	private String documentVersion;

	@Column(name = "binding_id", nullable = false, length = 128)
	private String bindingId;

	@Column(name = "claim_id", nullable = false, length = 128)
	private String claimId;

	@Column(name = "canonical_subject_key", nullable = false, columnDefinition = "TEXT")
	private String canonicalSubjectKey;

	@Column(name = "issue_identity_key", nullable = false, length = 64)
	private String issueIdentityKey;

	@Column(name = "drift_classification", length = 32)
	private String driftClassification;

	@Column(name = "severity_status", nullable = false, length = 32)
	private String severityStatus;

	@Column(name = "severity", length = 32)
	private String severity;

	@Column(name = "state", nullable = false, length = 32)
	private String state;

	@Column(name = "opened_at_sha", nullable = false, length = 128)
	private String openedAtSha;

	@Column(name = "last_observed_sha", nullable = false, length = 128)
	private String lastObservedSha;

	@Column(name = "last_observed_at", nullable = false)
	private Instant lastObservedAt;

	@Column(name = "last_consistent_sha", length = 128)
	private String lastConsistentSha;

	@Column(name = "last_consistent_at")
	private Instant lastConsistentAt;

	@Column(name = "revert_observed", nullable = false)
	private boolean revertObserved;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private Long rowVersion;

	public DriftIssueEntity(
			UUID driftIssueId,
			UUID tenantId,
			UUID workspaceId,
			UUID repositoryConnectionId,
			String targetKind,
			String targetRef,
			UUID documentId,
			String documentVersion,
			String bindingId,
			String claimId,
			String canonicalSubjectKey,
			String issueIdentityKey,
			String driftClassification,
			String severityStatus,
			String severity,
			String initialState,
			String observedSha,
			Instant now) {
		if (!"DETECTED".equals(initialState) && !"ANALYZED".equals(initialState)) {
			throw new IllegalArgumentException("New drift issue must start at DETECTED or ANALYZED: " + initialState);
		}
		this.driftIssueId = Objects.requireNonNull(driftIssueId, "driftIssueId");
		this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
		this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
		this.repositoryConnectionId = Objects.requireNonNull(repositoryConnectionId, "repositoryConnectionId");
		this.targetKind = Objects.requireNonNull(targetKind, "targetKind");
		this.targetRef = Objects.requireNonNull(targetRef, "targetRef");
		this.documentId = Objects.requireNonNull(documentId, "documentId");
		this.documentVersion = Objects.requireNonNull(documentVersion, "documentVersion");
		this.bindingId = Objects.requireNonNull(bindingId, "bindingId");
		this.claimId = Objects.requireNonNull(claimId, "claimId");
		this.canonicalSubjectKey = Objects.requireNonNull(canonicalSubjectKey, "canonicalSubjectKey");
		this.issueIdentityKey = Objects.requireNonNull(issueIdentityKey, "issueIdentityKey");
		this.driftClassification = driftClassification;
		this.severityStatus = Objects.requireNonNull(severityStatus, "severityStatus");
		this.severity = severity;
		this.state = initialState;
		this.openedAtSha = Objects.requireNonNull(observedSha, "observedSha");
		this.lastObservedSha = observedSha;
		this.lastObservedAt = Objects.requireNonNull(now, "now");
		this.revertObserved = false;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public boolean isOpen() {
		return "DETECTED".equals(state) || "ANALYZED".equals(state) || "SUGGESTED".equals(state);
	}

	public void applyMismatchObservation(
			String observedSha,
			boolean promotedToAnalyzed,
			String newClassification,
			String newSeverityStatus,
			String newSeverity,
			Instant now) {
		if (!isOpen()) {
			throw new IllegalStateException("Closed drift issue cannot be updated: " + state);
		}
		this.lastObservedSha = Objects.requireNonNull(observedSha, "observedSha");
		this.lastObservedAt = Objects.requireNonNull(now, "now");
		this.revertObserved = false;
		if ("DETECTED".equals(this.state) && promotedToAnalyzed) {
			this.state = "ANALYZED";
		}
		if (newClassification != null) {
			this.driftClassification = newClassification;
		}
		if ("EVALUATED".equals(newSeverityStatus) && newSeverity != null) {
			this.severityStatus = newSeverityStatus;
			this.severity = newSeverity;
		}
		this.updatedAt = now;
	}

	/** Records R2 verified consistency without altering lifecycle state or last mismatch SHA. */
	public void recordConsistentRevert(String evaluatedSha, Instant now) {
		if (!isOpen()) {
			throw new IllegalStateException("Closed drift issue cannot record R2 revert: " + state);
		}
		this.lastConsistentSha = Objects.requireNonNull(evaluatedSha, "evaluatedSha");
		this.lastConsistentAt = Objects.requireNonNull(now, "now");
		this.revertObserved = true;
		this.updatedAt = now;
	}
}
