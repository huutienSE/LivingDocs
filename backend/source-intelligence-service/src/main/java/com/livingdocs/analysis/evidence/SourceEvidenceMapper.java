package com.livingdocs.analysis.evidence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.livingdocs.analysis.ast.JavaParseResult.SourceRange;
import com.livingdocs.analysis.ast.JavaParseResult.SourceReference;
import com.livingdocs.analysis.ast.JavaParseResult.SymbolKey;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FacetKind;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.ProvenanceRef;
import com.livingdocs.analysis.diff.StructuralDiff.ChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.ContextChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.DiagnosticCode;
import com.livingdocs.analysis.diff.StructuralDiff.DiffDiagnostic;
import com.livingdocs.analysis.diff.StructuralDiff.EntityFact;
import com.livingdocs.analysis.diff.StructuralDiff.EntitySide;
import com.livingdocs.analysis.diff.StructuralDiff.MatchBasis;
import com.livingdocs.analysis.diff.StructuralDiff.PathRelation;
import com.livingdocs.analysis.diff.StructuralDiff.PresenceRelation;
import com.livingdocs.analysis.diff.StructuralDiff.ProcessingStatus;
import com.livingdocs.analysis.diff.StructuralDiff.RawChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.SideContextFacts;
import com.livingdocs.analysis.diff.StructuralDiff.SidePresence;
import com.livingdocs.analysis.diff.StructuralDiff.SideRole;
import com.livingdocs.analysis.diff.StructuralDiff.SnapshotProvenance;
import com.livingdocs.analysis.diff.StructuralDiff.StructuralDiffResult;
import com.livingdocs.analysis.diff.StructuralDiff.StructuralEquality;
import com.livingdocs.analysis.diff.StructuralDiff.UnmatchedReason;
import com.livingdocs.analysis.diff.StructuralDiffInput.Intent;
import com.livingdocs.analysis.diff.StructuralDiffInput.SnapshotRef;
import com.livingdocs.analysis.impact.ImpactClassifier;
import com.livingdocs.analysis.impact.ImpactClassifier.AssessmentContext;
import com.livingdocs.analysis.impact.ImpactClassifier.BindingGranularity;
import com.livingdocs.analysis.impact.ImpactClassifier.CallerExclusion;
import com.livingdocs.analysis.impact.ImpactClassifier.ClaimVerdict;
import com.livingdocs.analysis.impact.ImpactClassifier.ControlledExportUnit;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentBinding;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentClaim;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentLookupKey;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentLookupResponse;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentWitnessSelector;
import com.livingdocs.analysis.impact.ImpactClassifier.DriftClassification;
import com.livingdocs.analysis.impact.ImpactClassifier.GroundedDocumentClaimRef;
import com.livingdocs.analysis.impact.ImpactClassifier.GroundedSourceFacetRef;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactFinding;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactOutcome;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactReport;
import com.livingdocs.analysis.impact.ImpactClassifier.IssueAdmission;
import com.livingdocs.analysis.impact.ImpactClassifier.LookupCompleteness;
import com.livingdocs.analysis.impact.ImpactClassifier.LookupGranularity;
import com.livingdocs.analysis.impact.ImpactClassifier.NegativeBasisKind;
import com.livingdocs.analysis.impact.ImpactClassifier.NegativeBasisWitnessSelector;
import com.livingdocs.analysis.impact.ImpactClassifier.ReasonCode;
import com.livingdocs.analysis.impact.ImpactClassifier.ReportProcessingStatus;
import com.livingdocs.analysis.impact.ImpactClassifier.ScopeCompleteness;
import com.livingdocs.analysis.impact.ImpactClassifier.ScopeManifestRef;
import com.livingdocs.analysis.impact.ImpactClassifier.SelectorGranularity;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticAssessmentInput;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticStatus;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticWitnessSelector;
import com.livingdocs.analysis.impact.ImpactClassifier.Severity;
import com.livingdocs.analysis.impact.ImpactClassifier.SeverityStatus;
import com.livingdocs.analysis.impact.ImpactClassifier.SourceEvidenceSelector;
import com.livingdocs.analysis.impact.ImpactClassifier.TargetKind;
import com.livingdocs.analysis.impact.ImpactClassifier.UnitDisposition;
import com.livingdocs.analysis.impact.ImpactClassifier.WitnessSelector;

/**
 * Deterministic grounded-evidence verifier and safe-projection mapper for M4.
 * Verifies M3 {@link WitnessSelector}s against M2 structural provenance and
 * caller-supplied S02/S04/S05 authority fixtures, invokes M3's
 * {@link ImpactClassifier#applyVerifiedWitnesses(ImpactReport, Map)}, and emits
 * a minimal, scope-safe {@link EvidenceResult}.
 */
public final class SourceEvidenceMapper {

	private static final Comparator<SourceRange> RANGE_COMPARATOR = Comparator
			.comparingInt(SourceRange::startLine)
			.thenComparingInt(SourceRange::startColumn)
			.thenComparingInt(SourceRange::endLine)
			.thenComparingInt(SourceRange::endColumn);

	private static final Comparator<SourceLocator> LOCATOR_COMPARATOR = Comparator
			.comparingInt((SourceLocator l) -> roleOrder(l.sideRole()))
			.thenComparing(SourceLocator::pinnedSha)
			.thenComparing(SourceLocator::path)
			.thenComparing(l -> l.filePresence().name())
			.thenComparing(l -> l.basis().name())
			.thenComparing(l -> l.symbolKey().map(JavaStructuralRepresentation::canonicalSymbolKey).orElse(""))
			.thenComparing(l -> l.facetKind().map(Enum::name).orElse(""))
			.thenComparing(l -> l.contextChangeKind().map(Enum::name).orElse(""))
			.thenComparing(SourceLocator::ranges, SourceEvidenceMapper::compareRangeLists);

	private static final Comparator<DocumentLookupKey> LOOKUP_KEY_COMPARATOR = Comparator
			.comparingInt((DocumentLookupKey k) -> roleOrder(k.sideRole()))
			.thenComparing(DocumentLookupKey::pinnedSha)
			.thenComparing(DocumentLookupKey::path)
			.thenComparing(k -> k.granularity().name())
			.thenComparing(k -> k.symbolKey().map(JavaStructuralRepresentation::canonicalSymbolKey).orElse(""));

	private static final Comparator<WitnessEvidence> WITNESS_COMPARATOR = Comparator
			.comparing(WitnessEvidence::witnessId)
			.thenComparing(w -> w.status().name())
			.thenComparing(w -> w.reasons().stream().map(Enum::name).reduce("", (a, b) -> a + "," + b))
			.thenComparing(w -> w.sourceLocators(), SourceEvidenceMapper::compareLocatorLists)
			.thenComparing(w -> w.documentRef().map(SourceEvidenceMapper::docRefSortKey).orElse(""))
			.thenComparing(w -> w.negativeBasis().map(SourceEvidenceMapper::negBasisSortKey).orElse(""))
			.thenComparing(w -> w.semanticStatus().map(Enum::name).orElse(""));

	private static final Comparator<SafeDiagnostic> DIAGNOSTIC_COMPARATOR = Comparator
			.comparing((SafeDiagnostic d) -> d.diffCode().map(Enum::name).orElse(""))
			.thenComparing(d -> d.impactCode().map(Enum::name).orElse(""))
			.thenComparing(d -> d.evidenceReason().map(Enum::name).orElse(""))
			.thenComparing((SafeDiagnostic d1, SafeDiagnostic d2) -> {
				if (d1.location().isEmpty() && d2.location().isEmpty()) {
					return 0;
				}
				if (d1.location().isEmpty()) {
					return -1;
				}
				if (d2.location().isEmpty()) {
					return 1;
				}
				return LOCATOR_COMPARATOR.compare(d1.location().get(), d2.location().get());
			});

	public SourceEvidenceMapper() {
	}

	public EvidenceResult map(EvidenceInput input) {
		Objects.requireNonNull(input, "input");

		// 1. Scope & context gate.
		if (input.authorizedContext().isEmpty()) {
			return gateRejected(EvidenceReason.SCOPE_DENIED);
		}
		AssessmentContext ctx = input.authorizedContext().get();
		if (ctx.declaredScopeCompleteness() == ScopeCompleteness.DENIED) {
			return gateRejected(EvidenceReason.SCOPE_DENIED);
		}
		if (!ctx.equals(input.proposedReport().context())) {
			return gateRejected(EvidenceReason.SCOPE_DENIED);
		}
		if (!isValidContext(ctx) || !ImpactClassifier.CLASSIFIER_VERSION.equals(input.proposedReport().classifierVersion())) {
			return gateRejected(EvidenceReason.INVALID_INPUT);
		}

		// Validate and deduplicate proposed report findings & selectors.
		Optional<ImpactReport> gatedReport = validateAndDedupeReport(ctx, input.proposedReport());
		if (gatedReport.isEmpty()) {
			return gateRejected(EvidenceReason.INVALID_INPUT);
		}
		ImpactReport dedupedReport = gatedReport.get();

		// 2. Build assessment-scoped indices.
		var safeDiagnostics = new LinkedHashSet<SafeDiagnostic>();
		var diffIndex = indexDiffs(ctx, input.diffResults(), safeDiagnostics);
		var snapshotIndex = indexSnapshots(ctx, input.snapshotProofs(), safeDiagnostics);
		var lookupIndex = indexLookups(ctx, input.lookupResponses(), safeDiagnostics);
		var semanticIndex = indexSemantics(ctx, input.semanticAssessments(), safeDiagnostics);
		var manifestIndex = indexManifests(ctx, input.scopeManifests(), safeDiagnostics);
		var exclusionIndex = indexCallerExclusions(ctx, input.callerExclusions(), safeDiagnostics);
		var exportIndex = indexControlledExports(ctx, input.controlledExports(), safeDiagnostics);

		var indices = new AssessmentIndices(
				ctx, diffIndex, snapshotIndex, lookupIndex, semanticIndex,
				manifestIndex, exclusionIndex, exportIndex);

		// 3. Verify each finding's witnesses (source/doc first, then semantic, plus negative basis).
		var witnessCache = new HashMap<WitnessCacheKey, WitnessVerification>();
		var verifiedByFinding = new LinkedHashMap<String, Set<String>>();
		var unredactedWitnessesByFinding = new LinkedHashMap<String, List<WitnessVerification>>();

		for (var finding : dedupedReport.findings()) {
			DiffResolution diffResolution = resolveDiffForFinding(ctx, finding, diffIndex);

			// Order: Source -> Document -> NegativeBasis -> Semantic (so semantic sees verified source/doc context).
			var orderedSelectors = new ArrayList<WitnessSelector>(finding.witnesses());
			orderedSelectors.sort(Comparator.comparingInt(SourceEvidenceMapper::selectorPhaseOrder));

			var verifiedIds = new LinkedHashSet<String>();
			var resultsForFinding = new ArrayList<WitnessVerification>(orderedSelectors.size());
			for (var selector : orderedSelectors) {
				var cacheKey = new WitnessCacheKey(finding.findingId(), selector.witnessId());
				WitnessVerification verification = witnessCache.computeIfAbsent(cacheKey,
						ignored -> verifySelector(ctx, finding, selector, diffResolution, indices));
				resultsForFinding.add(verification);
				if (verification.evidence().status() == WitnessStatus.VERIFIED) {
					verifiedIds.add(selector.witnessId());
				}
			}
			verifiedByFinding.put(finding.findingId(), Set.copyOf(verifiedIds));
			unredactedWitnessesByFinding.put(finding.findingId(), resultsForFinding);
		}

		// 4. Invoke M3 downgrade via official entry point.
		ImpactReport adjustedReport = new ImpactClassifier().applyVerifiedWitnesses(dedupedReport, verifiedByFinding);

		// 5. Collect M2 and M3 safe diagnostics.
		collectDiffDiagnostics(ctx, diffIndex, snapshotIndex, safeDiagnostics);
		for (var diag : adjustedReport.diagnostics()) {
			safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.of(diag.code()),
					Optional.empty(), Optional.empty()));
		}

		// 6. Project safe FindingEvidence list.
		var projectedFindings = new ArrayList<FindingEvidence>(adjustedReport.findings().size());
		for (var adj : adjustedReport.findings()) {
			var rawVerifications = unredactedWitnessesByFinding.getOrDefault(adj.findingId(), List.of());
			var projectedWitnesses = new ArrayList<WitnessEvidence>(rawVerifications.size());
			boolean anyVerifiedWitness = false;
			boolean anyIndependentLocatorOrDocRef = false;
			boolean allVerified = !rawVerifications.isEmpty();

			for (var v : rawVerifications) {
				WitnessEvidence projected = redactWitness(v.evidence());
				projectedWitnesses.add(projected);
				if (projected.status() == WitnessStatus.VERIFIED) {
					anyVerifiedWitness = true;
				} else {
					allVerified = false;
					if (!projected.sourceLocators().isEmpty() || projected.documentRef().isPresent()) {
						anyIndependentLocatorOrDocRef = true;
					}
				}
			}

			var sortedWitnesses = sortAndDedupeWitnesses(projectedWitnesses);
			EvidenceStatus findingStatus;
			if (allVerified && !sortedWitnesses.isEmpty()) {
				findingStatus = EvidenceStatus.VERIFIED;
			} else if (anyVerifiedWitness || anyIndependentLocatorOrDocRef) {
				findingStatus = EvidenceStatus.PARTIAL;
			} else {
				findingStatus = EvidenceStatus.REJECTED;
			}

			projectedFindings.add(new FindingEvidence(
					adj.findingId(),
					adj.disposition(),
					adj.outcome(),
					adj.classification(),
					adj.severityStatus(),
					adj.severity(),
					adj.semanticConfidence(),
					adj.admission(),
					adj.primaryReason(),
					adj.admissionReason(),
					findingStatus,
					sortedWitnesses));
		}

		projectedFindings.sort(Comparator.comparing(FindingEvidence::findingId));
		List<FindingEvidence> immutableFindings = List.copyOf(projectedFindings);

		var sortedDiags = new ArrayList<>(safeDiagnostics);
		sortedDiags.sort(DIAGNOSTIC_COMPARATOR);
		List<SafeDiagnostic> immutableDiags = List.copyOf(sortedDiags);

		EvidenceStatus overallStatus;
		if (!immutableFindings.isEmpty()
				&& immutableFindings.stream().allMatch(f -> f.status() == EvidenceStatus.VERIFIED)) {
			overallStatus = EvidenceStatus.VERIFIED;
		} else if (immutableFindings.stream().anyMatch(f -> f.status() == EvidenceStatus.VERIFIED
				|| f.status() == EvidenceStatus.PARTIAL)
				|| immutableDiags.stream().anyMatch(d -> d.location().isPresent())) {
			overallStatus = EvidenceStatus.PARTIAL;
		} else {
			overallStatus = EvidenceStatus.REJECTED;
		}

		var cov = adjustedReport.coverage();
		Coverage safeCoverage = new Coverage(
				cov.evaluatedFileCount(),
				cov.excludedFileCount(),
				cov.failedOrIncompleteFileCount(),
				cov.completeCoverage());

		return new EvidenceResult(
				overallStatus,
				Optional.of(adjustedReport.processingStatus()),
				adjustedReport.overallOutcome(),
				Optional.of(safeCoverage),
				immutableFindings,
				immutableDiags);
	}

	// ---- Gate & Report Validation ----

	private static EvidenceResult gateRejected(EvidenceReason reason) {
		return new EvidenceResult(
				EvidenceStatus.REJECTED,
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				List.of(),
				List.of(new SafeDiagnostic(Optional.empty(), Optional.empty(), Optional.of(reason), Optional.empty())));
	}

	private static boolean isValidContext(AssessmentContext ctx) {
		if (isBlank(ctx.tenantId()) || isBlank(ctx.workspaceId()) || isBlank(ctx.repository())
				|| isBlank(ctx.targetRef()) || isBlank(ctx.jobId()) || isBlank(ctx.attemptId())
				|| isBlank(ctx.authoritativeTargetSha())) {
			return false;
		}
		if (ctx.intent() == Intent.REVISION_COMPARISON
				&& (ctx.comparisonBeforeSha().isEmpty() || isBlank(ctx.comparisonBeforeSha().get()))) {
			return false;
		}
		if (ctx.intent() == Intent.INITIAL_ASSESSMENT && ctx.comparisonBeforeSha().isPresent()) {
			return false;
		}
		if (ctx.scopeManifest().isPresent()) {
			var m = ctx.scopeManifest().get();
			if (!m.repository().equals(ctx.repository())
					|| m.targetKind() != ctx.targetKind()
					|| !m.targetRef().equals(ctx.targetRef())
					|| m.intent() != ctx.intent()
					|| !m.comparisonBeforeSha().equals(ctx.comparisonBeforeSha())
					|| !m.authoritativeTargetSha().equals(ctx.authoritativeTargetSha())) {
				return false;
			}
			if (ctx.declaredScopeCompleteness() == ScopeCompleteness.EMPTY_VERIFIED && m.eligibleFileCount() != 0) {
				return false;
			}
		}
		return true;
	}

	private static Optional<ImpactReport> validateAndDedupeReport(AssessmentContext ctx, ImpactReport report) {
		var findingsById = new LinkedHashMap<String, ImpactFinding>();
		for (var finding : report.findings()) {
			if (finding == null) {
				return Optional.empty();
			}
			var witnessesById = new LinkedHashMap<String, WitnessSelector>();
			for (var witness : finding.witnesses()) {
				if (witness == null || isBlank(witness.witnessId())) {
					return Optional.empty();
				}
				var existingWitness = witnessesById.get(witness.witnessId());
				if (existingWitness != null) {
					if (!existingWitness.equals(witness)) {
						return Optional.empty();
					}
				} else {
					witnessesById.put(witness.witnessId(), witness);
				}
			}

			Set<String> selectorIds = witnessesById.keySet();
			if (!selectorIds.containsAll(finding.requiredImpactWitnessIds())
					|| !selectorIds.containsAll(finding.requiredAdmissionWitnessIds())) {
				return Optional.empty();
			}
			if ((finding.outcome() == ImpactOutcome.ACTIONABLE
					|| finding.outcome() == ImpactOutcome.NO_IMPACT
					|| finding.disposition() == UnitDisposition.EXCLUDED)
					&& finding.requiredImpactWitnessIds().isEmpty()) {
				return Optional.empty();
			}
			if (finding.admission() == IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE
					&& finding.requiredAdmissionWitnessIds().isEmpty()) {
				return Optional.empty();
			}

			var normalizedFinding = new ImpactFinding(
					finding.findingId(), finding.disposition(), finding.outcome(),
					finding.classification(), finding.severity(), finding.severityStatus(),
					finding.severityRationale(), finding.semanticConfidence(),
					finding.admission(), finding.admissionReason(), finding.primaryReason(),
					finding.secondaryReasons(), finding.explanation(),
					finding.beforePath(), finding.afterPath(), finding.symbolKey(),
					finding.evaluatedFacets(), finding.documentId(), finding.documentVersion(),
					finding.documentBoundSha(), finding.bindingId(), finding.claimId(),
					List.copyOf(witnessesById.values()),
					finding.requiredImpactWitnessIds(), finding.requiredAdmissionWitnessIds(),
					finding.initialAssessment());

			var existingFinding = findingsById.get(normalizedFinding.findingId());
			if (existingFinding != null) {
				if (!existingFinding.equals(normalizedFinding)) {
					return Optional.empty();
				}
			} else {
				findingsById.put(normalizedFinding.findingId(), normalizedFinding);
			}
		}

		return Optional.of(new ImpactReport(
				report.classifierVersion(),
				report.processingStatus(),
				report.overallOutcome(),
				ctx,
				List.copyOf(findingsById.values()),
				report.coverage(),
				report.diagnostics()));
	}

	// ---- Indexing ----

	private static DiffIndex indexDiffs(
			AssessmentContext ctx,
			List<StructuralDiffResult> diffResults,
			Set<SafeDiagnostic> safeDiagnostics) {
		var byKey = new LinkedHashMap<DiffKey, StructuralDiffResult>();
		var conflictingKeys = new LinkedHashSet<DiffKey>();
		var diffsBySide = new LinkedHashMap<SourceIdentity, Set<StructuralDiffResult>>();
		var conflictingSides = new LinkedHashSet<SourceIdentity>();
		var inputLevelDiffs = new ArrayList<StructuralDiffResult>();

		for (var diff : diffResults) {
			if (!isDiffInRepositoryScope(ctx, diff)) {
				safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
						Optional.of(EvidenceReason.SCOPE_DENIED), Optional.empty()));
				continue;
			}
			if (!diffMatchesContextShasAndIntent(ctx, diff)) {
				safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
						Optional.of(EvidenceReason.SNAPSHOT_MISMATCH), Optional.empty()));
				continue;
			}

			if (diff.beforeSnapshot().isEmpty() && diff.afterSnapshot().isEmpty()) {
				inputLevelDiffs.add(diff);
				continue;
			}

			if (!isDiffInternallyConsistent(diff)) {
				safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
						Optional.of(EvidenceReason.SNAPSHOT_MISMATCH), Optional.empty()));
				if (diff.beforeSnapshot().isPresent()) {
					var b = diff.beforeSnapshot().get();
					conflictingSides.add(new SourceIdentity(SideRole.BEFORE, b.snapshot().repository(), b.snapshot().sha(), b.path()));
				}
				if (diff.afterSnapshot().isPresent()) {
					var a = diff.afterSnapshot().get();
					SideRole r = diff.intent() == Intent.INITIAL_ASSESSMENT ? SideRole.TARGET : SideRole.AFTER;
					conflictingSides.add(new SourceIdentity(r, a.snapshot().repository(), a.snapshot().sha(), a.path()));
				}
				continue;
			}

			if (diff.intent() == Intent.INITIAL_ASSESSMENT) {
				var targetProv = diff.afterSnapshot().get();
				var targetId = new SourceIdentity(SideRole.TARGET, targetProv.snapshot().repository(),
						targetProv.snapshot().sha(), targetProv.path());
				DiffKey key = new TargetDiffKey(targetId);
				recordDiffEntry(key, diff, byKey, conflictingKeys);
				diffsBySide.computeIfAbsent(targetId, ignored -> new LinkedHashSet<>()).add(diff);
			} else {
				if (diff.beforeSnapshot().isEmpty() || diff.afterSnapshot().isEmpty()) {
					safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
							Optional.of(EvidenceReason.SNAPSHOT_MISMATCH), Optional.empty()));
					continue;
				}
				var bProv = diff.beforeSnapshot().get();
				var aProv = diff.afterSnapshot().get();
				var beforeId = new SourceIdentity(SideRole.BEFORE, bProv.snapshot().repository(),
						bProv.snapshot().sha(), bProv.path());
				var afterId = new SourceIdentity(SideRole.AFTER, aProv.snapshot().repository(),
						aProv.snapshot().sha(), aProv.path());
				DiffKey key = new PairDiffKey(beforeId, afterId);
				recordDiffEntry(key, diff, byKey, conflictingKeys);
				diffsBySide.computeIfAbsent(beforeId, ignored -> new LinkedHashSet<>()).add(diff);
				diffsBySide.computeIfAbsent(afterId, ignored -> new LinkedHashSet<>()).add(diff);
			}
		}

		for (var entry : diffsBySide.entrySet()) {
			if (entry.getValue().size() > 1) {
				conflictingSides.add(entry.getKey());
			}
		}

		return new DiffIndex(byKey, conflictingKeys, diffsBySide, conflictingSides, inputLevelDiffs, diffResults.size());
	}

	private static void recordDiffEntry(
			DiffKey key,
			StructuralDiffResult diff,
			Map<DiffKey, StructuralDiffResult> byKey,
			Set<DiffKey> conflictingKeys) {
		var existing = byKey.get(key);
		if (existing != null) {
			if (!existing.equals(diff)) {
				conflictingKeys.add(key);
			}
		} else {
			byKey.put(key, diff);
		}
	}

	private static boolean isDiffInRepositoryScope(AssessmentContext ctx, StructuralDiffResult diff) {
		if (diff.repository().isPresent() && !diff.repository().get().equals(ctx.repository())) {
			return false;
		}
		if (diff.beforeSnapshot().isPresent()
				&& !diff.beforeSnapshot().get().snapshot().repository().equals(ctx.repository())) {
			return false;
		}
		if (diff.afterSnapshot().isPresent()
				&& !diff.afterSnapshot().get().snapshot().repository().equals(ctx.repository())) {
			return false;
		}
		return true;
	}

	private static boolean diffMatchesContextShasAndIntent(AssessmentContext ctx, StructuralDiffResult diff) {
		if (diff.intent() != ctx.intent()) {
			return false;
		}
		if (diff.intent() == Intent.INITIAL_ASSESSMENT) {
			if (diff.beforeSnapshot().isPresent()) {
				return false;
			}
			if (diff.afterSnapshot().isPresent()
					&& !diff.afterSnapshot().get().snapshot().sha().equals(ctx.authoritativeTargetSha())) {
				return false;
			}
			return true;
		}
		if (diff.beforeSnapshot().isPresent()
				&& !ctx.comparisonBeforeSha().map(diff.beforeSnapshot().get().snapshot().sha()::equals).orElse(false)) {
			return false;
		}
		if (diff.afterSnapshot().isPresent()
				&& !diff.afterSnapshot().get().snapshot().sha().equals(ctx.authoritativeTargetSha())) {
			return false;
		}
		return true;
	}

	private static boolean isDiffInternallyConsistent(StructuralDiffResult diff) {
		var fc = diff.fileComparison();
		if (diff.beforeSnapshot().isPresent() && fc.beforePath().isPresent()
				&& !diff.beforeSnapshot().get().path().equals(fc.beforePath().get())) {
			return false;
		}
		if (diff.afterSnapshot().isPresent() && fc.afterPath().isPresent()
				&& !diff.afterSnapshot().get().path().equals(fc.afterPath().get())) {
			return false;
		}
		if (diff.beforeContext().isPresent()) {
			if (diff.beforeSnapshot().isEmpty()) {
				return false;
			}
			var bc = diff.beforeContext().get();
			var bp = diff.beforeSnapshot().get();
			if (!bc.snapshot().equals(bp.snapshot()) || !bc.path().equals(bp.path())) {
				return false;
			}
		}
		if (diff.afterContext().isPresent()) {
			if (diff.afterSnapshot().isEmpty()) {
				return false;
			}
			var ac = diff.afterContext().get();
			var ap = diff.afterSnapshot().get();
			if (!ac.snapshot().equals(ap.snapshot()) || !ac.path().equals(ap.path())) {
				return false;
			}
		}
		if (diff.initialInventory().isPresent()) {
			if (diff.afterSnapshot().isEmpty()) {
				return false;
			}
			var inv = diff.initialInventory().get();
			var ap = diff.afterSnapshot().get();
			if (!inv.snapshot().equals(ap.snapshot()) || !inv.path().equals(ap.path())) {
				return false;
			}
			var invKeys = new HashMap<SymbolKey, EntitySide>();
			for (var e : inv.entities()) {
				if (!e.snapshot().equals(ap.snapshot()) || !e.path().equals(ap.path())) {
					return false;
				}
				var prev = invKeys.putIfAbsent(e.key(), e);
				if (prev != null && !prev.equals(e)) {
					return false;
				}
			}
		}
		var beforeEntities = new HashMap<SymbolKey, EntitySide>();
		var afterEntities = new HashMap<SymbolKey, EntitySide>();
		for (var fact : diff.entityFacts()) {
			if (fact.before().isPresent()) {
				if (diff.beforeSnapshot().isEmpty()) {
					return false;
				}
				var bs = fact.before().get();
				var bp = diff.beforeSnapshot().get();
				if (!bs.snapshot().equals(bp.snapshot()) || !bs.path().equals(bp.path())) {
					return false;
				}
				var prev = beforeEntities.putIfAbsent(bs.key(), bs);
				if (prev != null && !prev.equals(bs)) {
					return false;
				}
			}
			if (fact.after().isPresent()) {
				if (diff.afterSnapshot().isEmpty()) {
					return false;
				}
				var as = fact.after().get();
				var ap = diff.afterSnapshot().get();
				if (!as.snapshot().equals(ap.snapshot()) || !as.path().equals(ap.path())) {
					return false;
				}
				var prev = afterEntities.putIfAbsent(as.key(), as);
				if (prev != null && !prev.equals(as)) {
					return false;
				}
			}
		}
		return true;
	}

	private static SnapshotIndex indexSnapshots(
			AssessmentContext ctx,
			List<SnapshotProof> proofs,
			Set<SafeDiagnostic> safeDiagnostics) {
		var byId = new LinkedHashMap<SourceIdentity, SnapshotProof>();
		var conflicts = new LinkedHashSet<SourceIdentity>();
		for (var proof : proofs) {
			var snap = proof.provenance().snapshot();
			if (!snap.repository().equals(ctx.repository())) {
				safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
						Optional.of(EvidenceReason.SCOPE_DENIED), Optional.empty()));
				continue;
			}
			if (proof.sideRole() == SideRole.INPUT) {
				safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
						Optional.of(EvidenceReason.INVALID_SELECTOR), Optional.empty()));
				continue;
			}
			var key = new SourceIdentity(proof.sideRole(), snap.repository(), snap.sha(), proof.provenance().path());
			var existing = byId.get(key);
			if (existing != null) {
				if (!existing.equals(proof)) {
					conflicts.add(key);
				}
			} else {
				byId.put(key, proof);
			}
		}
		return new SnapshotIndex(byId, conflicts);
	}

	private static LookupIndex indexLookups(
			AssessmentContext ctx,
			List<DocumentLookupResponse> responses,
			Set<SafeDiagnostic> safeDiagnostics) {
		var byKey = new LinkedHashMap<DocumentLookupKey, IndexedLookup>();
		var conflictingKeys = new LinkedHashSet<DocumentLookupKey>();
		var seenBindings = new LinkedHashMap<BindingIdentityKey, NormalizedBinding>();
		var conflictingBindings = new LinkedHashSet<BindingIdentityKey>();
		var seenClaims = new LinkedHashMap<ClaimIdentityKey, ClaimOwnership>();
		var conflictingClaims = new LinkedHashSet<ClaimIdentityKey>();

		for (var resp : responses) {
			var key = resp.key();
			if (!matchesScope(ctx, key.tenantId(), key.workspaceId(), key.repository(),
					key.targetKind(), key.targetRef())) {
				safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
						Optional.of(EvidenceReason.SCOPE_DENIED), Optional.empty()));
				continue;
			}

			var localBindings = new ArrayList<DocumentBinding>();
			var normalizedSet = new LinkedHashSet<NormalizedBinding>();
			boolean hasForeignOrMalformed = false;

			for (var b : resp.bindings()) {
				if (!b.tenantId().equals(ctx.tenantId())
						|| !b.workspaceId().equals(ctx.workspaceId())
						|| !b.repository().equals(ctx.repository())) {
					hasForeignOrMalformed = true;
					safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
							Optional.of(EvidenceReason.SCOPE_DENIED), Optional.empty()));
					continue;
				}
				if (!isStructurallyValidBinding(b)) {
					hasForeignOrMalformed = true;
					continue;
				}
				localBindings.add(b);
				normalizedSet.add(NormalizedBinding.of(b));
			}

			var indexed = new IndexedLookup(
					key, resp.completeness(), List.copyOf(localBindings),
					Set.copyOf(normalizedSet), hasForeignOrMalformed, resp.diagnosticReason());

			var existing = byKey.get(key);
			if (existing != null) {
				if (!existing.equalsForEquivalence(indexed)) {
					conflictingKeys.add(key);
				}
			} else {
				byKey.put(key, indexed);
			}
		}

		// Track binding and claim consistency across non-conflicting responses.
		for (var entry : byKey.entrySet()) {
			if (conflictingKeys.contains(entry.getKey())) {
				continue;
			}
			for (var b : entry.getValue().localBindings()) {
				var bKey = new BindingIdentityKey(b.documentId(), b.documentVersion(), b.bindingId());
				var norm = NormalizedBinding.of(b);
				var existingB = seenBindings.get(bKey);
				if (existingB != null) {
					if (!existingB.equals(norm)) {
						conflictingBindings.add(bKey);
					}
				} else {
					seenBindings.put(bKey, norm);
				}

				var claimsByIdInBinding = new HashMap<String, DocumentClaim>();
				for (var c : b.claims()) {
					var prevInBinding = claimsByIdInBinding.get(c.claimId());
					var cKey = new ClaimIdentityKey(c.documentId(), c.claimId());
					if (prevInBinding != null && !prevInBinding.equals(c)) {
						conflictingClaims.add(cKey);
					}
					claimsByIdInBinding.put(c.claimId(), c);

					var ownership = new ClaimOwnership(b.documentVersion(), b.documentBoundSha(), b.bindingId(), c);
					var existingC = seenClaims.get(cKey);
					if (existingC != null) {
						if (!existingC.equals(ownership)) {
							conflictingClaims.add(cKey);
						}
					} else {
						seenClaims.put(cKey, ownership);
					}
				}
			}
		}

		return new LookupIndex(byKey, conflictingKeys, conflictingBindings, conflictingClaims);
	}

	private static boolean isStructurallyValidBinding(DocumentBinding b) {
		if (b.granularity() == BindingGranularity.EXACT_SYMBOL && b.boundSymbolKey().isEmpty()) {
			return false;
		}
		if (b.granularity() == BindingGranularity.FILE_ONLY && b.boundSymbolKey().isPresent()) {
			return false;
		}
		for (var c : b.claims()) {
			if (!c.documentId().equals(b.documentId())
					|| !c.documentVersion().equals(b.documentVersion())
					|| !c.documentBoundSha().equals(b.documentBoundSha())) {
				return false;
			}
		}
		return true;
	}

	private static SemanticIndex indexSemantics(
			AssessmentContext ctx,
			List<SemanticAssessmentInput> inputs,
			Set<SafeDiagnostic> safeDiagnostics) {
		var unique = new LinkedHashMap<NormalizedSemantic, SemanticAssessmentInput>();
		for (var sem : inputs) {
			if (!matchesScope(ctx, sem.tenantId(), sem.workspaceId(), sem.repository(),
					sem.targetKind(), sem.targetRef())) {
				safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
						Optional.of(EvidenceReason.SCOPE_DENIED), Optional.empty()));
				continue;
			}
			unique.putIfAbsent(NormalizedSemantic.of(sem), sem);
		}
		return new SemanticIndex(List.copyOf(unique.values()));
	}

	private static ManifestIndex indexManifests(
			AssessmentContext ctx,
			List<ScopeManifestRef> manifests,
			Set<SafeDiagnostic> safeDiagnostics) {
		var byId = new LinkedHashMap<String, ScopeManifestRef>();
		var conflicts = new LinkedHashSet<String>();
		for (var m : manifests) {
			if (!m.repository().equals(ctx.repository())
					|| m.targetKind() != ctx.targetKind()
					|| !m.targetRef().equals(ctx.targetRef())) {
				safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
						Optional.of(EvidenceReason.SCOPE_DENIED), Optional.empty()));
				continue;
			}
			var existing = byId.get(m.manifestId());
			if (existing != null) {
				if (!existing.equals(m)) {
					conflicts.add(m.manifestId());
				}
			} else {
				byId.put(m.manifestId(), m);
			}
		}
		return new ManifestIndex(byId, conflicts);
	}

	private static ExclusionIndex indexCallerExclusions(
			AssessmentContext ctx,
			List<CallerExclusion> exclusions,
			Set<SafeDiagnostic> safeDiagnostics) {
		var byId = new LinkedHashMap<String, CallerExclusion>();
		var conflicts = new LinkedHashSet<String>();
		for (var e : exclusions) {
			if (!e.repository().equals(ctx.repository())) {
				safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
						Optional.of(EvidenceReason.SCOPE_DENIED), Optional.empty()));
				continue;
			}
			var existing = byId.get(e.ruleId());
			if (existing != null) {
				if (!existing.equals(e)) {
					conflicts.add(e.ruleId());
				}
			} else {
				byId.put(e.ruleId(), e);
			}
		}
		return new ExclusionIndex(byId, conflicts);
	}

	private static ExportIndex indexControlledExports(
			AssessmentContext ctx,
			List<ControlledExportUnit> exports,
			Set<SafeDiagnostic> safeDiagnostics) {
		var byId = new LinkedHashMap<String, ControlledExportUnit>();
		var conflicts = new LinkedHashSet<String>();
		for (var x : exports) {
			if (!x.repository().equals(ctx.repository())) {
				safeDiagnostics.add(new SafeDiagnostic(Optional.empty(), Optional.empty(),
						Optional.of(EvidenceReason.SCOPE_DENIED), Optional.empty()));
				continue;
			}
			var existing = byId.get(x.exportProvenanceId());
			if (existing != null) {
				if (!existing.equals(x)) {
					conflicts.add(x.exportProvenanceId());
				}
			} else {
				byId.put(x.exportProvenanceId(), x);
			}
		}
		return new ExportIndex(byId, conflicts);
	}

	// ---- Diff Resolution for Finding ----

	private static DiffResolution resolveDiffForFinding(
			AssessmentContext ctx,
			ImpactFinding finding,
			DiffIndex diffIndex) {
		boolean emptyScopeManifestFinding = finding.beforePath().isEmpty()
				&& finding.afterPath().isEmpty()
				&& (finding.primaryReason() == ReasonCode.EMPTY_VERIFIED_SCOPE_NO_IMPACT
						|| finding.primaryReason() == ReasonCode.EMPTY_SCOPE_MANIFEST_MISSING);
		if (emptyScopeManifestFinding) {
			return new DiffResolution(Optional.empty(), Optional.empty(), true);
		}

		if (ctx.intent() == Intent.INITIAL_ASSESSMENT) {
			if (finding.beforePath().isPresent()) {
				return new DiffResolution(Optional.empty(), Optional.of(EvidenceReason.INVALID_SELECTOR), false);
			}
			Optional<String> targetPath = finding.afterPath().or(() -> inferSinglePath(finding, SideRole.TARGET));
			if (targetPath.isEmpty()) {
				return new DiffResolution(Optional.empty(), Optional.of(EvidenceReason.MISSING_DIFF), false);
			}
			var targetId = new SourceIdentity(SideRole.TARGET, ctx.repository(),
					ctx.authoritativeTargetSha(), targetPath.get());
			var key = new TargetDiffKey(targetId);
			if (diffIndex.conflictingKeys.contains(key) || diffIndex.conflictingSides.contains(targetId)) {
				return new DiffResolution(Optional.empty(), Optional.of(EvidenceReason.CONFLICTING_PROOF), false);
			}
			var diff = diffIndex.byKey.get(key);
			if (diff == null) {
				return new DiffResolution(Optional.empty(), Optional.of(EvidenceReason.MISSING_DIFF), false);
			}
			return new DiffResolution(Optional.of(diff), Optional.empty(), false);
		}

		// REVISION_COMPARISON
		String beforeSha = ctx.comparisonBeforeSha().orElse("");
		String afterSha = ctx.authoritativeTargetSha();
		if (finding.beforePath().isPresent() && finding.afterPath().isPresent()) {
			var bId = new SourceIdentity(SideRole.BEFORE, ctx.repository(), beforeSha, finding.beforePath().get());
			var aId = new SourceIdentity(SideRole.AFTER, ctx.repository(), afterSha, finding.afterPath().get());
			var key = new PairDiffKey(bId, aId);
			if (diffIndex.conflictingKeys.contains(key)
					|| diffIndex.conflictingSides.contains(bId)
					|| diffIndex.conflictingSides.contains(aId)) {
				return new DiffResolution(Optional.empty(), Optional.of(EvidenceReason.CONFLICTING_PROOF), false);
			}
			var diff = diffIndex.byKey.get(key);
			if (diff == null) {
				return new DiffResolution(Optional.empty(), Optional.of(EvidenceReason.MISSING_DIFF), false);
			}
			return new DiffResolution(Optional.of(diff), Optional.empty(), false);
		}

		// One or both paths missing on finding: disambiguate using present path + selector coordinates.
		var candidates = new ArrayList<StructuralDiffResult>();
		boolean sawConflict = false;
		for (var entry : diffIndex.byKey.entrySet()) {
			if (!(entry.getKey() instanceof PairDiffKey pairKey)) {
				continue;
			}
			if (finding.beforePath().isPresent() && !pairKey.before.path().equals(finding.beforePath().get())) {
				continue;
			}
			if (finding.afterPath().isPresent() && !pairKey.after.path().equals(finding.afterPath().get())) {
				continue;
			}
			if (!matchesFindingCoordinates(finding, entry.getValue())) {
				continue;
			}
			if (diffIndex.conflictingKeys.contains(pairKey)
					|| diffIndex.conflictingSides.contains(pairKey.before)
					|| diffIndex.conflictingSides.contains(pairKey.after)) {
				sawConflict = true;
				continue;
			}
			candidates.add(entry.getValue());
		}
		if (sawConflict || candidates.size() > 1) {
			return new DiffResolution(Optional.empty(), Optional.of(EvidenceReason.CONFLICTING_PROOF), false);
		}
		if (candidates.isEmpty()) {
			return new DiffResolution(Optional.empty(), Optional.of(EvidenceReason.MISSING_DIFF), false);
		}
		return new DiffResolution(Optional.of(candidates.getFirst()), Optional.empty(), false);
	}

	private static Optional<String> inferSinglePath(ImpactFinding finding, SideRole role) {
		var paths = new LinkedHashSet<String>();
		for (var w : finding.witnesses()) {
			if (w instanceof SourceEvidenceSelector src && src.sideRole() == role) {
				paths.add(src.path());
			} else if (w instanceof SemanticWitnessSelector sem && (role == SideRole.AFTER || role == SideRole.TARGET)) {
				paths.add(sem.path());
			}
		}
		return paths.size() == 1 ? Optional.of(paths.iterator().next()) : Optional.empty();
	}

	private static boolean matchesFindingCoordinates(ImpactFinding finding, StructuralDiffResult diff) {
		String beforePath = diff.beforeSnapshot().map(SnapshotProvenance::path).orElse("");
		String afterPath = diff.afterSnapshot().map(SnapshotProvenance::path).orElse("");
		for (var w : finding.witnesses()) {
			if (w instanceof SourceEvidenceSelector src) {
				if (src.sideRole() == SideRole.BEFORE && !src.path().equals(beforePath)) {
					return false;
				}
				if (src.sideRole() == SideRole.AFTER && !src.path().equals(afterPath)) {
					return false;
				}
			} else if (w instanceof SemanticWitnessSelector sem) {
				if (!sem.path().equals(afterPath)) {
					return false;
				}
			}
		}
		return true;
	}

	// ---- Witness Verification Dispatch ----

	private WitnessVerification verifySelector(
			AssessmentContext ctx,
			ImpactFinding finding,
			WitnessSelector selector,
			DiffResolution diffResolution,
			AssessmentIndices indices) {
		boolean isRequired = finding.requiredImpactWitnessIds().contains(selector.witnessId())
				|| finding.requiredAdmissionWitnessIds().contains(selector.witnessId());
		return switch (selector) {
			case SourceEvidenceSelector src -> verifySourceSelector(ctx, finding, src, isRequired, diffResolution, indices);
			case DocumentWitnessSelector doc -> verifyDocumentSelector(ctx, finding, doc, isRequired, diffResolution, indices);
			case SemanticWitnessSelector sem -> verifySemanticSelector(ctx, finding, sem, isRequired, diffResolution, indices);
			case NegativeBasisWitnessSelector neg -> verifyNegativeBasisSelector(ctx, finding, neg, isRequired, diffResolution, indices);
		};
	}

	// ---- §4 Source Selector Verification ----

	private WitnessVerification verifySourceSelector(
			AssessmentContext ctx,
			ImpactFinding finding,
			SourceEvidenceSelector selector,
			boolean isRequired,
			DiffResolution diffResolution,
			AssessmentIndices indices) {
		if (!selector.repository().equals(ctx.repository())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SCOPE_DENIED);
		}
		if (!isRoleValidForIntent(ctx.intent(), selector.sideRole())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}
		String expectedSha = expectedShaForRole(ctx, selector.sideRole());
		if (!selector.pinnedSha().equals(expectedSha)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SNAPSHOT_MISMATCH);
		}
		if (diffResolution.errorReason().isPresent()) {
			return unverifiedWitness(selector.witnessId(), diffResolution.errorReason().get());
		}
		if (diffResolution.diff().isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.MISSING_DIFF);
		}
		StructuralDiffResult diff = diffResolution.diff().get();

		Optional<SnapshotProvenance> diffProvOpt = selector.sideRole() == SideRole.BEFORE
				? diff.beforeSnapshot()
				: diff.afterSnapshot();
		if (diffProvOpt.isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.MISSING_SNAPSHOT);
		}
		SnapshotProvenance diffProv = diffProvOpt.get();
		if (!diffProv.path().equals(selector.path()) || diffProv.presence() != selector.presence()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SNAPSHOT_MISMATCH);
		}

		// Verify SnapshotProof authority.
		var snapCheck = verifySnapshotAuthority(ctx, selector.sideRole(), diffProv, indices.snapshotIndex);
		if (snapCheck.failureReason().isPresent()) {
			var locators = snapCheck.traceLocator().map(List::of).orElse(List.of());
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.UNVERIFIED,
					List.of(snapCheck.failureReason().get()),
					locators, Optional.empty(), Optional.empty(), Optional.empty()));
		}

		if (diffProv.presence() == SidePresence.UNAVAILABLE) {
			var unavailLocator = new SourceLocator(
					ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
					SidePresence.UNAVAILABLE, SourceBasis.SNAPSHOT_UNAVAILABLE,
					selector.symbolKey(), selector.facetKind(), selector.contextChangeKind(), List.of());
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.UNVERIFIED,
					List.of(EvidenceReason.SNAPSHOT_UNAVAILABLE),
					List.of(unavailLocator), Optional.empty(), Optional.empty(), Optional.empty()));
		}

		Optional<SideContextFacts> sideContext = selector.sideRole() == SideRole.BEFORE
				? diff.beforeContext() : diff.afterContext();

		return switch (selector.granularity()) {
			case FILE -> verifyFileSourceSelector(
					ctx, finding, selector, isRequired, diff, diffProv, snapCheck.proof().get(), sideContext);
			case SYMBOL -> verifySymbolSourceSelector(
					ctx, finding, selector, isRequired, diff, diffProv, snapCheck.proof().get(), sideContext, indices);
			case FACET -> verifyFacetSourceSelector(
					ctx, finding, selector, isRequired, diff, diffProv, snapCheck.proof().get(), sideContext, indices);
			case CONTEXT -> verifyContextSourceSelector(
					ctx, finding, selector, isRequired, diff, diffProv, snapCheck.proof().get(), sideContext);
		};
	}

	private WitnessVerification verifyFileSourceSelector(
			AssessmentContext ctx,
			ImpactFinding finding,
			SourceEvidenceSelector selector,
			boolean isRequired,
			StructuralDiffResult diff,
			SnapshotProvenance diffProv,
			SnapshotProof proof,
			Optional<SideContextFacts> sideContext) {
		if (selector.symbolKey().isPresent() || selector.facetKind().isPresent() || selector.contextChangeKind().isPresent()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}
		if (diffProv.presence() == SidePresence.ABSENT) {
			boolean validSide = (selector.sideRole() == SideRole.BEFORE && diff.fileComparison().presence() == PresenceRelation.ADDED)
					|| (selector.sideRole() == SideRole.AFTER && diff.fileComparison().presence() == PresenceRelation.REMOVED);
			if (!validSide) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED);
			}
			var loc = fileLocator(ctx, selector, SidePresence.ABSENT, SourceBasis.FILE_ABSENT);
			if (isRequired && (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage())) {
				return new WitnessVerification(new WitnessEvidence(
						selector.witnessId(), WitnessStatus.UNVERIFIED,
						List.of(EvidenceReason.INSUFFICIENT_COVERAGE),
						List.of(loc), Optional.empty(), Optional.empty(), Optional.empty()));
			}
			return verifiedSourceWitness(selector.witnessId(), List.of(loc));
		}

		// PRESENT
		boolean initialEmptyReferential = finding.initialAssessment()
				&& finding.primaryReason() == ReasonCode.REFERENTIAL_MISSING_TARGET_SYMBOL
				&& isRequired;
		ContentCheck contentCheck = verifyPresentContent(proof, sideContext);
		if (contentCheck.status() == ContentCheckStatus.VERIFIED) {
			var contentLocator = fileLocator(ctx, selector, SidePresence.PRESENT, SourceBasis.FILE_CONTENT);
			if (initialEmptyReferential) {
				if (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage()) {
					return new WitnessVerification(new WitnessEvidence(
							selector.witnessId(), WitnessStatus.UNVERIFIED,
							List.of(EvidenceReason.INSUFFICIENT_COVERAGE),
							List.of(contentLocator), Optional.empty(), Optional.empty(), Optional.empty()));
				}
				if (diff.initialInventory().isEmpty() || !diff.initialInventory().get().entities().isEmpty()) {
					return new WitnessVerification(new WitnessEvidence(
							selector.witnessId(), WitnessStatus.UNVERIFIED,
							List.of(EvidenceReason.SOURCE_BASIS_UNVERIFIED),
							List.of(contentLocator), Optional.empty(), Optional.empty(), Optional.empty()));
				}
				var emptyInvLoc = new SourceLocator(
						ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
						SidePresence.PRESENT, SourceBasis.TARGET_INVENTORY_EMPTY,
						Optional.empty(), Optional.empty(), Optional.empty(), List.of());
				return verifiedSourceWitness(selector.witnessId(), List.of(contentLocator, emptyInvLoc));
			}
			if (ctx.intent() == Intent.REVISION_COMPARISON
					&& isRequired
					&& (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage())) {
				return new WitnessVerification(new WitnessEvidence(
						selector.witnessId(), WitnessStatus.UNVERIFIED,
						List.of(EvidenceReason.INSUFFICIENT_COVERAGE),
						List.of(contentLocator), Optional.empty(), Optional.empty(), Optional.empty()));
			}
			return verifiedSourceWitness(selector.witnessId(), List.of(contentLocator));
		}

		var identityLocator = fileLocator(ctx, selector, SidePresence.PRESENT, SourceBasis.FILE_IDENTITY);
		if (contentCheck.status() == ContentCheckStatus.NO_SOURCE && !isRequired) {
			return verifiedSourceWitness(selector.witnessId(), List.of(identityLocator));
		}
		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.UNVERIFIED,
				List.of(contentCheck.failureReason().get()),
				List.of(identityLocator), Optional.empty(), Optional.empty(), Optional.empty()));
	}

	private WitnessVerification verifySymbolSourceSelector(
			AssessmentContext ctx,
			ImpactFinding finding,
			SourceEvidenceSelector selector,
			boolean isRequired,
			StructuralDiffResult diff,
			SnapshotProvenance diffProv,
			SnapshotProof proof,
			Optional<SideContextFacts> sideContext,
			AssessmentIndices indices) {
		if (selector.symbolKey().isEmpty() || selector.contextChangeKind().isPresent()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}
		SymbolKey symKey = selector.symbolKey().get();

		if (diffProv.presence() == SidePresence.ABSENT) {
			if (selector.facetKind().isPresent()) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
			}
			boolean matchingAddOrRemove = diff.entityFacts().stream().anyMatch(f ->
					entityMatchesSideAndKey(f, selector.sideRole() == SideRole.BEFORE ? SideRole.AFTER : SideRole.BEFORE, symKey)
							&& f.unmatchedReason().equals(Optional.of(
									selector.sideRole() == SideRole.BEFORE ? UnmatchedReason.FILE_ADDED : UnmatchedReason.FILE_REMOVED)));
			if (!matchingAddOrRemove) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED);
			}
			var absentLoc = new SourceLocator(
					ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
					SidePresence.ABSENT, SourceBasis.FILE_ABSENT,
					Optional.of(symKey), Optional.empty(), Optional.empty(), List.of());
			if (isRequired && (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage())) {
				return new WitnessVerification(new WitnessEvidence(
						selector.witnessId(), WitnessStatus.UNVERIFIED,
						List.of(EvidenceReason.INSUFFICIENT_COVERAGE),
						List.of(absentLoc), Optional.empty(), Optional.empty(), Optional.empty()));
			}
			return verifiedSourceWitness(selector.witnessId(), List.of(absentLoc));
		}

		// PRESENT side.
		var extraLocators = new ArrayList<SourceLocator>();
		var extraFailures = new ArrayList<EvidenceReason>();

		// If M3 emitted only AFTER SYMBOL selector for a file addition (BEFORE ABSENT), verify BEFORE ABSENT too!
		if (ctx.intent() == Intent.REVISION_COMPARISON
				&& selector.sideRole() == SideRole.AFTER
				&& diff.beforeSnapshot().isPresent()
				&& diff.beforeSnapshot().get().presence() == SidePresence.ABSENT) {
			var beforeProv = diff.beforeSnapshot().get();
			boolean factIsFileAdded = diff.entityFacts().stream().anyMatch(f ->
					f.change() == ChangeKind.ADDED
							&& f.after().map(s -> s.key().equals(symKey)).orElse(false)
							&& f.unmatchedReason().equals(Optional.of(UnmatchedReason.FILE_ADDED)));
			var beforeSnapCheck = verifySnapshotAuthority(ctx, SideRole.BEFORE, beforeProv, indices.snapshotIndex);
			if (beforeSnapCheck.failureReason().isPresent()) {
				extraFailures.add(beforeSnapCheck.failureReason().get());
			} else if (!factIsFileAdded || diff.fileComparison().presence() != PresenceRelation.ADDED) {
				extraFailures.add(EvidenceReason.SOURCE_BASIS_UNVERIFIED);
			} else if (isRequired && (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage())) {
				extraFailures.add(EvidenceReason.INSUFFICIENT_COVERAGE);
			} else {
				extraLocators.add(new SourceLocator(
						ctx.repository(), SideRole.BEFORE, beforeProv.snapshot().sha(), beforeProv.path(),
						SidePresence.ABSENT, SourceBasis.FILE_ABSENT,
						Optional.of(symKey), Optional.empty(), Optional.empty(), List.of()));
			}
		}

		ContentCheck contentCheck = verifyPresentContent(proof, sideContext);
		if (contentCheck.status() != ContentCheckStatus.VERIFIED) {
			var identityLoc = fileLocator(ctx, selector, SidePresence.PRESENT, SourceBasis.FILE_IDENTITY);
			var trace = new ArrayList<>(extraLocators);
			trace.add(identityLoc);
			var reasons = new LinkedHashSet<EvidenceReason>();
			reasons.add(contentCheck.failureReason().get());
			reasons.addAll(extraFailures);
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.UNVERIFIED,
					List.copyOf(reasons), trace, Optional.empty(), Optional.empty(), Optional.empty()));
		}
		String rawSource = proof.exactDecodedSource().get();
		LineIndex lineIndex = LineIndex.build(rawSource);

		Optional<EntitySide> entityOpt = findEntitySide(diff, selector.sideRole(), symKey);
		if (entityOpt.isEmpty()) {
			if (selector.facetKind().isPresent()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED, extraLocators);
			}
			boolean validAbsentInPresent = ctx.intent() == Intent.REVISION_COMPARISON
					&& diff.status() == ProcessingStatus.COMPLETE
					&& diff.coverage().fullCoverage()
					&& isProvenAbsentInPresentSide(diff, selector.sideRole(), symKey);
			if (validAbsentInPresent && extraFailures.isEmpty()) {
				var loc = new SourceLocator(
						ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
						SidePresence.PRESENT, SourceBasis.SYMBOL_ABSENT,
						Optional.of(symKey), Optional.empty(), Optional.empty(), List.of());
				var all = new ArrayList<>(extraLocators);
				all.add(loc);
				return verifiedSourceWitness(selector.witnessId(), all);
			}
			var reason = (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage())
					? EvidenceReason.INSUFFICIENT_COVERAGE : EvidenceReason.SOURCE_BASIS_UNVERIFIED;
			var reasons = new LinkedHashSet<EvidenceReason>();
			reasons.add(reason);
			reasons.addAll(extraFailures);
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.UNVERIFIED,
					List.copyOf(reasons), extraLocators, Optional.empty(), Optional.empty(), Optional.empty()));
		}

		// Entity exists on this side.
		EntitySide entity = entityOpt.get();
		if (!entity.snapshot().equals(new SnapshotRef(ctx.repository(), selector.pinnedSha()))
				|| !entity.path().equals(selector.path())) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.SNAPSHOT_MISMATCH, extraLocators);
		}

		boolean coverageFailed = ctx.intent() == Intent.REVISION_COMPARISON
				&& isRequired
				&& (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage());

		var requestedFacets = EnumSet.noneOf(FacetKind.class);
		selector.facetKind().ifPresent(requestedFacets::add);
		if (isRequired) {
			requestedFacets.addAll(finding.evaluatedFacets());
		}

		var outLocators = new ArrayList<>(extraLocators);
		var reasons = new LinkedHashSet<>(extraFailures);
		if (coverageFailed) {
			reasons.add(EvidenceReason.INSUFFICIENT_COVERAGE);
		}

		if (requestedFacets.isEmpty()) {
			var refCheck = verifyFacetRefs(rawSource, lineIndex, entity.facetReferences().get(FacetKind.DECLARATION));
			if (!refCheck.validRanges().isEmpty()) {
				outLocators.add(new SourceLocator(
						ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
						SidePresence.PRESENT, SourceBasis.SYMBOL_PRESENT,
						Optional.of(symKey), Optional.empty(), Optional.empty(), refCheck.validRanges()));
			}
			reasons.addAll(refCheck.failureReasons());
		} else {
			for (var facet : requestedFacets) {
				if (!entity.facetFingerprints().containsKey(facet) || !entity.facetReferences().containsKey(facet)) {
					reasons.add(EvidenceReason.SOURCE_BASIS_UNVERIFIED);
					continue;
				}
				var refCheck = verifyFacetRefs(rawSource, lineIndex, entity.facetReferences().get(facet));
				if (!refCheck.validRanges().isEmpty()) {
					outLocators.add(new SourceLocator(
							ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
							SidePresence.PRESENT, SourceBasis.FACET_PRESENT,
							Optional.of(symKey), Optional.of(facet), Optional.empty(), refCheck.validRanges()));
				}
				reasons.addAll(refCheck.failureReasons());
			}
		}

		if (reasons.isEmpty()) {
			return verifiedSourceWitness(selector.witnessId(), outLocators);
		}
		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.UNVERIFIED,
				List.copyOf(reasons), outLocators, Optional.empty(), Optional.empty(), Optional.empty()));
	}

	private WitnessVerification verifyFacetSourceSelector(
			AssessmentContext ctx,
			ImpactFinding finding,
			SourceEvidenceSelector selector,
			boolean isRequired,
			StructuralDiffResult diff,
			SnapshotProvenance diffProv,
			SnapshotProof proof,
			Optional<SideContextFacts> sideContext,
			AssessmentIndices indices) {
		if (selector.presence() != SidePresence.PRESENT
				|| selector.symbolKey().isEmpty()
				|| selector.facetKind().isEmpty()
				|| selector.contextChangeKind().isPresent()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}
		SymbolKey symKey = selector.symbolKey().get();
		FacetKind facet = selector.facetKind().get();

		ContentCheck contentCheck = verifyPresentContent(proof, sideContext);
		if (contentCheck.status() != ContentCheckStatus.VERIFIED) {
			var identityLoc = fileLocator(ctx, selector, SidePresence.PRESENT, SourceBasis.FILE_IDENTITY);
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.UNVERIFIED,
					List.of(contentCheck.failureReason().get()),
					List.of(identityLoc), Optional.empty(), Optional.empty(), Optional.empty()));
		}
		String rawSource = proof.exactDecodedSource().get();
		LineIndex lineIndex = LineIndex.build(rawSource);

		Optional<EntitySide> entityOpt = findEntitySide(diff, selector.sideRole(), symKey);
		if (entityOpt.isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED);
		}
		EntitySide entity = entityOpt.get();
		if (!entity.snapshot().equals(new SnapshotRef(ctx.repository(), selector.pinnedSha()))
				|| !entity.path().equals(selector.path())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SNAPSHOT_MISMATCH);
		}

		boolean coverageFailed = ctx.intent() == Intent.REVISION_COMPARISON
				&& isRequired
				&& (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage());

		if (entity.facetFingerprints().containsKey(facet) && entity.facetReferences().containsKey(facet)) {
			var refCheck = verifyFacetRefs(rawSource, lineIndex, entity.facetReferences().get(facet));
			var locators = refCheck.validRanges().isEmpty()
					? List.<SourceLocator>of()
					: List.of(new SourceLocator(
							ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
							SidePresence.PRESENT, SourceBasis.FACET_PRESENT,
							Optional.of(symKey), Optional.of(facet), Optional.empty(), refCheck.validRanges()));
			var reasons = new LinkedHashSet<EvidenceReason>();
			if (coverageFailed) {
				reasons.add(EvidenceReason.INSUFFICIENT_COVERAGE);
			}
			reasons.addAll(refCheck.failureReasons());
			if (reasons.isEmpty()) {
				return verifiedSourceWitness(selector.witnessId(), locators);
			}
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.UNVERIFIED,
					List.copyOf(reasons), locators, Optional.empty(), Optional.empty(), Optional.empty()));
		}

		// FACET_ABSENT rule (§4): revision COMPLETE, entity on both sides with EXACT_SYMBOL_KEY,
		// facet in changedFacets(), opposite side has verified content + all facet refs verified.
		if (ctx.intent() == Intent.REVISION_COMPARISON
				&& diff.status() == ProcessingStatus.COMPLETE
				&& diff.coverage().fullCoverage()) {
			Optional<EntityFact> factOpt = diff.entityFacts().stream()
					.filter(f -> f.matchBasis() == MatchBasis.EXACT_SYMBOL_KEY
							&& f.before().isPresent() && f.after().isPresent()
							&& f.after().get().key().equals(symKey)
							&& f.changedFacets().contains(facet))
					.findFirst();
			if (factOpt.isPresent()) {
				SideRole oppRole = selector.sideRole() == SideRole.BEFORE ? SideRole.AFTER : SideRole.BEFORE;
				EntitySide oppSide = oppRole == SideRole.BEFORE ? factOpt.get().before().get() : factOpt.get().after().get();
				Optional<SnapshotProvenance> oppProvOpt = oppRole == SideRole.BEFORE
						? diff.beforeSnapshot() : diff.afterSnapshot();
				Optional<SideContextFacts> oppContext = oppRole == SideRole.BEFORE
						? diff.beforeContext() : diff.afterContext();

				if (oppProvOpt.isPresent()
						&& oppSide.facetFingerprints().containsKey(facet)
						&& oppSide.facetReferences().containsKey(facet)) {
					var oppSnapCheck = verifySnapshotAuthority(ctx, oppRole, oppProvOpt.get(), indices.snapshotIndex);
					if (oppSnapCheck.failureReason().isEmpty() && oppSnapCheck.proof().isPresent()) {
						ContentCheck oppContent = verifyPresentContent(oppSnapCheck.proof().get(), oppContext);
						if (oppContent.status() == ContentCheckStatus.VERIFIED) {
							String oppRaw = oppSnapCheck.proof().get().exactDecodedSource().get();
							var oppRefCheck = verifyFacetRefs(oppRaw, LineIndex.build(oppRaw),
									oppSide.facetReferences().get(facet));
							if (oppRefCheck.failureReasons().isEmpty()) {
								var absentLocator = new SourceLocator(
										ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
										SidePresence.PRESENT, SourceBasis.FACET_ABSENT,
										Optional.of(symKey), Optional.of(facet), Optional.empty(), List.of());
								return verifiedSourceWitness(selector.witnessId(), List.of(absentLocator));
							}
							return unverifiedWitness(selector.witnessId(), oppRefCheck.failureReasons().getFirst());
						}
						return unverifiedWitness(selector.witnessId(), oppContent.failureReason().get());
					}
					if (oppSnapCheck.failureReason().isPresent()) {
						return unverifiedWitness(selector.witnessId(), oppSnapCheck.failureReason().get());
					}
				}
			}
		}

		return unverifiedWitness(selector.witnessId(),
				coverageFailed ? EvidenceReason.INSUFFICIENT_COVERAGE : EvidenceReason.SOURCE_BASIS_UNVERIFIED);
	}

	private WitnessVerification verifyContextSourceSelector(
			AssessmentContext ctx,
			ImpactFinding finding,
			SourceEvidenceSelector selector,
			boolean isRequired,
			StructuralDiffResult diff,
			SnapshotProvenance diffProv,
			SnapshotProof proof,
			Optional<SideContextFacts> sideContext) {
		if (selector.contextChangeKind().isEmpty()
				|| selector.symbolKey().isPresent()
				|| selector.facetKind().isPresent()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}
		ContextChangeKind kind = selector.contextChangeKind().get();
		if (!diff.fileComparison().contextChanges().contains(kind)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED);
		}

		if (diffProv.presence() == SidePresence.ABSENT) {
			boolean validSide = (selector.sideRole() == SideRole.BEFORE && diff.fileComparison().presence() == PresenceRelation.ADDED)
					|| (selector.sideRole() == SideRole.AFTER && diff.fileComparison().presence() == PresenceRelation.REMOVED);
			if (!validSide) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED);
			}
			var loc = new SourceLocator(
					ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
					SidePresence.ABSENT, SourceBasis.FILE_ABSENT,
					Optional.empty(), Optional.empty(), Optional.of(kind), List.of());
			if (isRequired && (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage())) {
				return new WitnessVerification(new WitnessEvidence(
						selector.witnessId(), WitnessStatus.UNVERIFIED,
						List.of(EvidenceReason.INSUFFICIENT_COVERAGE),
						List.of(loc), Optional.empty(), Optional.empty(), Optional.empty()));
			}
			return verifiedSourceWitness(selector.witnessId(), List.of(loc));
		}

		ContentCheck contentCheck = verifyPresentContent(proof, sideContext);
		if (contentCheck.status() != ContentCheckStatus.VERIFIED) {
			var identityLoc = fileLocator(ctx, selector, SidePresence.PRESENT, SourceBasis.FILE_IDENTITY);
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.UNVERIFIED,
					List.of(contentCheck.failureReason().get()),
					List.of(identityLoc), Optional.empty(), Optional.empty(), Optional.empty()));
		}

		boolean coverageFailed = false;
		if (kind == ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE && isRequired) {
			coverageFailed = true;
		} else if (ctx.intent() == Intent.REVISION_COMPARISON
				&& isRequired
				&& (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage())) {
			coverageFailed = true;
		}

		SideContextFacts ctxFacts = sideContext.get();
		if (kind == ContextChangeKind.SCOPE_ORDER_CHANGED) {
			if (ctxFacts.topLevelLayout() == null) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED);
			}
			var loc = new SourceLocator(
					ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
					SidePresence.PRESENT, SourceBasis.CONTEXT_PRESENT,
					Optional.empty(), Optional.empty(), Optional.of(kind), List.of());
			if (coverageFailed) {
				return new WitnessVerification(new WitnessEvidence(
						selector.witnessId(), WitnessStatus.UNVERIFIED,
						List.of(EvidenceReason.INSUFFICIENT_COVERAGE),
						List.of(loc), Optional.empty(), Optional.empty(), Optional.empty()));
			}
			return verifiedSourceWitness(selector.witnessId(), List.of(loc));
		}

		List<ProvenanceRef> refs = extractContextRefs(ctxFacts, kind);
		if (refs.isEmpty()) {
			var emptyLoc = new SourceLocator(
					ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
					SidePresence.PRESENT, SourceBasis.CONTEXT_EMPTY,
					Optional.empty(), Optional.empty(), Optional.of(kind), List.of());
			if (coverageFailed) {
				return new WitnessVerification(new WitnessEvidence(
						selector.witnessId(), WitnessStatus.UNVERIFIED,
						List.of(EvidenceReason.INSUFFICIENT_COVERAGE),
						List.of(emptyLoc), Optional.empty(), Optional.empty(), Optional.empty()));
			}
			return verifiedSourceWitness(selector.witnessId(), List.of(emptyLoc));
		}

		String rawSource = proof.exactDecodedSource().get();
		var refCheck = verifyFacetRefs(rawSource, LineIndex.build(rawSource), refs);
		var locators = refCheck.validRanges().isEmpty()
				? List.<SourceLocator>of()
				: List.of(new SourceLocator(
						ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
						SidePresence.PRESENT, SourceBasis.CONTEXT_PRESENT,
						Optional.empty(), Optional.empty(), Optional.of(kind), refCheck.validRanges()));
		var reasons = new LinkedHashSet<EvidenceReason>();
		if (coverageFailed) {
			reasons.add(EvidenceReason.INSUFFICIENT_COVERAGE);
		}
		reasons.addAll(refCheck.failureReasons());
		if (reasons.isEmpty()) {
			return verifiedSourceWitness(selector.witnessId(), locators);
		}
		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.UNVERIFIED,
				List.copyOf(reasons), locators, Optional.empty(), Optional.empty(), Optional.empty()));
	}

	private static List<ProvenanceRef> extractContextRefs(SideContextFacts ctxFacts, ContextChangeKind kind) {
		return switch (kind) {
			case PACKAGE_CHANGED -> ctxFacts.fileContext().packageDecl()
					.map(p -> List.of(p.provenance())).orElse(List.of());
			case IMPORTS_CHANGED -> ctxFacts.fileContext().imports().stream()
					.map(JavaStructuralRepresentation.ImportProjection::provenance).toList();
			case MODULE_CHANGED -> ctxFacts.fileContext().module()
					.map(m -> List.of(m.provenance())).orElse(List.of());
			case INITIALIZER_BLOCK_CHANGED -> ctxFacts.initializerBlocks().stream()
					.map(JavaStructuralRepresentation.InitializerBlockProjection::provenance).toList();
			case RESIDUAL_STRUCTURAL_CHANGE -> ctxFacts.residualRegions().stream()
					.map(JavaStructuralRepresentation.ResidualRegion::provenance).toList();
			case SCOPE_ORDER_CHANGED -> List.of();
		};
	}

	// ---- §5 Document Selector Verification ----

	private WitnessVerification verifyDocumentSelector(
			AssessmentContext ctx,
			ImpactFinding finding,
			DocumentWitnessSelector selector,
			boolean isRequired,
			DiffResolution diffResolution,
			AssessmentIndices indices) {
		if (diffResolution.errorReason().isPresent()) {
			return unverifiedWitness(selector.witnessId(), diffResolution.errorReason().get());
		}
		if (diffResolution.diff().isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.MISSING_DIFF);
		}
		StructuralDiffResult diff = diffResolution.diff().get();

		if (!selector.evaluatedTargetSha().equals(ctx.authoritativeTargetSha())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.DOCUMENT_UNVERIFIED);
		}

		// Verify binding fields match finding metadata if present on finding.
		if ((finding.documentId().isPresent() && !finding.documentId().get().equals(selector.documentId()))
				|| (finding.documentVersion().isPresent() && !finding.documentVersion().get().equals(selector.documentVersion()))
				|| (finding.documentBoundSha().isPresent() && !finding.documentBoundSha().get().equals(selector.documentBoundSha()))
				|| (finding.bindingId().isPresent() && !finding.bindingId().get().equals(selector.bindingId()))) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}

		var bindingIdentity = new BindingIdentityKey(
				selector.documentId(), selector.documentVersion(), selector.bindingId());
		if (indices.lookupIndex.conflictingBindings.contains(bindingIdentity)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF, EvidenceReason.DOCUMENT_UNVERIFIED);
		}

		List<IndexedLookup> eligibleLookups = collectEligibleDocumentLookups(ctx, finding, diff, isRequired, indices);
		if (eligibleLookups.isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.LOOKUP_UNVERIFIED);
		}

		Set<String> validDiffPaths = diffPaths(diff);
		Optional<DocumentBinding> matchedBindingOpt = findMatchingBinding(
				eligibleLookups, finding, selector, validDiffPaths, isRequired);
		if (matchedBindingOpt.isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.DOCUMENT_UNVERIFIED);
		}
		DocumentBinding binding = matchedBindingOpt.get();

		// Binding-level selector (no claimId).
		if (selector.claimId().isEmpty()) {
			if (selector.documentedReferenceOrTerm().isPresent()) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
			}
			var docRef = new DocumentEvidenceRef(
					binding.documentId(), binding.documentVersion(), binding.documentBoundSha(),
					ctx.authoritativeTargetSha(), binding.bindingId(), Optional.empty(),
					binding.sectionRef(), Optional.empty());
			if (isRequired) {
				return new WitnessVerification(new WitnessEvidence(
						selector.witnessId(), WitnessStatus.UNVERIFIED,
						List.of(EvidenceReason.DOCUMENT_UNVERIFIED),
						List.of(), Optional.of(docRef), Optional.empty(), Optional.empty()));
			}
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.VERIFIED,
					List.of(), List.of(), Optional.of(docRef), Optional.empty(), Optional.empty()));
		}

		// Claim-level selector.
		String targetClaimId = selector.claimId().get();
		if (finding.claimId().isPresent() && !finding.claimId().get().equals(targetClaimId)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}
		var claimIdentity = new ClaimIdentityKey(binding.documentId(), targetClaimId);
		if (indices.lookupIndex.conflictingClaims.contains(claimIdentity)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF, EvidenceReason.DOCUMENT_UNVERIFIED);
		}

		Optional<DocumentClaim> claimOpt = binding.claims().stream()
				.filter(c -> c.claimId().equals(targetClaimId))
				.findFirst();
		if (claimOpt.isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.DOCUMENT_UNVERIFIED);
		}
		DocumentClaim claim = claimOpt.get();

		if (!claim.documentId().equals(binding.documentId())
				|| !claim.documentVersion().equals(binding.documentVersion())
				|| !claim.documentBoundSha().equals(binding.documentBoundSha())
				|| !claim.evaluatedTargetSha().equals(ctx.authoritativeTargetSha())
				|| !selector.documentedReferenceOrTerm().equals(Optional.of(claim.documentedReferenceOrTerm()))
				|| !validDiffPaths.contains(claim.targetSubjectPath())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.DOCUMENT_UNVERIFIED);
		}
		if (ctx.intent() == Intent.INITIAL_ASSESSMENT
				&& diff.afterSnapshot().isPresent()
				&& !claim.targetSubjectPath().equals(diff.afterSnapshot().get().path())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.DOCUMENT_UNVERIFIED);
		}
		if (finding.symbolKey().isPresent() && claim.targetSubjectSymbol().isPresent()
				&& !claim.targetSubjectSymbol().get().equals(finding.symbolKey().get())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.DOCUMENT_UNVERIFIED);
		}
		if (!finding.evaluatedFacets().isEmpty() && claim.targetSubjectFacet().isPresent()
				&& !finding.evaluatedFacets().contains(claim.targetSubjectFacet().get())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.DOCUMENT_UNVERIFIED);
		}

		var claimRef = new DocumentEvidenceRef(
				binding.documentId(), binding.documentVersion(), binding.documentBoundSha(),
				claim.evaluatedTargetSha(), binding.bindingId(), Optional.of(claim.claimId()),
				binding.sectionRef(), Optional.of(claim.verdict()));

		if (isRequired && !isClaimVerdictSuitableForFinding(ctx, finding, selector, diff, claim)) {
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.UNVERIFIED,
					List.of(EvidenceReason.DOCUMENT_UNVERIFIED),
					List.of(), Optional.of(claimRef), Optional.empty(), Optional.empty()));
		}

		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.VERIFIED,
				List.of(), List.of(), Optional.of(claimRef), Optional.empty(), Optional.empty()));
	}

	private List<IndexedLookup> collectEligibleDocumentLookups(
			AssessmentContext ctx,
			ImpactFinding finding,
			StructuralDiffResult diff,
			boolean requireContentHash,
			AssessmentIndices indices) {
		var eligibleSides = new ArrayList<SourceIdentity>();
		for (var role : List.of(SideRole.BEFORE, SideRole.AFTER, SideRole.TARGET)) {
			Optional<SnapshotProvenance> provOpt = switch (role) {
				case BEFORE -> diff.beforeSnapshot();
				case AFTER -> ctx.intent() == Intent.REVISION_COMPARISON ? diff.afterSnapshot() : Optional.empty();
				case TARGET -> ctx.intent() == Intent.INITIAL_ASSESSMENT ? diff.afterSnapshot() : Optional.empty();
				case INPUT -> Optional.empty();
			};
			if (provOpt.isEmpty() || provOpt.get().presence() != SidePresence.PRESENT) {
				continue;
			}
			var prov = provOpt.get();
			var snapCheck = verifySnapshotAuthority(ctx, role, prov, indices.snapshotIndex);
			if (snapCheck.failureReason().isPresent() || snapCheck.proof().isEmpty()) {
				continue;
			}
			Optional<SideContextFacts> sideCtx = role == SideRole.BEFORE ? diff.beforeContext() : diff.afterContext();
			ContentCheck cc = verifyPresentContent(snapCheck.proof().get(), sideCtx);
			if (cc.status() == ContentCheckStatus.VERIFIED
					|| (!requireContentHash && cc.status() == ContentCheckStatus.NO_SOURCE)) {
				eligibleSides.add(new SourceIdentity(role, ctx.repository(), prov.snapshot().sha(), prov.path()));
			}
		}

		if (eligibleSides.isEmpty()) {
			return List.of();
		}

		Set<SymbolKey> diffSymbols = collectDiffSymbols(diff);
		var result = new ArrayList<IndexedLookup>();
		for (var entry : indices.lookupIndex.byKey.entrySet()) {
			var k = entry.getKey();
			if (indices.lookupIndex.conflictingKeys.contains(k)) {
				continue;
			}
			var indexed = entry.getValue();
			if (indexed.completeness() != LookupCompleteness.COMPLETE) {
				continue;
			}
			boolean onVerifiedSide = eligibleSides.stream().anyMatch(side ->
					side.role() == k.sideRole() && side.sha().equals(k.pinnedSha()) && side.path().equals(k.path()));
			if (!onVerifiedSide) {
				continue;
			}
			if (finding.symbolKey().isPresent()) {
				if (k.granularity() == LookupGranularity.FILE
						|| (k.granularity() == LookupGranularity.SYMBOL && k.symbolKey().equals(finding.symbolKey()))) {
					result.add(indexed);
				}
			} else {
				if (k.granularity() == LookupGranularity.FILE
						|| (k.granularity() == LookupGranularity.SYMBOL
								&& k.symbolKey().map(diffSymbols::contains).orElse(false))) {
					result.add(indexed);
				}
			}
		}
		return result;
	}

	private static Optional<DocumentBinding> findMatchingBinding(
			List<IndexedLookup> lookups,
			ImpactFinding finding,
			DocumentWitnessSelector selector,
			Set<String> validDiffPaths,
			boolean isRequired) {
		for (var lookup : lookups) {
			for (var b : lookup.localBindings()) {
				if (!b.documentId().equals(selector.documentId())
						|| !b.documentVersion().equals(selector.documentVersion())
						|| !b.documentBoundSha().equals(selector.documentBoundSha())
						|| !b.bindingId().equals(selector.bindingId())
						|| !b.sectionRef().equals(selector.sectionRef())) {
					continue;
				}
				if (!validDiffPaths.contains(b.boundPath())) {
					continue;
				}
				if (finding.symbolKey().isPresent()) {
					if (b.granularity() == BindingGranularity.EXACT_SYMBOL
							&& !b.boundSymbolKey().equals(finding.symbolKey())) {
						continue;
					}
					if (b.granularity() == BindingGranularity.FILE_ONLY
							&& isRequired
							&& !isRemovedRevisionFile(finding)) {
						continue;
					}
				}
				return Optional.of(b);
			}
		}
		return Optional.empty();
	}

	private static boolean isRemovedRevisionFile(ImpactFinding finding) {
		return !finding.initialAssessment()
				&& finding.witnesses().stream().anyMatch(w -> w instanceof SourceEvidenceSelector src
						&& src.sideRole() == SideRole.AFTER
						&& src.presence() == SidePresence.ABSENT);
	}

	private static boolean isClaimVerdictSuitableForFinding(
			AssessmentContext ctx,
			ImpactFinding finding,
			DocumentWitnessSelector selector,
			StructuralDiffResult diff,
			DocumentClaim claim) {
		boolean hasRequiredSemantic = finding.witnesses().stream()
				.anyMatch(w -> w instanceof SemanticWitnessSelector
						&& finding.requiredImpactWitnessIds().contains(w.witnessId()));
		boolean requiredForAdmission = finding.requiredAdmissionWitnessIds().contains(selector.witnessId());
		boolean requiredForImpact = finding.requiredImpactWitnessIds().contains(selector.witnessId());

		if (hasRequiredSemantic) {
			// When semantic witness is required for impact, document witness is only required
			// for independent initial admission (POTENTIAL_MISMATCH_INITIAL_ONLY) unless impact == admission.
			if (requiredForAdmission && !finding.requiredAdmissionWitnessIds().equals(finding.requiredImpactWitnessIds())) {
				return claim.verdict() == ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY;
			}
			// If admission shares the full semantic set, claim must at least be executable-applicable.
			return claim.verdict() == ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY
					|| claim.verdict() == ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN
					|| claim.verdict() == ClaimVerdict.BEHAVIOR_CONSISTENT_PROVEN
					|| claim.verdict() == ClaimVerdict.STALE_CLAIM_AGAINST_TARGET;
		}

		if (finding.outcome() == ImpactOutcome.INCONCLUSIVE) {
			return finding.admission() == IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE
					&& claim.verdict() == ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY;
		}

		if (finding.outcome() == ImpactOutcome.NO_IMPACT) {
			if (!requiredForImpact) {
				return true;
			}
			if (finding.evaluatedFacets().equals(Set.of(FacetKind.MEMBER_ORDER))) {
				return claim.verdict() == ClaimVerdict.DECLARATION_CONSISTENT;
			}
			if (claim.verdict() == ClaimVerdict.REFERENCED_ENTITY_REQUIRED) {
				if (finding.symbolKey().isEmpty()) {
					return false;
				}
				return findEntitySide(diff,
						ctx.intent() == Intent.INITIAL_ASSESSMENT ? SideRole.TARGET : SideRole.AFTER,
						finding.symbolKey().get()).isPresent();
			}
			if (claim.verdict() == ClaimVerdict.DECLARATION_CONSISTENT
					|| claim.verdict() == ClaimVerdict.BEHAVIOR_CONSISTENT_PROVEN) {
				if (finding.symbolKey().isEmpty() && (finding.witnesses().stream()
						.anyMatch(w -> w instanceof SourceEvidenceSelector src
								&& src.granularity() == SelectorGranularity.CONTEXT)
						|| (diff.fileComparison().rawChanges().contains(RawChangeKind.COMMENT)
								&& !isPathChangeNoImpact(ctx, finding, diff)))) {
					return claim.targetSubjectSymbol().isEmpty() && claim.targetSubjectFacet().isEmpty();
				}
				return true;
			}
			return false;
		}

		// ACTIONABLE (deterministic)
		DriftClassification cls = finding.classification().orElse(null);
		if (cls == null) {
			return false;
		}
		return switch (cls) {
			case REFERENTIAL -> {
				boolean verdictOk = claim.verdict() == ClaimVerdict.REFERENCED_ENTITY_REQUIRED
						|| claim.verdict() == ClaimVerdict.DECLARATION_INCOMPATIBLE
						|| claim.verdict() == ClaimVerdict.STALE_CLAIM_AGAINST_TARGET;
				if (!verdictOk) {
					yield false;
				}
				if (finding.initialAssessment()) {
					yield finding.symbolKey().isEmpty()
							&& diff.status() == ProcessingStatus.COMPLETE
							&& diff.initialInventory().isPresent()
							&& diff.initialInventory().get().entities().isEmpty();
				}
				if (finding.symbolKey().isPresent()) {
					SymbolKey symKey = finding.symbolKey().get();
					Optional<EntityFact> factOpt = diff.entityFacts().stream()
							.filter(f -> f.change() == ChangeKind.REMOVED
									&& f.before().map(s -> s.key().equals(symKey)).orElse(false))
							.findFirst();
					if (factOpt.isEmpty() || diff.afterSnapshot().isEmpty()) {
						yield false;
					}
					var afterProv = diff.afterSnapshot().get();
					if (afterProv.presence() == SidePresence.ABSENT) {
						yield true;
					}
					if (afterProv.presence() == SidePresence.PRESENT && diff.status() == ProcessingStatus.COMPLETE) {
						boolean hasOverload = diff.entityFacts().stream()
								.filter(f -> f.change() == ChangeKind.ADDED)
								.map(f -> f.after().orElseThrow().key())
								.anyMatch(ak -> isPotentialOverloadOrCounterpart(symKey, ak));
						yield !hasOverload || claim.targetSubjectSymbol().equals(Optional.of(symKey));
					}
					yield false;
				}
				// File-level REFERENTIAL (removal or move).
				var fc = diff.fileComparison();
				if (fc.presence() != PresenceRelation.REMOVED && fc.pathRelation() != PathRelation.PATH_CHANGED) {
					yield false;
				}
				if (!diff.beforeSnapshot().map(s -> claim.targetSubjectPath().equals(s.path())).orElse(false)) {
					yield false;
				}
				if (claim.targetSubjectSymbol().isPresent()) {
					SymbolKey claimSym = claim.targetSubjectSymbol().get();
					yield diff.entityFacts().stream().anyMatch(f -> f.change() == ChangeKind.REMOVED
							&& f.before().map(s -> s.key().equals(claimSym)).orElse(false))
							&& (fc.presence() == PresenceRelation.REMOVED || diff.status() == ProcessingStatus.COMPLETE);
				}
				yield true;
			}
			case SIGNATURE -> claim.verdict() == ClaimVerdict.DECLARATION_INCOMPATIBLE
					|| claim.verdict() == ClaimVerdict.STALE_CLAIM_AGAINST_TARGET;
			case SEMANTIC -> {
				if (claim.verdict() != ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN
						&& claim.verdict() != ClaimVerdict.STALE_CLAIM_AGAINST_TARGET) {
					yield false;
				}
				// Must match executable facet when finding evaluates executable facet.
				if (finding.evaluatedFacets().contains(FacetKind.BODY)
						|| finding.evaluatedFacets().contains(FacetKind.INITIALIZER)) {
					yield claim.targetSubjectFacet().isEmpty()
							|| finding.evaluatedFacets().contains(claim.targetSubjectFacet().get());
				}
				yield true;
			}
		};
	}

	private static boolean isPathChangeNoImpact(AssessmentContext ctx, ImpactFinding finding, StructuralDiffResult diff) {
		if (diff.fileComparison().pathRelation() != PathRelation.PATH_CHANGED
				|| finding.outcome() != ImpactOutcome.NO_IMPACT || finding.symbolKey().isPresent()
				|| !finding.evaluatedFacets().isEmpty()) {
			return false;
		}
		// In m3-impact-v1 both rename and comment witnesses have FILE granularity.
		// The classifier encodes their distinct axis tags in the length-prefixed finding ID.
		var parts = List.of(ctx.tenantId(), ctx.workspaceId(), ctx.repository(), ctx.targetKind().name(),
				ctx.targetRef(), ctx.intent().name(), ctx.comparisonBeforeSha().orElse(""), ctx.authoritativeTargetSha(),
				finding.disposition().name(), finding.outcome().name(), finding.beforePath().orElse(""),
				finding.afterPath().orElse(""), "", "", finding.documentId().orElse(""),
				finding.documentVersion().orElse(""), finding.bindingId().orElse(""), finding.claimId().orElse(""),
				finding.primaryReason().name(), "NO_IMPACT|FILE:PATH_CHANGED");
		var digest = sha256Digest();
		var size = ByteBuffer.allocate(Integer.BYTES);
		digest.update(size.putInt(0, parts.size()).array());
		for (String part : parts) {
			byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
			digest.update(size.putInt(0, bytes.length).array());
			digest.update(bytes);
		}
		return finding.findingId().equals("finding:" + HexFormat.of().formatHex(digest.digest()));
	}

	// ---- §6 Semantic Selector Verification ----

	private WitnessVerification verifySemanticSelector(
			AssessmentContext ctx,
			ImpactFinding finding,
			SemanticWitnessSelector selector,
			boolean isRequired,
			DiffResolution diffResolution,
			AssessmentIndices indices) {
		if (!selector.jobId().equals(ctx.jobId())
				|| !selector.attemptId().equals(ctx.attemptId())
				|| !selector.evaluatedTargetSha().equals(ctx.authoritativeTargetSha())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
		}
		if (finding.symbolKey().isEmpty()
				|| !finding.symbolKey().get().equals(selector.symbolKey())
				|| !finding.evaluatedFacets().contains(selector.facetKind())
				|| !finding.documentId().equals(Optional.of(selector.documentId()))
				|| !finding.documentVersion().equals(Optional.of(selector.documentVersion()))
				|| !finding.claimId().equals(Optional.of(selector.claimId()))
				|| finding.documentBoundSha().isEmpty()
				|| finding.bindingId().isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}
		if (diffResolution.errorReason().isPresent()) {
			return unverifiedWitness(selector.witnessId(), diffResolution.errorReason().get());
		}
		if (diffResolution.diff().isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.MISSING_DIFF);
		}
		StructuralDiffResult primaryDiff = diffResolution.diff().get();
		if (primaryDiff.afterSnapshot().isEmpty()
				|| !primaryDiff.afterSnapshot().get().path().equals(selector.path())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SNAPSHOT_MISMATCH);
		}

		String boundSha = finding.documentBoundSha().get();
		String bindingId = finding.bindingId().get();

		// Find matching SemanticAssessmentInputs for exact context & claim.
		var matchingCandidates = new ArrayList<SemanticAssessmentInput>();
		for (var cand : indices.semanticIndex.inputs) {
			if (cand.jobId().equals(ctx.jobId())
					&& cand.attemptId().equals(ctx.attemptId())
					&& cand.evaluatedTargetSha().equals(ctx.authoritativeTargetSha())
					&& cand.path().equals(selector.path())
					&& cand.symbolKey().equals(selector.symbolKey())
					&& cand.documentId().equals(selector.documentId())
					&& cand.documentVersion().equals(selector.documentVersion())
					&& cand.documentBoundSha().equals(boundSha)
					&& cand.groundedDocumentRefs().stream().anyMatch(d ->
							d.documentId().equals(selector.documentId())
									&& d.documentVersion().equals(selector.documentVersion())
									&& d.documentBoundSha().equals(boundSha)
									&& d.bindingId().equals(bindingId)
									&& d.claimId().equals(selector.claimId()))) {
				matchingCandidates.add(cand);
			}
		}

		if (matchingCandidates.size() > 1) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF, EvidenceReason.SEMANTIC_UNVERIFIED);
		}
		if (matchingCandidates.isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
		}

		SemanticAssessmentInput cand = matchingCandidates.getFirst();
		if (!cand.withinDeadline()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
		}

		boolean statusValid;
		if (finding.outcome() == ImpactOutcome.ACTIONABLE
				&& finding.classification().equals(Optional.of(DriftClassification.SEMANTIC))) {
			statusValid = cand.status() == SemanticStatus.BEHAVIOR_MISMATCH
					&& finding.semanticConfidence().equals(cand.confidence());
		} else if (finding.outcome() == ImpactOutcome.NO_IMPACT) {
			statusValid = cand.status() == SemanticStatus.BEHAVIOR_CONSISTENT;
		} else {
			statusValid = false;
		}
		if (!statusValid) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
		}

		// Compare grounded refs as sets (permutation-invariant).
		if (!Set.copyOf(cand.groundedSourceRefs()).equals(Set.copyOf(selector.groundedSourceRefs()))
				|| !Set.copyOf(cand.groundedDocumentRefs()).equals(Set.copyOf(selector.groundedDocumentRefs()))) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
		}

		// Verify EVERY GroundedSourceFacetRef; keep ONLY primary finding's verified locators.
		SideRole expectedTargetRole = ctx.intent() == Intent.INITIAL_ASSESSMENT ? SideRole.TARGET : SideRole.AFTER;
		boolean hasPrimaryTargetRef = false;
		var primaryLocators = new ArrayList<SourceLocator>();

		for (var sref : cand.groundedSourceRefs()) {
			if (!isRoleValidForIntent(ctx.intent(), sref.sideRole())) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}
			if (!sref.pinnedSha().equals(expectedShaForRole(ctx, sref.sideRole()))) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}
			Optional<StructuralDiffResult> refDiffOpt = findDiffForSide(ctx, sref.sideRole(), sref.pinnedSha(), sref.path(), indices.diffIndex);
			if (refDiffOpt.isEmpty()) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}
			StructuralDiffResult refDiff = refDiffOpt.get();
			if (ctx.intent() == Intent.REVISION_COMPARISON
					&& (refDiff.status() != ProcessingStatus.COMPLETE || !refDiff.coverage().fullCoverage())) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}

			Optional<SnapshotProvenance> provOpt = sref.sideRole() == SideRole.BEFORE
					? refDiff.beforeSnapshot() : refDiff.afterSnapshot();
			Optional<SideContextFacts> sideCtx = sref.sideRole() == SideRole.BEFORE
					? refDiff.beforeContext() : refDiff.afterContext();
			if (provOpt.isEmpty() || provOpt.get().presence() != SidePresence.PRESENT) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}
			var snapCheck = verifySnapshotAuthority(ctx, sref.sideRole(), provOpt.get(), indices.snapshotIndex);
			if (snapCheck.failureReason().isPresent() || snapCheck.proof().isEmpty()) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}
			ContentCheck cc = verifyPresentContent(snapCheck.proof().get(), sideCtx);
			if (cc.status() != ContentCheckStatus.VERIFIED) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}

			Optional<EntitySide> entityOpt = findEntitySide(refDiff, sref.sideRole(), sref.symbolKey());
			if (entityOpt.isEmpty()) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}
			EntitySide entity = entityOpt.get();
			if (!entity.facetFingerprints().containsKey(sref.facetKind())
					|| !entity.facetReferences().containsKey(sref.facetKind())) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}
			String rawSource = snapCheck.proof().get().exactDecodedSource().get();
			var refCheck = verifyFacetRefs(rawSource, LineIndex.build(rawSource),
					entity.facetReferences().get(sref.facetKind()));
			if (!refCheck.failureReasons().isEmpty() || refCheck.validRanges().isEmpty()) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}

			List<SourceRange> effectiveRanges;
			if (sref.range().isPresent()) {
				SourceRange requestedRange = sref.range().get();
				if (!refCheck.validRanges().contains(requestedRange)) {
					return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
				}
				effectiveRanges = List.of(requestedRange);
			} else {
				effectiveRanges = refCheck.validRanges();
			}

			if (sref.sideRole() == expectedTargetRole
					&& sref.pinnedSha().equals(selector.evaluatedTargetSha())
					&& sref.path().equals(selector.path())
					&& sref.symbolKey().equals(selector.symbolKey())
					&& sref.facetKind() == selector.facetKind()) {
				hasPrimaryTargetRef = true;
			}
			if (refDiff.equals(primaryDiff)
					&& sref.symbolKey().equals(selector.symbolKey())
					&& sref.facetKind() == selector.facetKind()) {
				primaryLocators.add(new SourceLocator(
						ctx.repository(), sref.sideRole(), sref.pinnedSha(), sref.path(),
						SidePresence.PRESENT, SourceBasis.FACET_PRESENT,
						Optional.of(sref.symbolKey()), Optional.of(sref.facetKind()),
						Optional.empty(), effectiveRanges));
			}
		}

		if (!hasPrimaryTargetRef) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
		}

		// Verify EVERY GroundedDocumentClaimRef against §5 lookup/binding/claim index.
		Optional<DocumentEvidenceRef> primaryDocRef = Optional.empty();
		for (var dref : cand.groundedDocumentRefs()) {
			Optional<DocumentEvidenceRef> resolved = resolveGroundedDocumentRef(ctx, dref, indices);
			if (resolved.isEmpty()) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
			}
			if (dref.documentId().equals(selector.documentId())
					&& dref.documentVersion().equals(selector.documentVersion())
					&& dref.documentBoundSha().equals(boundSha)
					&& dref.bindingId().equals(bindingId)
					&& dref.claimId().equals(selector.claimId())) {
				primaryDocRef = resolved;
			}
		}

		if (primaryDocRef.isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SEMANTIC_UNVERIFIED);
		}

		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(),
				WitnessStatus.VERIFIED,
				List.of(),
				primaryLocators,
				primaryDocRef,
				Optional.empty(),
				Optional.of(cand.status())));
	}

	private Optional<DocumentEvidenceRef> resolveGroundedDocumentRef(
			AssessmentContext ctx,
			GroundedDocumentClaimRef dref,
			AssessmentIndices indices) {
		var bindingId = new BindingIdentityKey(dref.documentId(), dref.documentVersion(), dref.bindingId());
		var claimId = new ClaimIdentityKey(dref.documentId(), dref.claimId());
		if (indices.lookupIndex.conflictingBindings.contains(bindingId)
				|| indices.lookupIndex.conflictingClaims.contains(claimId)) {
			return Optional.empty();
		}

		for (var entry : indices.lookupIndex.byKey.entrySet()) {
			var k = entry.getKey();
			if (indices.lookupIndex.conflictingKeys.contains(k)) {
				continue;
			}
			var indexed = entry.getValue();
			if (indexed.completeness() != LookupCompleteness.COMPLETE) {
				continue;
			}
			// Lookup must sit on a verified PRESENT side of some non-conflicting in-scope diff.
			Optional<StructuralDiffResult> sideDiffOpt = findDiffForSide(ctx, k.sideRole(), k.pinnedSha(), k.path(), indices.diffIndex);
			if (sideDiffOpt.isEmpty()) {
				continue;
			}
			var sideDiff = sideDiffOpt.get();
			Optional<SnapshotProvenance> provOpt = k.sideRole() == SideRole.BEFORE
					? sideDiff.beforeSnapshot() : sideDiff.afterSnapshot();
			Optional<SideContextFacts> sideCtx = k.sideRole() == SideRole.BEFORE
					? sideDiff.beforeContext() : sideDiff.afterContext();
			if (provOpt.isEmpty() || provOpt.get().presence() != SidePresence.PRESENT) {
				continue;
			}
			var snapCheck = verifySnapshotAuthority(ctx, k.sideRole(), provOpt.get(), indices.snapshotIndex);
			if (snapCheck.failureReason().isPresent() || snapCheck.proof().isEmpty()) {
				continue;
			}
			if (verifyPresentContent(snapCheck.proof().get(), sideCtx).status() != ContentCheckStatus.VERIFIED) {
				continue;
			}

			Set<String> validPaths = diffPaths(sideDiff);
			for (var b : indexed.localBindings()) {
				if (!b.documentId().equals(dref.documentId())
						|| !b.documentVersion().equals(dref.documentVersion())
						|| !b.documentBoundSha().equals(dref.documentBoundSha())
						|| !b.bindingId().equals(dref.bindingId())
						|| !validPaths.contains(b.boundPath())) {
					continue;
				}
				if (dref.sectionRef().isPresent() && !dref.sectionRef().equals(b.sectionRef())) {
					continue;
				}
				for (var c : b.claims()) {
					if (c.claimId().equals(dref.claimId())
							&& c.evaluatedTargetSha().equals(ctx.authoritativeTargetSha())
							&& validPaths.contains(c.targetSubjectPath())) {
						return Optional.of(new DocumentEvidenceRef(
								b.documentId(), b.documentVersion(), b.documentBoundSha(),
								c.evaluatedTargetSha(), b.bindingId(), Optional.of(c.claimId()),
								b.sectionRef(), Optional.of(c.verdict())));
					}
				}
			}
		}
		return Optional.empty();
	}

	// ---- §7 Negative Basis Verification ----

	private WitnessVerification verifyNegativeBasisSelector(
			AssessmentContext ctx,
			ImpactFinding finding,
			NegativeBasisWitnessSelector selector,
			boolean isRequired,
			DiffResolution diffResolution,
			AssessmentIndices indices) {
		return switch (selector.kind()) {
			case EMPTY_VERIFIED_SCOPE -> verifyEmptyVerifiedScope(ctx, selector, indices);
			case COMPLETE_EMPTY_LOOKUP -> verifyCompleteEmptyLookup(ctx, finding, selector, diffResolution, indices);
			case UNCHANGED_VERIFIED -> verifyUnchangedOrWhitespace(ctx, finding, selector, diffResolution, indices, false);
			case WHITESPACE_ONLY_VERIFIED -> verifyUnchangedOrWhitespace(ctx, finding, selector, diffResolution, indices, true);
			case NON_JAVA_OUT_OF_SCOPE -> verifyNonJavaOutOfScope(ctx, finding, selector, diffResolution, indices);
			case CALLER_EXCLUSION_RULE -> verifyCallerExclusionRule(ctx, finding, selector, diffResolution, indices);
			case CONTROLLED_EXPORT_PROVENANCE -> verifyControlledExport(ctx, finding, selector, diffResolution, indices);
		};
	}

	private WitnessVerification verifyEmptyVerifiedScope(
			AssessmentContext ctx,
			NegativeBasisWitnessSelector selector,
			AssessmentIndices indices) {
		if (ctx.declaredScopeCompleteness() != ScopeCompleteness.EMPTY_VERIFIED
				|| indices.diffIndex.rawCount > 0) {
			return unverifiedWitness(selector.witnessId(),
					indices.diffIndex.rawCount > 0 ? EvidenceReason.CONFLICTING_PROOF : EvidenceReason.PROVENANCE_UNVERIFIED);
		}
		if (ctx.scopeManifest().isEmpty()
				|| selector.scopeManifest().isEmpty()
				|| selector.provenanceRefId().isEmpty()
				|| !selector.verifiedEmptyLookupKeys().isEmpty()
				|| selector.beforePath().isPresent()
				|| selector.afterPath().isPresent()
				|| selector.expectedBeforeRawSha256().isPresent()
				|| selector.expectedTargetRawSha256().isPresent()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED);
		}
		ScopeManifestRef ctxManifest = ctx.scopeManifest().get();
		if (!selector.scopeManifest().get().equals(ctxManifest)
				|| !selector.provenanceRefId().get().equals(ctxManifest.manifestId())
				|| !selector.beforeSha().equals(ctxManifest.comparisonBeforeSha())
				|| !selector.afterSha().equals(Optional.of(ctxManifest.authoritativeTargetSha()))) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED);
		}
		if (indices.manifestIndex.conflicts.contains(ctxManifest.manifestId())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF);
		}
		ScopeManifestRef authManifest = indices.manifestIndex.byId.get(ctxManifest.manifestId());
		if (authManifest == null
				|| !authManifest.equals(ctxManifest)
				|| authManifest.eligibleFileCount() != 0) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED);
		}
		var negEvidence = new NegativeBasisEvidence(
				NegativeBasisKind.EMPTY_VERIFIED_SCOPE, List.of(), Optional.of(authManifest.manifestId()));
		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.VERIFIED, List.of(), List.of(),
				Optional.empty(), Optional.of(negEvidence), Optional.empty()));
	}

	private WitnessVerification verifyCompleteEmptyLookup(
			AssessmentContext ctx,
			ImpactFinding finding,
			NegativeBasisWitnessSelector selector,
			DiffResolution diffResolution,
			AssessmentIndices indices) {
		if (diffResolution.errorReason().isPresent()) {
			return unverifiedWitness(selector.witnessId(), diffResolution.errorReason().get());
		}
		if (diffResolution.diff().isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.MISSING_DIFF);
		}
		StructuralDiffResult diff = diffResolution.diff().get();

		if (selector.provenanceRefId().isPresent() || selector.scopeManifest().isPresent()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}
		if (!selectorMatchesDiffPathsAndShas(ctx, finding, selector, diff, true)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SNAPSHOT_MISMATCH);
		}

		// Verify snapshots & content hashes for all diff sides first so valid locators can be retained.
		var sideCheck = verifyAllDiffSides(ctx, diff, true, indices.snapshotIndex);
		if (sideCheck.failureReasons().stream().anyMatch(r -> r != EvidenceReason.CONTENT_UNAVAILABLE
				&& r != EvidenceReason.CONTENT_MISMATCH && r != EvidenceReason.MISSING_SNAPSHOT
				&& r != EvidenceReason.SNAPSHOT_MISMATCH && r != EvidenceReason.SNAPSHOT_UNAVAILABLE
				&& r != EvidenceReason.CONFLICTING_PROOF)) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
		}
		if (!sideCheck.failureReasons().isEmpty()) {
			return unverifiedWithTrace(selector.witnessId(), sideCheck.failureReasons().getFirst(), sideCheck.locators());
		}

		// Check diff coverage: revision requires COMPLETE/fullCoverage; initial requires initialInventory.
		if (ctx.intent() == Intent.REVISION_COMPARISON) {
			if (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.INSUFFICIENT_COVERAGE, sideCheck.locators());
			}
		} else {
			if (diff.initialInventory().isEmpty()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.INSUFFICIENT_COVERAGE, sideCheck.locators());
			}
			if (finding.symbolKey().isEmpty()
					&& (diff.status() != ProcessingStatus.COMPLETE || !diff.coverage().fullCoverage())) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.INSUFFICIENT_COVERAGE, sideCheck.locators());
			}
		}

		// Compute expected lookup keys via M3's buildLookupKeys and filter for symbol findings.
		List<DocumentLookupKey> allKeys = ImpactClassifier.buildLookupKeys(ctx, List.of(diff));
		List<DocumentLookupKey> expectedKeys = finding.symbolKey().isPresent()
				? allKeys.stream()
						.filter(k -> k.granularity() == LookupGranularity.FILE
								|| k.symbolKey().equals(finding.symbolKey()))
						.toList()
				: allKeys;
		if (expectedKeys.isEmpty()) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.LOOKUP_UNVERIFIED, sideCheck.locators());
		}

		var expectedSet = Set.copyOf(expectedKeys);
		var selectorKeys = selector.verifiedEmptyLookupKeys();
		var selectorSet = Set.copyOf(selectorKeys);
		if (selectorKeys.size() != selectorSet.size()) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.LOOKUP_UNVERIFIED, sideCheck.locators());
		}
		if (isPathChangeNoImpact(ctx, finding, diff)) {
			var unchangedSymbols = new LinkedHashSet<SymbolKey>();
			for (var fact : diff.entityFacts()) {
				if (fact.change() == ChangeKind.UNCHANGED) {
					unchangedSymbols.add(fact.after().orElseThrow().key());
				}
			}
			expectedSet = Set.copyOf(expectedKeys.stream()
					.filter(k -> k.granularity() == LookupGranularity.FILE
							|| k.symbolKey().map(unchangedSymbols::contains).orElse(false))
					.toList());
		}
		if (!selectorSet.equals(expectedSet)) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.LOOKUP_UNVERIFIED, sideCheck.locators());
		}

		for (var reqKey : expectedSet) {
			if (indices.lookupIndex.conflictingKeys.contains(reqKey)) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF, sideCheck.locators());
			}
			IndexedLookup resp = indices.lookupIndex.byKey.get(reqKey);
			if (resp == null || resp.completeness() != LookupCompleteness.COMPLETE || resp.hasForeignOrMalformedBinding()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.LOOKUP_UNVERIFIED, sideCheck.locators());
			}
			if (reqKey.granularity() == LookupGranularity.SYMBOL || finding.symbolKey().isEmpty()) {
				if (!resp.localBindings().isEmpty()) {
					return unverifiedWithTrace(selector.witnessId(), EvidenceReason.LOOKUP_UNVERIFIED, sideCheck.locators());
				}
			} else {
				SymbolKey targetSym = finding.symbolKey().get();
				boolean hasRelevantBinding = resp.localBindings().stream().anyMatch(b ->
						b.granularity() == BindingGranularity.FILE_ONLY
								|| b.boundSymbolKey().equals(Optional.of(targetSym)));
				if (hasRelevantBinding) {
					return unverifiedWithTrace(selector.witnessId(), EvidenceReason.LOOKUP_UNVERIFIED, sideCheck.locators());
				}
			}
		}

		var sortedKeys = new ArrayList<>(expectedSet);
		sortedKeys.sort(LOOKUP_KEY_COMPARATOR);
		var negEvidence = new NegativeBasisEvidence(
				NegativeBasisKind.COMPLETE_EMPTY_LOOKUP, sortedKeys, Optional.empty());
		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.VERIFIED, List.of(),
				sideCheck.locators(), Optional.empty(), Optional.of(negEvidence), Optional.empty()));
	}

	private WitnessVerification verifyUnchangedOrWhitespace(
			AssessmentContext ctx,
			ImpactFinding finding,
			NegativeBasisWitnessSelector selector,
			DiffResolution diffResolution,
			AssessmentIndices indices,
			boolean expectWhitespaceOnly) {
		if (ctx.intent() != Intent.REVISION_COMPARISON) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}
		if (diffResolution.errorReason().isPresent()) {
			return unverifiedWitness(selector.witnessId(), diffResolution.errorReason().get());
		}
		if (diffResolution.diff().isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.MISSING_DIFF);
		}
		StructuralDiffResult diff = diffResolution.diff().get();

		if (!selectorMatchesDiffPathsAndShas(ctx, finding, selector, diff, false)
				|| !selector.verifiedEmptyLookupKeys().isEmpty()
				|| selector.provenanceRefId().isPresent()
				|| selector.scopeManifest().isPresent()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}

		var sideCheck = verifyAllDiffSides(ctx, diff, true, indices.snapshotIndex);
		if (!sideCheck.failureReasons().isEmpty()) {
			return unverifiedWithTrace(selector.witnessId(), sideCheck.failureReasons().getFirst(), sideCheck.locators());
		}

		var fc = diff.fileComparison();
		if (diff.status() != ProcessingStatus.COMPLETE
				|| !diff.coverage().fullCoverage()
				|| fc.presence() != PresenceRelation.BOTH_PRESENT
				|| fc.pathRelation() != PathRelation.SAME_PATH
				|| fc.structuralEquality() != StructuralEquality.EQUAL
				|| !fc.contextChanges().isEmpty()) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED, sideCheck.locators());
		}

		var beforeProv = diff.beforeSnapshot().orElseThrow();
		var afterProv = diff.afterSnapshot().orElseThrow();
		String beforeContent = sideCheck.rawSources().get(SideRole.BEFORE);
		String afterContent = sideCheck.rawSources().get(SideRole.AFTER);
		if (beforeContent == null || afterContent == null) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.CONTENT_UNAVAILABLE, sideCheck.locators());
		}

		if (expectWhitespaceOnly) {
			if (!ctx.whitespaceOnlyNoImpactPolicyEnabled()
					|| !fc.rawOnlyChange()
					|| !fc.rawChanges().equals(Set.of(RawChangeKind.WHITESPACE))
					|| beforeContent.equals(afterContent)) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED, sideCheck.locators());
			}
		} else {
			if (fc.rawOnlyChange()
					|| !fc.rawChanges().isEmpty()
					|| !beforeContent.equals(afterContent)) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED, sideCheck.locators());
			}
		}

		// Both FILE lookups derived from M3 buildLookupKeys must be COMPLETE, non-conflicting, and clean.
		List<DocumentLookupKey> derivedFileKeys = ImpactClassifier.buildLookupKeys(ctx, List.of(diff)).stream()
				.filter(k -> k.granularity() == LookupGranularity.FILE)
				.toList();
		if (derivedFileKeys.size() != 2) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.LOOKUP_UNVERIFIED, sideCheck.locators());
		}
		for (var fk : derivedFileKeys) {
			if (indices.lookupIndex.conflictingKeys.contains(fk)) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF, sideCheck.locators());
			}
			IndexedLookup resp = indices.lookupIndex.byKey.get(fk);
			if (resp == null || resp.completeness() != LookupCompleteness.COMPLETE || resp.hasForeignOrMalformedBinding()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.LOOKUP_UNVERIFIED, sideCheck.locators());
			}
		}

		// Scan ALL FILE and SYMBOL lookups for these two sides/paths/SHAs for incomplete/conflicting/stale claims.
		for (var conflictKey : indices.lookupIndex.conflictingKeys) {
			if (matchesSideOrOpposite(conflictKey, beforeProv, afterProv)) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF, sideCheck.locators());
			}
		}
		for (var entry : indices.lookupIndex.byKey.entrySet()) {
			var k = entry.getKey();
			if (!matchesSideOrOpposite(k, beforeProv, afterProv)) {
				continue;
			}
			IndexedLookup resp = entry.getValue();
			if (resp.completeness() != LookupCompleteness.COMPLETE || resp.hasForeignOrMalformedBinding()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.LOOKUP_UNVERIFIED, sideCheck.locators());
			}
			for (var b : resp.localBindings()) {
				var bId = new BindingIdentityKey(b.documentId(), b.documentVersion(), b.bindingId());
				if (indices.lookupIndex.conflictingBindings.contains(bId)) {
					return unverifiedWithTrace(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF, sideCheck.locators());
				}
				for (var c : b.claims()) {
					var cId = new ClaimIdentityKey(c.documentId(), c.claimId());
					if (indices.lookupIndex.conflictingClaims.contains(cId)) {
						return unverifiedWithTrace(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF, sideCheck.locators());
					}
					if (c.evaluatedTargetSha().equals(ctx.authoritativeTargetSha())
							&& isStaleOrMismatchVerdict(c.verdict())) {
						return unverifiedWithTrace(selector.witnessId(), EvidenceReason.DOCUMENT_UNVERIFIED, sideCheck.locators());
					}
				}
			}
		}

		var negEvidence = new NegativeBasisEvidence(selector.kind(), List.of(), Optional.empty());
		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.VERIFIED, List.of(),
				sideCheck.locators(), Optional.empty(), Optional.of(negEvidence), Optional.empty()));
	}

	private WitnessVerification verifyNonJavaOutOfScope(
			AssessmentContext ctx,
			ImpactFinding finding,
			NegativeBasisWitnessSelector selector,
			DiffResolution diffResolution,
			AssessmentIndices indices) {
		if (diffResolution.errorReason().isPresent()) {
			return unverifiedWitness(selector.witnessId(), diffResolution.errorReason().get());
		}
		if (diffResolution.diff().isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.MISSING_DIFF);
		}
		StructuralDiffResult diff = diffResolution.diff().get();

		if (selector.provenanceRefId().isPresent()
				|| selector.scopeManifest().isPresent()
				|| !selector.verifiedEmptyLookupKeys().isEmpty()
				|| !selectorMatchesDiffPathsAndShas(ctx, finding, selector, diff, false)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}
		if (diff.status() != ProcessingStatus.OUT_OF_SCOPE
				|| diff.diagnostics().stream().noneMatch(d -> d.code() == DiagnosticCode.UNSUPPORTED_LANGUAGE)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED);
		}
		for (String path : diffPaths(diff)) {
			if (path.endsWith(".java")) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.SOURCE_BASIS_UNVERIFIED);
			}
		}

		var sideCheck = verifyAllDiffSides(ctx, diff, false, indices.snapshotIndex);
		if (!sideCheck.failureReasons().isEmpty()) {
			return unverifiedWithTrace(selector.witnessId(), sideCheck.failureReasons().getFirst(), sideCheck.locators());
		}

		var negEvidence = new NegativeBasisEvidence(NegativeBasisKind.NON_JAVA_OUT_OF_SCOPE, List.of(), Optional.empty());
		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.VERIFIED, List.of(),
				sideCheck.locators(), Optional.empty(), Optional.of(negEvidence), Optional.empty()));
	}

	private WitnessVerification verifyCallerExclusionRule(
			AssessmentContext ctx,
			ImpactFinding finding,
			NegativeBasisWitnessSelector selector,
			DiffResolution diffResolution,
			AssessmentIndices indices) {
		if (diffResolution.errorReason().isPresent()) {
			return unverifiedWitness(selector.witnessId(), diffResolution.errorReason().get());
		}
		if (diffResolution.diff().isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.MISSING_DIFF);
		}
		StructuralDiffResult diff = diffResolution.diff().get();

		if (finding.symbolKey().isPresent()
				|| selector.provenanceRefId().isEmpty()
				|| !selector.verifiedEmptyLookupKeys().isEmpty()
				|| !selectorMatchesDiffPathsAndShas(ctx, finding, selector, diff, false)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}

		var sideCheck = verifyAllDiffSides(ctx, diff, false, indices.snapshotIndex);
		if (!sideCheck.failureReasons().isEmpty()) {
			return unverifiedWithTrace(selector.witnessId(), sideCheck.failureReasons().getFirst(), sideCheck.locators());
		}

		String ruleId = selector.provenanceRefId().get();
		if (indices.exclusionIndex.conflicts.contains(ruleId)) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF, sideCheck.locators());
		}
		CallerExclusion rule = indices.exclusionIndex.byId.get(ruleId);
		String targetPath = diff.afterSnapshot().map(SnapshotProvenance::path)
				.or(() -> diff.beforeSnapshot().map(SnapshotProvenance::path))
				.orElse("");
		if (rule == null
				|| !rule.repository().equals(ctx.repository())
				|| !rule.targetSha().equals(ctx.authoritativeTargetSha())
				|| !rule.path().equals(targetPath)) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
		}

		var negEvidence = new NegativeBasisEvidence(
				NegativeBasisKind.CALLER_EXCLUSION_RULE, List.of(), Optional.of(rule.ruleId()));
		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.VERIFIED, List.of(),
				sideCheck.locators(), Optional.empty(), Optional.of(negEvidence), Optional.empty()));
	}

	private WitnessVerification verifyControlledExport(
			AssessmentContext ctx,
			ImpactFinding finding,
			NegativeBasisWitnessSelector selector,
			DiffResolution diffResolution,
			AssessmentIndices indices) {
		if (diffResolution.errorReason().isPresent()) {
			return unverifiedWitness(selector.witnessId(), diffResolution.errorReason().get());
		}
		if (diffResolution.diff().isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.MISSING_DIFF);
		}
		StructuralDiffResult diff = diffResolution.diff().get();

		if (selector.provenanceRefId().isEmpty()
				|| !selector.verifiedEmptyLookupKeys().isEmpty()
				|| !selectorMatchesDiffPathsAndShas(ctx, finding, selector, diff, false)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.INVALID_SELECTOR);
		}

		String exportId = selector.provenanceRefId().get();
		if (indices.exportIndex.conflicts.contains(exportId)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.CONFLICTING_PROOF);
		}
		ControlledExportUnit exp = indices.exportIndex.byId.get(exportId);
		String targetPath = diff.afterSnapshot().map(SnapshotProvenance::path)
				.or(() -> diff.beforeSnapshot().map(SnapshotProvenance::path))
				.orElse("");
		if (exp == null
				|| !exp.repository().equals(ctx.repository())
				|| !exp.targetSha().equals(ctx.authoritativeTargetSha())
				|| !exp.path().equals(targetPath)) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED);
		}

		if (finding.symbolKey().isPresent()) {
			return verifySymbolControlledExport(ctx, finding, selector, diff, exp, indices.snapshotIndex);
		}
		return verifyWholeFileControlledExport(ctx, selector, diff, exp, indices.snapshotIndex);
	}

	private WitnessVerification verifySymbolControlledExport(
			AssessmentContext ctx,
			ImpactFinding finding,
			NegativeBasisWitnessSelector selector,
			StructuralDiffResult diff,
			ControlledExportUnit exp,
			SnapshotIndex snapshotIndex) {
		SymbolKey symKey = finding.symbolKey().get();
		if (!exp.symbolKey().equals(Optional.of(symKey))) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED);
		}
		if (ctx.intent() == Intent.INITIAL_ASSESSMENT) {
			if (exp.beforeSha().isPresent()
					|| exp.expectedBeforeRawSha256().isPresent()
					|| selector.expectedBeforeRawSha256().isPresent()) {
				return unverifiedWitness(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED);
			}
			var sideCheck = verifyAllDiffSides(ctx, diff, true, snapshotIndex);
			if (!sideCheck.failureReasons().isEmpty()) {
				return unverifiedWithTrace(selector.witnessId(), sideCheck.failureReasons().getFirst(), sideCheck.locators());
			}
			Optional<EntitySide> targetEntity = findEntitySide(diff, SideRole.TARGET, symKey);
			if (targetEntity.isEmpty()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
			String targetRaw = sideCheck.rawSources().get(SideRole.TARGET);
			String actualTargetHash = sha256LowerHex(targetRaw);
			if (!matchesOptionalExpectedHash(selector.expectedTargetRawSha256(), exp.expectedTargetRawSha256(), actualTargetHash)) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
			var refCheck = verifyFacetRefs(targetRaw, LineIndex.build(targetRaw),
					targetEntity.get().facetReferences().get(FacetKind.DECLARATION));
			if (!refCheck.failureReasons().isEmpty() || refCheck.validRanges().isEmpty()) {
				return unverifiedWithTrace(selector.witnessId(),
						refCheck.failureReasons().isEmpty() ? EvidenceReason.RANGE_UNAVAILABLE : refCheck.failureReasons().getFirst(),
						sideCheck.locators());
			}
			var symLoc = new SourceLocator(
					ctx.repository(), SideRole.TARGET, ctx.authoritativeTargetSha(), targetEntity.get().path(),
					SidePresence.PRESENT, SourceBasis.SYMBOL_PRESENT,
					Optional.of(symKey), Optional.empty(), Optional.empty(), refCheck.validRanges());
			var negEvidence = new NegativeBasisEvidence(
					NegativeBasisKind.CONTROLLED_EXPORT_PROVENANCE, List.of(), Optional.of(exp.exportProvenanceId()));
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.VERIFIED, List.of(),
					List.of(symLoc), Optional.empty(), Optional.of(negEvidence), Optional.empty()));
		}

		// REVISION_COMPARISON symbol export.
		if (exp.beforeSha().isPresent() && !exp.beforeSha().equals(ctx.comparisonBeforeSha())) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED);
		}
		Optional<EntityFact> factOpt = diff.entityFacts().stream()
				.filter(f -> entityMatchesSideAndKey(f, SideRole.BEFORE, symKey)
						|| entityMatchesSideAndKey(f, SideRole.AFTER, symKey))
				.findFirst();
		if (factOpt.isEmpty()) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED);
		}
		var sideCheck = verifyAllDiffSides(ctx, diff, true, snapshotIndex);
		if (!sideCheck.failureReasons().isEmpty()) {
			return unverifiedWithTrace(selector.witnessId(), sideCheck.failureReasons().getFirst(), sideCheck.locators());
		}

		var beforeProv = diff.beforeSnapshot().orElseThrow();
		var afterProv = diff.afterSnapshot().orElseThrow();
		if (selector.expectedBeforeRawSha256().isPresent() || exp.expectedBeforeRawSha256().isPresent()) {
			if (beforeProv.presence() != SidePresence.PRESENT) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
			String actualBeforeHash = sha256LowerHex(sideCheck.rawSources().get(SideRole.BEFORE));
			if (!matchesOptionalExpectedHash(selector.expectedBeforeRawSha256(), exp.expectedBeforeRawSha256(), actualBeforeHash)) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
		}
		if (selector.expectedTargetRawSha256().isPresent() || exp.expectedTargetRawSha256().isPresent()) {
			if (afterProv.presence() != SidePresence.PRESENT) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
			String actualAfterHash = sha256LowerHex(sideCheck.rawSources().get(SideRole.AFTER));
			if (!matchesOptionalExpectedHash(selector.expectedTargetRawSha256(), exp.expectedTargetRawSha256(), actualAfterHash)) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
		}

		var outLocators = new ArrayList<SourceLocator>();
		EntityFact fact = factOpt.get();
		if (beforeProv.presence() == SidePresence.ABSENT) {
			outLocators.add(new SourceLocator(
					ctx.repository(), SideRole.BEFORE, beforeProv.snapshot().sha(), beforeProv.path(),
					SidePresence.ABSENT, SourceBasis.FILE_ABSENT,
					Optional.of(symKey), Optional.empty(), Optional.empty(), List.of()));
		} else if (fact.before().isPresent()) {
			String rawBefore = sideCheck.rawSources().get(SideRole.BEFORE);
			var refCheck = verifyFacetRefs(rawBefore, LineIndex.build(rawBefore),
					fact.before().get().facetReferences().get(FacetKind.DECLARATION));
			if (!refCheck.failureReasons().isEmpty() || refCheck.validRanges().isEmpty()) {
				return unverifiedWithTrace(selector.witnessId(),
						refCheck.failureReasons().isEmpty() ? EvidenceReason.RANGE_UNAVAILABLE : refCheck.failureReasons().getFirst(),
						sideCheck.locators());
			}
			outLocators.add(new SourceLocator(
					ctx.repository(), SideRole.BEFORE, beforeProv.snapshot().sha(), beforeProv.path(),
					SidePresence.PRESENT, SourceBasis.SYMBOL_PRESENT,
					Optional.of(symKey), Optional.empty(), Optional.empty(), refCheck.validRanges()));
		} else if (diff.status() == ProcessingStatus.COMPLETE && diff.coverage().fullCoverage()) {
			outLocators.add(new SourceLocator(
					ctx.repository(), SideRole.BEFORE, beforeProv.snapshot().sha(), beforeProv.path(),
					SidePresence.PRESENT, SourceBasis.SYMBOL_ABSENT,
					Optional.of(symKey), Optional.empty(), Optional.empty(), List.of()));
		} else {
			outLocators.add(new SourceLocator(
					ctx.repository(), SideRole.BEFORE, beforeProv.snapshot().sha(), beforeProv.path(),
					SidePresence.PRESENT, SourceBasis.FILE_CONTENT,
					Optional.empty(), Optional.empty(), Optional.empty(), List.of()));
		}

		if (afterProv.presence() == SidePresence.ABSENT) {
			outLocators.add(new SourceLocator(
					ctx.repository(), SideRole.AFTER, afterProv.snapshot().sha(), afterProv.path(),
					SidePresence.ABSENT, SourceBasis.FILE_ABSENT,
					Optional.of(symKey), Optional.empty(), Optional.empty(), List.of()));
		} else if (fact.after().isPresent()) {
			String rawAfter = sideCheck.rawSources().get(SideRole.AFTER);
			var refCheck = verifyFacetRefs(rawAfter, LineIndex.build(rawAfter),
					fact.after().get().facetReferences().get(FacetKind.DECLARATION));
			if (!refCheck.failureReasons().isEmpty() || refCheck.validRanges().isEmpty()) {
				return unverifiedWithTrace(selector.witnessId(),
						refCheck.failureReasons().isEmpty() ? EvidenceReason.RANGE_UNAVAILABLE : refCheck.failureReasons().getFirst(),
						sideCheck.locators());
			}
			outLocators.add(new SourceLocator(
					ctx.repository(), SideRole.AFTER, afterProv.snapshot().sha(), afterProv.path(),
					SidePresence.PRESENT, SourceBasis.SYMBOL_PRESENT,
					Optional.of(symKey), Optional.empty(), Optional.empty(), refCheck.validRanges()));
		} else if (diff.status() == ProcessingStatus.COMPLETE && diff.coverage().fullCoverage()) {
			outLocators.add(new SourceLocator(
					ctx.repository(), SideRole.AFTER, afterProv.snapshot().sha(), afterProv.path(),
					SidePresence.PRESENT, SourceBasis.SYMBOL_ABSENT,
					Optional.of(symKey), Optional.empty(), Optional.empty(), List.of()));
		} else {
			outLocators.add(new SourceLocator(
					ctx.repository(), SideRole.AFTER, afterProv.snapshot().sha(), afterProv.path(),
					SidePresence.PRESENT, SourceBasis.FILE_CONTENT,
					Optional.empty(), Optional.empty(), Optional.empty(), List.of()));
		}

		var negEvidence = new NegativeBasisEvidence(
				NegativeBasisKind.CONTROLLED_EXPORT_PROVENANCE, List.of(), Optional.of(exp.exportProvenanceId()));
		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.VERIFIED, List.of(),
				outLocators, Optional.empty(), Optional.of(negEvidence), Optional.empty()));
	}

	private WitnessVerification verifyWholeFileControlledExport(
			AssessmentContext ctx,
			NegativeBasisWitnessSelector selector,
			StructuralDiffResult diff,
			ControlledExportUnit exp,
			SnapshotIndex snapshotIndex) {
		if (exp.symbolKey().isPresent()
				|| diff.status() != ProcessingStatus.COMPLETE
				|| !diff.coverage().fullCoverage()
				|| diff.fileComparison().pathRelation() == PathRelation.PATH_CHANGED) {
			return unverifiedWitness(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED);
		}
		var sideCheck = verifyAllDiffSides(ctx, diff, true, snapshotIndex);
		if (!sideCheck.failureReasons().isEmpty()) {
			return unverifiedWithTrace(selector.witnessId(), sideCheck.failureReasons().getFirst(), sideCheck.locators());
		}

		if (ctx.intent() == Intent.INITIAL_ASSESSMENT) {
			if (exp.beforeSha().isPresent()
					|| exp.expectedBeforeRawSha256().isPresent()
					|| selector.expectedBeforeRawSha256().isPresent()
					|| exp.expectedTargetRawSha256().isEmpty()
					|| !selector.expectedTargetRawSha256().equals(exp.expectedTargetRawSha256())) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
			var targetProv = diff.afterSnapshot().orElseThrow();
			if (targetProv.presence() != SidePresence.PRESENT) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
			String actualHash = sha256LowerHex(sideCheck.rawSources().get(SideRole.TARGET));
			if (!actualHash.equals(exp.expectedTargetRawSha256().get())) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
			var negEvidence = new NegativeBasisEvidence(
					NegativeBasisKind.CONTROLLED_EXPORT_PROVENANCE, List.of(), Optional.of(exp.exportProvenanceId()));
			return new WitnessVerification(new WitnessEvidence(
					selector.witnessId(), WitnessStatus.VERIFIED, List.of(),
					sideCheck.locators(), Optional.empty(), Optional.of(negEvidence), Optional.empty()));
		}

		// REVISION_COMPARISON whole-file export.
		if (exp.beforeSha().isEmpty()
				|| !exp.beforeSha().equals(ctx.comparisonBeforeSha())
				|| !selector.expectedBeforeRawSha256().equals(exp.expectedBeforeRawSha256())
				|| !selector.expectedTargetRawSha256().equals(exp.expectedTargetRawSha256())) {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
		}
		var beforeProv = diff.beforeSnapshot().orElseThrow();
		var afterProv = diff.afterSnapshot().orElseThrow();

		if (beforeProv.presence() == SidePresence.PRESENT) {
			if (exp.expectedBeforeRawSha256().isEmpty()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
			String beforeHash = sha256LowerHex(sideCheck.rawSources().get(SideRole.BEFORE));
			if (!beforeHash.equals(exp.expectedBeforeRawSha256().get())) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
		} else if (beforeProv.presence() == SidePresence.ABSENT) {
			if (exp.expectedBeforeRawSha256().isPresent()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
		} else {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.SNAPSHOT_UNAVAILABLE, sideCheck.locators());
		}

		if (afterProv.presence() == SidePresence.PRESENT) {
			if (exp.expectedTargetRawSha256().isEmpty()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
			String afterHash = sha256LowerHex(sideCheck.rawSources().get(SideRole.AFTER));
			if (!afterHash.equals(exp.expectedTargetRawSha256().get())) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
		} else if (afterProv.presence() == SidePresence.ABSENT) {
			if (exp.expectedTargetRawSha256().isPresent()) {
				return unverifiedWithTrace(selector.witnessId(), EvidenceReason.PROVENANCE_UNVERIFIED, sideCheck.locators());
			}
		} else {
			return unverifiedWithTrace(selector.witnessId(), EvidenceReason.SNAPSHOT_UNAVAILABLE, sideCheck.locators());
		}

		var negEvidence = new NegativeBasisEvidence(
				NegativeBasisKind.CONTROLLED_EXPORT_PROVENANCE, List.of(), Optional.of(exp.exportProvenanceId()));
		return new WitnessVerification(new WitnessEvidence(
				selector.witnessId(), WitnessStatus.VERIFIED, List.of(),
				sideCheck.locators(), Optional.empty(), Optional.of(negEvidence), Optional.empty()));
	}

	private static boolean matchesOptionalExpectedHash(
			Optional<String> selectorExpected,
			Optional<String> authorityExpected,
			String actualHash) {
		if (selectorExpected.isPresent() && authorityExpected.isPresent()
				&& !selectorExpected.equals(authorityExpected)) {
			return false;
		}
		if (selectorExpected.isPresent() && !selectorExpected.get().equals(actualHash)) {
			return false;
		}
		if (authorityExpected.isPresent() && !authorityExpected.get().equals(actualHash)) {
			return false;
		}
		return true;
	}

	private static boolean selectorMatchesDiffPathsAndShas(
			AssessmentContext ctx,
			ImpactFinding finding,
			NegativeBasisWitnessSelector selector,
			StructuralDiffResult diff,
			boolean allowEmptyInitialTargetPathOnSelector) {
		if (ctx.intent() == Intent.INITIAL_ASSESSMENT) {
			if (selector.beforeSha().isPresent() || selector.beforePath().isPresent()) {
				return false;
			}
			if (diff.afterSnapshot().isEmpty()) {
				return false;
			}
			var targetProv = diff.afterSnapshot().get();
			if (!selector.afterSha().equals(Optional.of(targetProv.snapshot().sha()))) {
				return false;
			}
			if (selector.afterPath().isPresent()) {
				return selector.afterPath().get().equals(targetProv.path())
						&& finding.afterPath().map(targetProv.path()::equals).orElse(true);
			}
			return allowEmptyInitialTargetPathOnSelector && finding.afterPath().map(targetProv.path()::equals).orElse(true);
		}

		if (diff.beforeSnapshot().isEmpty() || diff.afterSnapshot().isEmpty()) {
			return false;
		}
		var bProv = diff.beforeSnapshot().get();
		var aProv = diff.afterSnapshot().get();
		return selector.beforeSha().equals(Optional.of(bProv.snapshot().sha()))
				&& selector.beforePath().equals(Optional.of(bProv.path()))
				&& selector.afterSha().equals(Optional.of(aProv.snapshot().sha()))
				&& selector.afterPath().equals(Optional.of(aProv.path()))
				&& finding.beforePath().map(bProv.path()::equals).orElse(true)
				&& finding.afterPath().map(aProv.path()::equals).orElse(true);
	}

	// ---- Diagnostics Collection (§4) ----

	private static void collectDiffDiagnostics(
			AssessmentContext ctx,
			DiffIndex diffIndex,
			SnapshotIndex snapshotIndex,
			Set<SafeDiagnostic> safeDiagnostics) {
		for (var diff : diffIndex.inputLevelDiffs) {
			for (var diag : diff.diagnostics()) {
				if (diag.side() == SideRole.INPUT) {
					safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
							Optional.empty(), Optional.empty()));
				}
			}
		}

		for (var entry : diffIndex.byKey.entrySet()) {
			if (diffIndex.conflictingKeys.contains(entry.getKey())) {
				continue;
			}
			StructuralDiffResult diff = entry.getValue();
			for (var diag : diff.diagnostics()) {
				if (diag.side() == SideRole.INPUT) {
					safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
							Optional.empty(), Optional.empty()));
					continue;
				}
				if (diag.snapshot().isEmpty() || diag.path().isEmpty()) {
					safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
							Optional.of(EvidenceReason.MISSING_SNAPSHOT), Optional.empty()));
					continue;
				}
				SnapshotRef diagSnap = diag.snapshot().get();
				String diagPath = diag.path().get();
				if (!diagSnap.repository().equals(ctx.repository())) {
					safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
							Optional.of(EvidenceReason.SCOPE_DENIED), Optional.empty()));
					continue;
				}
				Optional<SnapshotProvenance> provOpt = diag.side() == SideRole.BEFORE
						? diff.beforeSnapshot() : diff.afterSnapshot();
				if (provOpt.isEmpty()
						|| !provOpt.get().snapshot().equals(diagSnap)
						|| !provOpt.get().path().equals(diagPath)) {
					safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
							Optional.of(EvidenceReason.SNAPSHOT_MISMATCH), Optional.empty()));
					continue;
				}
				SnapshotProvenance prov = provOpt.get();
				var snapCheck = verifySnapshotAuthority(ctx, diag.side(), prov, snapshotIndex);
				if (snapCheck.failureReason().isPresent()) {
					safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
							snapCheck.failureReason(), snapCheck.traceLocator()));
					continue;
				}

				if (prov.presence() == SidePresence.UNAVAILABLE) {
					var loc = new SourceLocator(
							ctx.repository(), diag.side(), diagSnap.sha(), diagPath,
							SidePresence.UNAVAILABLE, SourceBasis.SNAPSHOT_UNAVAILABLE,
							Optional.empty(), Optional.empty(), Optional.empty(), List.of());
					safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
							Optional.empty(), Optional.of(loc)));
					continue;
				}
				if (prov.presence() == SidePresence.ABSENT) {
					var loc = new SourceLocator(
							ctx.repository(), diag.side(), diagSnap.sha(), diagPath,
							SidePresence.ABSENT, SourceBasis.FILE_ABSENT,
							Optional.empty(), Optional.empty(), Optional.empty(), List.of());
					safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
							Optional.empty(), Optional.of(loc)));
					continue;
				}

				// PRESENT diagnostic.
				SnapshotProof proof = snapCheck.proof().get();
				Optional<SideContextFacts> sideCtx = diag.side() == SideRole.BEFORE
						? diff.beforeContext() : diff.afterContext();
				ContentCheck cc = verifyPresentContent(proof, sideCtx);

				if (diag.range().isEmpty()) {
					SourceBasis basis = cc.status() == ContentCheckStatus.VERIFIED
							? SourceBasis.FILE_CONTENT : SourceBasis.FILE_IDENTITY;
					var loc = new SourceLocator(
							ctx.repository(), diag.side(), diagSnap.sha(), diagPath,
							SidePresence.PRESENT, basis,
							Optional.empty(), Optional.empty(), Optional.empty(), List.of());
					Optional<EvidenceReason> evReason = cc.status() == ContentCheckStatus.MISMATCH
							? Optional.of(EvidenceReason.CONTENT_MISMATCH)
							: diag.rangeUnavailableReason().isPresent()
									? Optional.of(EvidenceReason.RANGE_UNAVAILABLE)
									: Optional.empty();
					safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
							evReason, Optional.of(loc)));
					continue;
				}

				SourceRange diagRange = diag.range().get();
				if (cc.status() == ContentCheckStatus.VERIFIED) {
					String rawSource = proof.exactDecodedSource().get();
					Optional<EvidenceReason> rangeErr = checkRange(rawSource, LineIndex.build(rawSource),
							diagRange, Optional.empty());
					if (rangeErr.isEmpty()) {
						var loc = new SourceLocator(
								ctx.repository(), diag.side(), diagSnap.sha(), diagPath,
								SidePresence.PRESENT, SourceBasis.DIAGNOSTIC_RANGE,
								Optional.empty(), Optional.empty(), Optional.empty(), List.of(diagRange));
						safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
								Optional.empty(), Optional.of(loc)));
					} else {
						var loc = new SourceLocator(
								ctx.repository(), diag.side(), diagSnap.sha(), diagPath,
								SidePresence.PRESENT, SourceBasis.FILE_CONTENT,
								Optional.empty(), Optional.empty(), Optional.empty(), List.of());
						safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
								rangeErr, Optional.of(loc)));
					}
				} else {
					var loc = new SourceLocator(
							ctx.repository(), diag.side(), diagSnap.sha(), diagPath,
							SidePresence.PRESENT, SourceBasis.FILE_IDENTITY,
							Optional.empty(), Optional.empty(), Optional.empty(), List.of());
					EvidenceReason reason = cc.status() == ContentCheckStatus.MISMATCH
							? EvidenceReason.CONTENT_MISMATCH
							: EvidenceReason.RANGE_UNAVAILABLE;
					safeDiagnostics.add(new SafeDiagnostic(Optional.of(diag.code()), Optional.empty(),
							Optional.of(reason), Optional.of(loc)));
				}
			}
		}
	}

	// ---- Snapshot, Content & Range Verification Helpers ----

	private static SnapshotAuthorityCheck verifySnapshotAuthority(
			AssessmentContext ctx,
			SideRole role,
			SnapshotProvenance diffProv,
			SnapshotIndex snapshotIndex) {
		var key = new SourceIdentity(role, ctx.repository(), diffProv.snapshot().sha(), diffProv.path());
		if (snapshotIndex.conflicts.contains(key)) {
			return new SnapshotAuthorityCheck(Optional.empty(), Optional.of(EvidenceReason.CONFLICTING_PROOF), Optional.empty());
		}
		SnapshotProof proof = snapshotIndex.byId.get(key);
		if (proof == null) {
			return new SnapshotAuthorityCheck(Optional.empty(), Optional.of(EvidenceReason.MISSING_SNAPSHOT), Optional.empty());
		}
		if (!proof.provenance().snapshot().equals(diffProv.snapshot())
				|| !proof.provenance().path().equals(diffProv.path())) {
			return new SnapshotAuthorityCheck(Optional.empty(), Optional.of(EvidenceReason.SNAPSHOT_MISMATCH), Optional.empty());
		}
		if (proof.provenance().presence() != diffProv.presence()) {
			if (proof.provenance().presence() == SidePresence.UNAVAILABLE && proof.exactDecodedSource().isEmpty()) {
				var traceLoc = new SourceLocator(
						ctx.repository(), role, diffProv.snapshot().sha(), diffProv.path(),
						SidePresence.UNAVAILABLE, SourceBasis.SNAPSHOT_UNAVAILABLE,
						Optional.empty(), Optional.empty(), Optional.empty(), List.of());
				return new SnapshotAuthorityCheck(Optional.empty(), Optional.of(EvidenceReason.SNAPSHOT_UNAVAILABLE),
						Optional.of(traceLoc));
			}
			return new SnapshotAuthorityCheck(Optional.empty(), Optional.of(EvidenceReason.SNAPSHOT_MISMATCH), Optional.empty());
		}
		if (diffProv.presence() == SidePresence.ABSENT) {
			if (proof.exactDecodedSource().isPresent()
					|| !proof.provenance().absenceReason().equals(diffProv.absenceReason())) {
				return new SnapshotAuthorityCheck(Optional.empty(), Optional.of(EvidenceReason.SNAPSHOT_MISMATCH), Optional.empty());
			}
		} else if (diffProv.presence() == SidePresence.UNAVAILABLE) {
			if (proof.exactDecodedSource().isPresent()) {
				return new SnapshotAuthorityCheck(Optional.empty(), Optional.of(EvidenceReason.SNAPSHOT_MISMATCH), Optional.empty());
			}
		}
		return new SnapshotAuthorityCheck(Optional.of(proof), Optional.empty(), Optional.empty());
	}

	private static SideCheckResult verifyAllDiffSides(
			AssessmentContext ctx,
			StructuralDiffResult diff,
			boolean requireFullContent,
			SnapshotIndex snapshotIndex) {
		var locators = new ArrayList<SourceLocator>();
		var failures = new ArrayList<EvidenceReason>();
		var rawSources = new HashMap<SideRole, String>();

		var sidesToCheck = new ArrayList<SideRole>();
		if (ctx.intent() == Intent.INITIAL_ASSESSMENT) {
			if (diff.afterSnapshot().isPresent()) {
				sidesToCheck.add(SideRole.TARGET);
			}
		} else {
			if (diff.beforeSnapshot().isPresent()) {
				sidesToCheck.add(SideRole.BEFORE);
			}
			if (diff.afterSnapshot().isPresent()) {
				sidesToCheck.add(SideRole.AFTER);
			}
		}

		for (var role : sidesToCheck) {
			SnapshotProvenance prov = role == SideRole.BEFORE
					? diff.beforeSnapshot().get() : diff.afterSnapshot().get();
			var snapCheck = verifySnapshotAuthority(ctx, role, prov, snapshotIndex);
			if (snapCheck.failureReason().isPresent()) {
				failures.add(snapCheck.failureReason().get());
				snapCheck.traceLocator().ifPresent(locators::add);
				continue;
			}
			SnapshotProof proof = snapCheck.proof().get();
			if (prov.presence() == SidePresence.ABSENT) {
				locators.add(new SourceLocator(
						ctx.repository(), role, prov.snapshot().sha(), prov.path(),
						SidePresence.ABSENT, SourceBasis.FILE_ABSENT,
						Optional.empty(), Optional.empty(), Optional.empty(), List.of()));
				continue;
			}
			if (prov.presence() == SidePresence.UNAVAILABLE) {
				failures.add(EvidenceReason.SNAPSHOT_UNAVAILABLE);
				locators.add(new SourceLocator(
						ctx.repository(), role, prov.snapshot().sha(), prov.path(),
						SidePresence.UNAVAILABLE, SourceBasis.SNAPSHOT_UNAVAILABLE,
						Optional.empty(), Optional.empty(), Optional.empty(), List.of()));
				continue;
			}

			Optional<SideContextFacts> sideCtx = role == SideRole.BEFORE ? diff.beforeContext() : diff.afterContext();
			ContentCheck cc = verifyPresentContent(proof, sideCtx);
			if (cc.status() == ContentCheckStatus.VERIFIED) {
				rawSources.put(role, proof.exactDecodedSource().get());
				locators.add(new SourceLocator(
						ctx.repository(), role, prov.snapshot().sha(), prov.path(),
						SidePresence.PRESENT, SourceBasis.FILE_CONTENT,
						Optional.empty(), Optional.empty(), Optional.empty(), List.of()));
			} else if (cc.status() == ContentCheckStatus.NO_SOURCE && !requireFullContent) {
				locators.add(new SourceLocator(
						ctx.repository(), role, prov.snapshot().sha(), prov.path(),
						SidePresence.PRESENT, SourceBasis.FILE_IDENTITY,
						Optional.empty(), Optional.empty(), Optional.empty(), List.of()));
			} else {
				failures.add(cc.failureReason().get());
				locators.add(new SourceLocator(
						ctx.repository(), role, prov.snapshot().sha(), prov.path(),
						SidePresence.PRESENT, SourceBasis.FILE_IDENTITY,
						Optional.empty(), Optional.empty(), Optional.empty(), List.of()));
			}
		}

		return new SideCheckResult(locators, failures, rawSources);
	}

	private static ContentCheck verifyPresentContent(SnapshotProof proof, Optional<SideContextFacts> sideContext) {
		if (proof.exactDecodedSource().isEmpty()) {
			return new ContentCheck(ContentCheckStatus.NO_SOURCE, Optional.of(EvidenceReason.CONTENT_UNAVAILABLE));
		}
		if (sideContext.isEmpty()) {
			return new ContentCheck(ContentCheckStatus.NO_M2_HASH, Optional.of(EvidenceReason.CONTENT_UNAVAILABLE));
		}
		String expectedSha = sideContext.get().rawFacts().rawSha256();
		String actualSha = sha256LowerHex(proof.exactDecodedSource().get());
		if (!expectedSha.equals(actualSha)) {
			return new ContentCheck(ContentCheckStatus.MISMATCH, Optional.of(EvidenceReason.CONTENT_MISMATCH));
		}
		return new ContentCheck(ContentCheckStatus.VERIFIED, Optional.empty());
	}

	private static FacetRefCheckResult verifyFacetRefs(
			String rawSource,
			LineIndex lineIndex,
			List<ProvenanceRef> refs) {
		if (refs == null || refs.isEmpty()) {
			return new FacetRefCheckResult(List.of(), List.of(EvidenceReason.RANGE_UNAVAILABLE));
		}
		var validRanges = new ArrayList<SourceRange>(refs.size());
		var failureReasons = new LinkedHashSet<EvidenceReason>();
		for (var ref : refs) {
			if (ref.reference().isEmpty()) {
				failureReasons.add(EvidenceReason.RANGE_UNAVAILABLE);
				continue;
			}
			SourceReference sr = ref.reference().get();
			Optional<EvidenceReason> err = checkRange(rawSource, lineIndex, sr.range(), Optional.of(sr.source()));
			if (err.isPresent()) {
				failureReasons.add(err.get());
			} else {
				validRanges.add(sr.range());
			}
		}
		return new FacetRefCheckResult(dedupeAndSortRanges(validRanges), List.copyOf(failureReasons));
	}

	private static Optional<EvidenceReason> checkRange(
			String rawSource,
			LineIndex lineIndex,
			SourceRange range,
			Optional<String> expectedSlice) {
		if (lineIndex.hasStandaloneCr()) {
			return Optional.of(EvidenceReason.RANGE_UNAVAILABLE);
		}
		List<LineSpan> lines = lineIndex.lines();
		if (range.startLine() < 1 || range.endLine() > lines.size() || range.startLine() > range.endLine()) {
			return Optional.of(EvidenceReason.RANGE_MISMATCH);
		}
		LineSpan startSpan = lines.get(range.startLine() - 1);
		LineSpan endSpan = lines.get(range.endLine() - 1);
		int startLineLen = startSpan.end - startSpan.start;
		int endLineLen = endSpan.end - endSpan.start;
		if (range.startColumn() < 1 || range.startColumn() > startLineLen
				|| range.endColumn() < 1 || range.endColumn() > endLineLen) {
			return Optional.of(EvidenceReason.RANGE_MISMATCH);
		}
		if (hasUnsupportedPrefix(rawSource, startSpan, range.startColumn())
				|| hasUnsupportedPrefix(rawSource, endSpan, range.endColumn())) {
			return Optional.of(EvidenceReason.RANGE_UNAVAILABLE);
		}
		int startOffset = startSpan.start + (range.startColumn() - 1);
		int endOffsetExclusive = endSpan.start + range.endColumn();
		if (startOffset > endOffsetExclusive || endOffsetExclusive > rawSource.length()) {
			return Optional.of(EvidenceReason.RANGE_MISMATCH);
		}
		if (expectedSlice.isPresent()) {
			String actual = rawSource.substring(startOffset, endOffsetExclusive);
			if (!actual.equals(expectedSlice.get())) {
				return Optional.of(EvidenceReason.RANGE_MISMATCH);
			}
		}
		return Optional.empty();
	}

	private static boolean hasUnsupportedPrefix(String rawSource, LineSpan span, int oneBasedCol) {
		int lineLen = span.end - span.start;
		int limit = Math.min(oneBasedCol, lineLen);
		for (int i = 0; i < limit; i++) {
			char ch = rawSource.charAt(span.start + i);
			if (ch == '\t' || Character.isSurrogate(ch)) {
				return true;
			}
			if (ch == '\\' && isJavaUnicodeEscapeStart(rawSource, span.start, span.end, i)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isJavaUnicodeEscapeStart(String rawSource, int lineStart, int lineEnd, int idxInLine) {
		int backslashCount = 0;
		for (int k = idxInLine - 1; k >= 0 && rawSource.charAt(lineStart + k) == '\\'; k--) {
			backslashCount++;
		}
		if ((backslashCount & 1) != 0) {
			return false;
		}
		int pos = idxInLine + 1;
		int lineLen = lineEnd - lineStart;
		if (pos >= lineLen || rawSource.charAt(lineStart + pos) != 'u') {
			return false;
		}
		while (pos < lineLen && rawSource.charAt(lineStart + pos) == 'u') {
			pos++;
		}
		if (pos + 4 > lineLen) {
			return false;
		}
		for (int d = 0; d < 4; d++) {
			if (!isHexDigit(rawSource.charAt(lineStart + pos + d))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isHexDigit(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	// ---- Redaction, Sorting & Utilities ----

	private static WitnessEvidence redactWitness(WitnessEvidence evidence) {
		var sortedLocators = dedupeAndSortLocators(evidence.sourceLocators());
		var sortedReasons = dedupeAndSortReasons(evidence.reasons());
		if (evidence.status() == WitnessStatus.VERIFIED) {
			return new WitnessEvidence(
					evidence.witnessId(), WitnessStatus.VERIFIED, sortedReasons, sortedLocators,
					evidence.documentRef(), evidence.negativeBasis(), evidence.semanticStatus());
		}
		boolean hasTrace = !sortedLocators.isEmpty() || evidence.documentRef().isPresent();
		if (hasTrace) {
			return new WitnessEvidence(
					evidence.witnessId(), WitnessStatus.UNVERIFIED, sortedReasons, sortedLocators,
					evidence.documentRef(), Optional.empty(), Optional.empty());
		}
		return new WitnessEvidence(
				"", WitnessStatus.UNVERIFIED, sortedReasons, List.of(),
				Optional.empty(), Optional.empty(), Optional.empty());
	}

	private static List<WitnessEvidence> sortAndDedupeWitnesses(List<WitnessEvidence> witnesses) {
		var sorted = new ArrayList<>(new LinkedHashSet<>(witnesses));
		sorted.sort(WITNESS_COMPARATOR);
		return List.copyOf(sorted);
	}

	private static List<EvidenceReason> dedupeAndSortReasons(List<EvidenceReason> reasons) {
		if (reasons.isEmpty()) {
			return List.of();
		}
		var list = new ArrayList<>(new LinkedHashSet<>(reasons));
		list.sort(Comparator.comparing(Enum::name));
		return List.copyOf(list);
	}

	private static List<SourceRange> dedupeAndSortRanges(List<SourceRange> ranges) {
		if (ranges.isEmpty()) {
			return List.of();
		}
		var list = new ArrayList<>(new LinkedHashSet<>(ranges));
		list.sort(RANGE_COMPARATOR);
		return List.copyOf(list);
	}

	private static List<SourceLocator> dedupeAndSortLocators(List<SourceLocator> locators) {
		if (locators.isEmpty()) {
			return List.of();
		}
		var normalized = new ArrayList<SourceLocator>(locators.size());
		for (var loc : locators) {
			normalized.add(new SourceLocator(
					loc.repository(), loc.sideRole(), loc.pinnedSha(), loc.path(),
					loc.filePresence(), loc.basis(), loc.symbolKey(), loc.facetKind(),
					loc.contextChangeKind(), dedupeAndSortRanges(loc.ranges())));
		}
		var deduped = new ArrayList<>(new LinkedHashSet<>(normalized));
		deduped.sort(LOCATOR_COMPARATOR);
		return List.copyOf(deduped);
	}

	private static WitnessVerification unverifiedWitness(String witnessId, EvidenceReason... reasons) {
		return new WitnessVerification(new WitnessEvidence(
				witnessId, WitnessStatus.UNVERIFIED, List.of(reasons),
				List.of(), Optional.empty(), Optional.empty(), Optional.empty()));
	}

	private static WitnessVerification unverifiedWithTrace(
			String witnessId,
			EvidenceReason reason,
			List<SourceLocator> traceLocators) {
		return new WitnessVerification(new WitnessEvidence(
				witnessId, WitnessStatus.UNVERIFIED, List.of(reason),
				traceLocators, Optional.empty(), Optional.empty(), Optional.empty()));
	}

	private static WitnessVerification verifiedSourceWitness(String witnessId, List<SourceLocator> locators) {
		return new WitnessVerification(new WitnessEvidence(
				witnessId, WitnessStatus.VERIFIED, List.of(),
				locators, Optional.empty(), Optional.empty(), Optional.empty()));
	}

	private static SourceLocator fileLocator(
			AssessmentContext ctx,
			SourceEvidenceSelector selector,
			SidePresence presence,
			SourceBasis basis) {
		return new SourceLocator(
				ctx.repository(), selector.sideRole(), selector.pinnedSha(), selector.path(),
				presence, basis, Optional.empty(), Optional.empty(), Optional.empty(), List.of());
	}

	private static Optional<EntitySide> findEntitySide(StructuralDiffResult diff, SideRole role, SymbolKey key) {
		if (role == SideRole.TARGET) {
			return diff.initialInventory()
					.flatMap(inv -> inv.entities().stream().filter(e -> e.key().equals(key)).findFirst());
		}
		for (var fact : diff.entityFacts()) {
			Optional<EntitySide> side = role == SideRole.BEFORE ? fact.before() : fact.after();
			if (side.isPresent() && side.get().key().equals(key)) {
				return side;
			}
		}
		return Optional.empty();
	}

	private static boolean isProvenAbsentInPresentSide(StructuralDiffResult diff, SideRole role, SymbolKey key) {
		boolean hasMatchingFact = false;
		for (var fact : diff.entityFacts()) {
			if (role == SideRole.BEFORE) {
				if (fact.before().map(s -> s.key().equals(key)).orElse(false)) {
					return false;
				}
				if (fact.change() == ChangeKind.ADDED
						&& fact.after().map(s -> s.key().equals(key)).orElse(false)
						&& fact.unmatchedReason().equals(Optional.of(UnmatchedReason.NO_COUNTERPART_KEY))) {
					hasMatchingFact = true;
				}
			} else if (role == SideRole.AFTER) {
				if (fact.after().map(s -> s.key().equals(key)).orElse(false)) {
					return false;
				}
				if (fact.change() == ChangeKind.REMOVED
						&& fact.before().map(s -> s.key().equals(key)).orElse(false)
						&& fact.unmatchedReason().equals(Optional.of(UnmatchedReason.NO_COUNTERPART_KEY))) {
					hasMatchingFact = true;
				}
			}
		}
		return hasMatchingFact;
	}

	private static boolean entityMatchesSideAndKey(EntityFact fact, SideRole role, SymbolKey key) {
		Optional<EntitySide> side = role == SideRole.BEFORE ? fact.before() : fact.after();
		return side.map(s -> s.key().equals(key)).orElse(false);
	}

	private static Optional<StructuralDiffResult> findDiffForSide(
			AssessmentContext ctx,
			SideRole role,
			String sha,
			String path,
			DiffIndex diffIndex) {
		var sideId = new SourceIdentity(role, ctx.repository(), sha, path);
		if (diffIndex.conflictingSides.contains(sideId)) {
			return Optional.empty();
		}
		var set = diffIndex.diffsBySide.get(sideId);
		if (set == null || set.size() != 1) {
			return Optional.empty();
		}
		StructuralDiffResult diff = set.iterator().next();
		DiffKey key = ctx.intent() == Intent.INITIAL_ASSESSMENT
				? new TargetDiffKey(sideId)
				: new PairDiffKey(
						new SourceIdentity(SideRole.BEFORE, ctx.repository(),
								diff.beforeSnapshot().orElseThrow().snapshot().sha(),
								diff.beforeSnapshot().orElseThrow().path()),
						new SourceIdentity(SideRole.AFTER, ctx.repository(),
								diff.afterSnapshot().orElseThrow().snapshot().sha(),
								diff.afterSnapshot().orElseThrow().path()));
		if (diffIndex.conflictingKeys.contains(key)) {
			return Optional.empty();
		}
		return Optional.of(diff);
	}

	private static Set<String> diffPaths(StructuralDiffResult diff) {
		var paths = new LinkedHashSet<String>();
		diff.beforeSnapshot().map(SnapshotProvenance::path).ifPresent(paths::add);
		diff.afterSnapshot().map(SnapshotProvenance::path).ifPresent(paths::add);
		return paths;
	}

	private static Set<SymbolKey> collectDiffSymbols(StructuralDiffResult diff) {
		var keys = new LinkedHashSet<SymbolKey>();
		diff.initialInventory().ifPresent(inv -> inv.entities().forEach(e -> keys.add(e.key())));
		for (var fact : diff.entityFacts()) {
			fact.before().map(EntitySide::key).ifPresent(keys::add);
			fact.after().map(EntitySide::key).ifPresent(keys::add);
		}
		return keys;
	}

	private static boolean matchesSideOrOpposite(
			DocumentLookupKey k,
			SnapshotProvenance beforeProv,
			SnapshotProvenance afterProv) {
		if (k.sideRole() == SideRole.BEFORE) {
			return k.pinnedSha().equals(beforeProv.snapshot().sha()) && k.path().equals(beforeProv.path());
		}
		if (k.sideRole() == SideRole.AFTER) {
			return k.pinnedSha().equals(afterProv.snapshot().sha()) && k.path().equals(afterProv.path());
		}
		return false;
	}

	private static boolean isStaleOrMismatchVerdict(ClaimVerdict verdict) {
		return verdict == ClaimVerdict.STALE_CLAIM_AGAINST_TARGET
				|| verdict == ClaimVerdict.DECLARATION_INCOMPATIBLE
				|| verdict == ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN
				|| verdict == ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY;
	}

	private static boolean isPotentialOverloadOrCounterpart(SymbolKey left, SymbolKey right) {
		return left.kind() == right.kind()
				&& left.packageName().equals(right.packageName())
				&& left.ownerTypes().equals(right.ownerTypes())
				&& left.name().equals(right.name())
				&& !left.equals(right);
	}

	private static boolean isRoleValidForIntent(Intent intent, SideRole role) {
		if (intent == Intent.INITIAL_ASSESSMENT) {
			return role == SideRole.TARGET;
		}
		return role == SideRole.BEFORE || role == SideRole.AFTER;
	}

	private static String expectedShaForRole(AssessmentContext ctx, SideRole role) {
		return role == SideRole.BEFORE ? ctx.comparisonBeforeSha().orElse("") : ctx.authoritativeTargetSha();
	}

	private static boolean matchesScope(
			AssessmentContext ctx,
			String tenantId,
			String workspaceId,
			String repository,
			TargetKind targetKind,
			String targetRef) {
		return ctx.tenantId().equals(tenantId)
				&& ctx.workspaceId().equals(workspaceId)
				&& ctx.repository().equals(repository)
				&& ctx.targetKind() == targetKind
				&& ctx.targetRef().equals(targetRef);
	}

	private static int selectorPhaseOrder(WitnessSelector w) {
		return switch (w) {
			case SourceEvidenceSelector ignored -> 0;
			case DocumentWitnessSelector ignored -> 1;
			case NegativeBasisWitnessSelector ignored -> 2;
			case SemanticWitnessSelector ignored -> 3;
		};
	}

	private static int roleOrder(SideRole role) {
		return switch (role) {
			case BEFORE -> 0;
			case AFTER -> 1;
			case TARGET -> 2;
			case INPUT -> 3;
		};
	}

	private static int compareRangeLists(List<SourceRange> a, List<SourceRange> b) {
		int min = Math.min(a.size(), b.size());
		for (int i = 0; i < min; i++) {
			int cmp = RANGE_COMPARATOR.compare(a.get(i), b.get(i));
			if (cmp != 0) {
				return cmp;
			}
		}
		return Integer.compare(a.size(), b.size());
	}

	private static int compareLocatorLists(List<SourceLocator> a, List<SourceLocator> b) {
		int min = Math.min(a.size(), b.size());
		for (int i = 0; i < min; i++) {
			int cmp = LOCATOR_COMPARATOR.compare(a.get(i), b.get(i));
			if (cmp != 0) {
				return cmp;
			}
		}
		return Integer.compare(a.size(), b.size());
	}

	private static String docRefSortKey(DocumentEvidenceRef ref) {
		return ref.documentId() + "|" + ref.documentVersion() + "|" + ref.documentBoundSha()
				+ "|" + ref.evaluatedTargetSha() + "|" + ref.bindingId()
				+ "|" + ref.claimId().orElse("") + "|" + ref.sectionRef().orElse("")
				+ "|" + ref.verdict().map(Enum::name).orElse("");
	}

	private static String negBasisSortKey(NegativeBasisEvidence neg) {
		var sb = new StringBuilder();
		sb.append(neg.kind().name()).append('|').append(neg.provenanceRefId().orElse(""));
		for (var k : neg.verifiedEmptyLookupKeys()) {
			sb.append('|').append(k.sideRole().name()).append(':').append(k.pinnedSha())
					.append(':').append(k.path()).append(':').append(k.granularity().name())
					.append(':').append(k.symbolKey().map(JavaStructuralRepresentation::canonicalSymbolKey).orElse(""));
		}
		return sb.toString();
	}

	private static String sha256LowerHex(String content) {
		return HexFormat.of().formatHex(sha256Digest().digest(content.getBytes(StandardCharsets.UTF_8)));
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	// ---- Internal Index & Helper Records ----

	private record SourceIdentity(SideRole role, String repository, String sha, String path) {
	}

	private sealed interface DiffKey permits TargetDiffKey, PairDiffKey {
	}

	private record TargetDiffKey(SourceIdentity target) implements DiffKey {
	}

	private record PairDiffKey(SourceIdentity before, SourceIdentity after) implements DiffKey {
	}

	private record DiffIndex(
			Map<DiffKey, StructuralDiffResult> byKey,
			Set<DiffKey> conflictingKeys,
			Map<SourceIdentity, Set<StructuralDiffResult>> diffsBySide,
			Set<SourceIdentity> conflictingSides,
			List<StructuralDiffResult> inputLevelDiffs,
			int rawCount) {
	}

	private record DiffResolution(
			Optional<StructuralDiffResult> diff,
			Optional<EvidenceReason> errorReason,
			boolean manifestOnly) {
	}

	private record SnapshotIndex(Map<SourceIdentity, SnapshotProof> byId, Set<SourceIdentity> conflicts) {
	}

	private record BindingIdentityKey(String documentId, String documentVersion, String bindingId) {
	}

	private record ClaimIdentityKey(String documentId, String claimId) {
	}

	private record ClaimOwnership(String documentVersion, String documentBoundSha, String bindingId, DocumentClaim claim) {
	}

	private record NormalizedBinding(
			String tenantId, String workspaceId, String repository,
			String documentId, String documentVersion, String documentBoundSha,
			String bindingId, BindingGranularity granularity, String boundPath,
			Optional<SymbolKey> boundSymbolKey, Optional<String> sectionRef,
			Set<DocumentClaim> claims) {
		static NormalizedBinding of(DocumentBinding b) {
			return new NormalizedBinding(
					b.tenantId(), b.workspaceId(), b.repository(),
					b.documentId(), b.documentVersion(), b.documentBoundSha(),
					b.bindingId(), b.granularity(), b.boundPath(),
					b.boundSymbolKey(), b.sectionRef(), Set.copyOf(b.claims()));
		}
	}

	private record IndexedLookup(
			DocumentLookupKey key,
			LookupCompleteness completeness,
			List<DocumentBinding> localBindings,
			Set<NormalizedBinding> normalizedBindings,
			boolean hasForeignOrMalformedBinding,
			Optional<String> diagnosticReason) {
		boolean equalsForEquivalence(IndexedLookup other) {
			return key.equals(other.key)
					&& completeness == other.completeness
					&& normalizedBindings.equals(other.normalizedBindings)
					&& hasForeignOrMalformedBinding == other.hasForeignOrMalformedBinding
					&& diagnosticReason.equals(other.diagnosticReason);
		}
	}

	private record LookupIndex(
			Map<DocumentLookupKey, IndexedLookup> byKey,
			Set<DocumentLookupKey> conflictingKeys,
			Set<BindingIdentityKey> conflictingBindings,
			Set<ClaimIdentityKey> conflictingClaims) {
	}

	private record NormalizedSemantic(
			String tenantId, String workspaceId, String repository,
			TargetKind targetKind, String targetRef, String jobId, String attemptId,
			String evaluatedTargetSha, String path, SymbolKey symbolKey,
			String documentId, String documentVersion, String documentBoundSha,
			SemanticStatus status, boolean withinDeadline,
			Set<GroundedSourceFacetRef> sourceRefs,
			Set<GroundedDocumentClaimRef> documentRefs,
			Optional<Double> confidence, Optional<String> rationale,
			Optional<ImpactClassifier.VerifiedImpactContext> impactContext) {
		static NormalizedSemantic of(SemanticAssessmentInput s) {
			return new NormalizedSemantic(
					s.tenantId(), s.workspaceId(), s.repository(),
					s.targetKind(), s.targetRef(), s.jobId(), s.attemptId(),
					s.evaluatedTargetSha(), s.path(), s.symbolKey(),
					s.documentId(), s.documentVersion(), s.documentBoundSha(),
					s.status(), s.withinDeadline(),
					Set.copyOf(s.groundedSourceRefs()), Set.copyOf(s.groundedDocumentRefs()),
					s.confidence(), s.rationale(), s.impactContext());
		}
	}

	private record SemanticIndex(List<SemanticAssessmentInput> inputs) {
	}

	private record ManifestIndex(Map<String, ScopeManifestRef> byId, Set<String> conflicts) {
	}

	private record ExclusionIndex(Map<String, CallerExclusion> byId, Set<String> conflicts) {
	}

	private record ExportIndex(Map<String, ControlledExportUnit> byId, Set<String> conflicts) {
	}

	private record AssessmentIndices(
			AssessmentContext ctx,
			DiffIndex diffIndex,
			SnapshotIndex snapshotIndex,
			LookupIndex lookupIndex,
			SemanticIndex semanticIndex,
			ManifestIndex manifestIndex,
			ExclusionIndex exclusionIndex,
			ExportIndex exportIndex) {
	}

	private record WitnessCacheKey(String findingId, String witnessId) {
	}

	private record WitnessVerification(WitnessEvidence evidence) {
	}

	private record SnapshotAuthorityCheck(
			Optional<SnapshotProof> proof,
			Optional<EvidenceReason> failureReason,
			Optional<SourceLocator> traceLocator) {
	}

	private record SideCheckResult(
			List<SourceLocator> locators,
			List<EvidenceReason> failureReasons,
			Map<SideRole, String> rawSources) {
	}

	private enum ContentCheckStatus {
		VERIFIED, NO_SOURCE, NO_M2_HASH, MISMATCH
	}

	private record ContentCheck(ContentCheckStatus status, Optional<EvidenceReason> failureReason) {
	}

	private record FacetRefCheckResult(List<SourceRange> validRanges, List<EvidenceReason> failureReasons) {
	}

	private record LineSpan(int start, int end) {
	}

	private record LineIndex(List<LineSpan> lines, boolean hasStandaloneCr) {
		static LineIndex build(String raw) {
			if (raw.isEmpty()) {
				return new LineIndex(List.of(), false);
			}
			var spans = new ArrayList<LineSpan>();
			int len = raw.length();
			int lineStart = 0;
			int i = 0;
			while (i < len) {
				char ch = raw.charAt(i);
				if (ch == '\r') {
					if (i + 1 < len && raw.charAt(i + 1) == '\n') {
						spans.add(new LineSpan(lineStart, i));
						i += 2;
						lineStart = i;
					} else {
						return new LineIndex(List.of(), true);
					}
				} else if (ch == '\n') {
					spans.add(new LineSpan(lineStart, i));
					i++;
					lineStart = i;
				} else {
					i++;
				}
			}
			if (lineStart < len) {
				spans.add(new LineSpan(lineStart, len));
			}
			return new LineIndex(spans, false);
		}
	}

	// ---- Public API Enums & Records ----

	public enum EvidenceStatus {
		VERIFIED, PARTIAL, REJECTED
	}

	public enum WitnessStatus {
		VERIFIED, UNVERIFIED
	}

	public enum SourceBasis {
		FILE_IDENTITY,
		FILE_CONTENT,
		FILE_ABSENT,
		SNAPSHOT_UNAVAILABLE,
		SYMBOL_PRESENT,
		SYMBOL_ABSENT,
		FACET_PRESENT,
		FACET_ABSENT,
		CONTEXT_PRESENT,
		CONTEXT_EMPTY,
		TARGET_INVENTORY_EMPTY,
		DIAGNOSTIC_RANGE
	}

	public enum EvidenceReason {
		INVALID_INPUT,
		SCOPE_DENIED,
		CONFLICTING_PROOF,
		MISSING_DIFF,
		INVALID_SELECTOR,
		MISSING_SNAPSHOT,
		SNAPSHOT_MISMATCH,
		SNAPSHOT_UNAVAILABLE,
		CONTENT_UNAVAILABLE,
		CONTENT_MISMATCH,
		INSUFFICIENT_COVERAGE,
		SOURCE_BASIS_UNVERIFIED,
		RANGE_UNAVAILABLE,
		RANGE_MISMATCH,
		LOOKUP_UNVERIFIED,
		DOCUMENT_UNVERIFIED,
		SEMANTIC_UNVERIFIED,
		PROVENANCE_UNVERIFIED
	}

	public record EvidenceInput(
			Optional<AssessmentContext> authorizedContext,
			ImpactReport proposedReport,
			List<StructuralDiffResult> diffResults,
			List<SnapshotProof> snapshotProofs,
			List<DocumentLookupResponse> lookupResponses,
			List<SemanticAssessmentInput> semanticAssessments,
			List<ScopeManifestRef> scopeManifests,
			List<CallerExclusion> callerExclusions,
			List<ControlledExportUnit> controlledExports) {

		public EvidenceInput {
			Objects.requireNonNull(authorizedContext, "authorizedContext");
			Objects.requireNonNull(proposedReport, "proposedReport");
			diffResults = List.copyOf(Objects.requireNonNull(diffResults, "diffResults"));
			snapshotProofs = List.copyOf(Objects.requireNonNull(snapshotProofs, "snapshotProofs"));
			lookupResponses = List.copyOf(Objects.requireNonNull(lookupResponses, "lookupResponses"));
			semanticAssessments = List.copyOf(Objects.requireNonNull(semanticAssessments, "semanticAssessments"));
			scopeManifests = List.copyOf(Objects.requireNonNull(scopeManifests, "scopeManifests"));
			callerExclusions = List.copyOf(Objects.requireNonNull(callerExclusions, "callerExclusions"));
			controlledExports = List.copyOf(Objects.requireNonNull(controlledExports, "controlledExports"));
		}

		@Override
		public String toString() {
			return "EvidenceInput[redacted]";
		}
	}

	public record SnapshotProof(
			SideRole sideRole,
			SnapshotProvenance provenance,
			Optional<String> exactDecodedSource) {

		public SnapshotProof {
			Objects.requireNonNull(sideRole, "sideRole");
			Objects.requireNonNull(provenance, "provenance");
			Objects.requireNonNull(exactDecodedSource, "exactDecodedSource");
		}

		@Override
		public String toString() {
			return "SnapshotProof[redacted]";
		}
	}

	public record EvidenceResult(
			EvidenceStatus status,
			Optional<ReportProcessingStatus> processingStatus,
			Optional<ImpactOutcome> overallOutcome,
			Optional<Coverage> coverage,
			List<FindingEvidence> findings,
			List<SafeDiagnostic> diagnostics) {

		public EvidenceResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(processingStatus, "processingStatus");
			Objects.requireNonNull(overallOutcome, "overallOutcome");
			Objects.requireNonNull(coverage, "coverage");
			findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
			diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
		}
	}

	public record Coverage(
			int evaluatedFileCount,
			int excludedFileCount,
			int failedOrIncompleteFileCount,
			boolean completeCoverage) {
	}

	public record FindingEvidence(
			String findingId,
			UnitDisposition disposition,
			ImpactOutcome outcome,
			Optional<DriftClassification> classification,
			SeverityStatus severityStatus,
			Optional<Severity> severity,
			Optional<Double> semanticConfidence,
			IssueAdmission admission,
			ReasonCode primaryReason,
			ReasonCode admissionReason,
			EvidenceStatus status,
			List<WitnessEvidence> witnesses) {

		public FindingEvidence {
			Objects.requireNonNull(findingId, "findingId");
			Objects.requireNonNull(disposition, "disposition");
			Objects.requireNonNull(outcome, "outcome");
			Objects.requireNonNull(classification, "classification");
			Objects.requireNonNull(severityStatus, "severityStatus");
			Objects.requireNonNull(severity, "severity");
			Objects.requireNonNull(semanticConfidence, "semanticConfidence");
			Objects.requireNonNull(admission, "admission");
			Objects.requireNonNull(primaryReason, "primaryReason");
			Objects.requireNonNull(admissionReason, "admissionReason");
			Objects.requireNonNull(status, "status");
			witnesses = List.copyOf(Objects.requireNonNull(witnesses, "witnesses"));
		}
	}

	public record WitnessEvidence(
			String witnessId,
			WitnessStatus status,
			List<EvidenceReason> reasons,
			List<SourceLocator> sourceLocators,
			Optional<DocumentEvidenceRef> documentRef,
			Optional<NegativeBasisEvidence> negativeBasis,
			Optional<SemanticStatus> semanticStatus) {

		public WitnessEvidence {
			Objects.requireNonNull(witnessId, "witnessId");
			Objects.requireNonNull(status, "status");
			reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
			sourceLocators = List.copyOf(Objects.requireNonNull(sourceLocators, "sourceLocators"));
			Objects.requireNonNull(documentRef, "documentRef");
			Objects.requireNonNull(negativeBasis, "negativeBasis");
			Objects.requireNonNull(semanticStatus, "semanticStatus");
		}
	}

	public record SourceLocator(
			String repository,
			SideRole sideRole,
			String pinnedSha,
			String path,
			SidePresence filePresence,
			SourceBasis basis,
			Optional<SymbolKey> symbolKey,
			Optional<FacetKind> facetKind,
			Optional<ContextChangeKind> contextChangeKind,
			List<SourceRange> ranges) {

		public SourceLocator {
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(sideRole, "sideRole");
			Objects.requireNonNull(pinnedSha, "pinnedSha");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(filePresence, "filePresence");
			Objects.requireNonNull(basis, "basis");
			Objects.requireNonNull(symbolKey, "symbolKey");
			Objects.requireNonNull(facetKind, "facetKind");
			Objects.requireNonNull(contextChangeKind, "contextChangeKind");
			ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
		}
	}

	public record DocumentEvidenceRef(
			String documentId,
			String documentVersion,
			String documentBoundSha,
			String evaluatedTargetSha,
			String bindingId,
			Optional<String> claimId,
			Optional<String> sectionRef,
			Optional<ClaimVerdict> verdict) {

		public DocumentEvidenceRef {
			Objects.requireNonNull(documentId, "documentId");
			Objects.requireNonNull(documentVersion, "documentVersion");
			Objects.requireNonNull(documentBoundSha, "documentBoundSha");
			Objects.requireNonNull(evaluatedTargetSha, "evaluatedTargetSha");
			Objects.requireNonNull(bindingId, "bindingId");
			Objects.requireNonNull(claimId, "claimId");
			Objects.requireNonNull(sectionRef, "sectionRef");
			Objects.requireNonNull(verdict, "verdict");
		}
	}

	public record NegativeBasisEvidence(
			NegativeBasisKind kind,
			List<DocumentLookupKey> verifiedEmptyLookupKeys,
			Optional<String> provenanceRefId) {

		public NegativeBasisEvidence {
			Objects.requireNonNull(kind, "kind");
			verifiedEmptyLookupKeys = List.copyOf(Objects.requireNonNull(verifiedEmptyLookupKeys, "verifiedEmptyLookupKeys"));
			Objects.requireNonNull(provenanceRefId, "provenanceRefId");
		}
	}

	public record SafeDiagnostic(
			Optional<DiagnosticCode> diffCode,
			Optional<ReasonCode> impactCode,
			Optional<EvidenceReason> evidenceReason,
			Optional<SourceLocator> location) {

		public SafeDiagnostic {
			Objects.requireNonNull(diffCode, "diffCode");
			Objects.requireNonNull(impactCode, "impactCode");
			Objects.requireNonNull(evidenceReason, "evidenceReason");
			Objects.requireNonNull(location, "location");
		}
	}
}
