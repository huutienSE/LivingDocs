package com.livingdocs.analysis.port;

import com.livingdocs.analysis.diff.StructuralDiffInput.Intent;
import com.livingdocs.analysis.diff.StructuralDiffInput.Side;
import com.livingdocs.analysis.diff.StructuralDiffInput.SnapshotRef;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.SnapshotProof;
import com.livingdocs.analysis.impact.ImpactClassifier.ControlledExportUnit;
import com.livingdocs.analysis.impact.ImpactClassifier.ScopeManifestRef;
import com.livingdocs.analysis.impact.ImpactClassifier.TargetKind;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal port isolating S03 from S02 (API-069 / API-070 / API-071) pinned source & currentness contracts.
 */
public interface SourceAuthorityPort {

	CurrentnessCheckResult validateCurrentness(CurrentnessCheckRequest request);

	ResolvedSnapshotsResult resolveSnapshots(SnapshotResolutionRequest request);

	enum CurrentnessVerdict {
		CURRENT,
		STALE,
		UNKNOWN,
		DENIED
	}

	enum ResolutionStatus {
		RESOLVED,
		UNAVAILABLE,
		DENIED
	}

	record ValidationReceipt(
			UUID validationId,
			UUID tenantId,
			UUID repositoryConnectionId,
			TargetKind targetKind,
			String targetRef,
			String validatedHeadSha,
			String configRevision,
			long sourceRevision,
			Optional<String> fenceToken,
			Instant checkedAt,
			Instant expiresAt) {

		public ValidationReceipt {
			Objects.requireNonNull(validationId, "validationId");
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(repositoryConnectionId, "repositoryConnectionId");
			Objects.requireNonNull(targetKind, "targetKind");
			Objects.requireNonNull(targetRef, "targetRef");
			Objects.requireNonNull(validatedHeadSha, "validatedHeadSha");
			Objects.requireNonNull(configRevision, "configRevision");
			if (targetRef.isBlank() || validatedHeadSha.isBlank() || configRevision.isBlank()) {
				throw new IllegalArgumentException("targetRef, validatedHeadSha, and configRevision must not be blank");
			}
			if (sourceRevision < 0) {
				throw new IllegalArgumentException("sourceRevision must be >= 0");
			}
			Objects.requireNonNull(fenceToken, "fenceToken");
			Objects.requireNonNull(checkedAt, "checkedAt");
			Objects.requireNonNull(expiresAt, "expiresAt");
		}
	}

	record CurrentnessCheckRequest(
			UUID tenantId,
			UUID workspaceId,
			UUID repositoryConnectionId,
			TargetKind targetKind,
			String targetRef,
			String targetSha,
			String configRevision) {

		public CurrentnessCheckRequest {
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(workspaceId, "workspaceId");
			Objects.requireNonNull(repositoryConnectionId, "repositoryConnectionId");
			Objects.requireNonNull(targetKind, "targetKind");
			Objects.requireNonNull(targetRef, "targetRef");
			Objects.requireNonNull(targetSha, "targetSha");
			Objects.requireNonNull(configRevision, "configRevision");
		}
	}

	record CurrentnessCheckResult(
			CurrentnessVerdict verdict,
			Optional<ValidationReceipt> receipt,
			Optional<String> reasonCode) {

		public CurrentnessCheckResult {
			Objects.requireNonNull(verdict, "verdict");
			Objects.requireNonNull(receipt, "receipt");
			Objects.requireNonNull(reasonCode, "reasonCode");
		}
	}

	record SnapshotPair(
			Optional<Side> beforeSide,
			Side afterOrTargetSide,
			Optional<FileAssociationSpec> explicitAssociation) {

		public SnapshotPair {
			Objects.requireNonNull(beforeSide, "beforeSide");
			Objects.requireNonNull(afterOrTargetSide, "afterOrTargetSide");
			Objects.requireNonNull(explicitAssociation, "explicitAssociation");
		}
	}

	record FileAssociationSpec(
			SnapshotRef beforeSnapshot,
			String beforePath,
			SnapshotRef afterSnapshot,
			String afterPath) {

		public FileAssociationSpec {
			Objects.requireNonNull(beforeSnapshot, "beforeSnapshot");
			Objects.requireNonNull(beforePath, "beforePath");
			Objects.requireNonNull(afterSnapshot, "afterSnapshot");
			Objects.requireNonNull(afterPath, "afterPath");
		}
	}

	record SnapshotResolutionRequest(
			UUID tenantId,
			UUID workspaceId,
			UUID repositoryConnectionId,
			TargetKind targetKind,
			String targetRef,
			Intent intent,
			Optional<String> comparisonBeforeSha,
			String authoritativeTargetSha,
			String configRevision) {

		public SnapshotResolutionRequest {
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(workspaceId, "workspaceId");
			Objects.requireNonNull(repositoryConnectionId, "repositoryConnectionId");
			Objects.requireNonNull(targetKind, "targetKind");
			Objects.requireNonNull(targetRef, "targetRef");
			Objects.requireNonNull(intent, "intent");
			Objects.requireNonNull(comparisonBeforeSha, "comparisonBeforeSha");
			Objects.requireNonNull(authoritativeTargetSha, "authoritativeTargetSha");
			Objects.requireNonNull(configRevision, "configRevision");
		}
	}

	record ResolvedSnapshotsResult(
			ResolutionStatus status,
			Optional<ScopeManifestRef> scopeManifest,
			List<SnapshotPair> snapshotPairs,
			List<SnapshotProof> snapshotProofs,
			List<ControlledExportUnit> controlledExports,
			Optional<String> unavailableOrDenyReason) {

		public ResolvedSnapshotsResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(scopeManifest, "scopeManifest");
			snapshotPairs = List.copyOf(Objects.requireNonNull(snapshotPairs, "snapshotPairs"));
			snapshotProofs = List.copyOf(Objects.requireNonNull(snapshotProofs, "snapshotProofs"));
			controlledExports = List.copyOf(Objects.requireNonNull(controlledExports, "controlledExports"));
			Objects.requireNonNull(unavailableOrDenyReason, "unavailableOrDenyReason");
		}
	}
}
