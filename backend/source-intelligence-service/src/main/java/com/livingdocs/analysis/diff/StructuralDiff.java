package com.livingdocs.analysis.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.livingdocs.analysis.ast.JavaParseResult;
import com.livingdocs.analysis.ast.JavaParseResult.Severity;
import com.livingdocs.analysis.ast.JavaParseResult.SourceRange;
import com.livingdocs.analysis.ast.JavaParseResult.Status;
import com.livingdocs.analysis.ast.JavaParseResult.SymbolKey;
import com.livingdocs.analysis.ast.JavaSourceParser;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.EntityProjection;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FacetKind;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FieldDeclarationGroup;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FileContextProjection;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.Fingerprint;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.InitializerBlockProjection;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.ParseOutcome;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.ProvenanceRef;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.RawSourceFacts;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.ResidualRegion;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.ScopeElement;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.ScopeLayout;
import com.livingdocs.analysis.ast.SourceInput;
import com.livingdocs.analysis.diff.StructuralDiffInput.Absent;
import com.livingdocs.analysis.diff.StructuralDiffInput.Intent;
import com.livingdocs.analysis.diff.StructuralDiffInput.Present;
import com.livingdocs.analysis.diff.StructuralDiffInput.Side;
import com.livingdocs.analysis.diff.StructuralDiffInput.SnapshotRef;
import com.livingdocs.analysis.diff.StructuralDiffInput.Unavailable;

/**
 * Deterministic structural comparison entry point for one file association.
 *
 * <p>Processing precedence: invalid input -> scope (including mixed-language transitions)
 * -> Java parse failure -> unavailable or partial coverage -> structural comparison and attribution.
 */
public final class StructuralDiff {

	private final Function<SourceInput, ParseOutcome> parseGateway;

	public StructuralDiff() {
		this(new JavaSourceParser());
	}

	public StructuralDiff(JavaSourceParser parser) {
		Objects.requireNonNull(parser, "parser");
		this.parseGateway = source -> JavaStructuralRepresentation.parse(parser, source);
	}

	StructuralDiff(Function<SourceInput, ParseOutcome> parseGateway) {
		this.parseGateway = Objects.requireNonNull(parseGateway, "parseGateway");
	}

	public StructuralDiffResult compare(StructuralDiffInput input) {
		var inputDiagnostics = validateInput(input);
		if (!inputDiagnostics.isEmpty()) {
			Intent intent = input != null && input.intent() != null ? input.intent() : Intent.REVISION_COMPARISON;
			Optional<String> repo = input != null && !isBlank(input.repository())
					? Optional.of(input.repository()) : Optional.empty();
			return new StructuralDiffResult(
					ProcessingStatus.FAILED,
					intent,
					repo,
					JavaStructuralRepresentation.NORMALIZATION_VERSION,
					JavaStructuralRepresentation.PARSER_VERSION,
					JavaStructuralRepresentation.LANGUAGE_POLICY,
					Optional.empty(),
					Optional.empty(),
					List.of(),
					Optional.empty(),
					Optional.empty(),
					Optional.empty(),
					unresolvedFileComparison(Optional.empty(), Optional.empty(), List.of("Invalid comparison input")),
					new CoverageSummary(false, List.of("Comparison aborted due to invalid input")),
					inputDiagnostics);
		}

		if (input.intent() == Intent.INITIAL_ASSESSMENT) {
			return evaluateInitialAssessment(input, (Present) input.after());
		}
		return evaluateRevisionComparison(input, input.before().orElseThrow(), input.after());
	}

	private StructuralDiffResult evaluateInitialAssessment(StructuralDiffInput input, Present target) {
		String targetPath = target.path();
		var targetSnapshot = toSnapshotProvenance(target);
		if (!isJavaPath(targetPath)) {
			var outcome = parseGateway.apply(target.source());
			var diagnostics = mapParseDiagnostics(SideRole.TARGET, target.snapshot(), targetPath,
					outcome.parseResult(), DiagnosticCode.UNSUPPORTED_LANGUAGE);
			return new StructuralDiffResult(
					ProcessingStatus.OUT_OF_SCOPE,
					Intent.INITIAL_ASSESSMENT,
					Optional.of(input.repository()),
					JavaStructuralRepresentation.NORMALIZATION_VERSION,
					JavaStructuralRepresentation.PARSER_VERSION,
					JavaStructuralRepresentation.LANGUAGE_POLICY,
					Optional.empty(),
					Optional.of(targetSnapshot),
					List.of(),
					Optional.empty(),
					Optional.empty(),
					Optional.empty(),
					new FileComparison(PresenceRelation.INITIAL_TARGET, PathRelation.SINGLE_SIDE,
							Optional.empty(), Optional.of(targetPath), StructuralEquality.UNKNOWN,
							Set.of(), Set.of(), false, List.of("Non-Java target excluded from Java AST scope")),
					new CoverageSummary(false, List.of("Target path is outside Java AST scope: " + targetPath)),
					diagnostics);
		}

		var outcome = parseGateway.apply(target.source());
		var parseResult = outcome.parseResult();
		if (parseResult.status() == Status.FAILED) {
			var diagnostics = mapParseDiagnostics(SideRole.TARGET, target.snapshot(), targetPath, parseResult,
					DiagnosticCode.PARSE_FAILED);
			return new StructuralDiffResult(
					ProcessingStatus.FAILED,
					Intent.INITIAL_ASSESSMENT,
					Optional.of(input.repository()),
					JavaStructuralRepresentation.NORMALIZATION_VERSION,
					JavaStructuralRepresentation.PARSER_VERSION,
					JavaStructuralRepresentation.LANGUAGE_POLICY,
					Optional.empty(),
					Optional.of(targetSnapshot),
					List.of(),
					Optional.empty(),
					Optional.empty(),
					Optional.empty(),
					new FileComparison(PresenceRelation.INITIAL_TARGET, PathRelation.SINGLE_SIDE,
							Optional.empty(), Optional.of(targetPath), StructuralEquality.UNKNOWN,
							Set.of(), Set.of(), false, List.of("Target Java source failed to parse")),
					new CoverageSummary(false, List.of("Java parse failed for initial target: " + targetPath)),
					diagnostics);
		}

		if (parseResult.status() == Status.PARTIAL) {
			var diagnostics = mapParseDiagnostics(SideRole.TARGET, target.snapshot(), targetPath, parseResult,
					DiagnosticCode.PARTIAL_PARSE_COVERAGE);
			var gaps = extractCoverageGaps(SideRole.TARGET, targetPath, parseResult);
			return new StructuralDiffResult(
					ProcessingStatus.INCOMPLETE,
					Intent.INITIAL_ASSESSMENT,
					Optional.of(input.repository()),
					JavaStructuralRepresentation.NORMALIZATION_VERSION,
					JavaStructuralRepresentation.PARSER_VERSION,
					JavaStructuralRepresentation.LANGUAGE_POLICY,
					Optional.empty(),
					Optional.of(targetSnapshot),
					List.of(),
					Optional.empty(),
					Optional.empty(),
					Optional.empty(),
					new FileComparison(PresenceRelation.INITIAL_TARGET, PathRelation.SINGLE_SIDE,
							Optional.empty(), Optional.of(targetPath), StructuralEquality.UNKNOWN,
							Set.of(), Set.of(), false, List.of("Target Java source has partial coverage")),
					new CoverageSummary(false, gaps),
					diagnostics);
		}

		var representation = outcome.representation().orElseThrow();
		var inventory = buildTargetInventory(target.snapshot(), representation);
		var afterContext = toSideContextFacts(target.snapshot(), representation);
		if (!representation.residualRegions().isEmpty()) {
			var residualDiag = groundedDiagnostic(SideRole.TARGET, target.snapshot(), targetPath,
					DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE, Severity.INFO,
					"Target contains residual structural syntax outside declaration inventory",
					representation.residualRegions().getFirst().provenance());
			return new StructuralDiffResult(
					ProcessingStatus.INCOMPLETE,
					Intent.INITIAL_ASSESSMENT,
					Optional.of(input.repository()),
					representation.normalizationVersion(),
					representation.parserVersion(),
					representation.languagePolicy(),
					Optional.empty(),
					Optional.of(targetSnapshot),
					List.of(),
					Optional.of(inventory),
					Optional.empty(),
					Optional.of(afterContext),
					new FileComparison(PresenceRelation.INITIAL_TARGET, PathRelation.SINGLE_SIDE,
							Optional.empty(), Optional.of(targetPath), StructuralEquality.UNKNOWN,
							Set.of(ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE), Set.of(), false,
							List.of("Initial assessment inventory captured; residual structural tokens present")),
					new CoverageSummary(false,
							List.of("Residual structural regions present in initial target: " + targetPath)),
					List.of(residualDiag));
		}

		return new StructuralDiffResult(
				ProcessingStatus.COMPLETE,
				Intent.INITIAL_ASSESSMENT,
				Optional.of(input.repository()),
				representation.normalizationVersion(),
				representation.parserVersion(),
				representation.languagePolicy(),
				Optional.empty(),
				Optional.of(targetSnapshot),
				List.of(),
				Optional.of(inventory),
				Optional.empty(),
				Optional.of(afterContext),
				new FileComparison(PresenceRelation.INITIAL_TARGET, PathRelation.SINGLE_SIDE,
						Optional.empty(), Optional.of(targetPath), StructuralEquality.UNKNOWN,
						Set.of(), Set.of(), false, List.of("Initial assessment inventory captured without baseline")),
				new CoverageSummary(true, List.of()),
				List.of());
	}

	private StructuralDiffResult evaluateRevisionComparison(StructuralDiffInput input, Side before, Side after) {
		String beforePath = before.path();
		String afterPath = after.path();
		var beforeSnapshot = toSnapshotProvenance(before);
		var afterSnapshot = toSnapshotProvenance(after);
		boolean beforeJava = isJavaPath(beforePath);
		boolean afterJava = isJavaPath(afterPath);
		PathRelation pathRelation = beforePath.equals(afterPath) ? PathRelation.SAME_PATH : PathRelation.PATH_CHANGED;

		if (!beforeJava && !afterJava) {
			var diagnostics = new ArrayList<DiffDiagnostic>();
			collectNonJavaSideDiagnostics(before, SideRole.BEFORE, diagnostics);
			collectNonJavaSideDiagnostics(after, SideRole.AFTER, diagnostics);
			return new StructuralDiffResult(
					ProcessingStatus.OUT_OF_SCOPE,
					Intent.REVISION_COMPARISON,
					Optional.of(input.repository()),
					JavaStructuralRepresentation.NORMALIZATION_VERSION,
					JavaStructuralRepresentation.PARSER_VERSION,
					JavaStructuralRepresentation.LANGUAGE_POLICY,
					Optional.of(beforeSnapshot),
					Optional.of(afterSnapshot),
					List.of(),
					Optional.empty(),
					Optional.empty(),
					Optional.empty(),
					new FileComparison(PresenceRelation.UNRESOLVED, pathRelation,
							Optional.of(beforePath), Optional.of(afterPath), StructuralEquality.UNKNOWN,
							Set.of(), Set.of(), false, List.of("Both associated paths are outside Java AST scope")),
					new CoverageSummary(false, List.of("Non-Java file association excluded from Java AST scope")),
					diagnostics);
		}

		var diagnostics = new ArrayList<DiffDiagnostic>();
		var coverageGaps = new ArrayList<String>();
		var beforeEval = evaluateComparisonSide(before, SideRole.BEFORE, beforeJava, diagnostics, coverageGaps);
		var afterEval = evaluateComparisonSide(after, SideRole.AFTER, afterJava, diagnostics, coverageGaps);

		Optional<SideContextFacts> beforeContext = beforeEval.representation
				.map(rep -> toSideContextFacts(before.snapshot(), rep));
		Optional<SideContextFacts> afterContext = afterEval.representation
				.map(rep -> toSideContextFacts(after.snapshot(), rep));

		boolean mixedScope = beforeJava != afterJava;
		if (mixedScope) {
			String transition = beforePath + " -> " + afterPath;
			diagnostics.add(new DiffDiagnostic(SideRole.INPUT, DiagnosticCode.MIXED_LANGUAGE_SCOPE, Severity.INFO,
					"Java/non-Java file transition cannot complete structural comparison: " + transition,
					Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
			coverageGaps.add("Mixed Java/non-Java file transition: " + transition);
			return new StructuralDiffResult(
					ProcessingStatus.INCOMPLETE,
					Intent.REVISION_COMPARISON,
					Optional.of(input.repository()),
					JavaStructuralRepresentation.NORMALIZATION_VERSION,
					JavaStructuralRepresentation.PARSER_VERSION,
					JavaStructuralRepresentation.LANGUAGE_POLICY,
					Optional.of(beforeSnapshot),
					Optional.of(afterSnapshot),
					List.of(),
					Optional.empty(),
					beforeContext,
					afterContext,
					new FileComparison(PresenceRelation.UNRESOLVED, pathRelation,
							Optional.of(beforePath), Optional.of(afterPath), StructuralEquality.UNKNOWN,
							Set.of(), Set.of(), false,
							List.of("Mixed Java/non-Java file transition cannot be structurally compared")),
					new CoverageSummary(false, coverageGaps),
					diagnostics);
		}

		if (beforeEval.parseFailed || afterEval.parseFailed) {
			return new StructuralDiffResult(
					ProcessingStatus.FAILED,
					Intent.REVISION_COMPARISON,
					Optional.of(input.repository()),
					JavaStructuralRepresentation.NORMALIZATION_VERSION,
					JavaStructuralRepresentation.PARSER_VERSION,
					JavaStructuralRepresentation.LANGUAGE_POLICY,
					Optional.of(beforeSnapshot),
					Optional.of(afterSnapshot),
					List.of(),
					Optional.empty(),
					beforeContext,
					afterContext,
					new FileComparison(PresenceRelation.UNRESOLVED, pathRelation,
							Optional.of(beforePath), Optional.of(afterPath), StructuralEquality.UNKNOWN,
							Set.of(), Set.of(), false, List.of("Java parse failed on one or both comparison sides")),
					new CoverageSummary(false,
							coverageGaps.isEmpty() ? List.of("Java parse failure prevented comparison") : coverageGaps),
					diagnostics);
		}

		if (beforeEval.unavailable || afterEval.unavailable || beforeEval.partial || afterEval.partial) {
			return new StructuralDiffResult(
					ProcessingStatus.INCOMPLETE,
					Intent.REVISION_COMPARISON,
					Optional.of(input.repository()),
					JavaStructuralRepresentation.NORMALIZATION_VERSION,
					JavaStructuralRepresentation.PARSER_VERSION,
					JavaStructuralRepresentation.LANGUAGE_POLICY,
					Optional.of(beforeSnapshot),
					Optional.of(afterSnapshot),
					List.of(),
					Optional.empty(),
					beforeContext,
					afterContext,
					new FileComparison(PresenceRelation.UNRESOLVED, pathRelation,
							Optional.of(beforePath), Optional.of(afterPath), StructuralEquality.UNKNOWN,
							Set.of(), Set.of(), false, List.of("Comparison coverage is incomplete")),
					new CoverageSummary(false, coverageGaps),
					diagnostics);
		}

		if (before instanceof Absent absentBefore) {
			return compareAddedFile(input, absentBefore, (Present) after, beforeSnapshot, afterSnapshot,
					pathRelation, afterEval.representation.orElseThrow(), afterContext.orElseThrow());
		}
		if (after instanceof Absent absentAfter) {
			return compareRemovedFile(input, (Present) before, absentAfter, beforeSnapshot, afterSnapshot,
					pathRelation, beforeEval.representation.orElseThrow(), beforeContext.orElseThrow());
		}
		return comparePresentJavaFiles(input, (Present) before, (Present) after, beforeSnapshot, afterSnapshot,
				pathRelation, beforeEval.representation.orElseThrow(), afterEval.representation.orElseThrow(),
				beforeContext.orElseThrow(), afterContext.orElseThrow());
	}

	private StructuralDiffResult compareAddedFile(
			StructuralDiffInput input,
			Absent before,
			Present after,
			SnapshotProvenance beforeSnapshot,
			SnapshotProvenance afterSnapshot,
			PathRelation pathRelation,
			JavaStructuralRepresentation afterRep,
			SideContextFacts afterContext) {
		String normVersion = afterRep.normalizationVersion();
		var facts = afterRep.entities().stream()
				.map(entity -> new EntityFact(
						ChangeKind.ADDED,
						Optional.empty(),
						Optional.of(toEntitySide(after.snapshot(), after.path(), entity)),
						Set.of(),
						MatchBasis.UNMATCHED_AFTER,
						Optional.of(UnmatchedReason.FILE_ADDED),
						normVersion))
				.toList();

		var contextChanges = EnumSet.noneOf(ContextChangeKind.class);
		if (afterRep.fileContext().packageDecl().isPresent()) {
			contextChanges.add(ContextChangeKind.PACKAGE_CHANGED);
		}
		if (!afterRep.fileContext().imports().isEmpty()) {
			contextChanges.add(ContextChangeKind.IMPORTS_CHANGED);
		}
		if (afterRep.fileContext().module().isPresent()) {
			contextChanges.add(ContextChangeKind.MODULE_CHANGED);
		}
		if (!afterRep.initializerBlocks().isEmpty()) {
			contextChanges.add(ContextChangeKind.INITIALIZER_BLOCK_CHANGED);
		}

		var reasons = new ArrayList<String>();
		reasons.add("Verified absent in before snapshot: " + before.reason());
		if (pathRelation == PathRelation.PATH_CHANGED) {
			reasons.add("Associated file path changed: " + before.path() + " -> " + after.path());
		}

		if (!afterRep.residualRegions().isEmpty()) {
			contextChanges.add(ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE);
			reasons.add("Added file contains residual structural tokens outside entity inventory");
			var residualDiag = groundedDiagnostic(SideRole.AFTER, after.snapshot(), after.path(),
					DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE, Severity.INFO,
					"Added Java file contains unattributed residual structural tokens",
					afterRep.residualRegions().getFirst().provenance());
			return new StructuralDiffResult(
					ProcessingStatus.INCOMPLETE,
					Intent.REVISION_COMPARISON,
					Optional.of(input.repository()),
					normVersion,
					afterRep.parserVersion(),
					afterRep.languagePolicy(),
					Optional.of(beforeSnapshot),
					Optional.of(afterSnapshot),
					facts,
					Optional.empty(),
					Optional.empty(),
					Optional.of(afterContext),
					new FileComparison(PresenceRelation.ADDED, pathRelation,
							Optional.of(before.path()), Optional.of(after.path()),
							StructuralEquality.DIFFERENT, contextChanges, Set.of(), false, reasons),
					new CoverageSummary(false,
							List.of("Residual structural regions present in added file: " + after.path())),
					List.of(residualDiag));
		}

		return new StructuralDiffResult(
				ProcessingStatus.COMPLETE,
				Intent.REVISION_COMPARISON,
				Optional.of(input.repository()),
				normVersion,
				afterRep.parserVersion(),
				afterRep.languagePolicy(),
				Optional.of(beforeSnapshot),
				Optional.of(afterSnapshot),
				facts,
				Optional.empty(),
				Optional.empty(),
				Optional.of(afterContext),
				new FileComparison(PresenceRelation.ADDED, pathRelation,
						Optional.of(before.path()), Optional.of(after.path()),
						StructuralEquality.DIFFERENT, contextChanges, Set.of(), false, reasons),
				new CoverageSummary(true, List.of()),
				List.of());
	}

	private StructuralDiffResult compareRemovedFile(
			StructuralDiffInput input,
			Present before,
			Absent after,
			SnapshotProvenance beforeSnapshot,
			SnapshotProvenance afterSnapshot,
			PathRelation pathRelation,
			JavaStructuralRepresentation beforeRep,
			SideContextFacts beforeContext) {
		String normVersion = beforeRep.normalizationVersion();
		var facts = beforeRep.entities().stream()
				.map(entity -> new EntityFact(
						ChangeKind.REMOVED,
						Optional.of(toEntitySide(before.snapshot(), before.path(), entity)),
						Optional.empty(),
						Set.of(),
						MatchBasis.UNMATCHED_BEFORE,
						Optional.of(UnmatchedReason.FILE_REMOVED),
						normVersion))
				.toList();

		var contextChanges = EnumSet.noneOf(ContextChangeKind.class);
		if (beforeRep.fileContext().packageDecl().isPresent()) {
			contextChanges.add(ContextChangeKind.PACKAGE_CHANGED);
		}
		if (!beforeRep.fileContext().imports().isEmpty()) {
			contextChanges.add(ContextChangeKind.IMPORTS_CHANGED);
		}
		if (beforeRep.fileContext().module().isPresent()) {
			contextChanges.add(ContextChangeKind.MODULE_CHANGED);
		}
		if (!beforeRep.initializerBlocks().isEmpty()) {
			contextChanges.add(ContextChangeKind.INITIALIZER_BLOCK_CHANGED);
		}

		var reasons = new ArrayList<String>();
		reasons.add("Verified absent in after snapshot: " + after.reason());
		if (pathRelation == PathRelation.PATH_CHANGED) {
			reasons.add("Associated file path changed: " + before.path() + " -> " + after.path());
		}

		if (!beforeRep.residualRegions().isEmpty()) {
			contextChanges.add(ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE);
			reasons.add("Removed file contained residual structural tokens outside entity inventory");
			var residualDiag = groundedDiagnostic(SideRole.BEFORE, before.snapshot(), before.path(),
					DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE, Severity.INFO,
					"Removed Java file contained unattributed residual structural tokens",
					beforeRep.residualRegions().getFirst().provenance());
			return new StructuralDiffResult(
					ProcessingStatus.INCOMPLETE,
					Intent.REVISION_COMPARISON,
					Optional.of(input.repository()),
					normVersion,
					beforeRep.parserVersion(),
					beforeRep.languagePolicy(),
					Optional.of(beforeSnapshot),
					Optional.of(afterSnapshot),
					facts,
					Optional.empty(),
					Optional.of(beforeContext),
					Optional.empty(),
					new FileComparison(PresenceRelation.REMOVED, pathRelation,
							Optional.of(before.path()), Optional.of(after.path()),
							StructuralEquality.DIFFERENT, contextChanges, Set.of(), false, reasons),
					new CoverageSummary(false,
							List.of("Residual structural regions present in removed file: " + before.path())),
					List.of(residualDiag));
		}

		return new StructuralDiffResult(
				ProcessingStatus.COMPLETE,
				Intent.REVISION_COMPARISON,
				Optional.of(input.repository()),
				normVersion,
				beforeRep.parserVersion(),
				beforeRep.languagePolicy(),
				Optional.of(beforeSnapshot),
				Optional.of(afterSnapshot),
				facts,
				Optional.empty(),
				Optional.of(beforeContext),
				Optional.empty(),
				new FileComparison(PresenceRelation.REMOVED, pathRelation,
						Optional.of(before.path()), Optional.of(after.path()),
						StructuralEquality.DIFFERENT, contextChanges, Set.of(), false, reasons),
				new CoverageSummary(true, List.of()),
				List.of());
	}

	private StructuralDiffResult comparePresentJavaFiles(
			StructuralDiffInput input,
			Present before,
			Present after,
			SnapshotProvenance beforeSnapshot,
			SnapshotProvenance afterSnapshot,
			PathRelation pathRelation,
			JavaStructuralRepresentation beforeRep,
			JavaStructuralRepresentation afterRep,
			SideContextFacts beforeContext,
			SideContextFacts afterContext) {
		if (!beforeRep.normalizationVersion().equals(afterRep.normalizationVersion())
				|| !beforeRep.wholeFileFingerprint().isCompatibleWith(afterRep.wholeFileFingerprint())) {
			throw new IllegalArgumentException("Incompatible normalization versions between before and after sides: "
					+ beforeRep.normalizationVersion() + " vs " + afterRep.normalizationVersion());
		}

		String normVersion = afterRep.normalizationVersion();
		var beforeByKey = new LinkedHashMap<SymbolKey, EntityProjection>(beforeRep.entities().size() * 2);
		for (var entity : beforeRep.entities()) {
			beforeByKey.put(entity.key(), entity);
		}
		var afterByKey = new LinkedHashMap<SymbolKey, EntityProjection>(afterRep.entities().size() * 2);
		for (var entity : afterRep.entities()) {
			afterByKey.put(entity.key(), entity);
		}

		var matchedKeys = new HashSet<SymbolKey>();
		var entityFacts = new ArrayList<EntityFact>(beforeByKey.size() + afterByKey.size());
		int unmatchedRemovals = 0;
		int unmatchedAdditions = 0;

		for (var entry : beforeByKey.entrySet()) {
			var key = entry.getKey();
			var beforeProj = entry.getValue();
			var beforeSide = toEntitySide(before.snapshot(), before.path(), beforeProj);
			var afterProj = afterByKey.get(key);
			if (afterProj == null) {
				unmatchedRemovals++;
				entityFacts.add(new EntityFact(
						ChangeKind.REMOVED,
						Optional.of(beforeSide),
						Optional.empty(),
						Set.of(),
						MatchBasis.UNMATCHED_BEFORE,
						Optional.of(UnmatchedReason.NO_COUNTERPART_KEY),
						normVersion));
			} else {
				matchedKeys.add(key);
				var afterSide = toEntitySide(after.snapshot(), after.path(), afterProj);
				var changedFacets = compareEntityFacets(beforeProj, afterProj);
				ChangeKind change = changedFacets.isEmpty() ? ChangeKind.UNCHANGED : ChangeKind.MODIFIED;
				entityFacts.add(new EntityFact(
						change,
						Optional.of(beforeSide),
						Optional.of(afterSide),
						changedFacets,
						MatchBasis.EXACT_SYMBOL_KEY,
						Optional.empty(),
						normVersion));
			}
		}

		for (var entry : afterByKey.entrySet()) {
			if (!beforeByKey.containsKey(entry.getKey())) {
				unmatchedAdditions++;
				var afterSide = toEntitySide(after.snapshot(), after.path(), entry.getValue());
				entityFacts.add(new EntityFact(
						ChangeKind.ADDED,
						Optional.empty(),
						Optional.of(afterSide),
						Set.of(),
						MatchBasis.UNMATCHED_AFTER,
						Optional.of(UnmatchedReason.NO_COUNTERPART_KEY),
						normVersion));
			}
		}

		var contextChanges = EnumSet.noneOf(ContextChangeKind.class);
		var reasons = new ArrayList<String>();
		var residualDiagnostics = new ArrayList<DiffDiagnostic>();
		if (pathRelation == PathRelation.PATH_CHANGED) {
			reasons.add("Associated file path changed: " + before.path() + " -> " + after.path());
		}
		if (unmatchedRemovals > 0 && unmatchedAdditions > 0) {
			reasons.add("Unmatched symbol removals and additions present; cross-key equivalence is unresolved");
		}

		compareFileContext(beforeRep.fileContext(), afterRep.fileContext(), contextChanges, reasons);
		compareInitializerBlocks(beforeRep.initializerBlocks(), afterRep.initializerBlocks(), contextChanges, reasons);
		compareScopeLayouts(beforeRep, afterRep, matchedKeys, contextChanges, reasons);

		// Always compare both explicit residual regions and multi-field declaration grouping,
		// regardless of whether entity or context facets also changed.
		var residualEvidence = detectResidualChange(before, beforeRep, after, afterRep, matchedKeys);
		if (residualEvidence.isPresent()) {
			contextChanges.add(ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE);
			reasons.add(residualEvidence.orElseThrow().reason());
			residualDiagnostics.add(residualEvidence.orElseThrow().diagnostic());
		}

		var groupingEvidence = detectUnattributedFieldGroupingChange(before, beforeRep, after, afterRep, matchedKeys);
		if (groupingEvidence.isPresent()) {
			contextChanges.add(ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE);
			reasons.add(groupingEvidence.orElseThrow().reason());
			residualDiagnostics.add(groupingEvidence.orElseThrow().diagnostic());
		}

		boolean wholeFileEqual = beforeRep.wholeFileFingerprint().matchesContent(afterRep.wholeFileFingerprint());
		boolean anyEntityChanged = entityFacts.stream().anyMatch(fact -> fact.change() != ChangeKind.UNCHANGED);
		boolean anyAttributedChange = anyEntityChanged || !contextChanges.isEmpty();

		// Whole-file structural token stream remains the final safety backstop.
		if (!wholeFileEqual && !anyAttributedChange) {
			contextChanges.add(ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE);
			reasons.add("Whole-file structural tokens changed without entity or context facet attribution");
			residualDiagnostics.add(new DiffDiagnostic(SideRole.INPUT, DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE,
					Severity.INFO, "Whole-file structural tokens changed without localized declaration attribution",
					Optional.empty(), Optional.empty(), Optional.empty(),
					Optional.of("Whole-file token difference has no single localized residual range")));
		}

		var rawChanges = computeRawChanges(before.source().sourceContent(), beforeRep.rawFacts(),
				after.source().sourceContent(), afterRep.rawFacts(), reasons);

		StructuralEquality structuralEquality = (wholeFileEqual && !anyEntityChanged && contextChanges.isEmpty())
				? StructuralEquality.EQUAL : StructuralEquality.DIFFERENT;
		boolean rawOnlyChange = structuralEquality == StructuralEquality.EQUAL && !rawChanges.isEmpty();

		if (contextChanges.contains(ContextChangeKind.RESIDUAL_STRUCTURAL_CHANGE)) {
			return new StructuralDiffResult(
					ProcessingStatus.INCOMPLETE,
					Intent.REVISION_COMPARISON,
					Optional.of(input.repository()),
					normVersion,
					afterRep.parserVersion(),
					afterRep.languagePolicy(),
					Optional.of(beforeSnapshot),
					Optional.of(afterSnapshot),
					entityFacts,
					Optional.empty(),
					Optional.of(beforeContext),
					Optional.of(afterContext),
					new FileComparison(PresenceRelation.BOTH_PRESENT, pathRelation,
							Optional.of(before.path()), Optional.of(after.path()),
							StructuralEquality.DIFFERENT, contextChanges, rawChanges, false, reasons),
					new CoverageSummary(false,
							List.of("Unattributed residual or declaration-grouping change detected between snapshots")),
					residualDiagnostics);
		}

		return new StructuralDiffResult(
				ProcessingStatus.COMPLETE,
				Intent.REVISION_COMPARISON,
				Optional.of(input.repository()),
				normVersion,
				afterRep.parserVersion(),
				afterRep.languagePolicy(),
				Optional.of(beforeSnapshot),
				Optional.of(afterSnapshot),
				entityFacts,
				Optional.empty(),
				Optional.of(beforeContext),
				Optional.of(afterContext),
				new FileComparison(PresenceRelation.BOTH_PRESENT, pathRelation,
						Optional.of(before.path()), Optional.of(after.path()),
						structuralEquality, contextChanges, rawChanges, rawOnlyChange, reasons),
				new CoverageSummary(true, List.of()),
				List.of());
	}

	private static Set<FacetKind> compareEntityFacets(EntityProjection before, EntityProjection after) {
		var changed = EnumSet.noneOf(FacetKind.class);
		for (var kind : FacetKind.values()) {
			Fingerprint left = before.facets().get(kind);
			Fingerprint right = after.facets().get(kind);
			if (left == null && right == null) {
				continue;
			}
			if (left == null || right == null || !left.matchesContent(right)) {
				changed.add(kind);
			}
		}
		return changed;
	}

	private static void compareFileContext(FileContextProjection before, FileContextProjection after,
			Set<ContextChangeKind> contextChanges, List<String> reasons) {
		if (before.packageDecl().isPresent() != after.packageDecl().isPresent()
				|| (before.packageDecl().isPresent() && !before.packageDecl().orElseThrow().facet()
						.matchesContent(after.packageDecl().orElseThrow().facet()))) {
			contextChanges.add(ContextChangeKind.PACKAGE_CHANGED);
			reasons.add("Package declaration or package annotations changed");
		}

		if (before.imports().size() != after.imports().size()) {
			contextChanges.add(ContextChangeKind.IMPORTS_CHANGED);
			reasons.add("Import declarations changed");
		} else {
			for (int i = 0; i < before.imports().size(); i++) {
				if (!before.imports().get(i).facet().matchesContent(after.imports().get(i).facet())) {
					contextChanges.add(ContextChangeKind.IMPORTS_CHANGED);
					reasons.add("Import declarations or import ordering changed");
					break;
				}
			}
		}

		if (before.module().isPresent() != after.module().isPresent()) {
			contextChanges.add(ContextChangeKind.MODULE_CHANGED);
			reasons.add("Module declaration presence changed");
		} else if (before.module().isPresent()) {
			var bMod = before.module().orElseThrow();
			var aMod = after.module().orElseThrow();
			boolean modChanged = !bMod.headerFacet().matchesContent(aMod.headerFacet())
					|| bMod.directives().size() != aMod.directives().size();
			if (!modChanged) {
				for (int i = 0; i < bMod.directives().size(); i++) {
					if (!bMod.directives().get(i).facet().matchesContent(aMod.directives().get(i).facet())) {
						modChanged = true;
						break;
					}
				}
			}
			if (modChanged) {
				contextChanges.add(ContextChangeKind.MODULE_CHANGED);
				reasons.add("Module header, annotations, or directives changed");
			}
		}
	}

	private static void compareInitializerBlocks(List<InitializerBlockProjection> before,
			List<InitializerBlockProjection> after, Set<ContextChangeKind> contextChanges, List<String> reasons) {
		var beforeByType = groupInitializersByType(before);
		var afterByType = groupInitializersByType(after);
		var allTypes = new LinkedHashSet<SymbolKey>(beforeByType.keySet());
		allTypes.addAll(afterByType.keySet());

		for (var typeKey : allTypes) {
			var beforeList = beforeByType.getOrDefault(typeKey, List.of());
			var afterList = afterByType.getOrDefault(typeKey, List.of());
			if (beforeList.size() != afterList.size()) {
				contextChanges.add(ContextChangeKind.INITIALIZER_BLOCK_CHANGED);
				reasons.add("Initializer block count changed in " + typeKey);
				continue;
			}
			for (int i = 0; i < beforeList.size(); i++) {
				var left = beforeList.get(i);
				var right = afterList.get(i);
				if (left.isStatic() != right.isStatic()
						|| left.indexInType() != right.indexInType()
						|| !left.facet().matchesContent(right.facet())) {
					contextChanges.add(ContextChangeKind.INITIALIZER_BLOCK_CHANGED);
					reasons.add("Static or instance initializer block changed in " + typeKey);
					break;
				}
			}
		}
	}

	private static void compareScopeLayouts(JavaStructuralRepresentation beforeRep,
			JavaStructuralRepresentation afterRep, Set<SymbolKey> matchedKeys,
			Set<ContextChangeKind> contextChanges, List<String> reasons) {
		boolean includeTopResiduals = countTopLevelResiduals(beforeRep) == countTopLevelResiduals(afterRep);
		if (!retainedOrderDescriptors(beforeRep.topLevelLayout(), beforeRep, matchedKeys,
				InitializerRetention.NONE, includeTopResiduals)
				.equals(retainedOrderDescriptors(afterRep.topLevelLayout(), afterRep, matchedKeys,
						InitializerRetention.NONE, includeTopResiduals))) {
			contextChanges.add(ContextChangeKind.SCOPE_ORDER_CHANGED);
			reasons.add("Top-level relative element ordering changed");
		}
		if (beforeRep.moduleLayout().isPresent() && afterRep.moduleLayout().isPresent()
				&& !beforeRep.moduleLayout().orElseThrow().orderFacet()
						.matchesContent(afterRep.moduleLayout().orElseThrow().orderFacet())) {
			contextChanges.add(ContextChangeKind.SCOPE_ORDER_CHANGED);
			reasons.add("Module directive/residual ordering changed");
		}

		var beforeByType = groupInitializersByType(beforeRep.initializerBlocks());
		var afterByType = groupInitializersByType(afterRep.initializerBlocks());
		for (var entry : beforeRep.typeLayouts().entrySet()) {
			var typeKey = entry.getKey();
			if (!matchedKeys.contains(typeKey)) {
				continue;
			}
			var afterLayout = afterRep.typeLayouts().get(typeKey);
			if (afterLayout == null) {
				continue;
			}
			var initRetention = InitializerRetention.forType(
					beforeByType.getOrDefault(typeKey, List.of()),
					afterByType.getOrDefault(typeKey, List.of()));
			boolean includeTypeResiduals = countTypeResiduals(beforeRep, typeKey)
					== countTypeResiduals(afterRep, typeKey);
			if (!retainedOrderDescriptors(entry.getValue(), beforeRep, matchedKeys,
					initRetention, includeTypeResiduals)
					.equals(retainedOrderDescriptors(afterLayout, afterRep, matchedKeys,
							initRetention, includeTypeResiduals))) {
				contextChanges.add(ContextChangeKind.SCOPE_ORDER_CHANGED);
				reasons.add("Relative ordering of members, initializers, or residuals changed in " + typeKey);
			}
		}
	}

	private static Map<SymbolKey, List<InitializerBlockProjection>> groupInitializersByType(
			List<InitializerBlockProjection> initializers) {
		var grouped = new LinkedHashMap<SymbolKey, List<InitializerBlockProjection>>();
		for (var init : initializers) {
			grouped.computeIfAbsent(init.enclosingType(), ignored -> new ArrayList<>()).add(init);
		}
		return grouped;
	}

	private static long countTopLevelResiduals(JavaStructuralRepresentation rep) {
		return rep.residualRegions().stream()
				.filter(r -> r.enclosingType().isEmpty() && r.moduleName().isEmpty())
				.count();
	}

	private static long countTypeResiduals(JavaStructuralRepresentation rep, SymbolKey typeKey) {
		return rep.residualRegions().stream()
				.filter(r -> r.enclosingType().isPresent() && r.enclosingType().orElseThrow().equals(typeKey))
				.count();
	}

	private record InitializerRetention(
			boolean includeAllStatic,
			boolean includeAllInstance,
			Set<String> uniqueStaticSha256,
			Set<String> uniqueInstanceSha256) {

		private static final InitializerRetention NONE =
				new InitializerRetention(false, false, Set.of(), Set.of());

		private static InitializerRetention forType(
				List<InitializerBlockProjection> beforeInits,
				List<InitializerBlockProjection> afterInits) {
			long beforeStatic = countByStatic(beforeInits, true);
			long afterStatic = countByStatic(afterInits, true);
			long beforeInstance = countByStatic(beforeInits, false);
			long afterInstance = countByStatic(afterInits, false);

			boolean allStatic = beforeStatic == afterStatic;
			boolean allInstance = beforeInstance == afterInstance;
			Set<String> uniqueStatic = uniqueMatchedSha256(beforeInits, afterInits, true);
			Set<String> uniqueInstance = uniqueMatchedSha256(beforeInits, afterInits, false);
			return new InitializerRetention(allStatic, allInstance, uniqueStatic, uniqueInstance);
		}

		private static long countByStatic(List<InitializerBlockProjection> inits, boolean isStatic) {
			return inits.stream().filter(i -> i.isStatic() == isStatic).count();
		}

		private static Set<String> uniqueMatchedSha256(
				List<InitializerBlockProjection> beforeInits,
				List<InitializerBlockProjection> afterInits,
				boolean isStatic) {
			var beforeCounts = new LinkedHashMap<String, Integer>();
			for (var init : beforeInits) {
				if (init.isStatic() == isStatic) {
					beforeCounts.merge(init.facet().sha256(), 1, Integer::sum);
				}
			}
			var afterCounts = new LinkedHashMap<String, Integer>();
			for (var init : afterInits) {
				if (init.isStatic() == isStatic) {
					afterCounts.merge(init.facet().sha256(), 1, Integer::sum);
				}
			}
			var unique = new LinkedHashSet<String>();
			for (var entry : beforeCounts.entrySet()) {
				if (entry.getValue() == 1 && afterCounts.getOrDefault(entry.getKey(), 0) == 1) {
					unique.add(entry.getKey());
				}
			}
			return Set.copyOf(unique);
		}
	}

	private static List<String> retainedOrderDescriptors(ScopeLayout layout, JavaStructuralRepresentation rep,
			Set<SymbolKey> matchedKeys, InitializerRetention initRetention, boolean includeResiduals) {
		var list = new ArrayList<String>();
		for (var element : layout.elements()) {
			if (element instanceof ScopeElement.EntityElement ent) {
				if (matchedKeys.contains(ent.key())) {
					list.add("ENTITY:" + JavaStructuralRepresentation.canonicalSymbolKey(ent.key()));
				}
			} else if (element instanceof ScopeElement.InitializerElement init) {
				String sha = rep.initializerBlocks().get(init.initializerIndex()).facet().sha256();
				if (init.isStatic()) {
					if (initRetention.uniqueStaticSha256().contains(sha)) {
						list.add("INIT:STATIC:" + sha);
					} else if (initRetention.includeAllStatic()) {
						list.add("INIT:STATIC");
					}
				} else if (initRetention.uniqueInstanceSha256().contains(sha)) {
					list.add("INIT:INSTANCE:" + sha);
				} else if (initRetention.includeAllInstance()) {
					list.add("INIT:INSTANCE");
				}
			} else if (element instanceof ScopeElement.ResidualElement res) {
				if (includeResiduals) {
					list.add("RESIDUAL:" + rep.residualRegions().get(res.residualIndex()).facet().sha256());
				}
			}
		}
		return list;
	}

	private static Optional<UnattributedEvidence> detectResidualChange(
			Present before,
			JavaStructuralRepresentation beforeRep,
			Present after,
			JavaStructuralRepresentation afterRep,
			Set<SymbolKey> matchedKeys) {
		var beforeResiduals = beforeRep.residualRegions();
		var afterResiduals = afterRep.residualRegions();
		if (beforeResiduals.isEmpty() && afterResiduals.isEmpty()) {
			return Optional.empty();
		}

		int minSize = Math.min(beforeResiduals.size(), afterResiduals.size());
		for (int i = 0; i < minSize; i++) {
			var left = beforeResiduals.get(i);
			var right = afterResiduals.get(i);
			if (!left.enclosingType().equals(right.enclosingType())
					|| !left.moduleName().equals(right.moduleName())
					|| left.indexInScope() != right.indexInScope()
					|| !left.facet().matchesContent(right.facet())) {
				return Optional.of(new UnattributedEvidence(
						"Residual structural regions changed outside declaration inventory",
						groundedDiagnostic(SideRole.AFTER, after.snapshot(), after.path(),
								DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE, Severity.INFO,
								"Residual structural syntax modified in after snapshot",
								right.provenance())));
			}
		}

		if (afterResiduals.size() > beforeResiduals.size()) {
			var addedResidual = afterResiduals.get(minSize);
			return Optional.of(new UnattributedEvidence(
					"Residual structural region added outside declaration inventory",
					groundedDiagnostic(SideRole.AFTER, after.snapshot(), after.path(),
							DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE, Severity.INFO,
							"Residual structural syntax added in after snapshot",
							addedResidual.provenance())));
		}

		if (beforeResiduals.size() > afterResiduals.size()) {
			var removedResidual = beforeResiduals.get(minSize);
			return Optional.of(new UnattributedEvidence(
					"Residual structural region removed outside declaration inventory",
					groundedDiagnostic(SideRole.BEFORE, before.snapshot(), before.path(),
							DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE, Severity.INFO,
							"Residual structural syntax removed from before snapshot",
							removedResidual.provenance())));
		}

		// Equal residual count and content: verify relative anchor position against surviving elements.
		var movedIndex = findMovedResidualIndex(beforeRep, afterRep, matchedKeys);
		if (movedIndex.isPresent()) {
			var moved = afterResiduals.get(movedIndex.orElseThrow());
			return Optional.of(new UnattributedEvidence(
					"Residual structural region moved relative to surrounding declarations",
					groundedDiagnostic(SideRole.AFTER, after.snapshot(), after.path(),
							DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE, Severity.INFO,
							"Residual structural syntax moved in after snapshot",
							moved.provenance())));
		}
		return Optional.empty();
	}

	private static Optional<Integer> findMovedResidualIndex(JavaStructuralRepresentation beforeRep,
			JavaStructuralRepresentation afterRep, Set<SymbolKey> matchedKeys) {
		var topMoved = firstAnchorMismatch(
				residualAnchors(beforeRep.topLevelLayout(), matchedKeys),
				residualAnchors(afterRep.topLevelLayout(), matchedKeys));
		if (topMoved.isPresent()) {
			return topMoved;
		}
		for (var entry : beforeRep.typeLayouts().entrySet()) {
			if (!matchedKeys.contains(entry.getKey())) {
				continue;
			}
			var afterLayout = afterRep.typeLayouts().get(entry.getKey());
			if (afterLayout == null) {
				continue;
			}
			var typeMoved = firstAnchorMismatch(
					residualAnchors(entry.getValue(), matchedKeys),
					residualAnchors(afterLayout, matchedKeys));
			if (typeMoved.isPresent()) {
				return typeMoved;
			}
		}
		return Optional.empty();
	}

	private static Optional<Integer> firstAnchorMismatch(List<ResidualAnchor> beforeAnchors,
			List<ResidualAnchor> afterAnchors) {
		int min = Math.min(beforeAnchors.size(), afterAnchors.size());
		for (int i = 0; i < min; i++) {
			if (!beforeAnchors.get(i).anchor().equals(afterAnchors.get(i).anchor())) {
				return Optional.of(afterAnchors.get(i).residualIndex());
			}
		}
		if (afterAnchors.size() > beforeAnchors.size()) {
			return Optional.of(afterAnchors.get(min).residualIndex());
		}
		if (beforeAnchors.size() > afterAnchors.size()) {
			return Optional.of(beforeAnchors.get(min).residualIndex());
		}
		return Optional.empty();
	}

	private static List<ResidualAnchor> residualAnchors(ScopeLayout layout, Set<SymbolKey> matchedKeys) {
		var anchors = new ArrayList<ResidualAnchor>();
		String prevRetained = "<START>";
		for (var element : layout.elements()) {
			if (element instanceof ScopeElement.ResidualElement res) {
				anchors.add(new ResidualAnchor(res.residualIndex(), prevRetained));
			} else if (element instanceof ScopeElement.EntityElement ent) {
				if (matchedKeys.contains(ent.key())) {
					prevRetained = "ENTITY:" + JavaStructuralRepresentation.canonicalSymbolKey(ent.key());
				}
			} else if (element instanceof ScopeElement.InitializerElement init) {
				prevRetained = init.isStatic() ? "INIT:STATIC" : "INIT:INSTANCE";
			} else if (element instanceof ScopeElement.PackageElement) {
				prevRetained = "PACKAGE";
			} else if (element instanceof ScopeElement.ImportElement imp) {
				prevRetained = "IMPORT:" + imp.importIndex();
			} else if (element instanceof ScopeElement.ModuleElement) {
				prevRetained = "MODULE";
			} else if (element instanceof ScopeElement.ModuleDirectiveElement dir) {
				prevRetained = "DIRECTIVE:" + dir.directiveIndex();
			}
		}
		return anchors;
	}

	private static Optional<UnattributedEvidence> detectUnattributedFieldGroupingChange(
			Present before,
			JavaStructuralRepresentation beforeRep,
			Present after,
			JavaStructuralRepresentation afterRep,
			Set<SymbolKey> matchedKeys) {
		if (beforeRep.fieldGroups().isEmpty() && afterRep.fieldGroups().isEmpty()) {
			return Optional.empty();
		}
		var beforeGroupByField = indexMatchedFieldPeers(beforeRep.fieldGroups(), matchedKeys);
		var afterGroupByField = indexMatchedFieldPeers(afterRep.fieldGroups(), matchedKeys);

		for (var entry : afterGroupByField.entrySet()) {
			var fieldKey = entry.getKey();
			var afterPeerInfo = entry.getValue();
			var beforePeerInfo = beforeGroupByField.get(fieldKey);
			if (beforePeerInfo != null && !beforePeerInfo.matchedPeers().equals(afterPeerInfo.matchedPeers())) {
				return Optional.of(new UnattributedEvidence(
						"Multi-field declaration grouping changed across matched fields: " + fieldKey.name(),
						groundedDiagnostic(SideRole.AFTER, after.snapshot(), after.path(),
								DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE, Severity.INFO,
								"Field declaration grouping split or merged across statements for " + fieldKey.name(),
								afterPeerInfo.group().provenance())));
			}
		}
		return Optional.empty();
	}

	private static Map<SymbolKey, FieldPeerInfo> indexMatchedFieldPeers(List<FieldDeclarationGroup> groups,
			Set<SymbolKey> matchedKeys) {
		var map = new LinkedHashMap<SymbolKey, FieldPeerInfo>();
		for (var group : groups) {
			var matchedInGroup = new HashSet<SymbolKey>();
			for (var key : group.fieldKeys()) {
				if (matchedKeys.contains(key)) {
					matchedInGroup.add(key);
				}
			}
			var immutablePeers = Set.copyOf(matchedInGroup);
			for (var key : matchedInGroup) {
				map.put(key, new FieldPeerInfo(immutablePeers, group));
			}
		}
		return map;
	}

	private static Set<RawChangeKind> computeRawChanges(String beforeSource, RawSourceFacts beforeFacts,
			String afterSource, RawSourceFacts afterFacts, List<String> reasons) {
		if (beforeFacts.hasSameRawContent(afterFacts) && beforeSource.equals(afterSource)) {
			return Set.of();
		}
		var changes = EnumSet.noneOf(RawChangeKind.class);
		if (!beforeFacts.hasSameWhitespace(afterFacts)) {
			changes.add(RawChangeKind.WHITESPACE);
			reasons.add("Whitespace or formatting tokens changed");
		}
		if (!beforeFacts.hasSameComments(afterFacts)) {
			changes.add(RawChangeKind.COMMENT);
			reasons.add("Source comments changed");
		}
		return changes;
	}

	private static DiffDiagnostic groundedDiagnostic(SideRole role, SnapshotRef snapshot, String path,
			DiagnosticCode code, Severity severity, String message, ProvenanceRef provenance) {
		if (provenance.reference().isPresent()) {
			return new DiffDiagnostic(role, code, severity, message, Optional.of(snapshot),
					Optional.of(path), Optional.of(provenance.reference().orElseThrow().range()), Optional.empty());
		}
		return new DiffDiagnostic(role, code, severity, message, Optional.of(snapshot),
				Optional.of(path), Optional.empty(), provenance.unavailableReason());
	}

	private SideEvaluation evaluateComparisonSide(Side side, SideRole role, boolean isJava,
			List<DiffDiagnostic> diagnostics, List<String> coverageGaps) {
		String path = side.path();
		if (!isJava) {
			collectNonJavaSideDiagnostics(side, role, diagnostics);
			boolean unavailable = side instanceof Unavailable;
			if (unavailable) {
				coverageGaps.add(role + " snapshot unavailable for " + path);
			}
			return new SideEvaluation(false, false, unavailable, Optional.empty());
		}

		if (side instanceof Absent) {
			return new SideEvaluation(false, false, false, Optional.empty());
		}

		if (side instanceof Unavailable unavailable) {
			diagnostics.add(new DiffDiagnostic(role, DiagnosticCode.SNAPSHOT_UNAVAILABLE, Severity.ERROR,
					unavailable.diagnostic(), Optional.of(unavailable.snapshot()), Optional.of(path),
					Optional.empty(), Optional.empty()));
			coverageGaps.add(role + " snapshot unavailable for " + path + ": " + unavailable.diagnostic());
			return new SideEvaluation(false, false, true, Optional.empty());
		}

		var present = (Present) side;
		var outcome = parseGateway.apply(present.source());
		var parseResult = outcome.parseResult();
		if (parseResult.status() == Status.FAILED) {
			diagnostics.addAll(mapParseDiagnostics(role, present.snapshot(), path, parseResult,
					DiagnosticCode.PARSE_FAILED));
			coverageGaps.add(role + " Java parse failed for " + path);
			return new SideEvaluation(true, false, false, Optional.empty());
		}
		if (parseResult.status() == Status.PARTIAL) {
			diagnostics.addAll(mapParseDiagnostics(role, present.snapshot(), path, parseResult,
					DiagnosticCode.PARTIAL_PARSE_COVERAGE));
			coverageGaps.addAll(extractCoverageGaps(role, path, parseResult));
			return new SideEvaluation(false, true, false, Optional.empty());
		}
		return new SideEvaluation(false, false, false, outcome.representation());
	}

	private void collectNonJavaSideDiagnostics(Side side, SideRole role, List<DiffDiagnostic> diagnostics) {
		String path = side.path();
		if (side instanceof Present present) {
			var outcome = parseGateway.apply(present.source());
			diagnostics.addAll(mapParseDiagnostics(role, present.snapshot(), path, outcome.parseResult(),
					DiagnosticCode.UNSUPPORTED_LANGUAGE));
			return;
		}
		diagnostics.add(new DiffDiagnostic(role, DiagnosticCode.UNSUPPORTED_LANGUAGE, Severity.INFO,
				"Expected a .java file", Optional.of(side.snapshot()), Optional.of(path),
				Optional.empty(), Optional.empty()));
		if (side instanceof Unavailable unavailable) {
			diagnostics.add(new DiffDiagnostic(role, DiagnosticCode.SNAPSHOT_UNAVAILABLE, Severity.ERROR,
					unavailable.diagnostic(), Optional.of(unavailable.snapshot()), Optional.of(path),
					Optional.empty(), Optional.empty()));
		}
	}

	private static List<DiffDiagnostic> validateInput(StructuralDiffInput input) {
		var diagnostics = new ArrayList<DiffDiagnostic>();
		if (input == null) {
			diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.empty(),
					"Comparison input is required"));
			return diagnostics;
		}
		if (isBlank(input.repository())) {
			diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.empty(),
					"Repository binding is required"));
		}
		if (input.intent() == null) {
			diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.empty(),
					"Comparison intent is required"));
		}
		if (input.before() == null) {
			diagnostics.add(invalidInput(SideRole.BEFORE, Optional.empty(), Optional.empty(),
					"Before snapshot holder cannot be null"));
		}
		if (input.after() == null) {
			diagnostics.add(invalidInput(SideRole.AFTER, Optional.empty(), Optional.empty(),
					"After snapshot is required"));
		}
		if (input.association() == null) {
			diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.empty(),
					"File association holder cannot be null"));
		}
		if (!diagnostics.isEmpty() && (input.intent() == null || input.before() == null
				|| input.after() == null || input.association() == null)) {
			return diagnostics;
		}

		String expectedRepo = input.repository();
		if (input.intent() == Intent.INITIAL_ASSESSMENT) {
			if (input.before().isPresent()) {
				diagnostics.add(invalidInput(SideRole.BEFORE, safeSnapshot(input.before().orElseThrow()),
						safePath(input.before().orElseThrow()),
						"Initial assessment must not include a before snapshot"));
			}
			if (input.association().isPresent()) {
				diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.empty(),
						"Initial assessment must not include a file association"));
			}
			validateSide(input.after(), SideRole.TARGET, expectedRepo, diagnostics);
			if (!(input.after() instanceof Present)) {
				diagnostics.add(invalidInput(SideRole.TARGET, safeSnapshot(input.after()),
						safePath(input.after()),
						"Initial assessment requires a PRESENT target snapshot"));
			}
			return diagnostics;
		}

		if (input.before().isEmpty()) {
			diagnostics.add(invalidInput(SideRole.BEFORE, Optional.empty(), Optional.empty(),
					"Revision comparison requires a before snapshot"));
			validateSide(input.after(), SideRole.AFTER, expectedRepo, diagnostics);
			return diagnostics;
		}

		var before = input.before().orElseThrow();
		var after = input.after();
		validateSide(before, SideRole.BEFORE, expectedRepo, diagnostics);
		validateSide(after, SideRole.AFTER, expectedRepo, diagnostics);

		if (before instanceof Absent && after instanceof Absent) {
			diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.empty(),
					"Revision comparison cannot have both sides verified absent"));
		}

		String beforePath = before.path();
		String afterPath = after.path();
		boolean validBeforePath = isValidRepositoryRelativePath(beforePath);
		boolean validAfterPath = isValidRepositoryRelativePath(afterPath);

		if (input.association().isPresent()) {
			var association = input.association().orElseThrow();
			boolean validAssocBefore = isValidRepositoryRelativePath(association.beforePath());
			boolean validAssocAfter = isValidRepositoryRelativePath(association.afterPath());
			if (!validAssocBefore || !validAssocAfter) {
				diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.empty(),
						"File association requires valid repository-relative before and after paths"));
			} else if (validBeforePath && validAfterPath
					&& (!association.beforePath().equals(beforePath)
							|| !association.afterPath().equals(afterPath))) {
				diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.empty(),
						"File association paths must match before and after snapshot paths"));
			}
		} else if (validBeforePath && validAfterPath && !beforePath.equals(afterPath)) {
			diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.empty(),
					"Different before and after paths require an explicit file association"));
		}

		if (diagnostics.isEmpty()
				&& before.snapshot().repository().equals(after.snapshot().repository())
				&& before.snapshot().sha().equals(after.snapshot().sha())
				&& beforePath.equals(afterPath)) {
			if (before instanceof Present beforePresent && after instanceof Present afterPresent) {
				if (!Objects.equals(beforePresent.source().sourceContent(), afterPresent.source().sourceContent())) {
					diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.of(beforePath),
							"Contradictory PRESENT contents for identical repository, SHA, and path"));
				}
			} else if ((before instanceof Present && after instanceof Absent)
					|| (before instanceof Absent && after instanceof Present)) {
				diagnostics.add(invalidInput(SideRole.INPUT, Optional.empty(), Optional.of(beforePath),
						"Contradictory PRESENT and verified ABSENT states for identical repository, SHA, and path"));
			}
		}
		return diagnostics;
	}

	private static void validateSide(Side side, SideRole role, String expectedRepo,
			List<DiffDiagnostic> diagnostics) {
		var snapshot = safeSnapshot(side);
		var path = safePath(side);
		if (side.snapshot() == null) {
			diagnostics.add(invalidInput(role, Optional.empty(), path, "Snapshot reference is required"));
		} else {
			if (isBlank(side.snapshot().repository())) {
				diagnostics.add(invalidInput(role, Optional.empty(), path, "Snapshot repository is required"));
			} else if (!isBlank(expectedRepo) && !side.snapshot().repository().equals(expectedRepo)) {
				diagnostics.add(invalidInput(role, snapshot, path,
						"Snapshot repository does not match input repository binding"));
			}
			if (isBlank(side.snapshot().sha())) {
				diagnostics.add(invalidInput(role, Optional.empty(), path, "Pinned snapshot SHA is required"));
			}
		}

		if (side instanceof Present present) {
			if (present.source() == null || !isValidRepositoryRelativePath(present.source().filePath())) {
				diagnostics.add(invalidInput(role, snapshot, path,
						"A valid repository-relative source file path is required"));
			} else if (isJavaPath(present.source().filePath()) && present.source().sourceContent() == null) {
				diagnostics.add(invalidInput(role, snapshot, Optional.of(present.source().filePath()),
						"Java source content is required"));
			}
		} else if (side instanceof Absent absent) {
			if (!isValidRepositoryRelativePath(absent.path())) {
				diagnostics.add(invalidInput(role, snapshot, path,
						"A valid repository-relative absent snapshot path is required"));
			}
			if (isBlank(absent.reason())) {
				diagnostics.add(invalidInput(role, snapshot, path, "Verified absence reason is required"));
			}
		} else if (side instanceof Unavailable unavailable) {
			if (!isValidRepositoryRelativePath(unavailable.path())) {
				diagnostics.add(invalidInput(role, snapshot, path,
						"A valid repository-relative unavailable snapshot path is required"));
			}
			if (isBlank(unavailable.diagnostic())) {
				diagnostics.add(invalidInput(role, snapshot, path, "Unavailable snapshot diagnostic is required"));
			}
		}
	}

	static boolean isValidRepositoryRelativePath(String path) {
		if (path == null || path.isBlank()) {
			return false;
		}
		if (path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0) {
			return false;
		}
		if (path.length() >= 2 && isAsciiLetter(path.charAt(0)) && path.charAt(1) == ':') {
			return false;
		}
		for (int i = 0; i < path.length(); i++) {
			if (Character.isISOControl(path.charAt(i))) {
				return false;
			}
		}
		String[] segments = path.split("/", -1);
		for (String segment : segments) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
				return false;
			}
		}
		return true;
	}

	private static boolean isAsciiLetter(char ch) {
		return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
	}

	private static SnapshotProvenance toSnapshotProvenance(Side side) {
		if (side instanceof Present present) {
			return new SnapshotProvenance(present.snapshot(), present.path(), SidePresence.PRESENT,
					Optional.empty(), Optional.empty());
		}
		if (side instanceof Absent absent) {
			return new SnapshotProvenance(absent.snapshot(), absent.path(), SidePresence.ABSENT,
					Optional.of(absent.reason()), Optional.empty());
		}
		var unavailable = (Unavailable) side;
		return new SnapshotProvenance(unavailable.snapshot(), unavailable.path(), SidePresence.UNAVAILABLE,
				Optional.empty(), Optional.of(unavailable.diagnostic()));
	}

	private static TargetInventory buildTargetInventory(SnapshotRef snapshot,
			JavaStructuralRepresentation representation) {
		var entities = representation.entities().stream()
				.map(entity -> toEntitySide(snapshot, representation.filePath(), entity))
				.toList();
		return new TargetInventory(
				snapshot,
				representation.filePath(),
				entities,
				representation.fileContext(),
				representation.fieldGroups(),
				representation.initializerBlocks(),
				representation.residualRegions(),
				representation.topLevelLayout(),
				representation.typeLayouts(),
				representation.wholeFileFingerprint());
	}

	private static SideContextFacts toSideContextFacts(SnapshotRef snapshot,
			JavaStructuralRepresentation representation) {
		return new SideContextFacts(
				snapshot,
				representation.filePath(),
				representation.fileContext(),
				representation.fieldGroups(),
				representation.initializerBlocks(),
				representation.residualRegions(),
				representation.topLevelLayout(),
				representation.typeLayouts(),
				representation.wholeFileFingerprint(),
				representation.rawFacts());
	}

	static EntitySide toEntitySide(SnapshotRef snapshot, String path, EntityProjection projection) {
		return new EntitySide(
				snapshot,
				path,
				projection.key(),
				projection.declarationRef(),
				projection.headerRef(),
				projection.sharedDeclarationRef(),
				projection.sharedPrefixRef(),
				projection.bodyRef(),
				projection.initializerRef(),
				projection.annotationDefaultRef(),
				projection.facetReferences(),
				projection.facets());
	}

	private static List<DiffDiagnostic> mapParseDiagnostics(SideRole role, SnapshotRef snapshot, String path,
			JavaParseResult parseResult, DiagnosticCode fallbackCode) {
		if (parseResult.diagnostics().isEmpty()) {
			return List.of(new DiffDiagnostic(role, fallbackCode, Severity.ERROR,
					"Parser reported " + parseResult.status(), Optional.of(snapshot), Optional.of(path),
					Optional.empty(), Optional.empty()));
		}
		return parseResult.diagnostics().stream().map(diag -> {
			DiagnosticCode mapped = switch (diag.code()) {
				case INVALID_INPUT -> DiagnosticCode.INVALID_INPUT;
				case UNSUPPORTED_LANGUAGE -> DiagnosticCode.UNSUPPORTED_LANGUAGE;
				default -> fallbackCode;
			};
			return new DiffDiagnostic(role, mapped, diag.severity(), diag.message(),
					Optional.of(snapshot), Optional.of(path), diag.range(), Optional.empty());
		}).toList();
	}

	private static List<String> extractCoverageGaps(SideRole role, String path, JavaParseResult parseResult) {
		if (parseResult.coverage().isEmpty()) {
			return List.of(role + " partial parse coverage for " + path);
		}
		return parseResult.coverage().orElseThrow().gaps().stream()
				.map(gap -> role + " (" + path + "): " + gap.reason())
				.toList();
	}

	private static FileComparison unresolvedFileComparison(Optional<String> beforePath, Optional<String> afterPath,
			List<String> reasons) {
		return new FileComparison(PresenceRelation.UNRESOLVED, PathRelation.UNRESOLVED,
				beforePath, afterPath, StructuralEquality.UNKNOWN, Set.of(), Set.of(), false, reasons);
	}

	private static Optional<SnapshotRef> safeSnapshot(Side side) {
		if (side == null || side.snapshot() == null
				|| isBlank(side.snapshot().repository()) || isBlank(side.snapshot().sha())) {
			return Optional.empty();
		}
		return Optional.of(side.snapshot());
	}

	private static Optional<String> safePath(Side side) {
		if (side == null || isBlank(side.path())) {
			return Optional.empty();
		}
		return Optional.of(side.path());
	}

	private static DiffDiagnostic invalidInput(SideRole role, Optional<SnapshotRef> snapshot,
			Optional<String> path, String message) {
		Optional<SnapshotRef> effectiveSnapshot = role == SideRole.INPUT ? Optional.empty() : snapshot;
		return new DiffDiagnostic(role, DiagnosticCode.INVALID_INPUT, Severity.ERROR, message,
				effectiveSnapshot, path, Optional.empty(), Optional.empty());
	}

	private static boolean isJavaPath(String path) {
		return path != null && path.endsWith(".java");
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private record SideEvaluation(boolean parseFailed, boolean partial, boolean unavailable,
			Optional<JavaStructuralRepresentation> representation) {
	}

	private record ResidualAnchor(int residualIndex, String anchor) {
	}

	private record FieldPeerInfo(Set<SymbolKey> matchedPeers, FieldDeclarationGroup group) {
	}

	private record UnattributedEvidence(String reason, DiffDiagnostic diagnostic) {
	}

	public enum ProcessingStatus {
		COMPLETE, INCOMPLETE, FAILED, OUT_OF_SCOPE
	}

	public enum SidePresence {
		PRESENT, ABSENT, UNAVAILABLE
	}

	public enum DiagnosticCode {
		INVALID_INPUT,
		UNSUPPORTED_LANGUAGE,
		MIXED_LANGUAGE_SCOPE,
		PARSE_FAILED,
		SNAPSHOT_UNAVAILABLE,
		PARTIAL_PARSE_COVERAGE,
		RESIDUAL_STRUCTURAL_CHANGE
	}

	public enum SideRole {
		BEFORE, AFTER, TARGET, INPUT
	}

	public enum ChangeKind {
		ADDED, REMOVED, MODIFIED, UNCHANGED
	}

	public enum MatchBasis {
		EXACT_SYMBOL_KEY, UNMATCHED_BEFORE, UNMATCHED_AFTER
	}

	public enum UnmatchedReason {
		FILE_ADDED, FILE_REMOVED, NO_COUNTERPART_KEY
	}

	public enum PresenceRelation {
		BOTH_PRESENT, ADDED, REMOVED, INITIAL_TARGET, UNRESOLVED
	}

	public enum PathRelation {
		SAME_PATH, PATH_CHANGED, SINGLE_SIDE, UNRESOLVED
	}

	public enum StructuralEquality {
		EQUAL, DIFFERENT, UNKNOWN
	}

	public enum RawChangeKind {
		WHITESPACE, COMMENT
	}

	public enum ContextChangeKind {
		PACKAGE_CHANGED,
		IMPORTS_CHANGED,
		MODULE_CHANGED,
		INITIALIZER_BLOCK_CHANGED,
		SCOPE_ORDER_CHANGED,
		RESIDUAL_STRUCTURAL_CHANGE
	}

	public record SnapshotProvenance(
			SnapshotRef snapshot,
			String path,
			SidePresence presence,
			Optional<String> absenceReason,
			Optional<String> unavailableDiagnostic) {

		public SnapshotProvenance {
			Objects.requireNonNull(snapshot, "snapshot");
			if (isBlank(snapshot.repository()) || isBlank(snapshot.sha())) {
				throw new IllegalArgumentException("SnapshotProvenance requires non-blank repository and SHA");
			}
			if (!isValidRepositoryRelativePath(path)) {
				throw new IllegalArgumentException("SnapshotProvenance requires a valid repository-relative path");
			}
			Objects.requireNonNull(presence, "presence");
			Objects.requireNonNull(absenceReason, "absenceReason");
			Objects.requireNonNull(unavailableDiagnostic, "unavailableDiagnostic");
			switch (presence) {
				case PRESENT -> {
					if (absenceReason.isPresent() || unavailableDiagnostic.isPresent()) {
						throw new IllegalArgumentException(
								"PRESENT snapshot provenance cannot carry absence or unavailable reason");
					}
				}
				case ABSENT -> {
					if (absenceReason.isEmpty() || absenceReason.orElseThrow().isBlank()
							|| unavailableDiagnostic.isPresent()) {
						throw new IllegalArgumentException(
								"ABSENT snapshot provenance requires a verified absence reason");
					}
				}
				case UNAVAILABLE -> {
					if (unavailableDiagnostic.isEmpty() || unavailableDiagnostic.orElseThrow().isBlank()
							|| absenceReason.isPresent()) {
						throw new IllegalArgumentException(
								"UNAVAILABLE snapshot provenance requires an unavailable diagnostic");
					}
				}
			}
		}
	}

	public record DiffDiagnostic(
			SideRole side,
			DiagnosticCode code,
			Severity severity,
			String message,
			Optional<SnapshotRef> snapshot,
			Optional<String> path,
			Optional<SourceRange> range,
			Optional<String> rangeUnavailableReason) {

		public DiffDiagnostic {
			Objects.requireNonNull(side, "side");
			Objects.requireNonNull(code, "code");
			Objects.requireNonNull(severity, "severity");
			if (Objects.requireNonNull(message, "message").isBlank()) {
				throw new IllegalArgumentException("Diagnostic message cannot be blank");
			}
			Objects.requireNonNull(snapshot, "snapshot");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(range, "range");
			Objects.requireNonNull(rangeUnavailableReason, "rangeUnavailableReason");
			if (side == SideRole.INPUT && snapshot.isPresent()) {
				throw new IllegalArgumentException("INPUT-scoped diagnostic cannot attach a single side snapshot");
			}
			if (range.isPresent() && rangeUnavailableReason.isPresent()) {
				throw new IllegalArgumentException(
						"Diagnostic cannot carry both an exact range and a range-unavailable reason");
			}
			if (rangeUnavailableReason.isPresent() && rangeUnavailableReason.orElseThrow().isBlank()) {
				throw new IllegalArgumentException("Range-unavailable reason cannot be blank");
			}
		}
	}

	public record EntitySide(
			SnapshotRef snapshot,
			String path,
			SymbolKey key,
			ProvenanceRef declarationRef,
			ProvenanceRef headerRef,
			Optional<ProvenanceRef> sharedDeclarationRef,
			Optional<ProvenanceRef> sharedPrefixRef,
			Optional<ProvenanceRef> bodyRef,
			Optional<ProvenanceRef> initializerRef,
			Optional<ProvenanceRef> annotationDefaultRef,
			Map<FacetKind, List<ProvenanceRef>> facetReferences,
			Map<FacetKind, Fingerprint> facetFingerprints) {

		public EntitySide {
			Objects.requireNonNull(snapshot, "snapshot");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(declarationRef, "declarationRef");
			Objects.requireNonNull(headerRef, "headerRef");
			Objects.requireNonNull(sharedDeclarationRef, "sharedDeclarationRef");
			Objects.requireNonNull(sharedPrefixRef, "sharedPrefixRef");
			Objects.requireNonNull(bodyRef, "bodyRef");
			Objects.requireNonNull(initializerRef, "initializerRef");
			Objects.requireNonNull(annotationDefaultRef, "annotationDefaultRef");
			facetReferences = copyFacetRefsMap(facetReferences);
			facetFingerprints = copyFacetMap(facetFingerprints);
			if (!facetReferences.keySet().equals(facetFingerprints.keySet())) {
				throw new IllegalArgumentException("EntitySide facetReferences and facetFingerprints keys must match");
			}
		}
	}

	public record EntityFact(
			ChangeKind change,
			Optional<EntitySide> before,
			Optional<EntitySide> after,
			Set<FacetKind> changedFacets,
			MatchBasis matchBasis,
			Optional<UnmatchedReason> unmatchedReason,
			String normalizationVersion) {

		public EntityFact {
			Objects.requireNonNull(change, "change");
			Objects.requireNonNull(before, "before");
			Objects.requireNonNull(after, "after");
			changedFacets = copyEnumSet(changedFacets, FacetKind.class);
			Objects.requireNonNull(matchBasis, "matchBasis");
			Objects.requireNonNull(unmatchedReason, "unmatchedReason");
			if (Objects.requireNonNull(normalizationVersion, "normalizationVersion").isBlank()) {
				throw new IllegalArgumentException("Normalization version cannot be blank");
			}
			switch (change) {
				case ADDED -> {
					if (before.isPresent() || after.isEmpty() || !changedFacets.isEmpty()
							|| matchBasis != MatchBasis.UNMATCHED_AFTER || unmatchedReason.isEmpty()
							|| unmatchedReason.orElseThrow() == UnmatchedReason.FILE_REMOVED) {
						throw new IllegalArgumentException(
								"ADDED fact requires only after side, UNMATCHED_AFTER, and compatible unmatched reason");
					}
				}
				case REMOVED -> {
					if (before.isEmpty() || after.isPresent() || !changedFacets.isEmpty()
							|| matchBasis != MatchBasis.UNMATCHED_BEFORE || unmatchedReason.isEmpty()
							|| unmatchedReason.orElseThrow() == UnmatchedReason.FILE_ADDED) {
						throw new IllegalArgumentException(
								"REMOVED fact requires only before side, UNMATCHED_BEFORE, and compatible unmatched reason");
					}
				}
				case MODIFIED -> {
					if (before.isEmpty() || after.isEmpty() || changedFacets.isEmpty()
							|| matchBasis != MatchBasis.EXACT_SYMBOL_KEY || unmatchedReason.isPresent()
							|| !before.orElseThrow().key().equals(after.orElseThrow().key())) {
						throw new IllegalArgumentException(
								"MODIFIED fact requires both sides with equal SymbolKey, changed facets, and EXACT_SYMBOL_KEY");
					}
				}
				case UNCHANGED -> {
					if (before.isEmpty() || after.isEmpty() || !changedFacets.isEmpty()
							|| matchBasis != MatchBasis.EXACT_SYMBOL_KEY || unmatchedReason.isPresent()
							|| !before.orElseThrow().key().equals(after.orElseThrow().key())) {
						throw new IllegalArgumentException(
								"UNCHANGED fact requires both sides with equal SymbolKey, no changed facets, and EXACT_SYMBOL_KEY");
					}
				}
			}
		}
	}

	public record SideContextFacts(
			SnapshotRef snapshot,
			String path,
			FileContextProjection fileContext,
			List<FieldDeclarationGroup> fieldGroups,
			List<InitializerBlockProjection> initializerBlocks,
			List<ResidualRegion> residualRegions,
			ScopeLayout topLevelLayout,
			Map<SymbolKey, ScopeLayout> typeLayouts,
			Fingerprint wholeFileFingerprint,
			RawSourceFacts rawFacts) {

		public SideContextFacts {
			Objects.requireNonNull(snapshot, "snapshot");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(fileContext, "fileContext");
			fieldGroups = List.copyOf(fieldGroups);
			initializerBlocks = List.copyOf(initializerBlocks);
			residualRegions = List.copyOf(residualRegions);
			Objects.requireNonNull(topLevelLayout, "topLevelLayout");
			typeLayouts = copyLayoutMap(typeLayouts);
			Objects.requireNonNull(wholeFileFingerprint, "wholeFileFingerprint");
			Objects.requireNonNull(rawFacts, "rawFacts");
		}
	}

	public record FileComparison(
			PresenceRelation presence,
			PathRelation pathRelation,
			Optional<String> beforePath,
			Optional<String> afterPath,
			StructuralEquality structuralEquality,
			Set<ContextChangeKind> contextChanges,
			Set<RawChangeKind> rawChanges,
			boolean rawOnlyChange,
			List<String> reasons) {

		public FileComparison {
			Objects.requireNonNull(presence, "presence");
			Objects.requireNonNull(pathRelation, "pathRelation");
			Objects.requireNonNull(beforePath, "beforePath");
			Objects.requireNonNull(afterPath, "afterPath");
			Objects.requireNonNull(structuralEquality, "structuralEquality");
			contextChanges = copyEnumSet(contextChanges, ContextChangeKind.class);
			rawChanges = copyEnumSet(rawChanges, RawChangeKind.class);
			reasons = List.copyOf(reasons);
			if (rawOnlyChange && (structuralEquality != StructuralEquality.EQUAL || rawChanges.isEmpty())) {
				throw new IllegalArgumentException("rawOnlyChange requires structural equality and non-empty rawChanges");
			}
		}
	}

	public record TargetInventory(
			SnapshotRef snapshot,
			String path,
			List<EntitySide> entities,
			FileContextProjection fileContext,
			List<FieldDeclarationGroup> fieldGroups,
			List<InitializerBlockProjection> initializerBlocks,
			List<ResidualRegion> residualRegions,
			ScopeLayout topLevelLayout,
			Map<SymbolKey, ScopeLayout> typeLayouts,
			Fingerprint wholeFileFingerprint) {

		public TargetInventory {
			Objects.requireNonNull(snapshot, "snapshot");
			Objects.requireNonNull(path, "path");
			entities = List.copyOf(entities);
			Objects.requireNonNull(fileContext, "fileContext");
			fieldGroups = List.copyOf(fieldGroups);
			initializerBlocks = List.copyOf(initializerBlocks);
			residualRegions = List.copyOf(residualRegions);
			Objects.requireNonNull(topLevelLayout, "topLevelLayout");
			typeLayouts = copyLayoutMap(typeLayouts);
			Objects.requireNonNull(wholeFileFingerprint, "wholeFileFingerprint");
		}
	}

	public record CoverageSummary(boolean fullCoverage, List<String> gaps) {
		public CoverageSummary {
			gaps = List.copyOf(gaps);
			if (fullCoverage && !gaps.isEmpty()) {
				throw new IllegalArgumentException("Full coverage cannot carry coverage gaps");
			}
			if (!fullCoverage && gaps.isEmpty()) {
				throw new IllegalArgumentException("Incomplete coverage requires at least one explicit gap");
			}
		}
	}

	public record StructuralDiffResult(
			ProcessingStatus status,
			Intent intent,
			Optional<String> repository,
			String normalizationVersion,
			String parserVersion,
			String languagePolicy,
			Optional<SnapshotProvenance> beforeSnapshot,
			Optional<SnapshotProvenance> afterSnapshot,
			List<EntityFact> entityFacts,
			Optional<TargetInventory> initialInventory,
			Optional<SideContextFacts> beforeContext,
			Optional<SideContextFacts> afterContext,
			FileComparison fileComparison,
			CoverageSummary coverage,
			List<DiffDiagnostic> diagnostics) {

		public StructuralDiffResult {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(intent, "intent");
			Objects.requireNonNull(repository, "repository");
			if (Objects.requireNonNull(normalizationVersion, "normalizationVersion").isBlank()) {
				throw new IllegalArgumentException("Normalization version cannot be blank");
			}
			if (Objects.requireNonNull(parserVersion, "parserVersion").isBlank()) {
				throw new IllegalArgumentException("Parser version cannot be blank");
			}
			if (Objects.requireNonNull(languagePolicy, "languagePolicy").isBlank()) {
				throw new IllegalArgumentException("Language policy cannot be blank");
			}
			Objects.requireNonNull(beforeSnapshot, "beforeSnapshot");
			Objects.requireNonNull(afterSnapshot, "afterSnapshot");
			entityFacts = List.copyOf(entityFacts);
			Objects.requireNonNull(initialInventory, "initialInventory");
			Objects.requireNonNull(beforeContext, "beforeContext");
			Objects.requireNonNull(afterContext, "afterContext");
			Objects.requireNonNull(fileComparison, "fileComparison");
			Objects.requireNonNull(coverage, "coverage");
			diagnostics = List.copyOf(diagnostics);

			if ((status == ProcessingStatus.COMPLETE) != coverage.fullCoverage()) {
				throw new IllegalArgumentException("COMPLETE status and fullCoverage must be consistent");
			}
			if (status != ProcessingStatus.COMPLETE && diagnostics.isEmpty()) {
				throw new IllegalArgumentException("Non-complete results require diagnostics");
			}
			if (status == ProcessingStatus.COMPLETE
					&& diagnostics.stream().anyMatch(diag -> diag.severity() == Severity.ERROR)) {
				throw new IllegalArgumentException("COMPLETE result cannot contain error diagnostics");
			}
			if (intent != Intent.INITIAL_ASSESSMENT && initialInventory.isPresent()) {
				throw new IllegalArgumentException("Initial inventory is only valid for INITIAL_ASSESSMENT");
			}
			if (intent == Intent.INITIAL_ASSESSMENT && !entityFacts.isEmpty()) {
				throw new IllegalArgumentException("INITIAL_ASSESSMENT cannot emit revision diff entityFacts");
			}
			if (intent == Intent.INITIAL_ASSESSMENT && status == ProcessingStatus.COMPLETE
					&& initialInventory.isEmpty()) {
				throw new IllegalArgumentException("COMPLETE initial assessment requires target initialInventory");
			}
			if ((status == ProcessingStatus.FAILED || status == ProcessingStatus.OUT_OF_SCOPE)
					&& initialInventory.isPresent()) {
				throw new IllegalArgumentException(
						"FAILED or OUT_OF_SCOPE initial assessment cannot expose unverified initialInventory");
			}
		}
	}

	private static Map<FacetKind, Fingerprint> copyFacetMap(Map<FacetKind, Fingerprint> source) {
		Objects.requireNonNull(source, "facetFingerprints");
		if (source.isEmpty()) {
			return Map.of();
		}
		var copy = new EnumMap<FacetKind, Fingerprint>(FacetKind.class);
		copy.putAll(source);
		return Collections.unmodifiableMap(copy);
	}

	private static Map<FacetKind, List<ProvenanceRef>> copyFacetRefsMap(
			Map<FacetKind, List<ProvenanceRef>> source) {
		Objects.requireNonNull(source, "facetReferences");
		if (source.isEmpty()) {
			return Map.of();
		}
		var copy = new EnumMap<FacetKind, List<ProvenanceRef>>(FacetKind.class);
		for (var entry : source.entrySet()) {
			var kind = Objects.requireNonNull(entry.getKey(), "facetKind");
			var refs = List.copyOf(Objects.requireNonNull(entry.getValue(), "provenanceRefs"));
			if (refs.isEmpty()) {
				throw new IllegalArgumentException("Facet reference list cannot be empty for " + kind);
			}
			copy.put(kind, refs);
		}
		return Collections.unmodifiableMap(copy);
	}

	private static <E extends Enum<E>> Set<E> copyEnumSet(Set<E> source, Class<E> enumType) {
		Objects.requireNonNull(source, "enumSet");
		if (source.isEmpty()) {
			return Set.of();
		}
		var copy = EnumSet.noneOf(enumType);
		copy.addAll(source);
		return Collections.unmodifiableSet(copy);
	}

	private static Map<SymbolKey, ScopeLayout> copyLayoutMap(Map<SymbolKey, ScopeLayout> source) {
		Objects.requireNonNull(source, "typeLayouts");
		if (source.isEmpty()) {
			return Map.of();
		}
		var copy = new LinkedHashMap<>(source);
		return Collections.unmodifiableMap(copy);
	}
}
