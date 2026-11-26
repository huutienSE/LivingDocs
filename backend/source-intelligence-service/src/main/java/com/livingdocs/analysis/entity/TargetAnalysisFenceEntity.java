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
@Table(schema = "intelligence", name = "target_analysis_fences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TargetAnalysisFenceEntity {

	@Id
	@Column(name = "fence_id", nullable = false)
	private UUID fenceId;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Column(name = "repository_connection_id", nullable = false)
	private UUID repositoryConnectionId;

	@Column(name = "target_kind", nullable = false, length = 32)
	private String targetKind;

	@Column(name = "target_ref", nullable = false, length = 512)
	private String targetRef;

	@Column(name = "latest_source_revision", nullable = false)
	private Long latestSourceRevision;

	@Column(name = "latest_validated_sha", nullable = false, length = 128)
	private String latestValidatedSha;

	@Column(name = "latest_config_revision", nullable = false, length = 255)
	private String latestConfigRevision;

	@Column(name = "last_fenced_job_id")
	private UUID lastFencedJobId;

	@Column(name = "last_validated_at", nullable = false)
	private Instant lastValidatedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private Long rowVersion;

	public TargetAnalysisFenceEntity(
			UUID fenceId,
			UUID tenantId,
			UUID repositoryConnectionId,
			String targetKind,
			String targetRef,
			long initialSourceRevision,
			String validatedSha,
			String configRevision,
			UUID lastFencedJobId,
			Instant validatedAt,
			Instant now) {
		this.fenceId = Objects.requireNonNull(fenceId, "fenceId");
		this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
		this.repositoryConnectionId = Objects.requireNonNull(repositoryConnectionId, "repositoryConnectionId");
		this.targetKind = Objects.requireNonNull(targetKind, "targetKind");
		this.targetRef = Objects.requireNonNull(targetRef, "targetRef");
		if (initialSourceRevision < 0) {
			throw new IllegalArgumentException("initialSourceRevision must be >= 0");
		}
		this.latestSourceRevision = initialSourceRevision;
		this.latestValidatedSha = Objects.requireNonNull(validatedSha, "validatedSha");
		this.latestConfigRevision = Objects.requireNonNull(configRevision, "configRevision");
		this.lastFencedJobId = lastFencedJobId;
		this.lastValidatedAt = Objects.requireNonNull(validatedAt, "validatedAt");
		this.updatedAt = Objects.requireNonNull(now, "now");
	}

	public boolean isInitialized() {
		return this.lastFencedJobId != null;
	}

	public boolean acceptsRevision(long candidateRevision) {
		return candidateRevision >= 0 && (!isInitialized() || candidateRevision >= this.latestSourceRevision);
	}

	public void advance(
			long candidateRevision,
			String validatedSha,
			String configRevision,
			UUID jobId,
			Instant validatedAt,
			Instant now) {
		Objects.requireNonNull(validatedSha, "validatedSha");
		Objects.requireNonNull(configRevision, "configRevision");
		if (!acceptsRevision(candidateRevision)) {
			throw new IllegalStateException("Cannot regress target fence from "
					+ this.latestSourceRevision + " to " + candidateRevision);
		}
		if (isInitialized()
				&& candidateRevision == this.latestSourceRevision
				&& !Objects.equals(this.latestValidatedSha, validatedSha)) {
			throw new IllegalArgumentException("Contradictory validatedHeadSha for already-fenced revision "
					+ candidateRevision);
		}
		this.latestSourceRevision = candidateRevision;
		this.latestValidatedSha = validatedSha;
		this.latestConfigRevision = configRevision;
		this.lastFencedJobId = Objects.requireNonNull(jobId, "jobId");
		this.lastValidatedAt = Objects.requireNonNull(validatedAt, "validatedAt");
		this.updatedAt = Objects.requireNonNull(now, "now");
	}
}
