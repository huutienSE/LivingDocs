package com.livingdocs.analysis.impact;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.livingdocs.analysis.ast.JavaParseResult.SourceRange;
import com.livingdocs.analysis.ast.JavaParseResult.SymbolKey;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FacetKind;
import com.livingdocs.analysis.diff.StructuralDiff.ChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.ContextChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.DiagnosticCode;
import com.livingdocs.analysis.diff.StructuralDiff.EntityFact;
import com.livingdocs.analysis.diff.StructuralDiff.EntitySide;
import com.livingdocs.analysis.diff.StructuralDiff.PathRelation;
import com.livingdocs.analysis.diff.StructuralDiff.PresenceRelation;
import com.livingdocs.analysis.diff.StructuralDiff.ProcessingStatus;
import com.livingdocs.analysis.diff.StructuralDiff.RawChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.SidePresence;
import com.livingdocs.analysis.diff.StructuralDiff.SideRole;
import com.livingdocs.analysis.diff.StructuralDiff.SnapshotProvenance;
import com.livingdocs.analysis.diff.StructuralDiff.StructuralDiffResult;
import com.livingdocs.analysis.diff.StructuralDiff.StructuralEquality;
import com.livingdocs.analysis.diff.StructuralDiffInput.Intent;

/**
 * Deterministic impact classifier for M3: consumes M2 structural diff facts,
 * authenticated caller scope, S04 document lookup/claim fixtures, and optional
 * S05 semantic assessment fixtures to produce an immutable {@link ImpactReport}
 * with typed {@link WitnessSelector}s for M4 evidence grounding.
 */
public final class ImpactClassifier {

	public static final String CLASSIFIER_VERSION = "m3-impact-v1";

	private static final Comparator<DocumentLookupKey> LOOKUP_KEY_COMPARATOR = Comparator
			.comparing(DocumentLookupKey::tenantId)
			.thenComparing(DocumentLookupKey::workspaceId)
			.thenComparing(DocumentLookupKey::repository)
			.thenComparing(key -> key.targetKind().name())
			.thenComparing(DocumentLookupKey::targetRef)
			.thenComparing(key -> key.sideRole().name())
			.thenComparing(DocumentLookupKey::pinnedSha)
			.thenComparing(DocumentLookupKey::path)
			.thenComparing(key -> key.granularity().name())
			.thenComparing(key -> key.symbolKey()
					.map(JavaStructuralRepresentation::canonicalSymbolKey)
					.orElse(""));

	private static final Comparator<WitnessSelector> WITNESS_COMPARATOR = Comparator
			.comparing(WitnessSelector::witnessId);

	private static final Comparator<ImpactFinding> FINDING_COMPARATOR = Comparator
			.comparing((ImpactFinding f) -> f.disposition().name())
			.thenComparing(f -> f.beforePath().orElse(""))
			.thenComparing(f -> f.afterPath().orElse(""))
			.thenComparing(f -> f.symbolKey()
					.map(JavaStructuralRepresentation::canonicalSymbolKey)
					.orElse(""))
			.thenComparing(f -> f.evaluatedFacets().stream()
					.map(FacetKind::name)
					.sorted()
					.reduce((a, b) -> a + "," + b)
					.orElse(""))
			.thenComparing(f -> f.documentId().orElse(""))
			.thenComparing(f -> f.documentVersion().orElse(""))
			.thenComparing(f -> f.bindingId().orElse(""))
			.thenComparing(f -> f.claimId().orElse(""))
			.thenComparing(f -> f.primaryReason().name())
			.thenComparing(ImpactFinding::findingId);

	private static final Comparator<ImpactDiagnostic> DIAGNOSTIC_COMPARATOR = Comparator
			.comparing((ImpactDiagnostic d) -> d.severity().name())
			.thenComparing(d -> d.code().name())
			.thenComparing(ImpactDiagnostic::message);

	/**
	 * Derives the exact deterministic set of S04 lookup keys (both old and new paths/symbols)
	 * required to evaluate {@code diffResults} under {@code context}.
	 */
	public static List<DocumentLookupKey> buildLookupKeys(
			AssessmentContext context,
			List<StructuralDiffResult> diffResults) {
		if (context == null || diffResults == null || !validateContext(context).isEmpty()) {
			return List.of();
		}
		if (context.declaredScopeCompleteness() == ScopeCompleteness.DENIED) {
			return List.of();
		}
		var keys = new LinkedHashSet<DocumentLookupKey>();
		for (var result : diffResults) {
			if (result == null || !validateDiffMatchesContext(context, result).isEmpty()) {
				continue;
			}
			if (result.status() == ProcessingStatus.OUT_OF_SCOPE || result.status() == ProcessingStatus.FAILED) {
				continue;
			}
			if (result.intent() == Intent.INITIAL_ASSESSMENT) {
				if (result.initialInventory().isPresent()) {
					var inv = result.initialInventory().orElseThrow();
					keys.add(fileLookupKey(context, SideRole.TARGET, inv.snapshot().sha(), inv.path()));
					for (var entity : inv.entities()) {
						keys.add(symbolLookupKey(context, SideRole.TARGET,
								entity.snapshot().sha(), entity.path(), entity.key()));
					}
				}
				continue;
			}

			boolean includeUnchangedSymbols = collectFileLevelChangeAxes(context, result).stream()
					.anyMatch(axis -> axis.requiresSymbolLookup(ChangeKind.UNCHANGED));
			keys.addAll(revisionLookupKeys(context, result,
					change -> change != ChangeKind.UNCHANGED || includeUnchangedSymbols));
		}
		var sorted = new ArrayList<>(keys);
		sorted.sort(LOOKUP_KEY_COMPARATOR);
		return List.copyOf(sorted);
	}

	private static Set<DocumentLookupKey> revisionLookupKeys(
			AssessmentContext context, StructuralDiffResult result, Predicate<ChangeKind> includeSymbol) {
		var keys = new LinkedHashSet<DocumentLookupKey>();
		result.beforeSnapshot().ifPresent(snapshot ->
				addRevisionLookupKeys(context, result, snapshot, SideRole.BEFORE, includeSymbol, keys));
		result.afterSnapshot().ifPresent(snapshot ->
				addRevisionLookupKeys(context, result, snapshot, SideRole.AFTER, includeSymbol, keys));
		return keys;
	}

	private static void addRevisionLookupKeys(
			AssessmentContext context, StructuralDiffResult result, SnapshotProvenance snapshot, SideRole role,
			Predicate<ChangeKind> includeSymbol, Set<DocumentLookupKey> keys) {
		if (snapshot.presence() != SidePresence.PRESENT || !isJavaPath(snapshot.path())) {
			return;
		}
		String sha = snapshot.snapshot().sha();
		keys.add(fileLookupKey(context, role, sha, snapshot.path()));
		for (var fact : result.entityFacts()) {
			if (!includeSymbol.test(fact.change())) {
				continue;
			}
			SymbolKey key = fact.before().map(EntitySide::key).orElseGet(() -> fact.after().orElseThrow().key());
			Optional<EntitySide> entitySide = role == SideRole.BEFORE ? fact.before() : fact.after();
			String path = entitySide.map(EntitySide::path).orElse(snapshot.path());
			keys.add(symbolLookupKey(context, role, sha, path, key));
		}
	}

	/**
	 * Deterministically classifies impact across all supplied diff results using
	 * declared scope, S04 document lookup/claim fixtures, S05 semantic fixtures,
	 * controlled export provenance, and caller exclusions.
	 */
	public ImpactReport classify(ImpactClassificationInput input) {
		if (input == null || input.context() == null) {
			var fallbackContext = new AssessmentContext(
					"unknown", "unknown", "unknown",
					TargetKind.TRACKED_BRANCH, "unknown", "unknown", "unknown",
					Optional.empty(), "unknown", Optional.empty(),
					Intent.REVISION_COMPARISON, ScopeCompleteness.EMPTY_UNVERIFIED, false, Optional.empty());
			return failedReport(fallbackContext,
					List.of(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.INVALID_ASSESSMENT_INPUT,
							"Impact classification input and context are required")),
					List.of("Assessment input or context missing"));
		}

		AssessmentContext context = input.context();
		List<ImpactDiagnostic> contextErrors = validateContext(context);
		if (!contextErrors.isEmpty()) {
			return failedReport(context, contextErrors, List.of("Assessment context validation failed"));
		}
		if (context.declaredScopeCompleteness() == ScopeCompleteness.DENIED) {
			return failedReport(context,
					List.of(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.SCOPE_DENIED,
							"Caller scope authorization denied at root assessment boundary")),
					List.of("Assessment scope authorization denied"));
		}
		if (context.declaredScopeCompleteness() == ScopeCompleteness.EMPTY_VERIFIED && !input.diffResults().isEmpty()) {
			return failedReport(context,
					List.of(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
							"EMPTY_VERIFIED declared scope cannot contain diff results")),
					List.of("Contradictory non-empty diff results supplied for EMPTY_VERIFIED scope"));
		}

		var diagnostics = new LinkedHashSet<ImpactDiagnostic>();
		var coverageGaps = new LinkedHashSet<String>();

		if (context.declaredScopeCompleteness() == ScopeCompleteness.PARTIAL) {
			coverageGaps.add("Declared evaluation scope completeness is PARTIAL");
		} else if (context.declaredScopeCompleteness() == ScopeCompleteness.EMPTY_UNVERIFIED) {
			coverageGaps.add("Declared scope is empty without verified completeness manifest");
		}

		// Validate all diffResults match context (fail closed on contradictions).
		for (var result : input.diffResults()) {
			if (result == null) {
				diagnostics.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.INVALID_ASSESSMENT_INPUT,
						"Diff result entry cannot be null"));
				return failedReport(context, List.copyOf(diagnostics),
						List.of("Null diff result encountered in assessment input"));
			}
			var diffMismatchErrors = validateDiffMatchesContext(context, result);
			if (!diffMismatchErrors.isEmpty()) {
				diagnostics.addAll(diffMismatchErrors);
				return failedReport(context, List.copyOf(diagnostics),
						List.of("Diff result contradicts assessment context"));
			}
		}

		// Index and sanitize S04 lookups (reject foreign scope without leaking foreign IDs, detect conflicts).
		var lookupIndex = indexLookups(context, input.lookupResponses(), diagnostics, coverageGaps);
		// Index and sanitize S05 semantic inputs.
		var semanticIndex = indexSemantics(context, input.semanticAssessments(), diagnostics);

		var rawFindings = new ArrayList<ImpactFinding>();
		int evaluatedFileCount = 0;
		int excludedFileCount = 0;
		int failedOrIncompleteFileCount = 0;
		boolean hadAnyEvaluableUnit = false;
		boolean allAttemptedUnitsBlocked = !input.diffResults().isEmpty();

		for (var result : input.diffResults()) {
			Optional<CallerExclusion> callerExclusion = findMatchingCallerExclusion(
					context, result, input.callerExclusions());
			if (callerExclusion.isPresent()) {
				var excl = callerExclusion.get();
				excludedFileCount++;
				rawFindings.add(buildCallerExcludedFinding(context, result, excl));
				allAttemptedUnitsBlocked = false;
				continue;
			}

			Optional<ControlledExportUnit> fileControlledExport = findVerifiedWholeFileControlledExport(
					context, result, input.controlledExports(), diagnostics);
			if (fileControlledExport.isPresent()) {
				var exp = fileControlledExport.get();
				excludedFileCount++;
				rawFindings.add(buildControlledExportExcludedFinding(context, result, Optional.empty(), exp));
				allAttemptedUnitsBlocked = false;
				continue;
			}

			if (result.status() == ProcessingStatus.OUT_OF_SCOPE) {
				excludedFileCount++;
				rawFindings.add(buildNonJavaExcludedFinding(context, result));
				allAttemptedUnitsBlocked = false;
				continue;
			}

			if (result.status() == ProcessingStatus.FAILED) {
				failedOrIncompleteFileCount++;
				hadAnyEvaluableUnit = true;
				coverageGaps.addAll(result.coverage().gaps());
				rawFindings.add(buildParseFailedFinding(context, result));
				continue;
			}

			// Handle INCOMPLETE / COMPLETE (mixed-language, unavailable, partial, residual, or clean).
			if (result.status() == ProcessingStatus.INCOMPLETE) {
				failedOrIncompleteFileCount++;
				hadAnyEvaluableUnit = true;
				coverageGaps.addAll(result.coverage().gaps());

				boolean hasEvaluableEntities = (result.intent() == Intent.INITIAL_ASSESSMENT
						&& result.initialInventory().isPresent())
						|| (!result.entityFacts().isEmpty());
				if (!hasEvaluableEntities) {
					rawFindings.add(buildIncompleteFileFinding(context, result));
					continue;
				}
				// Residual or field-grouping INCOMPLETE alongside entity facts / initial inventory:
				// preserve the residual gap finding AND evaluate the trustworthy entity facts/inventory.
				rawFindings.add(buildIncompleteFileFinding(context, result));
			} else {
				evaluatedFileCount++;
				hadAnyEvaluableUnit = true;
			}

			boolean unitProducedValidEvaluation;
			if (result.intent() == Intent.INITIAL_ASSESSMENT) {
				unitProducedValidEvaluation = evaluateInitialInventory(
						context, result, lookupIndex, semanticIndex, input.controlledExports(),
						rawFindings, coverageGaps);
			} else {
				unitProducedValidEvaluation = evaluateRevisionResult(
						context, result, lookupIndex, semanticIndex, input.controlledExports(),
						rawFindings, coverageGaps);
			}

			if (unitProducedValidEvaluation) {
				allAttemptedUnitsBlocked = false;
			}
		}

		// Empty diffResults handling (including manifest requirement for EMPTY_VERIFIED).
		if (input.diffResults().isEmpty()) {
			allAttemptedUnitsBlocked = false;
			if (context.declaredScopeCompleteness() == ScopeCompleteness.EMPTY_VERIFIED) {
				if (context.scopeManifest().isEmpty()) {
					coverageGaps.add("EMPTY_VERIFIED scope lacks ScopeManifestRef for M4 verification");
					rawFindings.add(buildMissingEmptyManifestFinding(context));
				} else if (coverageGaps.isEmpty()) {
					rawFindings.add(buildEmptyVerifiedNoImpactFinding(context, context.scopeManifest().orElseThrow()));
				}
			} else if (coverageGaps.isEmpty()) {
				coverageGaps.add("No diff results supplied for declared scope " + context.declaredScopeCompleteness());
			}
		}

		// Check if every attempted unit in scope was completely blocked without any completed evaluation.
		boolean hasOnlyFailedOrBlockedFindings = !rawFindings.isEmpty()
				&& rawFindings.stream().allMatch(ImpactClassifier::isBlockedOrFailedFinding);
		if (hadAnyEvaluableUnit && allAttemptedUnitsBlocked && hasOnlyFailedOrBlockedFindings) {
			List<ImpactFinding> sortedFindings = sortAndDedupeFindings(rawFindings);
			List<ImpactDiagnostic> sortedDiags = sortDiagnostics(diagnostics);
			List<String> sortedGaps = sortStrings(coverageGaps);
			List<String> sortedExclusions = deriveExcludedScopeReasons(sortedFindings);
			var counts = countOutcomes(sortedFindings);
			var summary = new ImpactCoverageSummary(
					false, evaluatedFileCount, excludedFileCount, failedOrIncompleteFileCount,
					counts.actionable, counts.noImpact, counts.inconclusive, counts.excluded,
					sortedGaps, sortedExclusions);
			return new ImpactReport(
					CLASSIFIER_VERSION,
					ReportProcessingStatus.FAILED,
					Optional.empty(),
					context,
					sortedFindings,
					summary,
					sortedDiags);
		}

		List<ImpactFinding> sortedFindings = sortAndDedupeFindings(rawFindings);
		List<ImpactDiagnostic> sortedDiags = sortDiagnostics(diagnostics);
		List<String> sortedGaps = sortStrings(coverageGaps);
		List<String> sortedExclusions = deriveExcludedScopeReasons(sortedFindings);

		return aggregateReport(
				context, sortedFindings, sortedDiags, sortedGaps, sortedExclusions,
				evaluatedFileCount, excludedFileCount, failedOrIncompleteFileCount);
	}

	/**
	 * Re-evaluates findings and report aggregation after M4 verifies witness selectors/ranges.
	 * Enforces two-tier downgrade: if deeper impact proof fails while all initial admission
	 * witnesses remain verified, downgrades outcome to {@code INCONCLUSIVE} and clears dependent
	 * classification/severity/confidence while preserving {@code SUPPORTED_BY_INITIAL_EVIDENCE}.
	 * Recomputes valid exclusion file counts and scope reasons from surviving verified exclusions.
	 */
	public ImpactReport applyVerifiedWitnesses(
			ImpactReport report,
			Map<String, Set<String>> verifiedWitnessIdsByFindingId) {
		Objects.requireNonNull(report, "report");
		Objects.requireNonNull(verifiedWitnessIdsByFindingId, "verifiedWitnessIdsByFindingId");

		if (report.processingStatus() == ReportProcessingStatus.FAILED) {
			return report;
		}

		var updatedFindings = new ArrayList<ImpactFinding>(report.findings().size());
		var updatedGaps = new LinkedHashSet<>(report.coverage().coverageGaps());
		boolean anyChanged = false;
		int downgradedWholeFileExclusions = 0;

		for (var finding : report.findings()) {
			Set<String> verified = verifiedWitnessIdsByFindingId.getOrDefault(finding.findingId(), Set.of());
			boolean admissionSatisfied = finding.requiredAdmissionWitnessIds().isEmpty()
					|| verified.containsAll(finding.requiredAdmissionWitnessIds());
			boolean impactSatisfied = finding.requiredImpactWitnessIds().isEmpty()
					|| verified.containsAll(finding.requiredImpactWitnessIds());

			if (finding.disposition() == UnitDisposition.EXCLUDED) {
				if (!impactSatisfied) {
					anyChanged = true;
					if (finding.symbolKey().isEmpty()) {
						downgradedWholeFileExclusions++;
					}
					updatedGaps.add("Required exclusion provenance witness unverified for finding " + finding.findingId());
					updatedFindings.add(downgradeToInconclusive(finding, false));
				} else {
					updatedFindings.add(finding);
				}
				continue;
			}

			if (finding.outcome() == ImpactOutcome.ACTIONABLE) {
				if (!impactSatisfied || !admissionSatisfied) {
					anyChanged = true;
					boolean keepInitialAdmission = !finding.requiredAdmissionWitnessIds().isEmpty() && admissionSatisfied;
					updatedGaps.add("Required impact witness unverified for finding " + finding.findingId());
					updatedFindings.add(downgradeToInconclusive(finding, keepInitialAdmission));
				} else {
					updatedFindings.add(finding);
				}
				continue;
			}

			if (finding.outcome() == ImpactOutcome.NO_IMPACT) {
				if (!impactSatisfied) {
					anyChanged = true;
					updatedGaps.add("Required no-impact basis witness unverified for finding " + finding.findingId());
					updatedFindings.add(downgradeToInconclusive(finding, false));
				} else {
					updatedFindings.add(finding);
				}
				continue;
			}

			// INCONCLUSIVE finding that previously carried SUPPORTED_BY_INITIAL_EVIDENCE.
			if (finding.admission() == IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE && !admissionSatisfied) {
				anyChanged = true;
				updatedGaps.add("Required initial admission witness unverified for finding " + finding.findingId());
				updatedFindings.add(downgradeToInconclusive(finding, false));
				continue;
			}

			updatedFindings.add(finding);
		}

		if (!anyChanged) {
			return report;
		}

		List<ImpactFinding> sortedFindings = sortAndDedupeFindings(updatedFindings);
		List<String> sortedExclusions = deriveExcludedScopeReasons(sortedFindings);
		int nextExcludedFileCount = Math.max(0, report.coverage().excludedFileCount() - downgradedWholeFileExclusions);
		int nextFailedOrIncompleteFileCount = report.coverage().failedOrIncompleteFileCount() + downgradedWholeFileExclusions;

		return aggregateReport(
				report.context(),
				sortedFindings,
				report.diagnostics(),
				sortStrings(updatedGaps),
				sortedExclusions,
				report.coverage().evaluatedFileCount(),
				nextExcludedFileCount,
				nextFailedOrIncompleteFileCount);
	}

	private static ImpactFinding downgradeToInconclusive(ImpactFinding finding, boolean keepInitialAdmission) {
		var secondary = new ArrayList<ReasonCode>();
		if (finding.primaryReason() != ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED) {
			secondary.add(finding.primaryReason());
		}
		for (var reason : finding.secondaryReasons()) {
			if (reason != ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED && !secondary.contains(reason)) {
				secondary.add(reason);
			}
		}
		IssueAdmission nextAdmission = keepInitialAdmission
				? IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE
				: IssueAdmission.INSUFFICIENT_EVIDENCE;
		ReasonCode nextAdmissionReason = keepInitialAdmission
				? finding.admissionReason()
				: ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED;
		return new ImpactFinding(
				finding.findingId(),
				UnitDisposition.EVALUATED,
				ImpactOutcome.INCONCLUSIVE,
				Optional.empty(),
				Optional.empty(),
				SeverityStatus.SEVERITY_NOT_EVALUATED,
				Optional.empty(),
				Optional.empty(),
				nextAdmission,
				nextAdmissionReason,
				ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED,
				secondary,
				"Downgraded after required witness verification failed: " + finding.explanation(),
				finding.beforePath(),
				finding.afterPath(),
				finding.symbolKey(),
				finding.evaluatedFacets(),
				finding.documentId(),
				finding.documentVersion(),
				finding.documentBoundSha(),
				finding.bindingId(),
				finding.claimId(),
				finding.witnesses(),
				Set.of(),
				keepInitialAdmission ? finding.requiredAdmissionWitnessIds() : Set.of(),
				finding.initialAssessment());
	}

	private boolean evaluateInitialInventory(
			AssessmentContext context,
			StructuralDiffResult result,
			LookupIndex lookupIndex,
			SemanticIndex semanticIndex,
			List<ControlledExportUnit> controlledExports,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		if (result.initialInventory().isEmpty()) {
			return false;
		}
		var inv = result.initialInventory().orElseThrow();
		String targetSha = inv.snapshot().sha();
		String targetPath = inv.path();
		boolean anyEvaluated = false;

		if (inv.entities().isEmpty()) {
			var fileLookup = evaluateSingleKeyLookup(
					fileLookupKey(context, SideRole.TARGET, targetSha, targetPath),
					Optional.empty(), lookupIndex, coverageGaps);
			var targetSrc = sourceSelector(context, SideRole.TARGET, targetSha, targetPath, SidePresence.PRESENT,
					SelectorGranularity.FILE, Optional.empty(), Optional.empty(), Optional.empty());
			if (fileLookup.incompleteReason.isPresent()) {
				findings.add(buildLookupBlockedFinding(
						context, Optional.empty(), Optional.of(targetPath), Optional.empty(),
						Set.of(), fileLookup.incompleteReason.get(), targetSrc, true));
				return false;
			}
			if (fileLookup.bindings.isEmpty()) {
				var negWitness = negativeBasisWitness(context, NegativeBasisKind.COMPLETE_EMPTY_LOOKUP,
						fileLookup.coveredEmptyKeys, Optional.empty(), Optional.empty(),
						Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
				findings.add(buildNoImpactFinding(
						context, Optional.empty(), Optional.of(targetPath), Optional.empty(), Set.of(),
						ReasonCode.COMPLETE_EMPTY_MAPPING_NO_IMPACT,
						"Empty initial inventory with verified complete empty lookup",
						Optional.empty(), Optional.empty(), Optional.empty(),
						Optional.empty(), Optional.empty(),
						List.of(negWitness), Set.of(negWitness.witnessId()), true));
				return true;
			}
			for (var binding : fileLookup.bindings) {
				evaluateInitialEmptyFileBinding(context, targetPath, binding, targetSrc, findings, coverageGaps);
			}
			return true;
		}

		for (var entity : inv.entities()) {
			Optional<ControlledExportUnit> symbolExport = findMatchingSymbolControlledExport(
					context, targetSha, targetPath, entity.key(), controlledExports);
			if (symbolExport.isPresent()) {
				findings.add(buildControlledExportExcludedFinding(
						context, result, Optional.of(entity.key()), symbolExport.get()));
				anyEvaluated = true;
				continue;
			}

			// Require BOTH TARGET FILE lookup AND TARGET SYMBOL lookup (symmetric with buildLookupKeys).
			var lookupEval = evaluateTargetEntityLookupCoverage(
					context, targetSha, targetPath, entity.key(), lookupIndex, coverageGaps);
			var targetSrc = sourceSelector(context, SideRole.TARGET, targetSha, targetPath,
					SidePresence.PRESENT, SelectorGranularity.SYMBOL, Optional.of(entity.key()),
					Optional.empty(), Optional.empty());

			if (lookupEval.bindings.isEmpty()) {
				if (lookupEval.incompleteReason.isPresent()) {
					findings.add(buildLookupBlockedFinding(
							context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()),
							Set.of(), lookupEval.incompleteReason.get(), targetSrc, true));
					continue;
				}
				anyEvaluated = true;
				var negWitness = negativeBasisWitness(context, NegativeBasisKind.COMPLETE_EMPTY_LOOKUP,
						lookupEval.coveredEmptyKeys, Optional.empty(), result.afterSnapshot(),
						Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
				findings.add(buildNoImpactFinding(
						context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()), Set.of(),
						ReasonCode.COMPLETE_EMPTY_MAPPING_NO_IMPACT,
						"Complete empty file and symbol lookups for initial target symbol",
						Optional.empty(), Optional.empty(), Optional.empty(),
						Optional.empty(), Optional.empty(),
						List.of(targetSrc, negWitness), Set.of(negWitness.witnessId()), true));
				continue;
			}

			anyEvaluated = true;
			if (lookupEval.incompleteReason.isPresent()) {
				findings.add(buildLookupBlockedFinding(
						context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()),
						Set.of(), lookupEval.incompleteReason.get(), targetSrc, true));
			}
			for (var binding : lookupEval.bindings) {
				evaluateInitialEntityBinding(context, entity, binding, targetSrc, semanticIndex, findings, coverageGaps);
			}
		}
		return anyEvaluated;
	}

	private void evaluateInitialEmptyFileBinding(
			AssessmentContext context,
			String targetPath,
			DocumentBinding binding,
			SourceEvidenceSelector targetSrc,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		var claimsCheck = selectClaimsForTarget(context, binding, targetPath, Optional.empty(), Optional.empty());
		if (claimsCheck.contradictory) {
			coverageGaps.add("Contradictory claim metadata on document " + binding.documentId());
			findings.add(buildInconclusiveFinding(
					context, Optional.empty(), Optional.of(targetPath), Optional.empty(), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
					ReasonCode.CONTRADICTORY_DIFF_CONTEXT, List.of(),
					"Document claim contradicts selected binding version or bound SHA",
					binding, Optional.empty(), List.of(targetSrc), Set.of(), true));
		} else if (claimsCheck.missingTargetWitness) {
			findings.add(buildInconclusiveFinding(
					context, Optional.empty(), Optional.of(targetPath), Optional.empty(), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING,
					ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING, List.of(),
					"Document claim has not been evaluated against pinned initial target SHA",
					binding, Optional.empty(),
					List.of(targetSrc, documentSelector(context, binding, Optional.empty())),
					Set.of(), true));
		} else if (claimsCheck.matchingClaims.isEmpty()) {
			findings.add(buildInconclusiveFinding(
					context, Optional.empty(), Optional.of(targetPath), Optional.empty(), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
					ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, List.of(),
					"Empty initial Java file has document binding without verified target claim",
					binding, Optional.empty(),
					List.of(targetSrc, documentSelector(context, binding, Optional.empty())),
					Set.of(), true));
		} else {
			for (var claim : claimsCheck.matchingClaims) {
				var docWitness = documentSelector(context, binding, Optional.of(claim));
				var requiredIds = Set.of(targetSrc.witnessId(), docWitness.witnessId());
				if (claim.verdict() == ClaimVerdict.REFERENCED_ENTITY_REQUIRED
						|| claim.verdict() == ClaimVerdict.DECLARATION_INCOMPATIBLE
						|| claim.verdict() == ClaimVerdict.STALE_CLAIM_AGAINST_TARGET) {
					findings.add(buildActionableFinding(
							context, Optional.empty(), Optional.of(targetPath), Optional.empty(), Set.of(),
							DriftClassification.REFERENTIAL, claim.impactContext(), Optional.empty(),
							ReasonCode.REFERENTIAL_MISSING_TARGET_SYMBOL, claim.summaryRationale(),
							binding, Optional.of(claim.claimId()), List.of(targetSrc, docWitness),
							requiredIds, requiredIds, true));
				} else if (claim.verdict() == ClaimVerdict.DECLARATION_CONSISTENT
						|| claim.verdict() == ClaimVerdict.BEHAVIOR_CONSISTENT_PROVEN) {
					findings.add(buildNoImpactFinding(
							context, Optional.empty(), Optional.of(targetPath), Optional.empty(), Set.of(),
							ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT, claim.summaryRationale(),
							Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
							Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()),
							Optional.of(claim.claimId()), List.of(targetSrc, docWitness),
							requiredIds, true));
				} else {
					findings.add(buildInconclusiveFinding(
							context, Optional.empty(), Optional.of(targetPath), Optional.empty(), Set.of(),
							IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
							ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, List.of(),
							claim.summaryRationale(), binding, Optional.of(claim.claimId()),
							List.of(targetSrc, docWitness), Set.of(), true));
				}
			}
		}
	}

	private void evaluateInitialEntityBinding(
			AssessmentContext context,
			EntitySide entity,
			DocumentBinding binding,
			SourceEvidenceSelector targetSrc,
			SemanticIndex semanticIndex,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		String targetPath = entity.path();
		if (binding.granularity() == BindingGranularity.FILE_ONLY) {
			var docWitness = documentSelector(context, binding, Optional.empty());
			findings.add(buildInconclusiveFinding(
					context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.WEAK_FILE_LEVEL_MAPPING,
					ReasonCode.WEAK_FILE_LEVEL_MAPPING, List.of(),
					"File-only document binding establishes candidate relevance only for initial target",
					binding, Optional.empty(), List.of(targetSrc, docWitness), Set.of(), true));
			return;
		}

		var claimsCheck = selectClaimsForTarget(context, binding, targetPath, Optional.of(entity.key()), Optional.empty());
		if (claimsCheck.contradictory) {
			coverageGaps.add("Contradictory claim metadata on document " + binding.documentId());
			findings.add(buildInconclusiveFinding(
					context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
					ReasonCode.CONTRADICTORY_DIFF_CONTEXT, List.of(),
					"Document claim metadata contradicts selected binding version or bound SHA",
					binding, Optional.empty(), List.of(targetSrc), Set.of(), true));
			return;
		}
		if (claimsCheck.missingTargetWitness) {
			findings.add(buildInconclusiveFinding(
					context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING,
					ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING, List.of(),
					"Document claim has not been evaluated against pinned initial target SHA",
					binding, Optional.empty(),
					List.of(targetSrc, documentSelector(context, binding, Optional.empty())), Set.of(), true));
			return;
		}
		if (claimsCheck.matchingClaims.isEmpty()) {
			findings.add(buildInconclusiveFinding(
					context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
					ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, List.of(),
					"No verified document claim matches initial target symbol",
					binding, Optional.empty(),
					List.of(targetSrc, documentSelector(context, binding, Optional.empty())), Set.of(), true));
			return;
		}

		for (var claim : claimsCheck.matchingClaims) {
			var docWitness = documentSelector(context, binding, Optional.of(claim));
			var facetSet = claim.targetSubjectFacet().map(Set::of).orElse(Set.of(FacetKind.DECLARATION));
			Optional<String> claimId = Optional.of(claim.claimId());
			switch (claim.verdict()) {
				case DECLARATION_INCOMPATIBLE, STALE_CLAIM_AGAINST_TARGET -> findings.add(buildActionableFinding(
						context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()), facetSet,
						DriftClassification.SIGNATURE, claim.impactContext(), Optional.empty(),
						ReasonCode.SIGNATURE_CONTRACT_MISMATCH, claim.summaryRationale(), binding, claimId,
						List.of(targetSrc, docWitness),
						Set.of(targetSrc.witnessId(), docWitness.witnessId()),
						Set.of(targetSrc.witnessId(), docWitness.witnessId()), true));
				case BEHAVIOR_MISMATCH_PROVEN -> findings.add(buildActionableFinding(
						context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()),
						claim.targetSubjectFacet().map(Set::of).orElse(Set.of(FacetKind.BODY)),
						DriftClassification.SEMANTIC, claim.impactContext(), Optional.empty(),
						ReasonCode.SEMANTIC_BEHAVIOR_MISMATCH, claim.summaryRationale(), binding, claimId,
						List.of(targetSrc, docWitness),
						Set.of(targetSrc.witnessId(), docWitness.witnessId()),
						Set.of(targetSrc.witnessId(), docWitness.witnessId()), true));
				case DECLARATION_CONSISTENT, BEHAVIOR_CONSISTENT_PROVEN -> findings.add(buildNoImpactFinding(
						context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()), facetSet,
						ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT, claim.summaryRationale(),
						Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
						Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()), claimId,
						List.of(targetSrc, docWitness),
						Set.of(targetSrc.witnessId(), docWitness.witnessId()), true));
				case POTENTIAL_MISMATCH_INITIAL_ONLY -> {
					var admissionIds = Set.of(targetSrc.witnessId(), docWitness.witnessId());
					findings.add(buildInconclusiveFinding(
							context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()), facetSet,
							IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE,
							ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS,
							ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS, List.of(),
							claim.summaryRationale(), binding, claimId,
							List.of(targetSrc, docWitness), admissionIds, true));
				}
				case REFERENCED_ENTITY_REQUIRED -> findings.add(buildNoImpactFinding(
						context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()),
						Set.of(FacetKind.DECLARATION),
						ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT,
						"Referenced initial entity is present at target SHA",
						Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
						Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()), claimId,
						List.of(targetSrc, docWitness),
						Set.of(targetSrc.witnessId(), docWitness.witnessId()), true));
				case UNVERIFIED_OR_AMBIGUOUS -> findings.add(buildInconclusiveFinding(
						context, Optional.empty(), Optional.of(targetPath), Optional.of(entity.key()), facetSet,
						IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
						ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, List.of(),
						claim.summaryRationale(), binding, claimId,
						List.of(targetSrc, docWitness), Set.of(), true));
			}
		}
	}

	private boolean evaluateRevisionResult(
			AssessmentContext context,
			StructuralDiffResult result,
			LookupIndex lookupIndex,
			SemanticIndex semanticIndex,
			List<ControlledExportUnit> controlledExports,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		var beforeProv = result.beforeSnapshot().orElseThrow();
		var afterProv = result.afterSnapshot().orElseThrow();
		String beforePath = beforeProv.path();
		String afterPath = afterProv.path();
		String beforeSha = beforeProv.snapshot().sha();
		String afterSha = afterProv.snapshot().sha();
		var fc = result.fileComparison();

		// 1. Check unchanged / whitespace-only strict NO_IMPACT rules (Refinement #2).
		if (result.status() == ProcessingStatus.COMPLETE
				&& fc.presence() == PresenceRelation.BOTH_PRESENT
				&& fc.pathRelation() == PathRelation.SAME_PATH
				&& fc.structuralEquality() == StructuralEquality.EQUAL
				&& fc.contextChanges().isEmpty()) {
			var staleBindings = new LinkedHashMap<BindingIdentityKey, DocumentBinding>();
			for (var b : findBindingsForFile(lookupIndex, SideRole.BEFORE, beforeSha, beforePath)) {
				staleBindings.putIfAbsent(BindingIdentityKey.of(b), b);
			}
			for (var b : findBindingsForFile(lookupIndex, SideRole.AFTER, afterSha, afterPath)) {
				staleBindings.putIfAbsent(BindingIdentityKey.of(b), b);
			}
			boolean hasStaleClaim = staleBindings.values().stream()
					.flatMap(b -> b.claims().stream())
					.anyMatch(c -> c.evaluatedTargetSha().equals(context.authoritativeTargetSha())
							&& isStaleOrMismatchOnPresentTarget(c.verdict()));

			if (!hasStaleClaim && !fc.rawOnlyChange()) {
				var negWitness = negativeBasisWitness(context, NegativeBasisKind.UNCHANGED_VERIFIED,
						List.of(), Optional.of(beforeProv), Optional.of(afterProv),
						Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
				findings.add(buildNoImpactFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
						ReasonCode.UNCHANGED_WITHIN_EVALUATED_SCOPE,
						"File structurally and raw-identical within same path and complete coverage",
						Optional.empty(), Optional.empty(), Optional.empty(),
						Optional.empty(), Optional.empty(),
						List.of(negWitness), Set.of(negWitness.witnessId()), false));
				return true;
			}

			if (!hasStaleClaim
					&& fc.rawOnlyChange()
					&& fc.rawChanges().equals(Set.of(RawChangeKind.WHITESPACE))
					&& context.whitespaceOnlyNoImpactPolicyEnabled()) {
				var negWitness = negativeBasisWitness(context, NegativeBasisKind.WHITESPACE_ONLY_VERIFIED,
						List.of(), Optional.of(beforeProv), Optional.of(afterProv),
						Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
				findings.add(buildNoImpactFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
						ReasonCode.WHITESPACE_ONLY_NO_IMPACT,
						"Whitespace-only change within same path under approved whitespace policy",
						Optional.empty(), Optional.empty(), Optional.empty(),
						Optional.empty(), Optional.empty(),
						List.of(negWitness), Set.of(negWitness.witnessId()), false));
				return true;
			}
		}

		// 2. Evaluate entity facts (ADDED, REMOVED, MODIFIED, plus stale claims on UNCHANGED entities).
		var removedFacts = result.entityFacts().stream()
				.filter(f -> f.change() == ChangeKind.REMOVED)
				.toList();
		var addedFacts = result.entityFacts().stream()
				.filter(f -> f.change() == ChangeKind.ADDED)
				.toList();

		boolean anyEvaluated = false;
		for (var fact : result.entityFacts()) {
			if (fact.change() == ChangeKind.UNCHANGED) {
				SymbolKey unchangedKey = fact.after().orElseThrow().key();
				var entityBindings = new LinkedHashMap<BindingIdentityKey, DocumentBinding>();
				if (beforeProv.presence() == SidePresence.PRESENT) {
					for (var b : findSymbolBindings(lookupIndex, SideRole.BEFORE, beforeSha, beforePath, unchangedKey)) {
						entityBindings.putIfAbsent(BindingIdentityKey.of(b), b);
					}
				}
				if (afterProv.presence() == SidePresence.PRESENT) {
					for (var b : findSymbolBindings(lookupIndex, SideRole.AFTER, afterSha, afterPath, unchangedKey)) {
						entityBindings.putIfAbsent(BindingIdentityKey.of(b), b);
					}
				}
				for (var binding : entityBindings.values()) {
					var staleClaims = selectClaimsForTarget(
							context, binding, afterPath, Optional.of(unchangedKey), Optional.empty());
					for (var claim : staleClaims.matchingClaims) {
						if (isStaleOrMismatchOnPresentTarget(claim.verdict())) {
							anyEvaluated = true;
							emitStaleClaimOnUnchangedEntity(context, fact, binding, claim, findings);
						}
					}
				}
				continue;
			}
			SymbolKey key = fact.before().map(EntitySide::key).orElseGet(() -> fact.after().orElseThrow().key());

			Optional<ControlledExportUnit> symbolExport = findMatchingSymbolControlledExport(
					context, afterSha, afterPath, key, controlledExports);
			if (symbolExport.isPresent()) {
				findings.add(buildControlledExportExcludedFinding(
						context, result, Optional.of(key), symbolExport.get()));
				anyEvaluated = true;
				continue;
			}

			boolean entityOk = evaluateChangedEntityFact(
					context, result, fact, removedFacts, addedFacts,
					lookupIndex, semanticIndex, findings, coverageGaps);
			if (entityOk) {
				anyEvaluated = true;
			}
		}

		// 3. Evaluate every file-level change axis independently (imports, package, module, initializer blocks,
		// declaration/scope order, comments/Javadoc, path change, or policy-off whitespace/baseline stale checks),
		// even when the file ALSO contains modified/added/removed entities!
		for (var axis : collectFileLevelChangeAxes(context, result)) {
			boolean axisOk = evaluateFileLevelAxis(
					context, result, axis, lookupIndex, findings, coverageGaps);
			if (axisOk) {
				anyEvaluated = true;
			}
		}

		return anyEvaluated;
	}

	private static List<FileLevelChangeAxis> collectFileLevelChangeAxes(
			AssessmentContext context,
			StructuralDiffResult result) {
		var fc = result.fileComparison();
		var axes = new ArrayList<FileLevelChangeAxis>();
		if (fc.pathRelation() == PathRelation.PATH_CHANGED) {
			axes.add(FileLevelChangeAxis.PATH_CHANGED);
		}
		if (fc.contextChanges().contains(ContextChangeKind.PACKAGE_CHANGED)) {
			axes.add(FileLevelChangeAxis.PACKAGE_CHANGED);
		}
		if (fc.contextChanges().contains(ContextChangeKind.IMPORTS_CHANGED)) {
			axes.add(FileLevelChangeAxis.IMPORTS_CHANGED);
		}
		if (fc.contextChanges().contains(ContextChangeKind.MODULE_CHANGED)) {
			axes.add(FileLevelChangeAxis.MODULE_CHANGED);
		}
		if (fc.contextChanges().contains(ContextChangeKind.INITIALIZER_BLOCK_CHANGED)) {
			axes.add(FileLevelChangeAxis.INITIALIZER_BLOCK_CHANGED);
		}
		if (fc.contextChanges().contains(ContextChangeKind.SCOPE_ORDER_CHANGED)) {
			axes.add(FileLevelChangeAxis.SCOPE_ORDER_CHANGED);
		}
		if (fc.rawChanges().contains(RawChangeKind.COMMENT)) {
			axes.add(FileLevelChangeAxis.COMMENT_CHANGED);
		}
		if (axes.isEmpty() && result.entityFacts().stream().allMatch(fact -> fact.change() == ChangeKind.UNCHANGED)) {
			if (fc.rawOnlyChange() && fc.rawChanges().contains(RawChangeKind.WHITESPACE)
					&& !context.whitespaceOnlyNoImpactPolicyEnabled()) {
				axes.add(FileLevelChangeAxis.WHITESPACE_POLICY_OFF);
			} else if (result.status() == ProcessingStatus.COMPLETE) {
				axes.add(FileLevelChangeAxis.FILE_BASELINE_OR_STALE);
			}
		}
		return axes;
	}

	private boolean evaluateFileLevelAxis(
			AssessmentContext context,
			StructuralDiffResult result,
			FileLevelChangeAxis axis,
			LookupIndex lookupIndex,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		var beforeProv = result.beforeSnapshot().orElseThrow();
		var afterProv = result.afterSnapshot().orElseThrow();
		String beforePath = beforeProv.path();
		String afterPath = afterProv.path();
		String beforeSha = beforeProv.snapshot().sha();
		String afterSha = afterProv.snapshot().sha();
		var fc = result.fileComparison();

		var requiredKeys = revisionLookupKeys(context, result, axis::requiresSymbolLookup);
		var fileLookup = evaluateFileAxisLookupCoverage(requiredKeys, lookupIndex, coverageGaps);

		var beforeSrc = sourceSelector(context, SideRole.BEFORE, beforeSha, beforePath, beforeProv.presence(),
				axis.selectorGranularity, Optional.empty(), Optional.empty(), axis.contextChangeKind);
		var afterSrc = sourceSelector(context, SideRole.AFTER, afterSha, afterPath, afterProv.presence(),
				axis.selectorGranularity, Optional.empty(), Optional.empty(), axis.contextChangeKind);
		var fallbackSrc = afterProv.presence() == SidePresence.PRESENT ? afterSrc : beforeSrc;

		if (fileLookup.bindings.isEmpty()) {
			if (fileLookup.incompleteReason.isPresent()) {
				findings.add(buildLookupBlockedFindingTagged(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(),
						Set.of(), fileLookup.incompleteReason.get(), fallbackSrc, false, axis.tag));
				return false;
			}
			var negWitness = negativeBasisWitness(context, NegativeBasisKind.COMPLETE_EMPTY_LOOKUP,
					fileLookup.coveredEmptyKeys, Optional.of(beforeProv), Optional.of(afterProv),
					Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
			findings.add(buildNoImpactFindingTagged(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
					ReasonCode.COMPLETE_EMPTY_MAPPING_NO_IMPACT,
					"Complete empty document lookup for " + axis.description,
					Optional.empty(), Optional.empty(), Optional.empty(),
					Optional.empty(), Optional.empty(),
					List.of(beforeSrc, afterSrc, negWitness), Set.of(negWitness.witnessId()), false, axis.tag));
			return true;
		}

		if (fileLookup.incompleteReason.isPresent()) {
			findings.add(buildLookupBlockedFindingTagged(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(),
					Set.of(), fileLookup.incompleteReason.get(), fallbackSrc, false, axis.tag));
		}

		for (var binding : fileLookup.bindings) {
			var claimsCheck = selectClaimsForFile(context, binding, beforePath, afterPath);
			if (claimsCheck.contradictory) {
				coverageGaps.add("Contradictory claim metadata on document " + binding.documentId());
				findings.add(buildInconclusiveFindingTagged(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
						IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
						ReasonCode.CONTRADICTORY_DIFF_CONTEXT, List.of(),
						"Document claim contradicts binding version or bound SHA",
						binding, Optional.empty(), List.of(beforeSrc, afterSrc), Set.of(), false, axis.tag));
				continue;
			}
			if (claimsCheck.missingTargetWitness) {
				findings.add(buildInconclusiveFindingTagged(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
						IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING,
						ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING, List.of(),
						"Document claim has not been evaluated against pinned target SHA for " + axis.description,
						binding, Optional.empty(),
						List.of(beforeSrc, afterSrc, documentSelector(context, binding, Optional.empty())),
						Set.of(), false, axis.tag));
				continue;
			}
			if (claimsCheck.matchingClaims.isEmpty()) {
				findings.add(buildInconclusiveFindingTagged(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
						IssueAdmission.INSUFFICIENT_EVIDENCE,
						axis.defaultInconclusiveReason, axis.defaultInconclusiveReason, List.of(),
						"File-level change (" + axis.description + ") lacks verified document claim at target SHA",
						binding, Optional.empty(),
						List.of(beforeSrc, afterSrc, documentSelector(context, binding, Optional.empty())),
						Set.of(), false, axis.tag));
				continue;
			}

			for (var claim : claimsCheck.matchingClaims) {
				var docWitness = documentSelector(context, binding, Optional.of(claim));
				var requiredIds = Set.of(beforeSrc.witnessId(), afterSrc.witnessId(), docWitness.witnessId());
				Optional<String> claimId = Optional.of(claim.claimId());
				if (!axis.contextOrCommentAxis
						&& claim.verdict() == ClaimVerdict.REFERENCED_ENTITY_REQUIRED
						&& (fc.presence() == PresenceRelation.REMOVED || fc.pathRelation() == PathRelation.PATH_CHANGED)
						&& claim.targetSubjectPath().equals(beforePath)) {
					findings.add(buildActionableFindingTagged(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
							DriftClassification.REFERENTIAL, claim.impactContext(), Optional.empty(),
							fc.presence() == PresenceRelation.REMOVED
									? ReasonCode.REFERENTIAL_REMOVED_TARGET_FILE
									: ReasonCode.REFERENTIAL_MISSING_TARGET_SYMBOL,
							claim.summaryRationale(), binding, claimId,
							List.of(beforeSrc, afterSrc, docWitness), requiredIds, requiredIds, false, axis.tag));
				} else if (claim.verdict() == ClaimVerdict.STALE_CLAIM_AGAINST_TARGET
						|| claim.verdict() == ClaimVerdict.DECLARATION_INCOMPATIBLE) {
					findings.add(buildActionableFindingTagged(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
							DriftClassification.SIGNATURE, claim.impactContext(), Optional.empty(),
							ReasonCode.SIGNATURE_CONTRACT_MISMATCH,
							claim.summaryRationale(), binding, claimId,
							List.of(beforeSrc, afterSrc, docWitness), requiredIds, requiredIds, false, axis.tag));
				} else if (claim.verdict() == ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN) {
					findings.add(buildActionableFindingTagged(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
							DriftClassification.SEMANTIC, claim.impactContext(), Optional.empty(),
							ReasonCode.SEMANTIC_BEHAVIOR_MISMATCH,
							claim.summaryRationale(), binding, claimId,
							List.of(beforeSrc, afterSrc, docWitness), requiredIds, requiredIds, false, axis.tag));
				} else if (claim.verdict() == ClaimVerdict.DECLARATION_CONSISTENT
						|| claim.verdict() == ClaimVerdict.BEHAVIOR_CONSISTENT_PROVEN) {
					boolean coversFileAxis = !axis.contextOrCommentAxis
							|| (claim.targetSubjectSymbol().isEmpty() && claim.targetSubjectFacet().isEmpty());
					if (coversFileAxis) {
						findings.add(buildNoImpactFindingTagged(
								context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
								ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT,
								claim.summaryRationale(),
								Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
								Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()), claimId,
								List.of(beforeSrc, afterSrc, docWitness), requiredIds, false, axis.tag));
					} else {
						findings.add(buildInconclusiveFindingTagged(
								context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
								IssueAdmission.INSUFFICIENT_EVIDENCE,
								axis.defaultInconclusiveReason, axis.defaultInconclusiveReason, List.of(),
								"Symbol/facet-scoped claim does not prove file-level " + axis.description + " has no impact",
								binding, claimId,
								List.of(beforeSrc, afterSrc, docWitness), Set.of(), false, axis.tag));
					}
				} else if (claim.verdict() == ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY) {
					findings.add(buildInconclusiveFindingTagged(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
							IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE,
							ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS,
							ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS, List.of(),
							claim.summaryRationale(), binding, claimId,
							List.of(beforeSrc, afterSrc, docWitness), requiredIds, false, axis.tag));
				} else {
					findings.add(buildInconclusiveFindingTagged(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.empty(), Set.of(),
							IssueAdmission.INSUFFICIENT_EVIDENCE,
							axis.defaultInconclusiveReason, axis.defaultInconclusiveReason, List.of(),
							claim.summaryRationale(), binding, claimId,
							List.of(beforeSrc, afterSrc, docWitness), Set.of(), false, axis.tag));
				}
			}
		}
		return true;
	}

	private enum FileLevelChangeAxis {
		PATH_CHANGED(
				SelectorGranularity.FILE,
				Optional.empty(),
				ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
				"FILE:PATH_CHANGED",
				"file path change",
				false),
		PACKAGE_CHANGED(
				SelectorGranularity.CONTEXT,
				Optional.of(ContextChangeKind.PACKAGE_CHANGED),
				ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW,
				"CONTEXT:PACKAGE_CHANGED",
				"package declaration change",
				true),
		IMPORTS_CHANGED(
				SelectorGranularity.CONTEXT,
				Optional.of(ContextChangeKind.IMPORTS_CHANGED),
				ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW,
				"CONTEXT:IMPORTS_CHANGED",
				"import declaration change",
				true),
		MODULE_CHANGED(
				SelectorGranularity.CONTEXT,
				Optional.of(ContextChangeKind.MODULE_CHANGED),
				ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW,
				"CONTEXT:MODULE_CHANGED",
				"module declaration change",
				true),
		INITIALIZER_BLOCK_CHANGED(
				SelectorGranularity.CONTEXT,
				Optional.of(ContextChangeKind.INITIALIZER_BLOCK_CHANGED),
				ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW,
				"CONTEXT:INITIALIZER_BLOCK_CHANGED",
				"initializer block change",
				true),
		SCOPE_ORDER_CHANGED(
				SelectorGranularity.CONTEXT,
				Optional.of(ContextChangeKind.SCOPE_ORDER_CHANGED),
				ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW,
				"CONTEXT:SCOPE_ORDER_CHANGED",
				"declaration/scope order change",
				true),
		COMMENT_CHANGED(
				SelectorGranularity.FILE,
				Optional.empty(),
				ReasonCode.RAW_COMMENT_OR_JAVADOC_CHANGED_NEEDS_REVIEW,
				"RAW:COMMENT",
				"comment or Javadoc change",
				true),
		WHITESPACE_POLICY_OFF(
				SelectorGranularity.FILE,
				Optional.empty(),
				ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
				"RAW:WHITESPACE_POLICY_OFF",
				"whitespace change under disabled policy",
				false),
		FILE_BASELINE_OR_STALE(
				SelectorGranularity.FILE,
				Optional.empty(),
				ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
				"FILE:BASELINE_OR_STALE",
				"file-level baseline or stale-claim check",
				false);

		final SelectorGranularity selectorGranularity;
		final Optional<ContextChangeKind> contextChangeKind;
		final ReasonCode defaultInconclusiveReason;
		final String tag;
		final String description;
		final boolean contextOrCommentAxis;

		FileLevelChangeAxis(
				SelectorGranularity selectorGranularity,
				Optional<ContextChangeKind> contextChangeKind,
				ReasonCode defaultInconclusiveReason,
				String tag,
				String description,
				boolean contextOrCommentAxis) {
			this.selectorGranularity = selectorGranularity;
			this.contextChangeKind = contextChangeKind;
			this.defaultInconclusiveReason = defaultInconclusiveReason;
			this.tag = tag;
			this.description = description;
			this.contextOrCommentAxis = contextOrCommentAxis;
		}

		boolean requiresSymbolLookup(ChangeKind change) {
			return contextOrCommentAxis
					|| (change == ChangeKind.UNCHANGED && (this == PATH_CHANGED || this == WHITESPACE_POLICY_OFF));
		}
	}

	private boolean evaluateChangedEntityFact(
			AssessmentContext context,
			StructuralDiffResult result,
			EntityFact fact,
			List<EntityFact> removedFacts,
			List<EntityFact> addedFacts,
			LookupIndex lookupIndex,
			SemanticIndex semanticIndex,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		var beforeProv = result.beforeSnapshot().orElseThrow();
		var afterProv = result.afterSnapshot().orElseThrow();
		String beforePath = fact.before().map(EntitySide::path).orElse(beforeProv.path());
		String afterPath = fact.after().map(EntitySide::path).orElse(afterProv.path());
		String beforeSha = beforeProv.snapshot().sha();
		String afterSha = afterProv.snapshot().sha();
		SymbolKey key = fact.before().map(EntitySide::key).orElseGet(() -> fact.after().orElseThrow().key());

		boolean needBeforeLookup = beforeProv.presence() == SidePresence.PRESENT;
		boolean needAfterLookup = afterProv.presence() == SidePresence.PRESENT;

		// Require BOTH FILE and SYMBOL lookup responses for every PRESENT side (Fix #4).
		var lookupEval = evaluateEntityLookupCoverage(
				context, needBeforeLookup, beforeSha, beforePath,
				needAfterLookup, afterSha, afterPath, key, lookupIndex, coverageGaps);

		var defaultBeforeSrc = sourceSelector(context, SideRole.BEFORE, beforeSha, beforePath, beforeProv.presence(),
				SelectorGranularity.SYMBOL, Optional.of(key), Optional.empty(), Optional.empty());
		var defaultAfterSrc = sourceSelector(context, SideRole.AFTER, afterSha, afterPath, afterProv.presence(),
				SelectorGranularity.SYMBOL, Optional.of(key), Optional.empty(), Optional.empty());
		var fallbackSrc = fact.after().isPresent() ? defaultAfterSrc : defaultBeforeSrc;

		if (lookupEval.bindings.isEmpty()) {
			if (lookupEval.incompleteReason.isPresent()) {
				findings.add(buildLookupBlockedFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						fact.changedFacets(), lookupEval.incompleteReason.get(), fallbackSrc, false));
				return false;
			}
			var negWitness = negativeBasisWitness(context, NegativeBasisKind.COMPLETE_EMPTY_LOOKUP,
					lookupEval.coveredEmptyKeys, Optional.of(beforeProv), Optional.of(afterProv),
					Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
			var witnesses = new ArrayList<WitnessSelector>();
			if (fact.before().isPresent()) {
				witnesses.add(defaultBeforeSrc);
			}
			if (fact.after().isPresent()) {
				witnesses.add(defaultAfterSrc);
			}
			witnesses.add(negWitness);
			findings.add(buildNoImpactFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
					fact.changedFacets(), ReasonCode.COMPLETE_EMPTY_MAPPING_NO_IMPACT,
					"Complete empty file and symbol lookups across all required sides for changed symbol",
					Optional.empty(), Optional.empty(), Optional.empty(),
					Optional.empty(), Optional.empty(),
					witnesses, Set.of(negWitness.witnessId()), false));
			return true;
		}

		if (lookupEval.incompleteReason.isPresent()) {
			// Keep valid bindings evaluated while recording the missing/incomplete required lookup key!
			findings.add(buildLookupBlockedFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
					fact.changedFacets(), lookupEval.incompleteReason.get(), fallbackSrc, false));
		}

		for (var binding : lookupEval.bindings) {
			if (binding.granularity() == BindingGranularity.FILE_ONLY
					&& !(fact.change() == ChangeKind.REMOVED && afterProv.presence() == SidePresence.ABSENT)) {
				var docWitness = documentSelector(context, binding, Optional.empty());
				var witnesses = new ArrayList<WitnessSelector>();
				if (fact.before().isPresent()) {
					witnesses.add(defaultBeforeSrc);
				}
				if (fact.after().isPresent()) {
					witnesses.add(defaultAfterSrc);
				}
				witnesses.add(docWitness);
				findings.add(buildInconclusiveFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						fact.changedFacets(), IssueAdmission.INSUFFICIENT_EVIDENCE,
						ReasonCode.WEAK_FILE_LEVEL_MAPPING, ReasonCode.WEAK_FILE_LEVEL_MAPPING, List.of(),
						"File-only mapping establishes candidate relevance only; insufficient for symbol mismatch",
						binding, Optional.empty(), witnesses, Set.of(), false));
				continue;
			}

			switch (fact.change()) {
				case REMOVED -> evaluateRemovedEntityBinding(
						context, result, fact, addedFacts, binding,
						defaultBeforeSrc, defaultAfterSrc, findings, coverageGaps);
				case ADDED -> evaluateAddedEntityBinding(
						context, result, fact, removedFacts, binding,
						defaultBeforeSrc, defaultAfterSrc, findings, coverageGaps);
				case MODIFIED -> evaluateModifiedEntityBinding(
						context, result, fact, binding, semanticIndex, findings, coverageGaps);
				case UNCHANGED -> {
					// Handled above.
				}
			}
		}
		return true;
	}

	private void evaluateRemovedEntityBinding(
			AssessmentContext context,
			StructuralDiffResult result,
			EntityFact fact,
			List<EntityFact> addedFacts,
			DocumentBinding binding,
			SourceEvidenceSelector beforeSrc,
			SourceEvidenceSelector afterSrc,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		var afterProv = result.afterSnapshot().orElseThrow();
		String beforePath = beforeSrc.path();
		String afterPath = afterProv.path();
		SymbolKey oldKey = fact.before().orElseThrow().key();

		var claimsCheck = selectClaimsForTarget(
				context, binding, beforePath, Optional.of(oldKey), Optional.empty());
		if (claimsCheck.contradictory) {
			coverageGaps.add("Contradictory claim metadata on document " + binding.documentId());
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
					ReasonCode.CONTRADICTORY_DIFF_CONTEXT, List.of(),
					"Document claim contradicts selected binding version or bound SHA",
					binding, Optional.empty(), List.of(beforeSrc, afterSrc), Set.of(), false));
			return;
		}
		if (claimsCheck.missingTargetWitness) {
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING,
					ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING, List.of(),
					"Document claim has not been evaluated against pinned target SHA",
					binding, Optional.empty(),
					List.of(beforeSrc, afterSrc, documentSelector(context, binding, Optional.empty())),
					Set.of(), false));
			return;
		}
		if (claimsCheck.matchingClaims.isEmpty()) {
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
					ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, List.of(),
					"No verified document claim targets removed symbol at target SHA",
					binding, Optional.empty(),
					List.of(beforeSrc, afterSrc, documentSelector(context, binding, Optional.empty())),
					Set.of(), false));
			return;
		}

		boolean hasSameNameAddedCounterpart = addedFacts.stream()
				.map(f -> f.after().orElseThrow().key())
				.anyMatch(addedKey -> isPotentialOverloadOrCounterpart(oldKey, addedKey));

		for (var claim : claimsCheck.matchingClaims) {
			var docWitness = documentSelector(context, binding, Optional.of(claim));
			var witnesses = List.<WitnessSelector>of(beforeSrc, afterSrc, docWitness);
			var requiredIds = Set.of(beforeSrc.witnessId(), afterSrc.witnessId(), docWitness.witnessId());
			Optional<String> claimId = Optional.of(claim.claimId());

			if (claim.verdict() == ClaimVerdict.REFERENCED_ENTITY_REQUIRED) {
				boolean targetAbsenceProven = afterProv.presence() == SidePresence.ABSENT
						|| result.status() == ProcessingStatus.COMPLETE;
				boolean exactSymbolDisambiguated = claim.targetSubjectSymbol().isPresent()
						&& claim.targetSubjectSymbol().orElseThrow().equals(oldKey);

				if (afterProv.presence() == SidePresence.PRESENT && hasSameNameAddedCounterpart && !exactSymbolDisambiguated) {
					findings.add(buildInconclusiveFinding(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
							IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.AMBIGUOUS_SYMBOL_COUNTERPART,
							ReasonCode.AMBIGUOUS_SYMBOL_COUNTERPART, List.of(),
							"Unmatched removal and same-name addition present without exact symbol disambiguation",
							binding, claimId, witnesses, Set.of(), false));
				} else if (afterProv.presence() == SidePresence.PRESENT
						&& hasSameNameAddedCounterpart
						&& exactSymbolDisambiguated
						&& result.status() == ProcessingStatus.COMPLETE) {
					findings.add(buildActionableFinding(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
							DriftClassification.REFERENTIAL, claim.impactContext(), Optional.empty(),
							ReasonCode.REFERENTIAL_MISSING_TARGET_SYMBOL,
							claim.summaryRationale(), binding, claimId, witnesses, requiredIds, requiredIds, false));
				} else if (targetAbsenceProven) {
					ReasonCode reason = afterProv.presence() == SidePresence.ABSENT
							? ReasonCode.REFERENTIAL_REMOVED_TARGET_FILE
							: ReasonCode.REFERENTIAL_MISSING_TARGET_SYMBOL;
					findings.add(buildActionableFinding(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
							DriftClassification.REFERENTIAL, claim.impactContext(), Optional.empty(),
							reason, claim.summaryRationale(), binding, claimId, witnesses, requiredIds, requiredIds, false));
				} else {
					findings.add(buildInconclusiveFinding(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
							IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.AMBIGUOUS_SYMBOL_COUNTERPART,
							ReasonCode.AMBIGUOUS_SYMBOL_COUNTERPART, List.of(),
							"Target coverage incomplete to prove referenced symbol is absent at target SHA",
							binding, claimId, witnesses, Set.of(), false));
				}
			} else if (claim.verdict() == ClaimVerdict.DECLARATION_CONSISTENT
					|| claim.verdict() == ClaimVerdict.BEHAVIOR_CONSISTENT_PROVEN) {
				findings.add(buildNoImpactFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
						ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT, claim.summaryRationale(),
						Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
						Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()), claimId,
						witnesses, requiredIds, false));
			} else if (claim.verdict() == ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY) {
				findings.add(buildInconclusiveFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
						IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE,
						ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS,
						ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS, List.of(),
						claim.summaryRationale(), binding, claimId, witnesses, requiredIds, false));
			} else if (claim.verdict() == ClaimVerdict.DECLARATION_INCOMPATIBLE
					|| claim.verdict() == ClaimVerdict.STALE_CLAIM_AGAINST_TARGET) {
				findings.add(buildActionableFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
						DriftClassification.REFERENTIAL, claim.impactContext(), Optional.empty(),
						ReasonCode.REFERENTIAL_MISSING_TARGET_SYMBOL,
						claim.summaryRationale(), binding, claimId, witnesses, requiredIds, requiredIds, false));
			} else {
				findings.add(buildInconclusiveFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(oldKey), Set.of(),
						IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
						ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, List.of(),
						claim.summaryRationale(), binding, claimId, witnesses, Set.of(), false));
			}
		}
	}

	private void evaluateAddedEntityBinding(
			AssessmentContext context,
			StructuralDiffResult result,
			EntityFact fact,
			List<EntityFact> removedFacts,
			DocumentBinding binding,
			SourceEvidenceSelector beforeSrc,
			SourceEvidenceSelector afterSrc,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		var beforeProv = result.beforeSnapshot().orElseThrow();
		String beforePath = beforeProv.path();
		String afterPath = afterSrc.path();
		SymbolKey newKey = fact.after().orElseThrow().key();

		var claimsCheck = selectClaimsForTarget(
				context, binding, afterPath, Optional.of(newKey), Optional.empty());
		if (claimsCheck.contradictory) {
			coverageGaps.add("Contradictory claim metadata on document " + binding.documentId());
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(newKey), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
					ReasonCode.CONTRADICTORY_DIFF_CONTEXT, List.of(),
					"Document claim contradicts selected binding version or bound SHA",
					binding, Optional.empty(), List.of(afterSrc), Set.of(), false));
			return;
		}
		if (claimsCheck.missingTargetWitness) {
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(newKey), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING,
					ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING, List.of(),
					"Document claim has not been evaluated against pinned target SHA",
					binding, Optional.empty(),
					List.of(afterSrc, documentSelector(context, binding, Optional.empty())), Set.of(), false));
			return;
		}
		if (claimsCheck.matchingClaims.isEmpty()) {
			boolean hasSameNameRemovedCounterpart = removedFacts.stream()
					.map(f -> f.before().orElseThrow().key())
					.anyMatch(remKey -> isPotentialOverloadOrCounterpart(remKey, newKey));
			ReasonCode reason = hasSameNameRemovedCounterpart
					? ReasonCode.AMBIGUOUS_SYMBOL_COUNTERPART
					: ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS;
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(newKey), Set.of(),
					IssueAdmission.INSUFFICIENT_EVIDENCE, reason, reason, List.of(),
					"No verified document claim establishes impact for added symbol",
					binding, Optional.empty(),
					List.of(afterSrc, documentSelector(context, binding, Optional.empty())), Set.of(), false));
			return;
		}

		for (var claim : claimsCheck.matchingClaims) {
			var docWitness = documentSelector(context, binding, Optional.of(claim));
			var witnesses = beforeProv.presence() == SidePresence.PRESENT
					? List.<WitnessSelector>of(beforeSrc, afterSrc, docWitness)
					: List.<WitnessSelector>of(afterSrc, docWitness);
			var requiredIds = witnesses.stream().map(WitnessSelector::witnessId).collect(Collectors.toSet());
			Optional<String> claimId = Optional.of(claim.claimId());

			switch (claim.verdict()) {
				case DECLARATION_INCOMPATIBLE, STALE_CLAIM_AGAINST_TARGET -> findings.add(buildActionableFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(newKey),
						Set.of(FacetKind.DECLARATION),
						DriftClassification.SIGNATURE, claim.impactContext(), Optional.empty(),
						ReasonCode.SIGNATURE_CONTRACT_MISMATCH, claim.summaryRationale(),
						binding, claimId, witnesses, requiredIds, requiredIds, false));
				case BEHAVIOR_MISMATCH_PROVEN -> findings.add(buildActionableFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(newKey),
						Set.of(FacetKind.BODY),
						DriftClassification.SEMANTIC, claim.impactContext(), Optional.empty(),
						ReasonCode.SEMANTIC_BEHAVIOR_MISMATCH, claim.summaryRationale(),
						binding, claimId, witnesses, requiredIds, requiredIds, false));
				case DECLARATION_CONSISTENT, BEHAVIOR_CONSISTENT_PROVEN, REFERENCED_ENTITY_REQUIRED ->
					findings.add(buildNoImpactFinding(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(newKey), Set.of(),
							ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT, claim.summaryRationale(),
							Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
							Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()), claimId,
							witnesses, requiredIds, false));
				case POTENTIAL_MISMATCH_INITIAL_ONLY -> findings.add(buildInconclusiveFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(newKey), Set.of(),
						IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE,
						ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS,
						ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS, List.of(),
						claim.summaryRationale(), binding, claimId, witnesses, requiredIds, false));
				case UNVERIFIED_OR_AMBIGUOUS -> findings.add(buildInconclusiveFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(newKey), Set.of(),
						IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
						ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, List.of(),
						claim.summaryRationale(), binding, claimId, witnesses, Set.of(), false));
			}
		}
	}

	private void evaluateModifiedEntityBinding(
			AssessmentContext context,
			StructuralDiffResult result,
			EntityFact fact,
			DocumentBinding binding,
			SemanticIndex semanticIndex,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		var beforeSide = fact.before().orElseThrow();
		var afterSide = fact.after().orElseThrow();
		String beforePath = beforeSide.path();
		String afterPath = afterSide.path();
		String beforeSha = beforeSide.snapshot().sha();
		String afterSha = afterSide.snapshot().sha();
		SymbolKey key = afterSide.key();
		Set<FacetKind> changedFacets = fact.changedFacets();

		// Axis 1: DECLARATION facet (evaluated independently).
		if (changedFacets.contains(FacetKind.DECLARATION)) {
			evaluateModifiedDeclarationFacet(
					context, beforeSha, beforePath, afterSha, afterPath, key, binding, findings, coverageGaps);
		}

		// Axis 2: BODY and/or INITIALIZER facets (evaluated independently even if DECLARATION also changed!).
		if (changedFacets.contains(FacetKind.BODY)) {
			evaluateModifiedExecutableFacet(
					context, beforeSha, beforePath, afterSha, afterPath, key, FacetKind.BODY,
					binding, semanticIndex, findings, coverageGaps);
		}
		if (changedFacets.contains(FacetKind.INITIALIZER)) {
			evaluateModifiedExecutableFacet(
					context, beforeSha, beforePath, afterSha, afterPath, key, FacetKind.INITIALIZER,
					binding, semanticIndex, findings, coverageGaps);
		}

		// Axis 3: MEMBER_ORDER facet (if changed without other facets or alongside them).
		if (changedFacets.contains(FacetKind.MEMBER_ORDER)) {
			evaluateModifiedMemberOrderFacet(
					context, beforeSha, beforePath, afterSha, afterPath, key, binding, findings, coverageGaps);
		}
	}

	private void evaluateModifiedDeclarationFacet(
			AssessmentContext context,
			String beforeSha,
			String beforePath,
			String afterSha,
			String afterPath,
			SymbolKey key,
			DocumentBinding binding,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		var beforeSrc = sourceSelector(context, SideRole.BEFORE, beforeSha, beforePath, SidePresence.PRESENT,
				SelectorGranularity.FACET, Optional.of(key), Optional.of(FacetKind.DECLARATION), Optional.empty());
		var afterSrc = sourceSelector(context, SideRole.AFTER, afterSha, afterPath, SidePresence.PRESENT,
				SelectorGranularity.FACET, Optional.of(key), Optional.of(FacetKind.DECLARATION), Optional.empty());

		var claimsCheck = selectClaimsForTarget(
				context, binding, afterPath, Optional.of(key), Optional.of(FacetKind.DECLARATION));
		if (claimsCheck.contradictory) {
			coverageGaps.add("Contradictory declaration claim metadata on document " + binding.documentId());
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
					Set.of(FacetKind.DECLARATION), IssueAdmission.INSUFFICIENT_EVIDENCE,
					ReasonCode.CONTRADICTORY_DIFF_CONTEXT, ReasonCode.CONTRADICTORY_DIFF_CONTEXT, List.of(),
					"Declaration claim contradicts selected binding version or bound SHA",
					binding, Optional.empty(), List.of(beforeSrc, afterSrc), Set.of(), false));
			return;
		}
		if (claimsCheck.missingTargetWitness) {
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
					Set.of(FacetKind.DECLARATION), IssueAdmission.INSUFFICIENT_EVIDENCE,
					ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING, ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING, List.of(),
					"Document claim has not been evaluated against pinned target SHA for changed declaration",
					binding, Optional.empty(),
					List.of(beforeSrc, afterSrc, documentSelector(context, binding, Optional.empty())),
					Set.of(), false));
			return;
		}
		if (claimsCheck.matchingClaims.isEmpty()) {
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
					Set.of(FacetKind.DECLARATION), IssueAdmission.INSUFFICIENT_EVIDENCE,
					ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, List.of(),
					"No verified declaration claim at target SHA for changed DECLARATION facet",
					binding, Optional.empty(),
					List.of(beforeSrc, afterSrc, documentSelector(context, binding, Optional.empty())),
					Set.of(), false));
			return;
		}

		for (var claim : claimsCheck.matchingClaims) {
			var docWitness = documentSelector(context, binding, Optional.of(claim));
			var witnesses = List.<WitnessSelector>of(beforeSrc, afterSrc, docWitness);
			var requiredIds = Set.of(beforeSrc.witnessId(), afterSrc.witnessId(), docWitness.witnessId());
			Optional<String> claimId = Optional.of(claim.claimId());

			switch (claim.verdict()) {
				case DECLARATION_INCOMPATIBLE, STALE_CLAIM_AGAINST_TARGET -> findings.add(buildActionableFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(FacetKind.DECLARATION), DriftClassification.SIGNATURE,
						claim.impactContext(), Optional.empty(),
						ReasonCode.SIGNATURE_CONTRACT_MISMATCH, claim.summaryRationale(),
						binding, claimId, witnesses, requiredIds, requiredIds, false));
				case DECLARATION_CONSISTENT -> findings.add(buildNoImpactFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(FacetKind.DECLARATION),
						claim.summaryRationale().toLowerCase().contains("revert")
								? ReasonCode.REVERTED_TO_CONSISTENT_NO_IMPACT
								: ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT,
						claim.summaryRationale(),
						Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
						Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()), claimId,
						witnesses, requiredIds, false));
				case POTENTIAL_MISMATCH_INITIAL_ONLY -> findings.add(buildInconclusiveFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(FacetKind.DECLARATION), IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE,
						ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS,
						ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS, List.of(),
						claim.summaryRationale(), binding, claimId, witnesses, requiredIds, false));
				case REFERENCED_ENTITY_REQUIRED -> findings.add(buildNoImpactFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(FacetKind.DECLARATION), ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT,
						"Referenced entity key remains valid at target SHA",
						Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
						Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()), claimId,
						witnesses, requiredIds, false));
				default -> findings.add(buildInconclusiveFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(FacetKind.DECLARATION), IssueAdmission.INSUFFICIENT_EVIDENCE,
						ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
						ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, List.of(),
						claim.summaryRationale(), binding, claimId, witnesses, Set.of(), false));
			}
		}
	}

	private void evaluateModifiedExecutableFacet(
			AssessmentContext context,
			String beforeSha,
			String beforePath,
			String afterSha,
			String afterPath,
			SymbolKey key,
			FacetKind facetKind,
			DocumentBinding binding,
			SemanticIndex semanticIndex,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		var beforeSrc = sourceSelector(context, SideRole.BEFORE, beforeSha, beforePath, SidePresence.PRESENT,
				SelectorGranularity.FACET, Optional.of(key), Optional.of(facetKind), Optional.empty());
		var afterSrc = sourceSelector(context, SideRole.AFTER, afterSha, afterPath, SidePresence.PRESENT,
				SelectorGranularity.FACET, Optional.of(key), Optional.of(facetKind), Optional.empty());

		var claimsCheck = selectClaimsForTarget(
				context, binding, afterPath, Optional.of(key), Optional.of(facetKind));
		if (claimsCheck.contradictory) {
			coverageGaps.add("Contradictory behavioral claim metadata on document " + binding.documentId());
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
					Set.of(facetKind), IssueAdmission.INSUFFICIENT_EVIDENCE,
					ReasonCode.CONTRADICTORY_DIFF_CONTEXT, ReasonCode.CONTRADICTORY_DIFF_CONTEXT, List.of(),
					"Behavioral claim contradicts selected binding version or bound SHA",
					binding, Optional.empty(), List.of(beforeSrc, afterSrc), Set.of(), false));
			return;
		}
		if (claimsCheck.missingTargetWitness) {
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
					Set.of(facetKind), IssueAdmission.INSUFFICIENT_EVIDENCE,
					ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING, ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING, List.of(),
					"Document claim has not been evaluated against pinned target SHA for changed " + facetKind,
					binding, Optional.empty(),
					List.of(beforeSrc, afterSrc, documentSelector(context, binding, Optional.empty())),
					Set.of(), false));
			return;
		}

		// Filter claims applicable to executable behavior (prevent declaration-only claims without facet from duplicating).
		List<DocumentClaim> executableClaims = claimsCheck.matchingClaims.stream()
				.filter(c -> c.targetSubjectFacet().isPresent() || isExecutableApplicableVerdict(c.verdict()))
				.toList();

		var semanticLookupKey = new SemanticKey(afterPath, key, binding.documentId(), binding.documentVersion());
		var semanticCandidates = semanticIndex.byTarget.getOrDefault(semanticLookupKey, List.of());

		ReasonCode needsSemanticReason = facetKind == FacetKind.INITIALIZER
				? ReasonCode.INITIALIZER_CHANGED_NEEDS_SEMANTIC
				: ReasonCode.BODY_CHANGED_NEEDS_SEMANTIC;

		// Case 1: No verified executable DocumentClaim exists on binding -> S05 cannot ground any claimId!
		if (executableClaims.isEmpty()) {
			Optional<ReasonCode> semRejection = evaluateOrphanSemanticCandidates(
					context, semanticCandidates, afterSha, afterPath, key, facetKind, binding, coverageGaps);
			ReasonCode finalReason = semRejection.orElse(needsSemanticReason);
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
					Set.of(facetKind), IssueAdmission.INSUFFICIENT_EVIDENCE,
					finalReason, finalReason, List.of(),
					semRejection.isPresent()
							? "Semantic assessment rejected or lacks verified target claim on binding: " + finalReason
							: facetKind + " changed while behavioral impact remains unassessed",
					binding, Optional.empty(),
					List.of(beforeSrc, afterSrc, documentSelector(context, binding, Optional.empty())),
					Set.of(), false));
			return;
		}

		// Identify any untargeted/orphan semantic candidates on this binding whose claimId does not match any executable claim.
		Set<String> validClaimIds = executableClaims.stream()
				.map(DocumentClaim::claimId)
				.collect(Collectors.toSet());
		List<SemanticAssessmentInput> orphanCandidates = semanticCandidates.stream()
				.filter(cand -> cand.groundedDocumentRefs().stream()
						.noneMatch(ref -> validClaimIds.contains(ref.claimId())))
				.toList();
		Optional<ReasonCode> orphanRejection = evaluateOrphanSemanticCandidates(
				context, orphanCandidates, afterSha, afterPath, key, facetKind, binding, coverageGaps);

		// Case 2: Evaluate each executable DocumentClaim independently with strict claimId matching (Fixes #1 & #2).
		for (var claim : executableClaims) {
			var docWitness = documentSelector(context, binding, Optional.of(claim));
			Optional<String> claimId = Optional.of(claim.claimId());
			boolean hasInitialProof = claim.verdict() == ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY;
			Set<String> initialAdmissionIds = hasInitialProof
					? Set.of(beforeSrc.witnessId(), afterSrc.witnessId(), docWitness.witnessId())
					: Set.of();

			List<SemanticAssessmentInput> claimCandidates = semanticCandidates.stream()
					.filter(cand -> cand.groundedDocumentRefs().stream()
							.anyMatch(ref -> ref.claimId().equals(claim.claimId())))
					.toList();

			if (!claimCandidates.isEmpty()) {
				var evaluatedSem = evaluateSemanticCandidatesForClaim(
						context, claimCandidates, afterSha, afterPath, key, facetKind, binding, claim, coverageGaps);
				if (evaluatedSem.rejectedReason.isPresent()) {
					ReasonCode rej = evaluatedSem.rejectedReason.get();
					findings.add(buildInconclusiveFinding(
							context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
							Set.of(facetKind),
							hasInitialProof ? IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE : IssueAdmission.INSUFFICIENT_EVIDENCE,
							hasInitialProof ? ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS : rej,
							rej, hasInitialProof ? List.of(ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS) : List.of(),
							claim.summaryRationale() + " (semantic assessment rejected: " + rej + ")",
							binding, claimId, List.of(beforeSrc, afterSrc, docWitness),
							initialAdmissionIds, false));
					continue;
				}
				if (evaluatedSem.acceptedInput.isPresent()) {
					var sem = evaluatedSem.acceptedInput.get();
					var semWitness = semanticSelector(context, sem, facetKind, claim.claimId());
					var witnesses = List.<WitnessSelector>of(beforeSrc, afterSrc, docWitness, semWitness);
					Set<String> requiredImpactIds = Set.of(
							beforeSrc.witnessId(), afterSrc.witnessId(), docWitness.witnessId(), semWitness.witnessId());

					if (sem.status() == SemanticStatus.BEHAVIOR_MISMATCH) {
						Set<String> requiredAdmissionIds = hasInitialProof ? initialAdmissionIds : requiredImpactIds;
						findings.add(buildActionableFinding(
								context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
								Set.of(facetKind), DriftClassification.SEMANTIC,
								sem.impactContext().or(claim::impactContext),
								sem.confidence(),
								ReasonCode.SEMANTIC_BEHAVIOR_MISMATCH,
								sem.rationale().orElse(claim.summaryRationale()),
								binding, claimId, witnesses, requiredImpactIds, requiredAdmissionIds, false));
						continue;
					}
					if (sem.status() == SemanticStatus.BEHAVIOR_CONSISTENT) {
						findings.add(buildNoImpactFinding(
								context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
								Set.of(facetKind), ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT,
								sem.rationale().orElse("Grounded S05 semantic assessment confirmed documentation claim remains consistent"),
								Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
								Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()), claimId,
								witnesses, requiredImpactIds, false));
						continue;
					}
					if (sem.status() == SemanticStatus.INCONCLUSIVE) {
						findings.add(buildInconclusiveFinding(
								context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
								Set.of(facetKind),
								hasInitialProof ? IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE : IssueAdmission.INSUFFICIENT_EVIDENCE,
								hasInitialProof ? ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS : needsSemanticReason,
								needsSemanticReason,
								hasInitialProof ? List.of(ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS) : List.of(),
								sem.rationale().orElse(claim.summaryRationale()),
								binding, claimId, List.of(beforeSrc, afterSrc, docWitness),
								initialAdmissionIds, false));
						continue;
					}
				}
			}

			// No S05 candidate specifically targeted this claim -> evaluate deterministic claim verdict on its own merits!
			var requiredDeterministicIds = Set.of(beforeSrc.witnessId(), afterSrc.witnessId(), docWitness.witnessId());
			if (claim.verdict() == ClaimVerdict.BEHAVIOR_CONSISTENT_PROVEN) {
				findings.add(buildNoImpactFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(facetKind),
						claim.summaryRationale().toLowerCase().contains("revert")
								? ReasonCode.REVERTED_TO_CONSISTENT_NO_IMPACT
								: ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT,
						claim.summaryRationale(),
						Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
						Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()), claimId,
						List.of(beforeSrc, afterSrc, docWitness), requiredDeterministicIds, false));
			} else if (claim.verdict() == ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN
					|| claim.verdict() == ClaimVerdict.STALE_CLAIM_AGAINST_TARGET) {
				findings.add(buildActionableFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(facetKind), DriftClassification.SEMANTIC,
						claim.impactContext(), Optional.empty(),
						ReasonCode.SEMANTIC_BEHAVIOR_MISMATCH, claim.summaryRationale(),
						binding, claimId, List.of(beforeSrc, afterSrc, docWitness),
						requiredDeterministicIds, requiredDeterministicIds, false));
			} else if (hasInitialProof) {
				ReasonCode primary = orphanRejection.orElse(ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS);
				List<ReasonCode> secondary = orphanRejection.isPresent()
						? List.of(ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS)
						: List.of();
				findings.add(buildInconclusiveFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(facetKind), IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE,
						ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS,
						primary, secondary, claim.summaryRationale(),
						binding, claimId, List.of(beforeSrc, afterSrc, docWitness),
						initialAdmissionIds, false));
			} else {
				ReasonCode primary = orphanRejection.orElse(needsSemanticReason);
				findings.add(buildInconclusiveFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(facetKind), IssueAdmission.INSUFFICIENT_EVIDENCE,
						primary, primary, List.of(), claim.summaryRationale(),
						binding, claimId, List.of(beforeSrc, afterSrc, docWitness),
						Set.of(), false));
			}
		}
	}

	private static boolean isExecutableApplicableVerdict(ClaimVerdict verdict) {
		return verdict == ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN
				|| verdict == ClaimVerdict.BEHAVIOR_CONSISTENT_PROVEN
				|| verdict == ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY
				|| verdict == ClaimVerdict.STALE_CLAIM_AGAINST_TARGET
				|| verdict == ClaimVerdict.UNVERIFIED_OR_AMBIGUOUS;
	}

	private void evaluateModifiedMemberOrderFacet(
			AssessmentContext context,
			String beforeSha,
			String beforePath,
			String afterSha,
			String afterPath,
			SymbolKey key,
			DocumentBinding binding,
			List<ImpactFinding> findings,
			Set<String> coverageGaps) {
		var beforeSrc = sourceSelector(context, SideRole.BEFORE, beforeSha, beforePath, SidePresence.PRESENT,
				SelectorGranularity.FACET, Optional.of(key), Optional.of(FacetKind.MEMBER_ORDER), Optional.empty());
		var afterSrc = sourceSelector(context, SideRole.AFTER, afterSha, afterPath, SidePresence.PRESENT,
				SelectorGranularity.FACET, Optional.of(key), Optional.of(FacetKind.MEMBER_ORDER), Optional.empty());
		var claimsCheck = selectClaimsForTarget(
				context, binding, afterPath, Optional.of(key), Optional.of(FacetKind.MEMBER_ORDER));
		if (claimsCheck.contradictory) {
			coverageGaps.add("Contradictory member-order claim metadata on document " + binding.documentId());
		}
		if (claimsCheck.matchingClaims.isEmpty()) {
			var docWitness = documentSelector(context, binding, Optional.empty());
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
					Set.of(FacetKind.MEMBER_ORDER), IssueAdmission.INSUFFICIENT_EVIDENCE,
					ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW,
					ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW, List.of(),
					"Member order changed in enclosing type; requires review against document binding",
					binding, Optional.empty(), List.of(beforeSrc, afterSrc, docWitness), Set.of(), false));
			return;
		}
		for (var claim : claimsCheck.matchingClaims) {
			var docWitness = documentSelector(context, binding, Optional.of(claim));
			Optional<String> claimId = Optional.of(claim.claimId());
			if (claim.verdict() == ClaimVerdict.DECLARATION_CONSISTENT) {
				var requiredIds = Set.of(beforeSrc.witnessId(), afterSrc.witnessId(), docWitness.witnessId());
				findings.add(buildNoImpactFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(FacetKind.MEMBER_ORDER), ReasonCode.VERIFIED_CLAIM_CONSISTENT_NO_IMPACT,
						"Verified claim confirms member order change does not affect documentation",
						Optional.of(binding.documentId()), Optional.of(binding.documentVersion()),
						Optional.of(binding.documentBoundSha()), Optional.of(binding.bindingId()), claimId,
						List.of(beforeSrc, afterSrc, docWitness), requiredIds, false));
			} else {
				findings.add(buildInconclusiveFinding(
						context, Optional.of(beforePath), Optional.of(afterPath), Optional.of(key),
						Set.of(FacetKind.MEMBER_ORDER), IssueAdmission.INSUFFICIENT_EVIDENCE,
						ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW,
						ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW, List.of(),
						claim.summaryRationale(),
						binding, claimId, List.of(beforeSrc, afterSrc, docWitness), Set.of(), false));
			}
		}
	}

	private void emitStaleClaimOnUnchangedEntity(
			AssessmentContext context,
			EntityFact fact,
			DocumentBinding binding,
			DocumentClaim claim,
			List<ImpactFinding> findings) {
		var beforeSide = fact.before().orElseThrow();
		var afterSide = fact.after().orElseThrow();
		var beforeSrc = sourceSelector(context, SideRole.BEFORE, beforeSide.snapshot().sha(), beforeSide.path(),
				SidePresence.PRESENT, SelectorGranularity.SYMBOL, Optional.of(afterSide.key()),
				claim.targetSubjectFacet(), Optional.empty());
		var afterSrc = sourceSelector(context, SideRole.AFTER, afterSide.snapshot().sha(), afterSide.path(),
				SidePresence.PRESENT, SelectorGranularity.SYMBOL, Optional.of(afterSide.key()),
				claim.targetSubjectFacet(), Optional.empty());
		var docWitness = documentSelector(context, binding, Optional.of(claim));
		var requiredIds = Set.of(beforeSrc.witnessId(), afterSrc.witnessId(), docWitness.witnessId());
		var facets = claim.targetSubjectFacet().map(Set::of).orElse(Set.of());

		if (claim.verdict() == ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY) {
			findings.add(buildInconclusiveFinding(
					context, Optional.of(beforeSide.path()), Optional.of(afterSide.path()),
					Optional.of(afterSide.key()), facets,
					IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE,
					ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS,
					ReasonCode.POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS, List.of(),
					claim.summaryRationale(), binding, Optional.of(claim.claimId()),
					List.of(beforeSrc, afterSrc, docWitness), requiredIds, false));
			return;
		}

		DriftClassification classification = claim.verdict() == ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN
				? DriftClassification.SEMANTIC
				: DriftClassification.SIGNATURE;
		ReasonCode reason = classification == DriftClassification.SEMANTIC
				? ReasonCode.SEMANTIC_BEHAVIOR_MISMATCH
				: ReasonCode.SIGNATURE_CONTRACT_MISMATCH;
		findings.add(buildActionableFinding(
				context, Optional.of(beforeSide.path()), Optional.of(afterSide.path()),
				Optional.of(afterSide.key()), facets,
				classification, claim.impactContext(), Optional.empty(),
				reason, claim.summaryRationale(), binding, Optional.of(claim.claimId()),
				List.of(beforeSrc, afterSrc, docWitness), requiredIds, requiredIds, false));
	}

	private Optional<ReasonCode> evaluateOrphanSemanticCandidates(
			AssessmentContext context,
			List<SemanticAssessmentInput> candidates,
			String targetSha,
			String path,
			SymbolKey key,
			FacetKind facetKind,
			DocumentBinding binding,
			Set<String> coverageGaps) {
		ReasonCode firstRejection = null;
		for (var candidate : candidates) {
			if (!candidate.jobId().equals(context.jobId())
					|| !candidate.attemptId().equals(context.attemptId())
					|| !candidate.withinDeadline()) {
				if (firstRejection == null) {
					firstRejection = ReasonCode.SEMANTIC_LATE_OR_WRONG_ATTEMPT;
				}
				coverageGaps.add("Late or mismatched-attempt semantic assessment for " + path);
				continue;
			}
			if (!candidate.evaluatedTargetSha().equals(targetSha)
					|| !candidate.documentId().equals(binding.documentId())
					|| !candidate.documentVersion().equals(binding.documentVersion())
					|| !candidate.documentBoundSha().equals(binding.documentBoundSha())) {
				if (firstRejection == null) {
					firstRejection = ReasonCode.SEMANTIC_WRONG_SHA_OR_VERSION;
				}
				coverageGaps.add("Semantic assessment SHA or document version mismatch for " + path);
				continue;
			}
			if (candidate.status() == SemanticStatus.UNAVAILABLE || candidate.status() == SemanticStatus.TIMED_OUT) {
				if (firstRejection == null) {
					firstRejection = ReasonCode.SEMANTIC_UNAVAILABLE_OR_FAILED;
				}
				coverageGaps.add("Semantic assessment unavailable/timed out for " + path);
				continue;
			}
			if (candidate.status() == SemanticStatus.NOT_REQUESTED) {
				continue;
			}
			// Any remaining active candidate in orphan list lacks a matching verified DocumentClaim on this binding!
			if (firstRejection == null) {
				firstRejection = ReasonCode.SEMANTIC_UNGROUNDED;
			}
			coverageGaps.add("Semantic assessment lacks concrete grounded source/claim refs for " + path);
		}
		return Optional.ofNullable(firstRejection);
	}

	private SemanticEvaluationResult evaluateSemanticCandidatesForClaim(
			AssessmentContext context,
			List<SemanticAssessmentInput> candidates,
			String targetSha,
			String path,
			SymbolKey key,
			FacetKind facetKind,
			DocumentBinding binding,
			DocumentClaim claim,
			Set<String> coverageGaps) {
		SemanticAssessmentInput accepted = null;
		ReasonCode firstRejection = null;

		for (var candidate : candidates) {
			if (!candidate.jobId().equals(context.jobId())
					|| !candidate.attemptId().equals(context.attemptId())
					|| !candidate.withinDeadline()) {
				if (firstRejection == null) {
					firstRejection = ReasonCode.SEMANTIC_LATE_OR_WRONG_ATTEMPT;
				}
				coverageGaps.add("Late or mismatched-attempt semantic assessment for " + path);
				continue;
			}
			if (!candidate.evaluatedTargetSha().equals(targetSha)
					|| !candidate.documentId().equals(binding.documentId())
					|| !candidate.documentVersion().equals(binding.documentVersion())
					|| !candidate.documentBoundSha().equals(binding.documentBoundSha())) {
				if (firstRejection == null) {
					firstRejection = ReasonCode.SEMANTIC_WRONG_SHA_OR_VERSION;
				}
				coverageGaps.add("Semantic assessment SHA or document version mismatch for " + path);
				continue;
			}
			if (candidate.status() == SemanticStatus.UNAVAILABLE || candidate.status() == SemanticStatus.TIMED_OUT) {
				if (firstRejection == null) {
					firstRejection = ReasonCode.SEMANTIC_UNAVAILABLE_OR_FAILED;
				}
				coverageGaps.add("Semantic assessment unavailable/timed out for " + path);
				continue;
			}
			if (candidate.status() == SemanticStatus.NOT_REQUESTED) {
				continue;
			}

			boolean sourceGrounded = candidate.groundedSourceRefs().stream().anyMatch(ref ->
					ref.sideRole() == SideRole.AFTER
							&& ref.pinnedSha().equals(targetSha)
							&& ref.path().equals(path)
							&& ref.symbolKey().equals(key)
							&& ref.facetKind() == facetKind);
			// Fix #1: Require exact match on BOTH bindingId AND claim.claimId()!
			boolean docGrounded = candidate.groundedDocumentRefs().stream().anyMatch(ref ->
					ref.documentId().equals(binding.documentId())
							&& ref.documentVersion().equals(binding.documentVersion())
							&& ref.documentBoundSha().equals(binding.documentBoundSha())
							&& ref.bindingId().equals(binding.bindingId())
							&& ref.claimId().equals(claim.claimId()));
			if (!sourceGrounded || !docGrounded) {
				if (firstRejection == null) {
					firstRejection = ReasonCode.SEMANTIC_UNGROUNDED;
				}
				coverageGaps.add("Semantic assessment lacks concrete grounded source/claim refs for "
						+ path + " claim " + claim.claimId());
				continue;
			}

			if (accepted != null && !accepted.equals(candidate)) {
				coverageGaps.add("Conflicting semantic assessments for " + path + " claim " + claim.claimId());
				return new SemanticEvaluationResult(Optional.empty(), Optional.of(ReasonCode.CONTRADICTORY_DIFF_CONTEXT));
			}
			accepted = candidate;
		}

		if (accepted != null) {
			return new SemanticEvaluationResult(Optional.of(accepted), Optional.empty());
		}
		return new SemanticEvaluationResult(Optional.empty(), Optional.ofNullable(firstRejection));
	}

	private record SemanticEvaluationResult(
			Optional<SemanticAssessmentInput> acceptedInput,
			Optional<ReasonCode> rejectedReason) {
	}

	private LookupIndex indexLookups(
			AssessmentContext context,
			List<DocumentLookupResponse> responses,
			Set<ImpactDiagnostic> diagnostics,
			Set<String> coverageGaps) {
		var byKey = new LinkedHashMap<DocumentLookupKey, DocumentLookupResponse>();
		var conflictingKeys = new LinkedHashSet<DocumentLookupKey>();

		for (var response : responses) {
			if (response == null) {
				diagnostics.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.INVALID_ASSESSMENT_INPUT,
						"Null lookup response ignored"));
				coverageGaps.add("Null document lookup response encountered");
				continue;
			}
			var key = response.key();
			if (!matchesScope(context, key.tenantId(), key.workspaceId(), key.repository(),
					key.targetKind(), key.targetRef())) {
				diagnostics.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.FOREIGN_SCOPE_REJECTED,
						"Rejected external lookup response outside authorized assessment scope"));
				continue;
			}

			// Sanitize bindings inside matching lookup response.
			var validBindings = new ArrayList<DocumentBinding>();
			boolean hadForeignBinding = false;
			for (var binding : response.bindings()) {
				if (binding == null) {
					continue;
				}
				if (!binding.tenantId().equals(context.tenantId())
						|| !binding.workspaceId().equals(context.workspaceId())
						|| !binding.repository().equals(context.repository())) {
					hadForeignBinding = true;
					diagnostics.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.FOREIGN_SCOPE_REJECTED,
							"Filtered foreign document binding outside authorized tenant/workspace/repository"));
					continue;
				}
				validBindings.add(binding);
			}
			if (hadForeignBinding && response.completeness() == LookupCompleteness.COMPLETE && validBindings.isEmpty()) {
				response = new DocumentLookupResponse(
						key, LookupCompleteness.DENIED, List.of(),
						Optional.of("Filtered foreign scope bindings from lookup response"));
			} else if (hadForeignBinding) {
				response = new DocumentLookupResponse(
						key, response.completeness(), validBindings, response.diagnosticReason());
			}

			var existing = byKey.get(key);
			if (existing != null) {
				if (!existing.equals(response)) {
					conflictingKeys.add(key);
					coverageGaps.add("Conflicting lookup responses received for key at path " + key.path());
					byKey.put(key, new DocumentLookupResponse(
							key, LookupCompleteness.AMBIGUOUS, List.of(),
							Optional.of("Conflicting duplicate lookup responses")));
				}
			} else {
				byKey.put(key, response);
			}
		}
		return new LookupIndex(byKey, conflictingKeys);
	}

	private SemanticIndex indexSemantics(
			AssessmentContext context,
			List<SemanticAssessmentInput> inputs,
			Set<ImpactDiagnostic> diagnostics) {
		var byTarget = new LinkedHashMap<SemanticKey, List<SemanticAssessmentInput>>();
		for (var input : inputs) {
			if (input == null) {
				continue;
			}
			if (!matchesScope(context, input.tenantId(), input.workspaceId(), input.repository(),
					input.targetKind(), input.targetRef())) {
				diagnostics.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.FOREIGN_SCOPE_REJECTED,
						"Rejected foreign semantic assessment outside authorized assessment scope"));
				continue;
			}
			var key = new SemanticKey(input.path(), input.symbolKey(), input.documentId(), input.documentVersion());
			byTarget.computeIfAbsent(key, ignored -> new ArrayList<>()).add(input);
		}
		return new SemanticIndex(byTarget);
	}

	private Optional<ReasonCode> checkRequiredLookupKey(
			DocumentLookupKey requiredKey,
			Optional<SymbolKey> filterSymbolForFileLookup,
			LookupIndex index,
			Set<String> coverageGaps,
			List<DocumentLookupKey> coveredEmptyKeys,
			Map<BindingIdentityKey, DocumentBinding> combinedBindings) {
		DocumentLookupResponse resp = index.byKey.get(requiredKey);
		if (resp == null) {
			coverageGaps.add("Missing required " + requiredKey.granularity()
					+ " lookup response for " + requiredKey.sideRole() + " " + requiredKey.path());
			return Optional.of(ReasonCode.MISSING_OLD_OR_NEW_LOOKUP_KEY);
		}
		if (resp.completeness() != LookupCompleteness.COMPLETE) {
			ReasonCode reason = mapLookupIncompleteReason(resp.completeness());
			coverageGaps.add(requiredKey.granularity() + " lookup " + resp.completeness()
					+ " for " + requiredKey.sideRole() + " " + requiredKey.path());
			return Optional.of(reason);
		}

		boolean addedAny = false;
		for (var binding : resp.bindings()) {
			if (requiredKey.granularity() == LookupGranularity.FILE && filterSymbolForFileLookup.isPresent()) {
				SymbolKey targetSymbol = filterSymbolForFileLookup.get();
				if (binding.granularity() != BindingGranularity.FILE_ONLY
						&& !binding.boundSymbolKey().map(targetSymbol::equals).orElse(false)) {
					continue;
				}
			}
			combinedBindings.putIfAbsent(BindingIdentityKey.of(binding), binding);
			addedAny = true;
		}
		if (!addedAny) {
			coveredEmptyKeys.add(requiredKey);
		}
		return Optional.empty();
	}

	private LookupEvaluation evaluateSingleKeyLookup(
			DocumentLookupKey requiredKey,
			Optional<SymbolKey> filterSymbol,
			LookupIndex index,
			Set<String> coverageGaps) {
		var combinedBindings = new LinkedHashMap<BindingIdentityKey, DocumentBinding>();
		var emptyKeys = new ArrayList<DocumentLookupKey>();
		Optional<ReasonCode> reason = checkRequiredLookupKey(
				requiredKey, filterSymbol, index, coverageGaps, emptyKeys, combinedBindings);
		return new LookupEvaluation(reason, List.copyOf(combinedBindings.values()), emptyKeys);
	}

	private LookupEvaluation evaluateTargetEntityLookupCoverage(
			AssessmentContext context,
			String targetSha,
			String targetPath,
			SymbolKey key,
			LookupIndex index,
			Set<String> coverageGaps) {
		var combinedBindings = new LinkedHashMap<BindingIdentityKey, DocumentBinding>();
		var emptyKeys = new ArrayList<DocumentLookupKey>();
		Optional<ReasonCode> firstReason = Optional.empty();

		Optional<ReasonCode> fileReason = checkRequiredLookupKey(
				fileLookupKey(context, SideRole.TARGET, targetSha, targetPath),
				Optional.of(key), index, coverageGaps, emptyKeys, combinedBindings);
		if (fileReason.isPresent()) {
			firstReason = fileReason;
		}

		Optional<ReasonCode> symReason = checkRequiredLookupKey(
				symbolLookupKey(context, SideRole.TARGET, targetSha, targetPath, key),
				Optional.empty(), index, coverageGaps, emptyKeys, combinedBindings);
		if (firstReason.isEmpty() && symReason.isPresent()) {
			firstReason = symReason;
		}

		return new LookupEvaluation(firstReason, List.copyOf(combinedBindings.values()), emptyKeys);
	}

	private LookupEvaluation evaluateFileAxisLookupCoverage(
			Set<DocumentLookupKey> requiredKeys,
			LookupIndex index,
			Set<String> coverageGaps) {
		var combinedBindings = new LinkedHashMap<BindingIdentityKey, DocumentBinding>();
		var emptyKeys = new ArrayList<DocumentLookupKey>();
		Optional<ReasonCode> firstReason = Optional.empty();

		for (var key : requiredKeys) {
			Optional<ReasonCode> reason = checkRequiredLookupKey(
					key, Optional.empty(), index, coverageGaps, emptyKeys, combinedBindings);
			if (firstReason.isEmpty() && reason.isPresent()) {
				firstReason = reason;
			}
		}

		return new LookupEvaluation(firstReason, List.copyOf(combinedBindings.values()), emptyKeys);
	}

	private LookupEvaluation evaluateEntityLookupCoverage(
			AssessmentContext context,
			boolean needBefore,
			String beforeSha,
			String beforePath,
			boolean needAfter,
			String afterSha,
			String afterPath,
			SymbolKey key,
			LookupIndex index,
			Set<String> coverageGaps) {
		var combinedBindings = new LinkedHashMap<BindingIdentityKey, DocumentBinding>();
		var emptyKeys = new ArrayList<DocumentLookupKey>();
		Optional<ReasonCode> firstReason = Optional.empty();

		// Fix #4: Require BOTH FILE lookup and SYMBOL lookup for each PRESENT side declared in buildLookupKeys!
		if (needBefore) {
			Optional<ReasonCode> bf = checkRequiredLookupKey(
					fileLookupKey(context, SideRole.BEFORE, beforeSha, beforePath),
					Optional.of(key), index, coverageGaps, emptyKeys, combinedBindings);
			if (bf.isPresent()) {
				firstReason = bf;
			}
			Optional<ReasonCode> bs = checkRequiredLookupKey(
					symbolLookupKey(context, SideRole.BEFORE, beforeSha, beforePath, key),
					Optional.empty(), index, coverageGaps, emptyKeys, combinedBindings);
			if (firstReason.isEmpty() && bs.isPresent()) {
				firstReason = bs;
			}
		}

		if (needAfter) {
			Optional<ReasonCode> af = checkRequiredLookupKey(
					fileLookupKey(context, SideRole.AFTER, afterSha, afterPath),
					Optional.of(key), index, coverageGaps, emptyKeys, combinedBindings);
			if (firstReason.isEmpty() && af.isPresent()) {
				firstReason = af;
			}
			Optional<ReasonCode> as = checkRequiredLookupKey(
					symbolLookupKey(context, SideRole.AFTER, afterSha, afterPath, key),
					Optional.empty(), index, coverageGaps, emptyKeys, combinedBindings);
			if (firstReason.isEmpty() && as.isPresent()) {
				firstReason = as;
			}
		}

		return new LookupEvaluation(firstReason, List.copyOf(combinedBindings.values()), emptyKeys);
	}

	private static List<DocumentBinding> findBindingsForFile(
			LookupIndex index, SideRole role, String sha, String path) {
		var result = new LinkedHashMap<BindingIdentityKey, DocumentBinding>();
		for (var entry : index.byKey.entrySet()) {
			var k = entry.getKey();
			if (k.sideRole() == role && k.pinnedSha().equals(sha) && k.path().equals(path)) {
				for (var b : entry.getValue().bindings()) {
					result.putIfAbsent(BindingIdentityKey.of(b), b);
				}
			}
		}
		return List.copyOf(result.values());
	}

	private static List<DocumentBinding> findSymbolBindings(
			LookupIndex index, SideRole role, String sha, String path, SymbolKey key) {
		var result = new LinkedHashMap<BindingIdentityKey, DocumentBinding>();
		for (var entry : index.byKey.entrySet()) {
			var k = entry.getKey();
			if (k.sideRole() != role || !k.pinnedSha().equals(sha) || !k.path().equals(path)) {
				continue;
			}
			if (k.granularity() == LookupGranularity.SYMBOL
					&& k.symbolKey().map(key::equals).orElse(false)) {
				for (var b : entry.getValue().bindings()) {
					result.putIfAbsent(BindingIdentityKey.of(b), b);
				}
			} else if (k.granularity() == LookupGranularity.FILE) {
				for (var b : entry.getValue().bindings()) {
					if (b.granularity() == BindingGranularity.EXACT_SYMBOL
							&& b.boundSymbolKey().map(key::equals).orElse(false)) {
						result.putIfAbsent(BindingIdentityKey.of(b), b);
					}
				}
			}
		}
		return List.copyOf(result.values());
	}

	private static ClaimSelection selectClaimsForTarget(
			AssessmentContext context,
			DocumentBinding binding,
			String subjectPath,
			Optional<SymbolKey> subjectSymbol,
			Optional<FacetKind> subjectFacet) {
		var matching = new ArrayList<DocumentClaim>();
		boolean sawNonTargetEval = false;
		for (var claim : binding.claims()) {
			if (!claim.documentId().equals(binding.documentId())
					|| !claim.documentVersion().equals(binding.documentVersion())
					|| !claim.documentBoundSha().equals(binding.documentBoundSha())) {
				return new ClaimSelection(List.of(), false, true);
			}
			boolean pathMatches = claim.targetSubjectPath().equals(subjectPath)
					|| claim.targetSubjectPath().equals(binding.boundPath());
			boolean symbolMatches = subjectSymbol.isEmpty()
					|| claim.targetSubjectSymbol().isEmpty()
					|| claim.targetSubjectSymbol().get().equals(subjectSymbol.get());
			boolean facetMatches = subjectFacet.isEmpty()
					|| claim.targetSubjectFacet().isEmpty()
					|| claim.targetSubjectFacet().get() == subjectFacet.get();
			if (!pathMatches || !symbolMatches || !facetMatches) {
				continue;
			}
			if (!claim.evaluatedTargetSha().equals(context.authoritativeTargetSha())) {
				sawNonTargetEval = true;
				continue;
			}
			matching.add(claim);
		}
		return new ClaimSelection(matching, matching.isEmpty() && sawNonTargetEval, false);
	}

	private static ClaimSelection selectClaimsForFile(
			AssessmentContext context,
			DocumentBinding binding,
			String beforePath,
			String afterPath) {
		var matching = new ArrayList<DocumentClaim>();
		boolean sawNonTargetEval = false;
		for (var claim : binding.claims()) {
			if (!claim.documentId().equals(binding.documentId())
					|| !claim.documentVersion().equals(binding.documentVersion())
					|| !claim.documentBoundSha().equals(binding.documentBoundSha())) {
				return new ClaimSelection(List.of(), false, true);
			}
			boolean pathMatches = claim.targetSubjectPath().equals(beforePath)
					|| claim.targetSubjectPath().equals(afterPath);
			if (!pathMatches) {
				continue;
			}
			if (!claim.evaluatedTargetSha().equals(context.authoritativeTargetSha())) {
				sawNonTargetEval = true;
				continue;
			}
			matching.add(claim);
		}
		return new ClaimSelection(matching, matching.isEmpty() && sawNonTargetEval, false);
	}

	private record ClaimSelection(
			List<DocumentClaim> matchingClaims,
			boolean missingTargetWitness,
			boolean contradictory) {
	}

	private ImpactReport aggregateReport(
			AssessmentContext context,
			List<ImpactFinding> sortedFindings,
			List<ImpactDiagnostic> sortedDiags,
			List<String> sortedGaps,
			List<String> sortedExclusions,
			int evaluatedFileCount,
			int excludedFileCount,
			int failedOrIncompleteFileCount) {
		var counts = countOutcomes(sortedFindings);
		boolean hasGaps = !sortedGaps.isEmpty();

		ReportProcessingStatus processingStatus;
		Optional<ImpactOutcome> overallOutcome;

		boolean allExcluded = !sortedFindings.isEmpty()
				&& counts.excluded == sortedFindings.size()
				&& counts.actionable == 0
				&& counts.noImpact == 0
				&& counts.inconclusive == 0;

		if (allExcluded) {
			processingStatus = ReportProcessingStatus.OUT_OF_SCOPE;
			overallOutcome = Optional.empty();
		} else if (counts.actionable > 0) {
			processingStatus = hasGaps ? ReportProcessingStatus.PARTIAL : ReportProcessingStatus.COMPLETE;
			overallOutcome = Optional.of(ImpactOutcome.ACTIONABLE);
		} else if (hasGaps) {
			processingStatus = ReportProcessingStatus.PARTIAL;
			overallOutcome = Optional.of(ImpactOutcome.INCONCLUSIVE);
		} else if (counts.inconclusive > 0) {
			processingStatus = ReportProcessingStatus.COMPLETE;
			overallOutcome = Optional.of(ImpactOutcome.INCONCLUSIVE);
		} else if (counts.noImpact > 0) {
			processingStatus = ReportProcessingStatus.COMPLETE;
			overallOutcome = Optional.of(ImpactOutcome.NO_IMPACT);
		} else {
			processingStatus = ReportProcessingStatus.PARTIAL;
			overallOutcome = Optional.of(ImpactOutcome.INCONCLUSIVE);
		}

		boolean completeCoverage = !hasGaps && processingStatus != ReportProcessingStatus.FAILED;
		var summary = new ImpactCoverageSummary(
				completeCoverage,
				evaluatedFileCount,
				excludedFileCount,
				failedOrIncompleteFileCount,
				counts.actionable,
				counts.noImpact,
				counts.inconclusive,
				counts.excluded,
				sortedGaps,
				sortedExclusions);

		return new ImpactReport(
				CLASSIFIER_VERSION,
				processingStatus,
				overallOutcome,
				context,
				sortedFindings,
				summary,
				sortedDiags);
	}

	private ImpactReport failedReport(
			AssessmentContext context,
			List<ImpactDiagnostic> diagnostics,
			List<String> gaps) {
		var summary = new ImpactCoverageSummary(false, 0, 0, 0, 0, 0, 0, 0, sortStrings(gaps), List.of());
		return new ImpactReport(
				CLASSIFIER_VERSION,
				ReportProcessingStatus.FAILED,
				Optional.empty(),
				context,
				List.of(),
				summary,
				sortDiagnostics(diagnostics));
	}

	private ImpactFinding buildActionableFinding(
			AssessmentContext context,
			Optional<String> beforePath,
			Optional<String> afterPath,
			Optional<SymbolKey> symbolKey,
			Set<FacetKind> evaluatedFacets,
			DriftClassification classification,
			Optional<VerifiedImpactContext> impactContext,
			Optional<Double> semanticConfidence,
			ReasonCode primaryReason,
			String explanation,
			DocumentBinding binding,
			Optional<String> claimId,
			List<WitnessSelector> witnesses,
			Set<String> requiredImpactIds,
			Set<String> requiredAdmissionIds,
			boolean initialAssessment) {
		return buildActionableFindingTagged(
				context, beforePath, afterPath, symbolKey, evaluatedFacets,
				classification, impactContext, semanticConfidence, primaryReason,
				explanation, binding, claimId, witnesses, requiredImpactIds,
				requiredAdmissionIds, initialAssessment, classification.name());
	}

	private ImpactFinding buildActionableFindingTagged(
			AssessmentContext context,
			Optional<String> beforePath,
			Optional<String> afterPath,
			Optional<SymbolKey> symbolKey,
			Set<FacetKind> evaluatedFacets,
			DriftClassification classification,
			Optional<VerifiedImpactContext> impactContext,
			Optional<Double> semanticConfidence,
			ReasonCode primaryReason,
			String explanation,
			DocumentBinding binding,
			Optional<String> claimId,
			List<WitnessSelector> witnesses,
			Set<String> requiredImpactIds,
			Set<String> requiredAdmissionIds,
			boolean initialAssessment,
			String tagSuffix) {
		Optional<Severity> severity = impactContext.map(VerifiedImpactContext::level);
		SeverityStatus severityStatus = severity.isPresent()
				? SeverityStatus.EVALUATED : SeverityStatus.SEVERITY_NOT_EVALUATED;
		Optional<String> severityRationale = impactContext.map(ctx ->
				ctx.consequenceRationale() + " [" + ctx.criticalityRef() + "]");
		Optional<Double> effectiveConfidence = classification == DriftClassification.SEMANTIC
				? semanticConfidence : Optional.empty();
		Optional<String> docId = Optional.of(binding.documentId());
		Optional<String> docVer = Optional.of(binding.documentVersion());
		Optional<String> bindingId = Optional.of(binding.bindingId());
		String tag = tagSuffix.equals(classification.name())
				? classification.name()
				: classification.name() + "|" + tagSuffix;
		String findingId = computeFindingId(context, UnitDisposition.EVALUATED, ImpactOutcome.ACTIONABLE,
				beforePath, afterPath, symbolKey, evaluatedFacets,
				docId, docVer, bindingId, claimId, primaryReason, tag);
		return new ImpactFinding(
				findingId,
				UnitDisposition.EVALUATED,
				ImpactOutcome.ACTIONABLE,
				Optional.of(classification),
				severity,
				severityStatus,
				severityRationale,
				effectiveConfidence,
				IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE,
				primaryReason,
				primaryReason,
				List.of(),
				explanation,
				beforePath,
				afterPath,
				symbolKey,
				evaluatedFacets,
				docId,
				docVer,
				Optional.of(binding.documentBoundSha()),
				bindingId,
				claimId,
				sortWitnesses(witnesses),
				requiredImpactIds,
				requiredAdmissionIds,
				initialAssessment);
	}

	private ImpactFinding buildNoImpactFinding(
			AssessmentContext context,
			Optional<String> beforePath,
			Optional<String> afterPath,
			Optional<SymbolKey> symbolKey,
			Set<FacetKind> evaluatedFacets,
			ReasonCode primaryReason,
			String explanation,
			Optional<String> documentId,
			Optional<String> documentVersion,
			Optional<String> documentBoundSha,
			Optional<String> bindingId,
			Optional<String> claimId,
			List<WitnessSelector> witnesses,
			Set<String> requiredImpactIds,
			boolean initialAssessment) {
		return buildNoImpactFindingTagged(
				context, beforePath, afterPath, symbolKey, evaluatedFacets,
				primaryReason, explanation, documentId, documentVersion, documentBoundSha,
				bindingId, claimId, witnesses, requiredImpactIds, initialAssessment, "");
	}

	private ImpactFinding buildNoImpactFindingTagged(
			AssessmentContext context,
			Optional<String> beforePath,
			Optional<String> afterPath,
			Optional<SymbolKey> symbolKey,
			Set<FacetKind> evaluatedFacets,
			ReasonCode primaryReason,
			String explanation,
			Optional<String> documentId,
			Optional<String> documentVersion,
			Optional<String> documentBoundSha,
			Optional<String> bindingId,
			Optional<String> claimId,
			List<WitnessSelector> witnesses,
			Set<String> requiredImpactIds,
			boolean initialAssessment,
			String tagSuffix) {
		String tag = tagSuffix.isEmpty() ? "NO_IMPACT" : "NO_IMPACT|" + tagSuffix;
		String findingId = computeFindingId(context, UnitDisposition.EVALUATED, ImpactOutcome.NO_IMPACT,
				beforePath, afterPath, symbolKey, evaluatedFacets,
				documentId, documentVersion, bindingId, claimId, primaryReason, tag);
		return new ImpactFinding(
				findingId,
				UnitDisposition.EVALUATED,
				ImpactOutcome.NO_IMPACT,
				Optional.empty(),
				Optional.empty(),
				SeverityStatus.SEVERITY_NOT_EVALUATED,
				Optional.empty(),
				Optional.empty(),
				IssueAdmission.NOT_APPLICABLE,
				primaryReason,
				primaryReason,
				List.of(),
				explanation,
				beforePath,
				afterPath,
				symbolKey,
				evaluatedFacets,
				documentId,
				documentVersion,
				documentBoundSha,
				bindingId,
				claimId,
				sortWitnesses(witnesses),
				requiredImpactIds,
				Set.of(),
				initialAssessment);
	}

	private ImpactFinding buildInconclusiveFinding(
			AssessmentContext context,
			Optional<String> beforePath,
			Optional<String> afterPath,
			Optional<SymbolKey> symbolKey,
			Set<FacetKind> evaluatedFacets,
			IssueAdmission admission,
			ReasonCode admissionReason,
			ReasonCode primaryReason,
			List<ReasonCode> secondaryReasons,
			String explanation,
			DocumentBinding binding,
			Optional<String> claimId,
			List<WitnessSelector> witnesses,
			Set<String> requiredAdmissionIds,
			boolean initialAssessment) {
		return buildInconclusiveFindingTagged(
				context, beforePath, afterPath, symbolKey, evaluatedFacets,
				admission, admissionReason, primaryReason, secondaryReasons,
				explanation, binding, claimId, witnesses, requiredAdmissionIds,
				initialAssessment, "");
	}

	private ImpactFinding buildInconclusiveFindingTagged(
			AssessmentContext context,
			Optional<String> beforePath,
			Optional<String> afterPath,
			Optional<SymbolKey> symbolKey,
			Set<FacetKind> evaluatedFacets,
			IssueAdmission admission,
			ReasonCode admissionReason,
			ReasonCode primaryReason,
			List<ReasonCode> secondaryReasons,
			String explanation,
			DocumentBinding binding,
			Optional<String> claimId,
			List<WitnessSelector> witnesses,
			Set<String> requiredAdmissionIds,
			boolean initialAssessment,
			String tagSuffix) {
		Optional<String> docId = binding == null ? Optional.empty() : Optional.of(binding.documentId());
		Optional<String> docVer = binding == null ? Optional.empty() : Optional.of(binding.documentVersion());
		Optional<String> docSha = binding == null ? Optional.empty() : Optional.of(binding.documentBoundSha());
		Optional<String> bindingId = binding == null ? Optional.empty() : Optional.of(binding.bindingId());
		String tag = tagSuffix.isEmpty() ? admission.name() : admission.name() + "|" + tagSuffix;
		String findingId = computeFindingId(context, UnitDisposition.EVALUATED, ImpactOutcome.INCONCLUSIVE,
				beforePath, afterPath, symbolKey, evaluatedFacets,
				docId, docVer, bindingId, claimId, primaryReason, tag);
		return new ImpactFinding(
				findingId,
				UnitDisposition.EVALUATED,
				ImpactOutcome.INCONCLUSIVE,
				Optional.empty(),
				Optional.empty(),
				SeverityStatus.SEVERITY_NOT_EVALUATED,
				Optional.empty(),
				Optional.empty(),
				admission,
				admissionReason,
				primaryReason,
				secondaryReasons,
				explanation,
				beforePath,
				afterPath,
				symbolKey,
				evaluatedFacets,
				docId,
				docVer,
				docSha,
				bindingId,
				claimId,
				sortWitnesses(witnesses),
				Set.of(),
				requiredAdmissionIds,
				initialAssessment);
	}

	private ImpactFinding buildLookupBlockedFinding(
			AssessmentContext context,
			Optional<String> beforePath,
			Optional<String> afterPath,
			Optional<SymbolKey> symbolKey,
			Set<FacetKind> evaluatedFacets,
			ReasonCode blockedReason,
			SourceEvidenceSelector selector,
			boolean initialAssessment) {
		return buildLookupBlockedFindingTagged(
				context, beforePath, afterPath, symbolKey, evaluatedFacets,
				blockedReason, selector, initialAssessment, "");
	}

	private ImpactFinding buildLookupBlockedFindingTagged(
			AssessmentContext context,
			Optional<String> beforePath,
			Optional<String> afterPath,
			Optional<SymbolKey> symbolKey,
			Set<FacetKind> evaluatedFacets,
			ReasonCode blockedReason,
			SourceEvidenceSelector selector,
			boolean initialAssessment,
			String tagSuffix) {
		return buildInconclusiveFindingTagged(
				context, beforePath, afterPath, symbolKey, evaluatedFacets,
				IssueAdmission.INSUFFICIENT_EVIDENCE, blockedReason, blockedReason, List.of(),
				"Document lookup could not establish completeness: " + blockedReason,
				null, Optional.empty(), List.of(selector), Set.of(), initialAssessment, tagSuffix);
	}

	private ImpactFinding buildEmptyVerifiedNoImpactFinding(
			AssessmentContext context,
			ScopeManifestRef manifest) {
		var negWitness = negativeBasisWitness(context, NegativeBasisKind.EMPTY_VERIFIED_SCOPE,
				List.of(), Optional.empty(), Optional.empty(),
				Optional.of(manifest.manifestId()), Optional.of(manifest),
				Optional.empty(), Optional.empty());
		return buildNoImpactFinding(
				context, Optional.empty(), Optional.empty(), Optional.empty(), Set.of(),
				ReasonCode.EMPTY_VERIFIED_SCOPE_NO_IMPACT,
				"Declared evaluation scope is verified empty by manifest " + manifest.manifestId(),
				Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(),
				List.of(negWitness), Set.of(negWitness.witnessId()),
				context.intent() == Intent.INITIAL_ASSESSMENT);
	}

	private ImpactFinding buildMissingEmptyManifestFinding(AssessmentContext context) {
		return buildInconclusiveFinding(
				context, Optional.empty(), Optional.empty(), Optional.empty(), Set.of(),
				IssueAdmission.INSUFFICIENT_EVIDENCE,
				ReasonCode.EMPTY_SCOPE_MANIFEST_MISSING,
				ReasonCode.EMPTY_SCOPE_MANIFEST_MISSING,
				List.of(),
				"EMPTY_VERIFIED declared scope lacks ScopeManifestRef required for M4 grounding",
				null, Optional.empty(), List.of(), Set.of(),
				context.intent() == Intent.INITIAL_ASSESSMENT);
	}

	private ImpactFinding buildNonJavaExcludedFinding(AssessmentContext context, StructuralDiffResult result) {
		Optional<String> beforePath = result.beforeSnapshot().map(SnapshotProvenance::path);
		Optional<String> afterPath = result.afterSnapshot().map(SnapshotProvenance::path);
		var negWitness = negativeBasisWitness(context, NegativeBasisKind.NON_JAVA_OUT_OF_SCOPE,
				List.of(), result.beforeSnapshot(), result.afterSnapshot(),
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
		String findingId = computeFindingId(context, UnitDisposition.EXCLUDED, ImpactOutcome.NO_IMPACT,
				beforePath, afterPath, Optional.empty(), Set.of(),
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				ReasonCode.NON_JAVA_OUT_OF_SCOPE, "EXCLUDED");
		return new ImpactFinding(
				findingId,
				UnitDisposition.EXCLUDED,
				ImpactOutcome.NO_IMPACT,
				Optional.empty(),
				Optional.empty(),
				SeverityStatus.SEVERITY_NOT_EVALUATED,
				Optional.empty(),
				Optional.empty(),
				IssueAdmission.NOT_APPLICABLE,
				ReasonCode.NON_JAVA_OUT_OF_SCOPE,
				ReasonCode.NON_JAVA_OUT_OF_SCOPE,
				List.of(),
				"Non-Java path excluded from Java AST slice; document flow remains separate",
				beforePath,
				afterPath,
				Optional.empty(),
				Set.of(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				List.of(negWitness),
				Set.of(negWitness.witnessId()),
				Set.of(),
				result.intent() == Intent.INITIAL_ASSESSMENT);
	}

	private ImpactFinding buildCallerExcludedFinding(
			AssessmentContext context,
			StructuralDiffResult result,
			CallerExclusion exclusion) {
		Optional<String> beforePath = result.beforeSnapshot().map(SnapshotProvenance::path);
		Optional<String> afterPath = result.afterSnapshot().map(SnapshotProvenance::path);
		var negWitness = negativeBasisWitness(context, NegativeBasisKind.CALLER_EXCLUSION_RULE,
				List.of(), result.beforeSnapshot(), result.afterSnapshot(),
				Optional.of(exclusion.ruleId()), Optional.empty(), Optional.empty(), Optional.empty());
		String findingId = computeFindingId(context, UnitDisposition.EXCLUDED, ImpactOutcome.NO_IMPACT,
				beforePath, afterPath, Optional.empty(), Set.of(),
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				ReasonCode.CALLER_DECLARED_EXCLUSION, exclusion.ruleId());
		return new ImpactFinding(
				findingId,
				UnitDisposition.EXCLUDED,
				ImpactOutcome.NO_IMPACT,
				Optional.empty(),
				Optional.empty(),
				SeverityStatus.SEVERITY_NOT_EVALUATED,
				Optional.empty(),
				Optional.empty(),
				IssueAdmission.NOT_APPLICABLE,
				ReasonCode.CALLER_DECLARED_EXCLUSION,
				ReasonCode.CALLER_DECLARED_EXCLUSION,
				List.of(),
				"Excluded by verified caller scope rule " + exclusion.ruleId() + ": " + exclusion.reason(),
				beforePath,
				afterPath,
				Optional.empty(),
				Set.of(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				List.of(negWitness),
				Set.of(negWitness.witnessId()),
				Set.of(),
				result.intent() == Intent.INITIAL_ASSESSMENT);
	}

	private ImpactFinding buildControlledExportExcludedFinding(
			AssessmentContext context,
			StructuralDiffResult result,
			Optional<SymbolKey> symbolKey,
			ControlledExportUnit exportUnit) {
		Optional<String> beforePath = result.beforeSnapshot().map(SnapshotProvenance::path);
		Optional<String> afterPath = result.afterSnapshot().map(SnapshotProvenance::path);
		var negWitness = negativeBasisWitness(context, NegativeBasisKind.CONTROLLED_EXPORT_PROVENANCE,
				List.of(), result.beforeSnapshot(), result.afterSnapshot(),
				Optional.of(exportUnit.exportProvenanceId()), Optional.empty(),
				exportUnit.expectedBeforeRawSha256(), exportUnit.expectedTargetRawSha256());
		String findingId = computeFindingId(context, UnitDisposition.EXCLUDED, ImpactOutcome.NO_IMPACT,
				beforePath, afterPath, symbolKey, Set.of(),
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				ReasonCode.CONTROLLED_EXPORT_EXCLUDED, exportUnit.exportProvenanceId());
		return new ImpactFinding(
				findingId,
				UnitDisposition.EXCLUDED,
				ImpactOutcome.NO_IMPACT,
				Optional.empty(),
				Optional.empty(),
				SeverityStatus.SEVERITY_NOT_EVALUATED,
				Optional.empty(),
				Optional.empty(),
				IssueAdmission.NOT_APPLICABLE,
				ReasonCode.CONTROLLED_EXPORT_EXCLUDED,
				ReasonCode.CONTROLLED_EXPORT_EXCLUDED,
				List.of(),
				"Matched controlled LivingDocs export provenance: " + exportUnit.exportProvenanceId(),
				beforePath,
				afterPath,
				symbolKey,
				Set.of(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				List.of(negWitness),
				Set.of(negWitness.witnessId()),
				Set.of(),
				result.intent() == Intent.INITIAL_ASSESSMENT);
	}

	private ImpactFinding buildParseFailedFinding(AssessmentContext context, StructuralDiffResult result) {
		Optional<String> beforePath = result.beforeSnapshot().map(SnapshotProvenance::path);
		Optional<String> afterPath = result.afterSnapshot().map(SnapshotProvenance::path);
		var witnesses = buildProvenanceSelectors(context, result);
		return buildInconclusiveFinding(
				context, beforePath, afterPath, Optional.empty(), Set.of(),
				IssueAdmission.INSUFFICIENT_EVIDENCE, ReasonCode.JAVA_PARSE_FAILED,
				ReasonCode.JAVA_PARSE_FAILED, List.of(),
				"Java parse failure prevented structural comparison; no false no-impact or deletion emitted",
				null, Optional.empty(), witnesses, Set.of(), result.intent() == Intent.INITIAL_ASSESSMENT);
	}

	private ImpactFinding buildIncompleteFileFinding(AssessmentContext context, StructuralDiffResult result) {
		Optional<String> beforePath = result.beforeSnapshot().map(SnapshotProvenance::path);
		Optional<String> afterPath = result.afterSnapshot().map(SnapshotProvenance::path);
		ReasonCode reason = resolveIncompleteReason(result);
		var witnesses = buildProvenanceSelectors(context, result);
		return buildInconclusiveFinding(
				context, beforePath, afterPath, Optional.empty(), Set.of(),
				IssueAdmission.INSUFFICIENT_EVIDENCE, reason, reason, List.of(),
				String.join("; ", result.coverage().gaps()),
				null, Optional.empty(), witnesses, Set.of(), result.intent() == Intent.INITIAL_ASSESSMENT);
	}

	private static List<WitnessSelector> buildProvenanceSelectors(
			AssessmentContext context, StructuralDiffResult result) {
		var list = new ArrayList<WitnessSelector>();
		if (result.beforeSnapshot().isPresent()) {
			var b = result.beforeSnapshot().orElseThrow();
			list.add(sourceSelector(context, SideRole.BEFORE, b.snapshot().sha(), b.path(), b.presence(),
					SelectorGranularity.FILE, Optional.empty(), Optional.empty(), Optional.empty()));
		}
		if (result.afterSnapshot().isPresent()) {
			var a = result.afterSnapshot().orElseThrow();
			SideRole role = result.intent() == Intent.INITIAL_ASSESSMENT ? SideRole.TARGET : SideRole.AFTER;
			list.add(sourceSelector(context, role, a.snapshot().sha(), a.path(), a.presence(),
					SelectorGranularity.FILE, Optional.empty(), Optional.empty(), Optional.empty()));
		}
		return list;
	}

	private static ReasonCode resolveIncompleteReason(StructuralDiffResult result) {
		for (var diag : result.diagnostics()) {
			if (diag.code() == DiagnosticCode.MIXED_LANGUAGE_SCOPE) {
				return ReasonCode.MIXED_LANGUAGE_TRANSITION_INCOMPLETE;
			}
			if (diag.code() == DiagnosticCode.SNAPSHOT_UNAVAILABLE) {
				return ReasonCode.SNAPSHOT_UNAVAILABLE_INCOMPLETE;
			}
			if (diag.code() == DiagnosticCode.PARTIAL_PARSE_COVERAGE) {
				return ReasonCode.PARTIAL_PARSE_INCOMPLETE;
			}
			if (diag.code() == DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE) {
				return ReasonCode.RESIDUAL_OR_GROUPING_INCOMPLETE;
			}
		}
		return ReasonCode.PARTIAL_PARSE_INCOMPLETE;
	}

	private static ReasonCode mapLookupIncompleteReason(LookupCompleteness completeness) {
		return switch (completeness) {
			case INCOMPLETE -> ReasonCode.LOOKUP_INCOMPLETE_OR_PAGINATED;
			case AMBIGUOUS -> ReasonCode.LOOKUP_AMBIGUOUS;
			case UNAVAILABLE -> ReasonCode.LOOKUP_UNAVAILABLE;
			case DENIED -> ReasonCode.LOOKUP_DENIED;
			case COMPLETE -> throw new IllegalArgumentException("COMPLETE lookup is not incomplete");
		};
	}

	private static boolean isBlockedOrFailedFinding(ImpactFinding f) {
		return f.disposition() == UnitDisposition.EVALUATED
				&& f.outcome() == ImpactOutcome.INCONCLUSIVE
				&& (f.primaryReason() == ReasonCode.JAVA_PARSE_FAILED
						|| f.primaryReason() == ReasonCode.SNAPSHOT_UNAVAILABLE_INCOMPLETE
						|| f.primaryReason() == ReasonCode.LOOKUP_UNAVAILABLE
						|| f.primaryReason() == ReasonCode.LOOKUP_DENIED
						|| f.primaryReason() == ReasonCode.PARTIAL_PARSE_INCOMPLETE);
	}

	private static boolean isStaleOrMismatchOnPresentTarget(ClaimVerdict verdict) {
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

	private static Optional<CallerExclusion> findMatchingCallerExclusion(
			AssessmentContext context,
			StructuralDiffResult result,
			List<CallerExclusion> exclusions) {
		String targetPath = result.afterSnapshot().map(SnapshotProvenance::path)
				.or(() -> result.beforeSnapshot().map(SnapshotProvenance::path))
				.orElse("");
		for (var excl : exclusions) {
			if (excl != null
					&& excl.repository().equals(context.repository())
					&& excl.targetSha().equals(context.authoritativeTargetSha())
					&& excl.path().equals(targetPath)) {
				return Optional.of(excl);
			}
		}
		return Optional.empty();
	}

	/**
	 * Fix #3: A file-level controlled export ({@code symbolKey.isEmpty()}) may only exclude
	 * the entire file when {@code result} has complete coverage AND the export unit proves it
	 * spans the entire {@code before -> after} raw file delta (matching {@code beforeSha},
	 * {@code expectedBeforeRawSha256}, and {@code expectedTargetRawSha256}). Otherwise, manual
	 * edits in the same file could be masked, so whole-file exclusion is refused and individual
	 * changed symbols/context/comments are evaluated normally.
	 */
	private static Optional<ControlledExportUnit> findVerifiedWholeFileControlledExport(
			AssessmentContext context,
			StructuralDiffResult result,
			List<ControlledExportUnit> exports,
			Set<ImpactDiagnostic> diagnostics) {
		String targetPath = result.afterSnapshot().map(SnapshotProvenance::path)
				.or(() -> result.beforeSnapshot().map(SnapshotProvenance::path))
				.orElse("");

		for (var exp : exports) {
			if (exp == null
					|| exp.symbolKey().isPresent()
					|| !exp.repository().equals(context.repository())
					|| !exp.targetSha().equals(context.authoritativeTargetSha())
					|| !exp.path().equals(targetPath)) {
				continue;
			}
			if (provesWholeFileCoverage(context, result, exp)) {
				return Optional.of(exp);
			}
			diagnostics.add(new ImpactDiagnostic(
					DiagnosticSeverity.INFO,
					ReasonCode.CONTROLLED_EXPORT_EXCLUDED,
					"Refused whole-file controlled export exclusion on " + targetPath
							+ " because export provenance does not prove full-file delta coverage"));
		}
		return Optional.empty();
	}

	private static boolean provesWholeFileCoverage(
			AssessmentContext context,
			StructuralDiffResult result,
			ControlledExportUnit exp) {
		if (result.status() != ProcessingStatus.COMPLETE || !result.coverage().fullCoverage()) {
			return false;
		}
		if (result.fileComparison().pathRelation() == PathRelation.PATH_CHANGED) {
			return false;
		}
		if (result.intent() == Intent.INITIAL_ASSESSMENT) {
			if (exp.beforeSha().isPresent() || exp.expectedBeforeRawSha256().isPresent()) {
				return false;
			}
			return result.afterContext().isPresent()
					&& exp.expectedTargetRawSha256().isPresent()
					&& exp.expectedTargetRawSha256().get().equals(
							result.afterContext().get().rawFacts().rawSha256());
		}

		// REVISION_COMPARISON: require exact match on comparisonBeforeSha, before raw hash (or verified absent), and after raw hash (or verified absent).
		if (exp.beforeSha().isEmpty() || !exp.beforeSha().equals(context.comparisonBeforeSha())) {
			return false;
		}
		var beforeProv = result.beforeSnapshot().orElseThrow();
		var afterProv = result.afterSnapshot().orElseThrow();

		boolean beforeProven;
		if (beforeProv.presence() == SidePresence.PRESENT) {
			beforeProven = result.beforeContext().isPresent()
					&& exp.expectedBeforeRawSha256().isPresent()
					&& exp.expectedBeforeRawSha256().get().equals(
							result.beforeContext().get().rawFacts().rawSha256());
		} else if (beforeProv.presence() == SidePresence.ABSENT) {
			beforeProven = exp.expectedBeforeRawSha256().isEmpty();
		} else {
			beforeProven = false;
		}

		boolean afterProven;
		if (afterProv.presence() == SidePresence.PRESENT) {
			afterProven = result.afterContext().isPresent()
					&& exp.expectedTargetRawSha256().isPresent()
					&& exp.expectedTargetRawSha256().get().equals(
							result.afterContext().get().rawFacts().rawSha256());
		} else if (afterProv.presence() == SidePresence.ABSENT) {
			afterProven = exp.expectedTargetRawSha256().isEmpty();
		} else {
			afterProven = false;
		}

		return beforeProven && afterProven;
	}

	private static Optional<ControlledExportUnit> findMatchingSymbolControlledExport(
			AssessmentContext context,
			String targetSha,
			String targetPath,
			SymbolKey symbolKey,
			List<ControlledExportUnit> exports) {
		for (var exp : exports) {
			if (exp != null
					&& exp.symbolKey().isPresent()
					&& exp.repository().equals(context.repository())
					&& exp.targetSha().equals(targetSha)
					&& exp.path().equals(targetPath)
					&& exp.symbolKey().get().equals(symbolKey)
					&& (exp.beforeSha().isEmpty() || exp.beforeSha().equals(context.comparisonBeforeSha()))) {
				return Optional.of(exp);
			}
		}
		return Optional.empty();
	}

	private static List<ImpactDiagnostic> validateContext(AssessmentContext context) {
		var errors = new ArrayList<ImpactDiagnostic>();
		if (isBlank(context.tenantId()) || isBlank(context.workspaceId())
				|| isBlank(context.repository()) || isBlank(context.targetRef())
				|| isBlank(context.jobId()) || isBlank(context.attemptId())
				|| isBlank(context.authoritativeTargetSha())) {
			errors.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.INVALID_ASSESSMENT_INPUT,
					"Assessment context requires non-blank tenant, workspace, repository, targetRef, jobId, attemptId, and authoritativeTargetSha"));
		}
		if (context.intent() == Intent.REVISION_COMPARISON
				&& (context.comparisonBeforeSha().isEmpty() || isBlank(context.comparisonBeforeSha().get()))) {
			errors.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.INVALID_ASSESSMENT_INPUT,
					"REVISION_COMPARISON assessment context requires a non-blank comparisonBeforeSha"));
		}
		if (context.intent() == Intent.INITIAL_ASSESSMENT && context.comparisonBeforeSha().isPresent()) {
			errors.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.INVALID_ASSESSMENT_INPUT,
					"INITIAL_ASSESSMENT assessment context cannot declare comparisonBeforeSha"));
		}
		if (context.scopeManifest().isPresent()) {
			var manifest = context.scopeManifest().get();
			if (!manifest.repository().equals(context.repository())
					|| manifest.targetKind() != context.targetKind()
					|| !manifest.targetRef().equals(context.targetRef())
					|| manifest.intent() != context.intent()
					|| !manifest.comparisonBeforeSha().equals(context.comparisonBeforeSha())
					|| !manifest.authoritativeTargetSha().equals(context.authoritativeTargetSha())) {
				errors.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
						"ScopeManifestRef metadata contradicts AssessmentContext binding, target, intent, or SHAs"));
			}
			if (context.declaredScopeCompleteness() == ScopeCompleteness.EMPTY_VERIFIED
					&& manifest.eligibleFileCount() != 0) {
				errors.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
						"EMPTY_VERIFIED ScopeManifestRef must report eligibleFileCount == 0"));
			}
		}
		return errors;
	}

	private static List<ImpactDiagnostic> validateDiffMatchesContext(
			AssessmentContext context, StructuralDiffResult result) {
		var errors = new ArrayList<ImpactDiagnostic>();
		if (result.intent() != context.intent()) {
			errors.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
					"Diff result intent " + result.intent() + " contradicts context intent " + context.intent()));
		}
		if (result.repository().isPresent() && !result.repository().get().equals(context.repository())) {
			errors.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
					"Diff result repository contradicts assessment context repository"));
		}
		if (result.afterSnapshot().isPresent()) {
			var afterSnap = result.afterSnapshot().get().snapshot();
			if (!afterSnap.repository().equals(context.repository())
					|| !afterSnap.sha().equals(context.authoritativeTargetSha())) {
				errors.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
						"Diff result target/after snapshot contradicts context repository or authoritativeTargetSha"));
			}
		}
		if (context.intent() == Intent.REVISION_COMPARISON && result.beforeSnapshot().isPresent()) {
			var beforeSnap = result.beforeSnapshot().get().snapshot();
			if (!beforeSnap.repository().equals(context.repository())
					|| !context.comparisonBeforeSha().map(beforeSnap.sha()::equals).orElse(false)) {
				errors.add(new ImpactDiagnostic(DiagnosticSeverity.ERROR, ReasonCode.CONTRADICTORY_DIFF_CONTEXT,
						"Diff result before snapshot contradicts context repository or comparisonBeforeSha"));
			}
		}
		return errors;
	}

	private static boolean matchesScope(
			AssessmentContext context,
			String tenantId,
			String workspaceId,
			String repository,
			TargetKind targetKind,
			String targetRef) {
		return context.tenantId().equals(tenantId)
				&& context.workspaceId().equals(workspaceId)
				&& context.repository().equals(repository)
				&& context.targetKind() == targetKind
				&& context.targetRef().equals(targetRef);
	}

	private static DocumentLookupKey fileLookupKey(
			AssessmentContext context, SideRole role, String sha, String path) {
		return new DocumentLookupKey(
				context.tenantId(), context.workspaceId(), context.repository(),
				context.targetKind(), context.targetRef(),
				role, sha, path, LookupGranularity.FILE, Optional.empty());
	}

	private static DocumentLookupKey symbolLookupKey(
			AssessmentContext context, SideRole role, String sha, String path, SymbolKey key) {
		return new DocumentLookupKey(
				context.tenantId(), context.workspaceId(), context.repository(),
				context.targetKind(), context.targetRef(),
				role, sha, path, LookupGranularity.SYMBOL, Optional.of(key));
	}

	private static SourceEvidenceSelector sourceSelector(
			AssessmentContext context,
			SideRole sideRole,
			String pinnedSha,
			String path,
			SidePresence presence,
			SelectorGranularity granularity,
			Optional<SymbolKey> symbolKey,
			Optional<FacetKind> facetKind,
			Optional<ContextChangeKind> contextChangeKind) {
		String witnessId = computeWitnessId("SOURCE", List.of(
				context.tenantId(), context.workspaceId(), context.repository(),
				context.targetKind().name(), context.targetRef(),
				sideRole.name(), pinnedSha, path, presence.name(), granularity.name(),
				symbolKey.map(JavaStructuralRepresentation::canonicalSymbolKey).orElse(""),
				facetKind.map(FacetKind::name).orElse(""),
				contextChangeKind.map(ContextChangeKind::name).orElse("")));
		return new SourceEvidenceSelector(
				witnessId, context.repository(), sideRole, pinnedSha, path, presence,
				granularity, symbolKey, facetKind, contextChangeKind);
	}

	private static DocumentWitnessSelector documentSelector(
			AssessmentContext context,
			DocumentBinding binding,
			Optional<DocumentClaim> claim) {
		String witnessId = computeWitnessId("DOCUMENT", List.of(
				context.tenantId(), context.workspaceId(), context.repository(),
				context.targetKind().name(), context.targetRef(),
				binding.documentId(), binding.documentVersion(), binding.documentBoundSha(),
				binding.bindingId(),
				binding.sectionRef().orElse(""),
				claim.map(DocumentClaim::claimId).orElse(""),
				claim.map(DocumentClaim::evaluatedTargetSha).orElse(""),
				claim.map(DocumentClaim::documentedReferenceOrTerm).orElse("")));
		return new DocumentWitnessSelector(
				witnessId,
				binding.documentId(),
				binding.documentVersion(),
				binding.documentBoundSha(),
				claim.map(DocumentClaim::evaluatedTargetSha).orElse(context.authoritativeTargetSha()),
				binding.bindingId(),
				binding.sectionRef(),
				claim.map(DocumentClaim::claimId),
				claim.map(DocumentClaim::documentedReferenceOrTerm));
	}

	private static SemanticWitnessSelector semanticSelector(
			AssessmentContext context,
			SemanticAssessmentInput sem,
			FacetKind facetKind,
			String matchedClaimId) {
		var parts = new ArrayList<String>();
		parts.add(context.tenantId());
		parts.add(context.workspaceId());
		parts.add(context.repository());
		parts.add(context.targetKind().name());
		parts.add(context.targetRef());
		parts.add(sem.jobId());
		parts.add(sem.attemptId());
		parts.add(sem.evaluatedTargetSha());
		parts.add(sem.path());
		parts.add(JavaStructuralRepresentation.canonicalSymbolKey(sem.symbolKey()));
		parts.add(facetKind.name());
		parts.add(sem.documentId());
		parts.add(sem.documentVersion());
		parts.add(matchedClaimId);
		parts.add(sem.status().name());
		String witnessId = computeWitnessId("SEMANTIC", parts);
		return new SemanticWitnessSelector(
				witnessId,
				sem.jobId(),
				sem.attemptId(),
				sem.evaluatedTargetSha(),
				sem.path(),
				sem.symbolKey(),
				facetKind,
				sem.documentId(),
				sem.documentVersion(),
				matchedClaimId,
				sem.groundedSourceRefs(),
				sem.groundedDocumentRefs());
	}

	private static NegativeBasisWitnessSelector negativeBasisWitness(
			AssessmentContext context,
			NegativeBasisKind kind,
			List<DocumentLookupKey> lookupKeys,
			Optional<SnapshotProvenance> beforeSnapshot,
			Optional<SnapshotProvenance> afterSnapshot,
			Optional<String> provenanceRefId,
			Optional<ScopeManifestRef> scopeManifest,
			Optional<String> expectedBeforeRawSha256,
			Optional<String> expectedTargetRawSha256) {
		var sortedKeys = new ArrayList<>(lookupKeys);
		sortedKeys.sort(LOOKUP_KEY_COMPARATOR);
		Optional<String> effectiveBeforeSha = beforeSnapshot.map(s -> s.snapshot().sha())
				.or(() -> scopeManifest.flatMap(ScopeManifestRef::comparisonBeforeSha));
		Optional<String> effectiveAfterSha = afterSnapshot.map(s -> s.snapshot().sha())
				.or(() -> scopeManifest.map(ScopeManifestRef::authoritativeTargetSha));

		var parts = new ArrayList<String>();
		parts.add(context.tenantId());
		parts.add(context.workspaceId());
		parts.add(context.repository());
		parts.add(context.targetKind().name());
		parts.add(context.targetRef());
		parts.add(context.authoritativeTargetSha());
		parts.add(kind.name());
		parts.add(beforeSnapshot.map(s -> s.snapshot().sha() + ":" + s.path() + ":" + s.presence()).orElse(""));
		parts.add(afterSnapshot.map(s -> s.snapshot().sha() + ":" + s.path() + ":" + s.presence()).orElse(""));
		parts.add(provenanceRefId.orElse(""));
		parts.add(scopeManifest.map(m -> m.manifestId() + ":" + m.intent() + ":"
				+ m.comparisonBeforeSha().orElse("") + ":" + m.authoritativeTargetSha()
				+ ":" + m.eligibleFileCount()).orElse(""));
		parts.add(expectedBeforeRawSha256.orElse(""));
		parts.add(expectedTargetRawSha256.orElse(""));
		for (var k : sortedKeys) {
			parts.add(k.granularity().name() + ":" + k.sideRole().name() + ":" + k.pinnedSha() + ":" + k.path() + ":"
					+ k.symbolKey().map(JavaStructuralRepresentation::canonicalSymbolKey).orElse(""));
		}
		String witnessId = computeWitnessId("NEGATIVE_BASIS", parts);
		return new NegativeBasisWitnessSelector(
				witnessId, kind, sortedKeys,
				effectiveBeforeSha,
				beforeSnapshot.map(SnapshotProvenance::path),
				effectiveAfterSha,
				afterSnapshot.map(SnapshotProvenance::path),
				provenanceRefId,
				scopeManifest,
				expectedBeforeRawSha256,
				expectedTargetRawSha256);
	}

	/**
	 * Fix #2: Includes both {@code bindingId} and {@code claimId} so two distinct claims on
	 * the same document, version, symbol, facet, and outcome never collide into one finding!
	 */
	private static String computeFindingId(
			AssessmentContext context,
			UnitDisposition disposition,
			ImpactOutcome outcome,
			Optional<String> beforePath,
			Optional<String> afterPath,
			Optional<SymbolKey> symbolKey,
			Set<FacetKind> facets,
			Optional<String> documentId,
			Optional<String> documentVersion,
			Optional<String> bindingId,
			Optional<String> claimId,
			ReasonCode primaryReason,
			String tag) {
		var parts = List.of(
				context.tenantId(),
				context.workspaceId(),
				context.repository(),
				context.targetKind().name(),
				context.targetRef(),
				context.intent().name(),
				context.comparisonBeforeSha().orElse(""),
				context.authoritativeTargetSha(),
				disposition.name(),
				outcome.name(),
				beforePath.orElse(""),
				afterPath.orElse(""),
				symbolKey.map(JavaStructuralRepresentation::canonicalSymbolKey).orElse(""),
				facets.stream().map(FacetKind::name).sorted().reduce((a, b) -> a + "," + b).orElse(""),
				documentId.orElse(""),
				documentVersion.orElse(""),
				bindingId.orElse(""),
				claimId.orElse(""),
				primaryReason.name(),
				tag);
		return "finding:" + sha256LengthPrefixed(parts);
	}

	private static String computeWitnessId(String kindTag, List<String> parts) {
		var all = new ArrayList<String>(parts.size() + 1);
		all.add(kindTag);
		all.addAll(parts);
		return "witness:" + sha256LengthPrefixed(all);
	}

	private static String sha256LengthPrefixed(List<String> parts) {
		var buffer = new ByteArrayOutputStream();
		writeInt32(buffer, parts.size());
		for (var part : parts) {
			byte[] bytes = Objects.requireNonNull(part, "part").getBytes(StandardCharsets.UTF_8);
			writeInt32(buffer, bytes.length);
			buffer.writeBytes(bytes);
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray());
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private static void writeInt32(ByteArrayOutputStream buffer, int value) {
		buffer.write((value >>> 24) & 0xFF);
		buffer.write((value >>> 16) & 0xFF);
		buffer.write((value >>> 8) & 0xFF);
		buffer.write(value & 0xFF);
	}

	private static List<ImpactFinding> sortAndDedupeFindings(List<ImpactFinding> findings) {
		var byId = new LinkedHashMap<String, ImpactFinding>();
		for (var f : findings) {
			byId.putIfAbsent(f.findingId(), f);
		}
		var list = new ArrayList<>(byId.values());
		list.sort(FINDING_COMPARATOR);
		return List.copyOf(list);
	}

	private static List<WitnessSelector> sortWitnesses(List<WitnessSelector> witnesses) {
		var byId = new LinkedHashMap<String, WitnessSelector>();
		for (var w : witnesses) {
			byId.putIfAbsent(w.witnessId(), w);
		}
		var list = new ArrayList<>(byId.values());
		list.sort(WITNESS_COMPARATOR);
		return List.copyOf(list);
	}

	private static List<ImpactDiagnostic> sortDiagnostics(Collection<ImpactDiagnostic> diagnostics) {
		var list = new ArrayList<>(new LinkedHashSet<>(diagnostics));
		list.sort(DIAGNOSTIC_COMPARATOR);
		return List.copyOf(list);
	}

	private static List<String> sortStrings(Collection<String> values) {
		var list = new ArrayList<>(new LinkedHashSet<>(values));
		Collections.sort(list);
		return List.copyOf(list);
	}

	private static OutcomeCounts countOutcomes(List<ImpactFinding> findings) {
		int actionable = 0;
		int noImpact = 0;
		int inconclusive = 0;
		int excluded = 0;
		for (var f : findings) {
			if (f.disposition() == UnitDisposition.EXCLUDED) {
				excluded++;
			} else {
				switch (f.outcome()) {
					case ACTIONABLE -> actionable++;
					case NO_IMPACT -> noImpact++;
					case INCONCLUSIVE -> inconclusive++;
				}
			}
		}
		return new OutcomeCounts(actionable, noImpact, inconclusive, excluded);
	}

	private static List<String> deriveExcludedScopeReasons(List<ImpactFinding> findings) {
		var reasons = new LinkedHashSet<String>();
		for (var f : findings) {
			if (f.disposition() != UnitDisposition.EXCLUDED) {
				continue;
			}
			Optional<String> provId = f.witnesses().stream()
					.filter(w -> w instanceof NegativeBasisWitnessSelector)
					.map(w -> (NegativeBasisWitnessSelector) w)
					.findFirst()
					.flatMap(NegativeBasisWitnessSelector::provenanceRefId);
			String pathDesc = describeFindingPaths(f);
			switch (f.primaryReason()) {
				case CALLER_DECLARED_EXCLUSION ->
					reasons.add("Caller-declared exclusion [" + provId.orElse("unknown") + "]: " + pathDesc);
				case CONTROLLED_EXPORT_EXCLUDED -> {
					if (f.symbolKey().isPresent()) {
						reasons.add("Controlled export excluded [" + provId.orElse("unknown") + "]: "
								+ f.afterPath().or(f::beforePath).orElse(pathDesc)
								+ " :: " + JavaStructuralRepresentation.canonicalSymbolKey(f.symbolKey().orElseThrow()));
					} else {
						reasons.add("Controlled export excluded [" + provId.orElse("unknown") + "]: " + pathDesc);
					}
				}
				case NON_JAVA_OUT_OF_SCOPE ->
					reasons.add("Out of Java AST scope: " + pathDesc);
				default ->
					reasons.add("Excluded: " + pathDesc);
			}
		}
		return sortStrings(reasons);
	}

	private static String describeFindingPaths(ImpactFinding finding) {
		Optional<String> b = finding.beforePath();
		Optional<String> a = finding.afterPath();
		if (b.isEmpty()) {
			return a.orElse("-");
		}
		if (a.isEmpty()) {
			return b.get();
		}
		return b.get().equals(a.get()) ? a.get() : b.get() + " -> " + a.get();
	}

	private static boolean isJavaPath(String path) {
		return path != null && path.endsWith(".java");
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private record OutcomeCounts(int actionable, int noImpact, int inconclusive, int excluded) {
	}

	private record LookupIndex(
			Map<DocumentLookupKey, DocumentLookupResponse> byKey,
			Set<DocumentLookupKey> conflictingKeys) {
	}

	private record SemanticKey(String path, SymbolKey symbolKey, String documentId, String documentVersion) {
	}

	private record SemanticIndex(Map<SemanticKey, List<SemanticAssessmentInput>> byTarget) {
	}

	private record BindingIdentityKey(String documentId, String documentVersion, String bindingId) {
		static BindingIdentityKey of(DocumentBinding b) {
			return new BindingIdentityKey(b.documentId(), b.documentVersion(), b.bindingId());
		}
	}

	private record LookupEvaluation(
			Optional<ReasonCode> incompleteReason,
			List<DocumentBinding> bindings,
			List<DocumentLookupKey> coveredEmptyKeys) {
	}

	public enum TargetKind {
		PULL_REQUEST, TRACKED_BRANCH
	}

	public enum ScopeCompleteness {
		COMPLETE, PARTIAL, EMPTY_VERIFIED, EMPTY_UNVERIFIED, DENIED
	}

	public enum LookupGranularity {
		FILE, SYMBOL
	}

	public enum LookupCompleteness {
		COMPLETE, INCOMPLETE, AMBIGUOUS, UNAVAILABLE, DENIED
	}

	public enum BindingGranularity {
		FILE_ONLY, EXACT_SYMBOL
	}

	public enum ClaimVerdict {
		REFERENCED_ENTITY_REQUIRED,
		DECLARATION_INCOMPATIBLE,
		DECLARATION_CONSISTENT,
		BEHAVIOR_MISMATCH_PROVEN,
		BEHAVIOR_CONSISTENT_PROVEN,
		POTENTIAL_MISMATCH_INITIAL_ONLY,
		STALE_CLAIM_AGAINST_TARGET,
		UNVERIFIED_OR_AMBIGUOUS
	}

	public enum SemanticStatus {
		NOT_REQUESTED,
		BEHAVIOR_MISMATCH,
		BEHAVIOR_CONSISTENT,
		INCONCLUSIVE,
		UNAVAILABLE,
		TIMED_OUT
	}

	public enum ReportProcessingStatus {
		COMPLETE, PARTIAL, FAILED, OUT_OF_SCOPE
	}

	public enum UnitDisposition {
		EVALUATED, EXCLUDED
	}

	public enum ImpactOutcome {
		ACTIONABLE, NO_IMPACT, INCONCLUSIVE
	}

	public enum DriftClassification {
		REFERENTIAL, SIGNATURE, SEMANTIC
	}

	public enum Severity {
		LOW, MEDIUM, HIGH, CRITICAL
	}

	public enum SeverityStatus {
		EVALUATED, SEVERITY_NOT_EVALUATED
	}

	public enum IssueAdmission {
		SUPPORTED_BY_INITIAL_EVIDENCE, INSUFFICIENT_EVIDENCE, NOT_APPLICABLE
	}

	public enum DiagnosticSeverity {
		INFO, ERROR
	}

	public enum SelectorGranularity {
		FILE, SYMBOL, FACET, CONTEXT
	}

	public enum NegativeBasisKind {
		COMPLETE_EMPTY_LOOKUP,
		UNCHANGED_VERIFIED,
		WHITESPACE_ONLY_VERIFIED,
		EMPTY_VERIFIED_SCOPE,
		NON_JAVA_OUT_OF_SCOPE,
		CALLER_EXCLUSION_RULE,
		CONTROLLED_EXPORT_PROVENANCE
	}

	public enum ReasonCode {
		INVALID_ASSESSMENT_INPUT,
		SCOPE_DENIED,
		FOREIGN_SCOPE_REJECTED,
		CONTRADICTORY_DIFF_CONTEXT,
		REFERENTIAL_MISSING_TARGET_SYMBOL,
		REFERENTIAL_REMOVED_TARGET_FILE,
		SIGNATURE_CONTRACT_MISMATCH,
		SEMANTIC_BEHAVIOR_MISMATCH,
		POTENTIAL_MISMATCH_PENDING_DEEPER_ANALYSIS,
		BODY_CHANGED_NEEDS_SEMANTIC,
		INITIALIZER_CHANGED_NEEDS_SEMANTIC,
		ANNOTATION_OR_DEFAULT_NEEDS_SEMANTIC,
		WEAK_FILE_LEVEL_MAPPING,
		DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS,
		DOCUMENT_TARGET_WITNESS_MISSING,
		AMBIGUOUS_SYMBOL_COUNTERPART,
		LOOKUP_INCOMPLETE_OR_PAGINATED,
		LOOKUP_AMBIGUOUS,
		LOOKUP_UNAVAILABLE,
		LOOKUP_DENIED,
		MISSING_OLD_OR_NEW_LOOKUP_KEY,
		SEMANTIC_UNAVAILABLE_OR_FAILED,
		SEMANTIC_LATE_OR_WRONG_ATTEMPT,
		SEMANTIC_WRONG_SHA_OR_VERSION,
		SEMANTIC_UNGROUNDED,
		COMPLETE_EMPTY_MAPPING_NO_IMPACT,
		VERIFIED_CLAIM_CONSISTENT_NO_IMPACT,
		REVERTED_TO_CONSISTENT_NO_IMPACT,
		WHITESPACE_ONLY_NO_IMPACT,
		UNCHANGED_WITHIN_EVALUATED_SCOPE,
		EMPTY_VERIFIED_SCOPE_NO_IMPACT,
		EMPTY_SCOPE_MANIFEST_MISSING,
		RAW_COMMENT_OR_JAVADOC_CHANGED_NEEDS_REVIEW,
		CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW,
		RESIDUAL_OR_GROUPING_INCOMPLETE,
		NON_JAVA_OUT_OF_SCOPE,
		CALLER_DECLARED_EXCLUSION,
		CONTROLLED_EXPORT_EXCLUDED,
		MIXED_LANGUAGE_TRANSITION_INCOMPLETE,
		JAVA_PARSE_FAILED,
		SNAPSHOT_UNAVAILABLE_INCOMPLETE,
		PARTIAL_PARSE_INCOMPLETE,
		INITIAL_INVENTORY_UNBOUND_INCOMPLETE,
		REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED
	}

	/**
	 * Verifiable S02 manifest reference proving an {@code EMPTY_VERIFIED} evaluation scope
	 * so M4 can cross-check manifest authority before accepting an empty-scope {@code NO_IMPACT}.
	 */
	public record ScopeManifestRef(
			String manifestId,
			String repository,
			TargetKind targetKind,
			String targetRef,
			Intent intent,
			Optional<String> comparisonBeforeSha,
			String authoritativeTargetSha,
			int eligibleFileCount) {

		public ScopeManifestRef {
			if (Objects.requireNonNull(manifestId, "manifestId").isBlank()) {
				throw new IllegalArgumentException("manifestId cannot be blank");
			}
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(targetKind, "targetKind");
			Objects.requireNonNull(targetRef, "targetRef");
			Objects.requireNonNull(intent, "intent");
			Objects.requireNonNull(comparisonBeforeSha, "comparisonBeforeSha");
			Objects.requireNonNull(authoritativeTargetSha, "authoritativeTargetSha");
			if (eligibleFileCount < 0) {
				throw new IllegalArgumentException("eligibleFileCount cannot be negative");
			}
		}
	}

	public record AssessmentContext(
			String tenantId,
			String workspaceId,
			String repository,
			TargetKind targetKind,
			String targetRef,
			String jobId,
			String attemptId,
			Optional<String> configVersion,
			String authoritativeTargetSha,
			Optional<String> comparisonBeforeSha,
			Intent intent,
			ScopeCompleteness declaredScopeCompleteness,
			boolean whitespaceOnlyNoImpactPolicyEnabled,
			Optional<ScopeManifestRef> scopeManifest) {

		public AssessmentContext {
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(workspaceId, "workspaceId");
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(targetKind, "targetKind");
			Objects.requireNonNull(targetRef, "targetRef");
			Objects.requireNonNull(jobId, "jobId");
			Objects.requireNonNull(attemptId, "attemptId");
			Objects.requireNonNull(configVersion, "configVersion");
			Objects.requireNonNull(authoritativeTargetSha, "authoritativeTargetSha");
			Objects.requireNonNull(comparisonBeforeSha, "comparisonBeforeSha");
			Objects.requireNonNull(intent, "intent");
			Objects.requireNonNull(declaredScopeCompleteness, "declaredScopeCompleteness");
			Objects.requireNonNull(scopeManifest, "scopeManifest");
		}

		public AssessmentContext(
				String tenantId,
				String workspaceId,
				String repository,
				TargetKind targetKind,
				String targetRef,
				String jobId,
				String attemptId,
				Optional<String> configVersion,
				String authoritativeTargetSha,
				Optional<String> comparisonBeforeSha,
				Intent intent,
				ScopeCompleteness declaredScopeCompleteness,
				boolean whitespaceOnlyNoImpactPolicyEnabled) {
			this(tenantId, workspaceId, repository, targetKind, targetRef, jobId, attemptId,
					configVersion, authoritativeTargetSha, comparisonBeforeSha, intent,
					declaredScopeCompleteness, whitespaceOnlyNoImpactPolicyEnabled, Optional.empty());
		}
	}

	public record ControlledExportUnit(
			String repository,
			Optional<String> beforeSha,
			String targetSha,
			String path,
			Optional<SymbolKey> symbolKey,
			Optional<String> expectedBeforeRawSha256,
			Optional<String> expectedTargetRawSha256,
			String exportProvenanceId) {

		public ControlledExportUnit {
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(beforeSha, "beforeSha");
			Objects.requireNonNull(targetSha, "targetSha");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(symbolKey, "symbolKey");
			Objects.requireNonNull(expectedBeforeRawSha256, "expectedBeforeRawSha256");
			Objects.requireNonNull(expectedTargetRawSha256, "expectedTargetRawSha256");
			if (Objects.requireNonNull(exportProvenanceId, "exportProvenanceId").isBlank()) {
				throw new IllegalArgumentException("Controlled export provenance ID cannot be blank");
			}
		}

		public static ControlledExportUnit forSymbol(
				String repository,
				String targetSha,
				String path,
				SymbolKey symbolKey,
				String exportProvenanceId) {
			return new ControlledExportUnit(
					repository,
					Optional.empty(),
					targetSha,
					path,
					Optional.of(Objects.requireNonNull(symbolKey, "symbolKey")),
					Optional.empty(),
					Optional.empty(),
					exportProvenanceId);
		}

		public static ControlledExportUnit forWholeFile(
				String repository,
				Optional<String> beforeSha,
				String targetSha,
				String path,
				Optional<String> expectedBeforeRawSha256,
				Optional<String> expectedTargetRawSha256,
				String exportProvenanceId) {
			return new ControlledExportUnit(
					repository,
					beforeSha,
					targetSha,
					path,
					Optional.empty(),
					expectedBeforeRawSha256,
					expectedTargetRawSha256,
					exportProvenanceId);
		}
	}

	public record CallerExclusion(
			String repository,
			String targetSha,
			String path,
			String ruleId,
			String reason) {

		public CallerExclusion {
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(targetSha, "targetSha");
			Objects.requireNonNull(path, "path");
			if (Objects.requireNonNull(ruleId, "ruleId").isBlank()) {
				throw new IllegalArgumentException("Caller exclusion ruleId cannot be blank");
			}
			if (Objects.requireNonNull(reason, "reason").isBlank()) {
				throw new IllegalArgumentException("Caller exclusion reason cannot be blank");
			}
		}
	}

	public record DocumentLookupKey(
			String tenantId,
			String workspaceId,
			String repository,
			TargetKind targetKind,
			String targetRef,
			SideRole sideRole,
			String pinnedSha,
			String path,
			LookupGranularity granularity,
			Optional<SymbolKey> symbolKey) {

		public DocumentLookupKey {
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(workspaceId, "workspaceId");
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(targetKind, "targetKind");
			Objects.requireNonNull(targetRef, "targetRef");
			Objects.requireNonNull(sideRole, "sideRole");
			Objects.requireNonNull(pinnedSha, "pinnedSha");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(granularity, "granularity");
			Objects.requireNonNull(symbolKey, "symbolKey");
			if (granularity == LookupGranularity.SYMBOL && symbolKey.isEmpty()) {
				throw new IllegalArgumentException("SYMBOL lookup key requires a SymbolKey");
			}
			if (granularity == LookupGranularity.FILE && symbolKey.isPresent()) {
				throw new IllegalArgumentException("FILE lookup key cannot carry a SymbolKey");
			}
		}
	}

	public record DocumentLookupResponse(
			DocumentLookupKey key,
			LookupCompleteness completeness,
			List<DocumentBinding> bindings,
			Optional<String> diagnosticReason) {

		public DocumentLookupResponse {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(completeness, "completeness");
			bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
			Objects.requireNonNull(diagnosticReason, "diagnosticReason");
		}
	}

	public record VerifiedImpactContext(
			Severity level,
			String consequenceRationale,
			String criticalityRef) {

		public VerifiedImpactContext {
			Objects.requireNonNull(level, "level");
			if (Objects.requireNonNull(consequenceRationale, "consequenceRationale").isBlank()) {
				throw new IllegalArgumentException("Consequence rationale cannot be blank");
			}
			if (Objects.requireNonNull(criticalityRef, "criticalityRef").isBlank()) {
				throw new IllegalArgumentException("Criticality ref cannot be blank");
			}
		}
	}

	public record DocumentClaim(
			String claimId,
			String documentId,
			String documentVersion,
			String documentBoundSha,
			String evaluatedTargetSha,
			String documentedReferenceOrTerm,
			String targetSubjectPath,
			Optional<SymbolKey> targetSubjectSymbol,
			Optional<FacetKind> targetSubjectFacet,
			ClaimVerdict verdict,
			String summaryRationale,
			Optional<VerifiedImpactContext> impactContext) {

		public DocumentClaim {
			if (Objects.requireNonNull(claimId, "claimId").isBlank()) {
				throw new IllegalArgumentException("claimId cannot be blank");
			}
			Objects.requireNonNull(documentId, "documentId");
			Objects.requireNonNull(documentVersion, "documentVersion");
			Objects.requireNonNull(documentBoundSha, "documentBoundSha");
			Objects.requireNonNull(evaluatedTargetSha, "evaluatedTargetSha");
			if (Objects.requireNonNull(documentedReferenceOrTerm, "documentedReferenceOrTerm").isBlank()) {
				throw new IllegalArgumentException("documentedReferenceOrTerm cannot be blank");
			}
			Objects.requireNonNull(targetSubjectPath, "targetSubjectPath");
			Objects.requireNonNull(targetSubjectSymbol, "targetSubjectSymbol");
			Objects.requireNonNull(targetSubjectFacet, "targetSubjectFacet");
			Objects.requireNonNull(verdict, "verdict");
			if (Objects.requireNonNull(summaryRationale, "summaryRationale").isBlank()) {
				throw new IllegalArgumentException("summaryRationale cannot be blank");
			}
			Objects.requireNonNull(impactContext, "impactContext");
		}
	}

	public record DocumentBinding(
			String tenantId,
			String workspaceId,
			String repository,
			String documentId,
			String documentVersion,
			String documentBoundSha,
			String bindingId,
			BindingGranularity granularity,
			String boundPath,
			Optional<SymbolKey> boundSymbolKey,
			Optional<String> sectionRef,
			List<DocumentClaim> claims) {

		public DocumentBinding {
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(workspaceId, "workspaceId");
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(documentId, "documentId");
			Objects.requireNonNull(documentVersion, "documentVersion");
			Objects.requireNonNull(documentBoundSha, "documentBoundSha");
			Objects.requireNonNull(bindingId, "bindingId");
			Objects.requireNonNull(granularity, "granularity");
			Objects.requireNonNull(boundPath, "boundPath");
			Objects.requireNonNull(boundSymbolKey, "boundSymbolKey");
			Objects.requireNonNull(sectionRef, "sectionRef");
			claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
		}
	}

	public record GroundedSourceFacetRef(
			SideRole sideRole,
			String pinnedSha,
			String path,
			SymbolKey symbolKey,
			FacetKind facetKind,
			Optional<SourceRange> range) {

		public GroundedSourceFacetRef {
			Objects.requireNonNull(sideRole, "sideRole");
			Objects.requireNonNull(pinnedSha, "pinnedSha");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(symbolKey, "symbolKey");
			Objects.requireNonNull(facetKind, "facetKind");
			Objects.requireNonNull(range, "range");
		}
	}

	public record GroundedDocumentClaimRef(
			String documentId,
			String documentVersion,
			String documentBoundSha,
			String bindingId,
			String claimId,
			Optional<String> sectionRef) {

		public GroundedDocumentClaimRef {
			Objects.requireNonNull(documentId, "documentId");
			Objects.requireNonNull(documentVersion, "documentVersion");
			Objects.requireNonNull(documentBoundSha, "documentBoundSha");
			Objects.requireNonNull(bindingId, "bindingId");
			Objects.requireNonNull(claimId, "claimId");
			Objects.requireNonNull(sectionRef, "sectionRef");
		}
	}

	public record SemanticAssessmentInput(
			String tenantId,
			String workspaceId,
			String repository,
			TargetKind targetKind,
			String targetRef,
			String jobId,
			String attemptId,
			String evaluatedTargetSha,
			String path,
			SymbolKey symbolKey,
			String documentId,
			String documentVersion,
			String documentBoundSha,
			SemanticStatus status,
			boolean withinDeadline,
			List<GroundedSourceFacetRef> groundedSourceRefs,
			List<GroundedDocumentClaimRef> groundedDocumentRefs,
			Optional<Double> confidence,
			Optional<String> rationale,
			Optional<VerifiedImpactContext> impactContext) {

		public SemanticAssessmentInput {
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(workspaceId, "workspaceId");
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(targetKind, "targetKind");
			Objects.requireNonNull(targetRef, "targetRef");
			Objects.requireNonNull(jobId, "jobId");
			Objects.requireNonNull(attemptId, "attemptId");
			Objects.requireNonNull(evaluatedTargetSha, "evaluatedTargetSha");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(symbolKey, "symbolKey");
			Objects.requireNonNull(documentId, "documentId");
			Objects.requireNonNull(documentVersion, "documentVersion");
			Objects.requireNonNull(documentBoundSha, "documentBoundSha");
			Objects.requireNonNull(status, "status");
			groundedSourceRefs = List.copyOf(Objects.requireNonNull(groundedSourceRefs, "groundedSourceRefs"));
			groundedDocumentRefs = List.copyOf(Objects.requireNonNull(groundedDocumentRefs, "groundedDocumentRefs"));
			Objects.requireNonNull(confidence, "confidence");
			Objects.requireNonNull(rationale, "rationale");
			Objects.requireNonNull(impactContext, "impactContext");
		}
	}

	public record ImpactClassificationInput(
			AssessmentContext context,
			List<StructuralDiffResult> diffResults,
			List<DocumentLookupResponse> lookupResponses,
			List<SemanticAssessmentInput> semanticAssessments,
			List<ControlledExportUnit> controlledExports,
			List<CallerExclusion> callerExclusions) {

		public ImpactClassificationInput {
			diffResults = diffResults == null ? List.of() : copyListAllowingNullElements(diffResults);
			lookupResponses = lookupResponses == null ? List.of() : copyListAllowingNullElements(lookupResponses);
			semanticAssessments = semanticAssessments == null ? List.of() : copyListAllowingNullElements(semanticAssessments);
			controlledExports = controlledExports == null ? List.of() : copyListAllowingNullElements(controlledExports);
			callerExclusions = callerExclusions == null ? List.of() : copyListAllowingNullElements(callerExclusions);
		}
	}

	private static <T> List<T> copyListAllowingNullElements(List<T> source) {
		return Collections.unmodifiableList(new ArrayList<>(source));
	}

	public sealed interface WitnessSelector
			permits SourceEvidenceSelector, DocumentWitnessSelector,
			SemanticWitnessSelector, NegativeBasisWitnessSelector {
		String witnessId();
	}

	public record SourceEvidenceSelector(
			String witnessId,
			String repository,
			SideRole sideRole,
			String pinnedSha,
			String path,
			SidePresence presence,
			SelectorGranularity granularity,
			Optional<SymbolKey> symbolKey,
			Optional<FacetKind> facetKind,
			Optional<ContextChangeKind> contextChangeKind) implements WitnessSelector {

		public SourceEvidenceSelector {
			Objects.requireNonNull(witnessId, "witnessId");
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(sideRole, "sideRole");
			Objects.requireNonNull(pinnedSha, "pinnedSha");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(presence, "presence");
			Objects.requireNonNull(granularity, "granularity");
			Objects.requireNonNull(symbolKey, "symbolKey");
			Objects.requireNonNull(facetKind, "facetKind");
			Objects.requireNonNull(contextChangeKind, "contextChangeKind");
		}
	}

	public record DocumentWitnessSelector(
			String witnessId,
			String documentId,
			String documentVersion,
			String documentBoundSha,
			String evaluatedTargetSha,
			String bindingId,
			Optional<String> sectionRef,
			Optional<String> claimId,
			Optional<String> documentedReferenceOrTerm) implements WitnessSelector {

		public DocumentWitnessSelector {
			Objects.requireNonNull(witnessId, "witnessId");
			Objects.requireNonNull(documentId, "documentId");
			Objects.requireNonNull(documentVersion, "documentVersion");
			Objects.requireNonNull(documentBoundSha, "documentBoundSha");
			Objects.requireNonNull(evaluatedTargetSha, "evaluatedTargetSha");
			Objects.requireNonNull(bindingId, "bindingId");
			Objects.requireNonNull(sectionRef, "sectionRef");
			Objects.requireNonNull(claimId, "claimId");
			Objects.requireNonNull(documentedReferenceOrTerm, "documentedReferenceOrTerm");
		}
	}

	public record SemanticWitnessSelector(
			String witnessId,
			String jobId,
			String attemptId,
			String evaluatedTargetSha,
			String path,
			SymbolKey symbolKey,
			FacetKind facetKind,
			String documentId,
			String documentVersion,
			String claimId,
			List<GroundedSourceFacetRef> groundedSourceRefs,
			List<GroundedDocumentClaimRef> groundedDocumentRefs) implements WitnessSelector {

		public SemanticWitnessSelector {
			Objects.requireNonNull(witnessId, "witnessId");
			Objects.requireNonNull(jobId, "jobId");
			Objects.requireNonNull(attemptId, "attemptId");
			Objects.requireNonNull(evaluatedTargetSha, "evaluatedTargetSha");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(symbolKey, "symbolKey");
			Objects.requireNonNull(facetKind, "facetKind");
			Objects.requireNonNull(documentId, "documentId");
			Objects.requireNonNull(documentVersion, "documentVersion");
			if (Objects.requireNonNull(claimId, "claimId").isBlank()) {
				throw new IllegalArgumentException("SemanticWitnessSelector claimId cannot be blank");
			}
			groundedSourceRefs = List.copyOf(Objects.requireNonNull(groundedSourceRefs, "groundedSourceRefs"));
			groundedDocumentRefs = List.copyOf(Objects.requireNonNull(groundedDocumentRefs, "groundedDocumentRefs"));
		}
	}

	public record NegativeBasisWitnessSelector(
			String witnessId,
			NegativeBasisKind kind,
			List<DocumentLookupKey> verifiedEmptyLookupKeys,
			Optional<String> beforeSha,
			Optional<String> beforePath,
			Optional<String> afterSha,
			Optional<String> afterPath,
			Optional<String> provenanceRefId,
			Optional<ScopeManifestRef> scopeManifest,
			Optional<String> expectedBeforeRawSha256,
			Optional<String> expectedTargetRawSha256) implements WitnessSelector {

		public NegativeBasisWitnessSelector {
			Objects.requireNonNull(witnessId, "witnessId");
			Objects.requireNonNull(kind, "kind");
			verifiedEmptyLookupKeys = List.copyOf(Objects.requireNonNull(verifiedEmptyLookupKeys, "verifiedEmptyLookupKeys"));
			Objects.requireNonNull(beforeSha, "beforeSha");
			Objects.requireNonNull(beforePath, "beforePath");
			Objects.requireNonNull(afterSha, "afterSha");
			Objects.requireNonNull(afterPath, "afterPath");
			Objects.requireNonNull(provenanceRefId, "provenanceRefId");
			Objects.requireNonNull(scopeManifest, "scopeManifest");
			Objects.requireNonNull(expectedBeforeRawSha256, "expectedBeforeRawSha256");
			Objects.requireNonNull(expectedTargetRawSha256, "expectedTargetRawSha256");
		}
	}

	public record ImpactFinding(
			String findingId,
			UnitDisposition disposition,
			ImpactOutcome outcome,
			Optional<DriftClassification> classification,
			Optional<Severity> severity,
			SeverityStatus severityStatus,
			Optional<String> severityRationale,
			Optional<Double> semanticConfidence,
			IssueAdmission admission,
			ReasonCode admissionReason,
			ReasonCode primaryReason,
			List<ReasonCode> secondaryReasons,
			String explanation,
			Optional<String> beforePath,
			Optional<String> afterPath,
			Optional<SymbolKey> symbolKey,
			Set<FacetKind> evaluatedFacets,
			Optional<String> documentId,
			Optional<String> documentVersion,
			Optional<String> documentBoundSha,
			Optional<String> bindingId,
			Optional<String> claimId,
			List<WitnessSelector> witnesses,
			Set<String> requiredImpactWitnessIds,
			Set<String> requiredAdmissionWitnessIds,
			boolean initialAssessment) {

		public ImpactFinding {
			if (Objects.requireNonNull(findingId, "findingId").isBlank()) {
				throw new IllegalArgumentException("findingId cannot be blank");
			}
			Objects.requireNonNull(disposition, "disposition");
			Objects.requireNonNull(outcome, "outcome");
			Objects.requireNonNull(classification, "classification");
			Objects.requireNonNull(severity, "severity");
			Objects.requireNonNull(severityStatus, "severityStatus");
			Objects.requireNonNull(severityRationale, "severityRationale");
			Objects.requireNonNull(semanticConfidence, "semanticConfidence");
			Objects.requireNonNull(admission, "admission");
			Objects.requireNonNull(admissionReason, "admissionReason");
			Objects.requireNonNull(primaryReason, "primaryReason");
			secondaryReasons = List.copyOf(Objects.requireNonNull(secondaryReasons, "secondaryReasons"));
			if (Objects.requireNonNull(explanation, "explanation").isBlank()) {
				throw new IllegalArgumentException("explanation cannot be blank");
			}
			Objects.requireNonNull(beforePath, "beforePath");
			Objects.requireNonNull(afterPath, "afterPath");
			Objects.requireNonNull(symbolKey, "symbolKey");
			evaluatedFacets = evaluatedFacets.isEmpty()
					? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(evaluatedFacets));
			Objects.requireNonNull(documentId, "documentId");
			Objects.requireNonNull(documentVersion, "documentVersion");
			Objects.requireNonNull(documentBoundSha, "documentBoundSha");
			Objects.requireNonNull(bindingId, "bindingId");
			Objects.requireNonNull(claimId, "claimId");
			witnesses = List.copyOf(Objects.requireNonNull(witnesses, "witnesses"));
			requiredImpactWitnessIds = Set.copyOf(Objects.requireNonNull(requiredImpactWitnessIds, "requiredImpactWitnessIds"));
			requiredAdmissionWitnessIds = Set.copyOf(Objects.requireNonNull(requiredAdmissionWitnessIds, "requiredAdmissionWitnessIds"));

			if (outcome == ImpactOutcome.ACTIONABLE && classification.isEmpty()) {
				throw new IllegalArgumentException("ACTIONABLE finding requires a DriftClassification");
			}
			if (outcome != ImpactOutcome.ACTIONABLE && classification.isPresent()) {
				throw new IllegalArgumentException("Non-ACTIONABLE finding cannot assign DriftClassification");
			}
			if (severity.isPresent() != (severityStatus == SeverityStatus.EVALUATED)) {
				throw new IllegalArgumentException("Severity presence must match SeverityStatus.EVALUATED");
			}
			if (classification.orElse(null) != DriftClassification.SEMANTIC && semanticConfidence.isPresent()) {
				throw new IllegalArgumentException("Deterministic findings cannot fabricate semanticConfidence");
			}
			if (disposition == UnitDisposition.EXCLUDED && outcome != ImpactOutcome.NO_IMPACT) {
				throw new IllegalArgumentException("EXCLUDED unit must carry scoped NO_IMPACT outcome");
			}
		}
	}

	public record ImpactDiagnostic(
			DiagnosticSeverity severity,
			ReasonCode code,
			String message) {

		public ImpactDiagnostic {
			Objects.requireNonNull(severity, "severity");
			Objects.requireNonNull(code, "code");
			if (Objects.requireNonNull(message, "message").isBlank()) {
				throw new IllegalArgumentException("Diagnostic message cannot be blank");
			}
		}
	}

	public record ImpactCoverageSummary(
			boolean completeCoverage,
			int evaluatedFileCount,
			int excludedFileCount,
			int failedOrIncompleteFileCount,
			int actionableCount,
			int noImpactCount,
			int inconclusiveCount,
			int excludedCount,
			List<String> coverageGaps,
			List<String> excludedScopeReasons) {

		public ImpactCoverageSummary {
			coverageGaps = List.copyOf(Objects.requireNonNull(coverageGaps, "coverageGaps"));
			excludedScopeReasons = List.copyOf(Objects.requireNonNull(excludedScopeReasons, "excludedScopeReasons"));
			if (completeCoverage && !coverageGaps.isEmpty()) {
				throw new IllegalArgumentException("Complete coverage cannot carry coverage gaps");
			}
		}
	}

	public record ImpactReport(
			String classifierVersion,
			ReportProcessingStatus processingStatus,
			Optional<ImpactOutcome> overallOutcome,
			AssessmentContext context,
			List<ImpactFinding> findings,
			ImpactCoverageSummary coverage,
			List<ImpactDiagnostic> diagnostics) {

		public ImpactReport {
			if (Objects.requireNonNull(classifierVersion, "classifierVersion").isBlank()) {
				throw new IllegalArgumentException("classifierVersion cannot be blank");
			}
			Objects.requireNonNull(processingStatus, "processingStatus");
			Objects.requireNonNull(overallOutcome, "overallOutcome");
			Objects.requireNonNull(context, "context");
			findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
			Objects.requireNonNull(coverage, "coverage");
			diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));

			if ((processingStatus == ReportProcessingStatus.FAILED
					|| processingStatus == ReportProcessingStatus.OUT_OF_SCOPE)
					&& overallOutcome.isPresent()) {
				throw new IllegalArgumentException("FAILED or OUT_OF_SCOPE report cannot carry an overallOutcome");
			}
			if ((processingStatus == ReportProcessingStatus.COMPLETE
					|| processingStatus == ReportProcessingStatus.PARTIAL)
					&& overallOutcome.isEmpty()) {
				throw new IllegalArgumentException("COMPLETE or PARTIAL report requires an overallOutcome");
			}
		}
	}
}
