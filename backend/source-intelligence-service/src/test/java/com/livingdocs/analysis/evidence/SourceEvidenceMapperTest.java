package com.livingdocs.analysis.evidence;

import static com.livingdocs.analysis.impact.ImpactClassifier.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.livingdocs.analysis.ast.JavaParseResult.Kind;
import com.livingdocs.analysis.ast.JavaParseResult.SourceRange;
import com.livingdocs.analysis.ast.JavaParseResult.SourceReference;
import com.livingdocs.analysis.ast.JavaParseResult.SymbolKey;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FacetKind;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.ProvenanceRef;
import com.livingdocs.analysis.ast.SourceInput;
import com.livingdocs.analysis.diff.StructuralDiff;
import com.livingdocs.analysis.diff.StructuralDiff.ChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.ContextChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.DiagnosticCode;
import com.livingdocs.analysis.diff.StructuralDiff.DiffDiagnostic;
import com.livingdocs.analysis.diff.StructuralDiff.EntityFact;
import com.livingdocs.analysis.diff.StructuralDiff.EntitySide;
import com.livingdocs.analysis.diff.StructuralDiff.ProcessingStatus;
import com.livingdocs.analysis.diff.StructuralDiff.SidePresence;
import com.livingdocs.analysis.diff.StructuralDiff.SideRole;
import com.livingdocs.analysis.diff.StructuralDiff.SnapshotProvenance;
import com.livingdocs.analysis.diff.StructuralDiff.StructuralDiffResult;
import com.livingdocs.analysis.diff.StructuralDiffInput;
import com.livingdocs.analysis.diff.StructuralDiffInput.Absent;
import com.livingdocs.analysis.diff.StructuralDiffInput.FileAssociation;
import com.livingdocs.analysis.diff.StructuralDiffInput.Intent;
import com.livingdocs.analysis.diff.StructuralDiffInput.Present;
import com.livingdocs.analysis.diff.StructuralDiffInput.SnapshotRef;
import com.livingdocs.analysis.diff.StructuralDiffInput.Unavailable;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.Coverage;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceInput;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceReason;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceResult;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceStatus;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.SnapshotProof;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.SourceBasis;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.SourceLocator;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.WitnessEvidence;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.WitnessStatus;
import com.livingdocs.analysis.impact.ImpactClassifier;

class SourceEvidenceMapperTest {

	private static final String TENANT = "tenant-1";
	private static final String WORKSPACE = "ws-1";
	private static final String REPO = "repo-payments";
	private static final String SHA_A = "sha-aaa";
	private static final String SHA_B = "sha-bbb";
	private static final String SHA_C = "sha-ccc";

	private final StructuralDiff diff = new StructuralDiff();
	private final ImpactClassifier classifier = new ImpactClassifier();
	private final SourceEvidenceMapper mapper = new SourceEvidenceMapper();

	@Test
	@DisplayName("M4-RV-01: Body facet grounding verifies exact M2 ranges and downgrades on content/SHA/role/path tamper")
	void rv01BodyFacetGroundingAndMutationDowngrade() {
		String beforeSrc = "class A { int value() { return 1; } }\n";
		String afterSrc = "class A { int value() { return 2; } }\n";
		String path = "src/A.java";

		var diffResult = compareJava(path, SHA_B, beforeSrc, path, SHA_C, afterSrc);
		assertEquals(ProcessingStatus.COMPLETE, diffResult.status());
		SymbolKey valueKey = changedSymbolKey(diffResult, "value");

		var context = branchContext(SHA_B, SHA_C, false);
		var claim = new DocumentClaim(
				"claim-value-body", "doc-a", "v1", SHA_A, SHA_C,
				"A.value() returns 1", path, Optional.of(valueKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN, "Document claims A.value() returns 1 instead of 2",
				Optional.of(new VerifiedImpactContext(Severity.MEDIUM, "Behavioral change", "api-core")));
		var binding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-a", "v1", SHA_A, "bind-a",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(valueKey), Optional.of("sec-1"), List.of(claim));
		var afterSymKey = symbolLookupKey(context, SideRole.AFTER, SHA_C, path, valueKey);
		var lookups = completeRevisionLookups(context, diffResult, afterSymKey, binding);

		var report = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffResult), lookups, List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), report.overallOutcome());

		var proofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, path, afterSrc));

		// Positive path: exact ranges match M2 BODY facetReferences.
		EvidenceResult verified = mapper.map(emptyInput(context, report, List.of(diffResult), proofs, lookups));
		assertEquals(EvidenceStatus.VERIFIED, verified.status());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), verified.overallOutcome());
		assertEquals(Optional.of(ReportProcessingStatus.COMPLETE), verified.processingStatus());
		assertEquals(1, verified.findings().size());
		var f = verified.findings().getFirst();
		assertEquals(EvidenceStatus.VERIFIED, f.status());
		assertEquals(ImpactOutcome.ACTIONABLE, f.outcome());

		List<SourceRange> expectedBeforeRanges = entitySide(diffResult, SideRole.BEFORE, valueKey)
				.facetReferences().get(FacetKind.BODY).stream().map(r -> r.reference().orElseThrow().range()).toList();
		List<SourceRange> expectedAfterRanges = entitySide(diffResult, SideRole.AFTER, valueKey)
				.facetReferences().get(FacetKind.BODY).stream().map(r -> r.reference().orElseThrow().range()).toList();

		List<SourceLocator> locators = f.witnesses().stream().flatMap(w -> w.sourceLocators().stream()).toList();
		assertTrue(locators.stream().anyMatch(l -> l.sideRole() == SideRole.BEFORE
				&& l.basis() == SourceBasis.FACET_PRESENT
				&& l.facetKind().equals(Optional.of(FacetKind.BODY))
				&& l.ranges().equals(expectedBeforeRanges)));
		assertTrue(locators.stream().anyMatch(l -> l.sideRole() == SideRole.AFTER
				&& l.basis() == SourceBasis.FACET_PRESENT
				&& l.facetKind().equals(Optional.of(FacetKind.BODY))
				&& l.ranges().equals(expectedAfterRanges)));

		// Mutation 1: alter content while keeping SHA/path -> CONTENT_MISMATCH, ranges stripped, downgraded.
		var tamperedContentProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, path, "class A { int value() { return 9; } }\n"));
		EvidenceResult contentMismatch = mapper.map(
				emptyInput(context, report, List.of(diffResult), tamperedContentProofs, lookups));
		assertEquals(EvidenceStatus.PARTIAL, contentMismatch.status());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), contentMismatch.overallOutcome());
		var mf = contentMismatch.findings().getFirst();
		assertEquals(ImpactOutcome.INCONCLUSIVE, mf.outcome());
		assertEquals(ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED, mf.primaryReason());
		assertTrue(mf.witnesses().stream().anyMatch(w -> w.status() == WitnessStatus.UNVERIFIED
				&& w.reasons().contains(EvidenceReason.CONTENT_MISMATCH)
				&& w.sourceLocators().stream().allMatch(l -> l.basis() == SourceBasis.FILE_IDENTITY && l.ranges().isEmpty())));

		// Mutation 2: wrong SHA/role/path on AFTER SnapshotProof -> MISSING_SNAPSHOT, downgraded.
		var wrongShaProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, "sha-wrong", path, afterSrc));
		EvidenceResult wrongShaResult = mapper.map(emptyInput(context, report, List.of(diffResult), wrongShaProofs, lookups));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), wrongShaResult.overallOutcome());

		var wrongRoleProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.TARGET, SHA_C, path, afterSrc));
		EvidenceResult wrongRoleResult = mapper.map(emptyInput(context, report, List.of(diffResult), wrongRoleProofs, lookups));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), wrongRoleResult.overallOutcome());

		var wrongPathProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, "src/Other.java", afterSrc));
		EvidenceResult wrongPathResult = mapper.map(emptyInput(context, report, List.of(diffResult), wrongPathProofs, lookups));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), wrongPathResult.overallOutcome());
	}

	@Test
	@DisplayName("M4-RV-02: File addition and removal verify FILE_ABSENT without ranges and reject missing/mismatched/unavailable absence")
	void rv02FileAddRemoveAbsenceProofAndTampering() {
		String src = "package com.example;\npublic class Service {\n    public void ping() {}\n}\n";
		String path = "src/Service.java";
		var context = branchContext(SHA_B, SHA_C, false);

		// 1. File addition (BEFORE ABSENT -> AFTER PRESENT). M3 emits only AFTER selector; M4 checks BEFORE ABSENT too!
		var addDiff = diff.compare(new StructuralDiffInput(
				REPO, Intent.REVISION_COMPARISON,
				Optional.of(new Absent(new SnapshotRef(REPO, SHA_B), path, "file-not-in-before")),
				new Present(new SnapshotRef(REPO, SHA_C), new SourceInput(path, src)),
				Optional.empty()));
		SymbolKey pingKey = changedSymbolKey(addDiff, "ping", ChangeKind.ADDED);
		var addClaim = new DocumentClaim(
				"claim-add", "doc-svc", "v1", SHA_A, SHA_C,
				"Service#ping() contract", path, Optional.of(pingKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Added ping signature differs",
				Optional.of(new VerifiedImpactContext(Severity.LOW, "Minor doc update", "svc")));
		var addBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-svc", "v1", SHA_A, "bind-add",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(pingKey), Optional.empty(), List.of(addClaim));
		var addLookups = completeRevisionLookups(
				context, addDiff, symbolLookupKey(context, SideRole.AFTER, SHA_C, path, pingKey), addBinding);
		var addReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(addDiff), addLookups, List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), addReport.overallOutcome());

		var addProofs = List.of(
				absentProof(SideRole.BEFORE, SHA_B, path, "file-not-in-before"),
				presentProof(SideRole.AFTER, SHA_C, path, src));
		String pingFindingId = addReport.findings().stream()
				.filter(f -> f.symbolKey().equals(Optional.of(pingKey))).findFirst().orElseThrow().findingId();
		EvidenceResult addVerified = mapper.map(emptyInput(context, addReport, List.of(addDiff), addProofs, addLookups));
		assertEquals(EvidenceStatus.VERIFIED, addVerified.status());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), addVerified.overallOutcome());
		List<SourceLocator> addLocs = addVerified.findings().stream()
				.filter(f -> f.findingId().equals(pingFindingId))
				.findFirst().orElseThrow()
				.witnesses().stream().flatMap(w -> w.sourceLocators().stream()).toList();
		assertTrue(addLocs.stream().anyMatch(l -> l.sideRole() == SideRole.BEFORE
				&& l.filePresence() == SidePresence.ABSENT
				&& l.basis() == SourceBasis.FILE_ABSENT
				&& l.symbolKey().equals(Optional.of(pingKey))
				&& l.ranges().isEmpty()));

		// Tamper BEFORE absence on file add -> downgraded!
		EvidenceResult missingBeforeAbsence = mapper.map(emptyInput(
				context, addReport, List.of(addDiff), List.of(presentProof(SideRole.AFTER, SHA_C, path, src)), addLookups));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), missingBeforeAbsence.overallOutcome());

		// 2. File removal (BEFORE PRESENT -> AFTER ABSENT).
		var remDiff = diff.compare(new StructuralDiffInput(
				REPO, Intent.REVISION_COMPARISON,
				Optional.of(new Present(new SnapshotRef(REPO, SHA_B), new SourceInput(path, src))),
				new Absent(new SnapshotRef(REPO, SHA_C), path, "deleted-in-target"),
				Optional.empty()));
		SymbolKey remPingKey = changedSymbolKey(remDiff, "ping", ChangeKind.REMOVED);
		var remClaim = new DocumentClaim(
				"claim-rem", "doc-svc", "v1", SHA_A, SHA_C,
				"Service#ping()", path, Optional.of(remPingKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.REFERENCED_ENTITY_REQUIRED, "ping() removed",
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Broken reference", "svc")));
		var remBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-svc", "v1", SHA_A, "bind-rem",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(remPingKey), Optional.empty(), List.of(remClaim));
		var remLookups = completeRevisionLookups(
				context, remDiff, symbolLookupKey(context, SideRole.BEFORE, SHA_B, path, remPingKey), remBinding);
		var remReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(remDiff), remLookups, List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), remReport.overallOutcome());

		var remProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, src),
				absentProof(SideRole.AFTER, SHA_C, path, "deleted-in-target"));
		String remFindingId = remReport.findings().stream()
				.filter(f -> f.symbolKey().equals(Optional.of(remPingKey))).findFirst().orElseThrow().findingId();
		EvidenceResult remVerified = mapper.map(emptyInput(context, remReport, List.of(remDiff), remProofs, remLookups));
		assertEquals(EvidenceStatus.VERIFIED, remVerified.status());
		assertTrue(remVerified.findings().stream()
				.filter(f -> f.findingId().equals(remFindingId))
				.findFirst().orElseThrow()
				.witnesses().stream()
				.flatMap(w -> w.sourceLocators().stream())
				.anyMatch(l -> l.sideRole() == SideRole.AFTER && l.basis() == SourceBasis.FILE_ABSENT
						&& l.symbolKey().equals(Optional.of(remPingKey)) && l.ranges().isEmpty()));

		// Mutate absence reason -> SNAPSHOT_MISMATCH, downgraded.
		var wrongReasonProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, src),
				absentProof(SideRole.AFTER, SHA_C, path, "other-reason"));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE),
				mapper.map(emptyInput(context, remReport, List.of(remDiff), wrongReasonProofs, remLookups)).overallOutcome());

		// Switch AFTER proof presence to UNAVAILABLE -> SNAPSHOT_UNAVAILABLE, downgraded.
		var unavailProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, src),
				unavailableProof(SideRole.AFTER, SHA_C, path, "fetch-timeout"));
		EvidenceResult unavailResult = mapper.map(emptyInput(context, remReport, List.of(remDiff), unavailProofs, remLookups));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), unavailResult.overallOutcome());
		assertTrue(unavailResult.findings().getFirst().witnesses().stream()
				.anyMatch(w -> w.reasons().contains(EvidenceReason.SNAPSHOT_UNAVAILABLE)));
	}

	@Test
	@DisplayName("M4-RV-03: Overload removal in PRESENT file proves SYMBOL_ABSENT without pairing and rejects conflicting/incomplete move")
	void rv03OverloadAbsenceAndFileMoveVerification() {
		String beforeSrc = "class Calc {\n    int m(int x) { return x; }\n    void keep() {}\n}\n";
		String afterSrc = "class Calc {\n    int m(long x) { return (int) x; }\n    void keep() {}\n}\n";
		String oldPath = "src/OldCalc.java";
		String newPath = "src/NewCalc.java";

		var moveDiff = compareJava(oldPath, SHA_B, beforeSrc, newPath, SHA_C, afterSrc);
		SymbolKey oldMKey = changedSymbolKey(moveDiff, "m", ChangeKind.REMOVED);
		SymbolKey newMKey = changedSymbolKey(moveDiff, "m", ChangeKind.ADDED);
		assertNotEquals(oldMKey, newMKey);

		var context = branchContext(SHA_B, SHA_C, false);
		var claim = new DocumentClaim(
				"claim-old-m", "doc-calc", "v1", SHA_A, SHA_C,
				"Calc#m(int)", oldPath, Optional.of(oldMKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.REFERENCED_ENTITY_REQUIRED, "Document references Calc#m(int)",
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Removed overload", "calc")));
		var binding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-calc", "v1", SHA_A, "bind-m",
				BindingGranularity.EXACT_SYMBOL, oldPath, Optional.of(oldMKey), Optional.empty(), List.of(claim));
		var lookups = completeRevisionLookups(
				context, moveDiff, symbolLookupKey(context, SideRole.BEFORE, SHA_B, oldPath, oldMKey), binding);
		var report = classifier.classify(new ImpactClassificationInput(
				context, List.of(moveDiff), lookups, List.of(), List.of(), List.of()));

		var findingOldM = report.findings().stream()
				.filter(f -> f.symbolKey().equals(Optional.of(oldMKey)))
				.findFirst().orElseThrow();
		assertEquals(ImpactOutcome.ACTIONABLE, findingOldM.outcome());
		assertEquals(Optional.of(DriftClassification.REFERENTIAL), findingOldM.classification());

		var proofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, oldPath, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, newPath, afterSrc));

		// 1. Verified move + overload absence -> SYMBOL_ABSENT for exact oldMKey on AFTER.
		EvidenceResult verified = mapper.map(emptyInput(context, report, List.of(moveDiff), proofs, lookups));
		assertEquals(EvidenceStatus.VERIFIED, verified.status());
		var evOldM = verified.findings().stream()
				.filter(f -> f.findingId().equals(findingOldM.findingId()))
				.findFirst().orElseThrow();
		assertEquals(ImpactOutcome.ACTIONABLE, evOldM.outcome());
		assertTrue(evOldM.witnesses().stream().flatMap(w -> w.sourceLocators().stream())
				.anyMatch(l -> l.sideRole() == SideRole.AFTER
						&& l.path().equals(newPath)
						&& l.basis() == SourceBasis.SYMBOL_ABSENT
						&& l.symbolKey().equals(Optional.of(oldMKey))
						&& l.ranges().isEmpty()));

		// 1b. Negative: dropping the unchanged-symbol (keep) lookups must downgrade the PATH_CHANGED finding.
		var lookupsWithoutKeep = lookups.stream()
				.filter(r -> !(r.key().granularity() == LookupGranularity.SYMBOL
						&& r.key().symbolKey().map(k -> k.name().equals("keep")).orElse(false)))
				.toList();
		EvidenceResult missingKeep = mapper.map(emptyInput(context, report, List.of(moveDiff), proofs, lookupsWithoutKeep));
		assertNotEquals(EvidenceStatus.VERIFIED, missingKeep.status());
		assertTrue(missingKeep.findings().stream().anyMatch(f -> f.outcome() == ImpactOutcome.INCONCLUSIVE
				&& f.primaryReason() == ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED));

		// A pure rename preserves an exact symbol claim verified against the target.
		var consistent = new DocumentClaim("claim-keep", "doc-calc", "v1", SHA_A, SHA_C,
				"Calc#m(int)", oldPath, Optional.of(oldMKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_CONSISTENT, "Declaration still matches", Optional.empty());
		var keptBinding = new DocumentBinding(TENANT, WORKSPACE, REPO, "doc-calc", "v1", SHA_A,
				"bind-keep", BindingGranularity.EXACT_SYMBOL, oldPath, Optional.of(oldMKey),
				Optional.empty(), List.of(consistent));
		var renameOnly = compareJava(oldPath, SHA_B, beforeSrc, newPath, SHA_C, beforeSrc);
		var renameLookups = completeRevisionLookups(context, renameOnly,
				symbolLookupKey(context, SideRole.BEFORE, SHA_B, oldPath, oldMKey), keptBinding);
		var renameReport = classifier.classify(new ImpactClassificationInput(context,
				List.of(renameOnly), renameLookups, List.of(), List.of(), List.of()));
		var renameFinding = renameReport.findings().stream()
				.filter(f -> f.symbolKey().isEmpty() && f.claimId().equals(Optional.of(consistent.claimId())))
				.findFirst().orElseThrow();
		assertEquals(ImpactOutcome.NO_IMPACT, renameFinding.outcome());
		var renameResult = mapper.map(emptyInput(context, renameReport, List.of(renameOnly),
				List.of(proofs.getFirst(), presentProof(SideRole.AFTER, SHA_C, newPath, beforeSrc)), renameLookups));
		var renameEvidence = renameResult.findings().stream()
				.filter(f -> f.findingId().equals(renameFinding.findingId())).findFirst().orElseThrow();
		assertEquals(ImpactOutcome.NO_IMPACT, renameEvidence.outcome());
		assertEquals(EvidenceStatus.VERIFIED, renameEvidence.status());

		// Conflicting diff pair sharing AFTER path -> CONFLICTING_PROOF, downgraded.
		var competingDiff = compareJava("src/OtherOld.java", SHA_B, beforeSrc, newPath, SHA_C, afterSrc);
		EvidenceResult conflicting = mapper.map(emptyInput(
				context, report, List.of(moveDiff, competingDiff), proofs, lookups));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), conflicting.overallOutcome());
		assertTrue(conflicting.findings().stream().flatMap(f -> f.witnesses().stream())
				.anyMatch(w -> w.reasons().contains(EvidenceReason.CONFLICTING_PROOF)));

		// 3. Incomplete coverage diff (residual in AFTER) -> SYMBOL_ABSENT rejected!
		var incompleteDiff = new StructuralDiffResult(
				ProcessingStatus.INCOMPLETE, moveDiff.intent(), moveDiff.repository(),
				moveDiff.normalizationVersion(), moveDiff.parserVersion(), moveDiff.languagePolicy(),
				moveDiff.beforeSnapshot(), moveDiff.afterSnapshot(), moveDiff.entityFacts(),
				moveDiff.initialInventory(), moveDiff.beforeContext(), moveDiff.afterContext(),
				moveDiff.fileComparison(), new StructuralDiff.CoverageSummary(false, List.of("residual gap")),
				List.of(new DiffDiagnostic(SideRole.INPUT, DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE,
						com.livingdocs.analysis.ast.JavaParseResult.Severity.INFO, "Residual",
						Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
		EvidenceResult incomplete = mapper.map(emptyInput(context, report, List.of(incompleteDiff), proofs, lookups));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), incomplete.overallOutcome());

		// 4. File-level move claim naming an UNCHANGED symbol ("keep") must not verify REFERENTIAL removal!
		var keepKey = new SymbolKey(Kind.METHOD, Optional.empty(), List.of("Calc"), "keep", List.of());
		var badMoveClaim = new DocumentClaim(
				"claim-bad-move", "doc-calc", "v1", SHA_A, SHA_C,
				"OldCalc.java#keep", oldPath, Optional.of(keepKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.REFERENCED_ENTITY_REQUIRED, "Move broke keep",
				Optional.of(new VerifiedImpactContext(Severity.LOW, "Low", "calc")));
		var badMoveBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-calc", "v1", SHA_A, "bind-file",
				BindingGranularity.FILE_ONLY, oldPath, Optional.empty(), Optional.empty(), List.of(badMoveClaim));
		var badMoveLookups = completeRevisionLookups(
				context, moveDiff, fileLookupKey(context, SideRole.BEFORE, SHA_B, oldPath), badMoveBinding);
		var badMoveReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(moveDiff), badMoveLookups, List.of(), List.of(), List.of()));
		EvidenceResult badMoveResult = mapper.map(emptyInput(context, badMoveReport, List.of(moveDiff), proofs, badMoveLookups));
		assertTrue(badMoveResult.findings().stream()
				.filter(f -> f.primaryReason() == ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED)
				.anyMatch(f -> f.outcome() == ImpactOutcome.INCONCLUSIVE));
	}

	@Test
	@DisplayName("M4-RV-04: Initial TARGET-only assessment verifies full inventory, retained incomplete inventory, BODY claim, and empty-file TARGET_INVENTORY_EMPTY")
	void rv04InitialAssessmentFullIncompleteBodyAndEmptyInventory() {
		String path = "src/Worker.java";
		String src = "package com.example;\npublic interface Worker {\n    void abstractOp();\n    default int run() { return 42; }\n}\n";
		var initDiff = diff.compare(new StructuralDiffInput(
				REPO, Intent.INITIAL_ASSESSMENT, Optional.empty(),
				new Present(new SnapshotRef(REPO, SHA_C), new SourceInput(path, src)), Optional.empty()));
		assertEquals(ProcessingStatus.COMPLETE, initDiff.status());

		SymbolKey runKey = initDiff.initialInventory().orElseThrow().entities().stream()
				.filter(e -> e.key().name().equals("run")).findFirst().orElseThrow().key();
		SymbolKey absKey = initDiff.initialInventory().orElseThrow().entities().stream()
				.filter(e -> e.key().name().equals("abstractOp")).findFirst().orElseThrow().key();

		var ctx = initialContext(SHA_C, false);
		var runClaim = new DocumentClaim(
				"claim-run-body", "doc-w", "v1", SHA_C, SHA_C,
				"Worker#run() returns 42", path, Optional.of(runKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN, "Mismatch in run body",
				Optional.of(new VerifiedImpactContext(Severity.MEDIUM, "Wrong behavior", "worker")));
		var runBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-w", "v1", SHA_C, "bind-run",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(runKey), Optional.empty(), List.of(runClaim));

		var absClaim = new DocumentClaim(
				"claim-abs-body", "doc-w", "v1", SHA_C, SHA_C,
				"Worker#abstractOp() body", path, Optional.of(absKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN, "Claims body on abstract method",
				Optional.of(new VerifiedImpactContext(Severity.LOW, "Wrong claim", "worker")));
		var absBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-w", "v1", SHA_C, "bind-abs",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(absKey), Optional.empty(), List.of(absClaim));

		var lookups = ImpactClassifier.buildLookupKeys(ctx, List.of(initDiff)).stream()
				.map(k -> {
					if (k.symbolKey().equals(Optional.of(runKey))) {
						return new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(runBinding), Optional.empty());
					}
					if (k.symbolKey().equals(Optional.of(absKey))) {
						return new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(absBinding), Optional.empty());
					}
					return new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty());
				}).toList();

		var report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(initDiff), lookups, List.of(), List.of(), List.of()));
		var proofs = List.of(presentProof(SideRole.TARGET, SHA_C, path, src));
		EvidenceResult result = mapper.map(emptyInput(ctx, report, List.of(initDiff), proofs, lookups));

		// Never emits BEFORE locators for INITIAL_ASSESSMENT.
		assertTrue(result.findings().stream().flatMap(f -> f.witnesses().stream())
				.flatMap(w -> w.sourceLocators().stream())
				.allMatch(l -> l.sideRole() == SideRole.TARGET));

		var runFinding = result.findings().stream()
				.filter(f -> f.primaryReason() == ReasonCode.SEMANTIC_BEHAVIOR_MISMATCH).findFirst().orElseThrow();
		assertEquals(EvidenceStatus.VERIFIED, runFinding.status());
		assertEquals(ImpactOutcome.ACTIONABLE, runFinding.outcome());
		assertTrue(runFinding.witnesses().stream().flatMap(w -> w.sourceLocators().stream())
				.anyMatch(l -> l.basis() == SourceBasis.FACET_PRESENT
						&& l.facetKind().equals(Optional.of(FacetKind.BODY))
						&& l.symbolKey().equals(Optional.of(runKey))));

		// Abstract method has no BODY facet -> downgraded!
		var absFinding = result.findings().stream()
				.filter(f -> f.primaryReason() == ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED).findFirst().orElseThrow();
		assertEquals(ImpactOutcome.INCONCLUSIVE, absFinding.outcome());

		// 2. Empty initial Java file with referential claim -> TARGET_INVENTORY_EMPTY when COMPLETE, fails when INCOMPLETE.
		String emptyPath = "src/EmptyFile.java";
		String emptySrc = "// empty file\n";
		var emptyDiff = diff.compare(new StructuralDiffInput(
				REPO, Intent.INITIAL_ASSESSMENT, Optional.empty(),
				new Present(new SnapshotRef(REPO, SHA_C), new SourceInput(emptyPath, emptySrc)), Optional.empty()));
		assertEquals(ProcessingStatus.COMPLETE, emptyDiff.status());
		var emptyClaim = new DocumentClaim(
				"claim-empty-ref", "doc-e", "v1", SHA_C, SHA_C,
				"EmptyFile#missing()", emptyPath, Optional.empty(), Optional.empty(),
				ClaimVerdict.REFERENCED_ENTITY_REQUIRED, "Referenced symbol missing in empty file",
				Optional.of(new VerifiedImpactContext(Severity.MEDIUM, "Missing entity", "empty")));
		var emptyBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-e", "v1", SHA_C, "bind-empty",
				BindingGranularity.FILE_ONLY, emptyPath, Optional.empty(), Optional.empty(), List.of(emptyClaim));
		var emptyLookups = List.of(completeFileLookup(ctx, SideRole.TARGET, SHA_C, emptyPath, List.of(emptyBinding)));
		var emptyReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(emptyDiff), emptyLookups, List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), emptyReport.overallOutcome());

		var emptyProofs = List.of(presentProof(SideRole.TARGET, SHA_C, emptyPath, emptySrc));
		EvidenceResult emptyVerified = mapper.map(emptyInput(ctx, emptyReport, List.of(emptyDiff), emptyProofs, emptyLookups));
		assertEquals(EvidenceStatus.VERIFIED, emptyVerified.status());
		assertTrue(emptyVerified.findings().getFirst().witnesses().stream()
				.flatMap(w -> w.sourceLocators().stream())
				.anyMatch(l -> l.basis() == SourceBasis.TARGET_INVENTORY_EMPTY && l.ranges().isEmpty()));

		// Retained partial inventory with INCOMPLETE (residual) diff keeps valid entity evidence,
		// while empty-inventory absence from INCOMPLETE diff is rejected.
		var residualInitDiff = new StructuralDiffResult(
				ProcessingStatus.INCOMPLETE, initDiff.intent(), initDiff.repository(),
				initDiff.normalizationVersion(), initDiff.parserVersion(), initDiff.languagePolicy(),
				Optional.empty(), initDiff.afterSnapshot(), List.of(), initDiff.initialInventory(),
				Optional.empty(), initDiff.afterContext(), initDiff.fileComparison(),
				new StructuralDiff.CoverageSummary(false, List.of("residual")),
				List.of(new DiffDiagnostic(SideRole.INPUT, DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE,
						com.livingdocs.analysis.ast.JavaParseResult.Severity.INFO, "Residual",
						Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
		var runOnlyLookups = ImpactClassifier.buildLookupKeys(ctx, List.of(residualInitDiff)).stream()
				.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE,
						k.symbolKey().equals(Optional.of(runKey)) ? List.of(runBinding) : List.of(), Optional.empty()))
				.toList();
		var residualReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(residualInitDiff), runOnlyLookups, List.of(), List.of(), List.of()));
		EvidenceResult residualResult = mapper.map(emptyInput(
				ctx, residualReport, List.of(residualInitDiff), proofs, runOnlyLookups));
		assertTrue(residualResult.findings().stream().anyMatch(f -> f.status() == EvidenceStatus.VERIFIED
				&& f.outcome() == ImpactOutcome.ACTIONABLE));

		var incompleteEmptyDiff = new StructuralDiffResult(
				ProcessingStatus.INCOMPLETE, emptyDiff.intent(), emptyDiff.repository(),
				emptyDiff.normalizationVersion(), emptyDiff.parserVersion(), emptyDiff.languagePolicy(),
				Optional.empty(), emptyDiff.afterSnapshot(), List.of(), emptyDiff.initialInventory(),
				Optional.empty(), emptyDiff.afterContext(), emptyDiff.fileComparison(),
				new StructuralDiff.CoverageSummary(false, List.of("residual")),
				List.of(new DiffDiagnostic(SideRole.INPUT, DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE,
						com.livingdocs.analysis.ast.JavaParseResult.Severity.INFO, "Residual",
						Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
		EvidenceResult incompleteEmptyResult = mapper.map(emptyInput(
				ctx, emptyReport, List.of(incompleteEmptyDiff), emptyProofs, emptyLookups));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), incompleteEmptyResult.overallOutcome());
	}

	@Test
	@DisplayName("M4-RV-05: Multi-segment DECLARATION refs for shared fields and compact record constructors verify independently without unioning")
	void rv05SharedFieldAndCompactConstructorMultiSegmentRefs() {
		String path = "src/Pair.java";
		String beforeSrc = "public record Pair(int a, int b) {\n    public Pair {\n        a = 1;\n    }\n}\n";
		String afterSrc = "public record Pair(int a, int second) {\n    public Pair {\n        a = 1;\n    }\n}\n";
		var recDiff = compareJava(path, SHA_B, beforeSrc, path, SHA_C, afterSrc);
		SymbolKey ctorKey = recDiff.entityFacts().stream()
				.filter(f -> f.change() == ChangeKind.MODIFIED
						&& f.after().map(s -> s.key().kind() == Kind.CONSTRUCTOR).orElse(false))
				.findFirst().orElseThrow().after().orElseThrow().key();

		// Compact constructor has 3 DECLARATION segments: header + 2 record components.
		List<SourceRange> expectedCtorDeclRanges = entitySide(recDiff, SideRole.AFTER, ctorKey)
				.facetReferences().get(FacetKind.DECLARATION).stream()
				.map(r -> r.reference().orElseThrow().range())
				.sorted(java.util.Comparator.comparingInt(SourceRange::startLine)
						.thenComparingInt(SourceRange::startColumn)
						.thenComparingInt(SourceRange::endLine)
						.thenComparingInt(SourceRange::endColumn))
				.toList();
		assertEquals(3, expectedCtorDeclRanges.size());

		var ctx = branchContext(SHA_B, SHA_C, false);
		var ctorClaim = new DocumentClaim(
				"claim-ctor", "doc-p", "v1", SHA_A, SHA_C,
				"Pair(int, int)", path, Optional.of(ctorKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Record component type changed",
				Optional.of(new VerifiedImpactContext(Severity.MEDIUM, "Contract mismatch", "pair")));
		var ctorBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-p", "v1", SHA_A, "bind-ctor",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(ctorKey), Optional.empty(), List.of(ctorClaim));
		var lookups = completeRevisionLookups(
				contextWith(ctx), recDiff, symbolLookupKey(ctx, SideRole.AFTER, SHA_C, path, ctorKey), ctorBinding);
		var report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(recDiff), lookups, List.of(), List.of(), List.of()));
		var proofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, path, afterSrc));

		EvidenceResult recVerified = mapper.map(emptyInput(ctx, report, List.of(recDiff), proofs, lookups));
		assertEquals(EvidenceStatus.VERIFIED, recVerified.status());
		SymbolKey finalCtorKey = ctorKey;
		var ctorFinding = recVerified.findings().stream()
				.filter(f -> f.witnesses().stream().flatMap(w -> w.sourceLocators().stream())
						.anyMatch(l -> l.symbolKey().equals(Optional.of(finalCtorKey))))
				.findFirst().orElseThrow();
		SourceLocator afterLoc = ctorFinding.witnesses().stream()
				.flatMap(w -> w.sourceLocators().stream())
				.filter(l -> l.sideRole() == SideRole.AFTER && l.facetKind().equals(Optional.of(FacetKind.DECLARATION)))
				.findFirst().orElseThrow();
		assertEquals(expectedCtorDeclRanges, afterLoc.ranges());

		// Shared field `int a, b[];` on multiline with second declarator corrupted via bad ProvenanceRef.
		String fPath = "src/Holder.java";
		String fBefore = "class Holder {\n    int a,\n        b[];\n}\n";
		String fAfter = "class Holder {\n    long a,\n        b[];\n}\n";
		var fDiff = compareJava(fPath, SHA_B, fBefore, fPath, SHA_C, fAfter);
		SymbolKey bFieldKey = changedSymbolKey(fDiff, "b", ChangeKind.MODIFIED);
		List<ProvenanceRef> bDeclRefs = entitySide(fDiff, SideRole.AFTER, bFieldKey)
				.facetReferences().get(FacetKind.DECLARATION);
		assertEquals(2, bDeclRefs.size());

		var fClaim = new DocumentClaim(
				"claim-b", "doc-h", "v1", SHA_A, SHA_C,
				"int b[]", fPath, Optional.of(bFieldKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Field b changed type",
				Optional.of(new VerifiedImpactContext(Severity.LOW, "Field type", "holder")));
		var fBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-h", "v1", SHA_A, "bind-b",
				BindingGranularity.EXACT_SYMBOL, fPath, Optional.of(bFieldKey), Optional.empty(), List.of(fClaim));
		var fLookups = completeRevisionLookups(
				ctx, fDiff, symbolLookupKey(ctx, SideRole.AFTER, SHA_C, fPath, bFieldKey), fBinding);
		var fReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(fDiff), fLookups, List.of(), List.of(), List.of()));
		var fProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, fPath, fBefore),
				presentProof(SideRole.AFTER, SHA_C, fPath, fAfter));
		EvidenceResult fVerified = mapper.map(emptyInput(ctx, fReport, List.of(fDiff), fProofs, fLookups));
		assertEquals(EvidenceStatus.VERIFIED, fVerified.status());

		// Corrupt second segment of AFTER b's DECLARATION facetReferences -> witness UNVERIFIED, first segment retained!
		var corruptedFacts = fDiff.entityFacts().stream().map(fact -> {
			if (!fact.after().map(s -> s.key().equals(bFieldKey)).orElse(false)) {
				return fact;
			}
			var oldAfter = fact.after().get();
			var refsMap = new java.util.EnumMap<FacetKind, List<ProvenanceRef>>(oldAfter.facetReferences());
			refsMap.put(FacetKind.DECLARATION, List.of(
					bDeclRefs.get(0),
					ProvenanceRef.of(new SourceReference("corrupted", bDeclRefs.get(1).reference().get().range()))));
			var mutatedAfter = new EntitySide(
					oldAfter.snapshot(), oldAfter.path(), oldAfter.key(), oldAfter.declarationRef(), oldAfter.headerRef(),
					oldAfter.sharedDeclarationRef(), oldAfter.sharedPrefixRef(), oldAfter.bodyRef(),
					oldAfter.initializerRef(), oldAfter.annotationDefaultRef(), refsMap, oldAfter.facetFingerprints());
			return new EntityFact(fact.change(), fact.before(), Optional.of(mutatedAfter),
					fact.changedFacets(), fact.matchBasis(), fact.unmatchedReason(), fact.normalizationVersion());
		}).toList();
		var corruptedDiff = new StructuralDiffResult(
				fDiff.status(), fDiff.intent(), fDiff.repository(), fDiff.normalizationVersion(),
				fDiff.parserVersion(), fDiff.languagePolicy(), fDiff.beforeSnapshot(), fDiff.afterSnapshot(),
				corruptedFacts, fDiff.initialInventory(), fDiff.beforeContext(), fDiff.afterContext(),
				fDiff.fileComparison(), fDiff.coverage(), fDiff.diagnostics());

		EvidenceResult fCorrupted = mapper.map(emptyInput(ctx, fReport, List.of(corruptedDiff), fProofs, fLookups));
		assertEquals(EvidenceStatus.PARTIAL, fCorrupted.status());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), fCorrupted.overallOutcome());
		String expectedBFindingId = fReport.findings().stream()
				.filter(f -> f.symbolKey().equals(Optional.of(bFieldKey))).findFirst().orElseThrow().findingId();
		var bFinding = fCorrupted.findings().stream()
				.filter(f -> f.findingId().equals(expectedBFindingId)).findFirst().orElseThrow();
		assertEquals(EvidenceStatus.PARTIAL, bFinding.status());
		WitnessEvidence afterWitness = bFinding.witnesses().stream()
				.filter(w -> w.status() == WitnessStatus.UNVERIFIED && w.reasons().contains(EvidenceReason.RANGE_MISMATCH))
				.findFirst().orElseThrow();
		assertEquals(List.of(bDeclRefs.get(0).reference().get().range()),
				afterWitness.sourceLocators().getFirst().ranges());
	}

	@Test
	@DisplayName("M4-RV-06: Initializer FACET_ABSENT, annotation default ref, and MEMBER_ORDER refs")
	void rv06FacetAbsentAnnotationDefaultAndMemberOrder() {
		String path = "src/Config.java";
		String beforeSrc = "public @interface Config {\n    int retries() default 3;\n    String mode();\n}\n";
		String afterSrc = "public @interface Config {\n    String mode();\n    int retries() default 5;\n}\n";
		var annDiff = compareJava(path, SHA_B, beforeSrc, path, SHA_C, afterSrc);
		SymbolKey retriesKey = changedSymbolKey(annDiff, "retries", ChangeKind.MODIFIED);
		SymbolKey configKey = changedSymbolKey(annDiff, "Config", ChangeKind.MODIFIED);

		SourceRange expectedDefaultRange = entitySide(annDiff, SideRole.AFTER, retriesKey)
				.annotationDefaultRef().orElseThrow().reference().orElseThrow().range();
		List<SourceRange> expectedOrderRanges = entitySide(annDiff, SideRole.AFTER, configKey)
				.facetReferences().get(FacetKind.MEMBER_ORDER).stream()
				.map(r -> r.reference().orElseThrow().range()).toList();
		assertEquals(2, expectedOrderRanges.size());

		var ctx = branchContext(SHA_B, SHA_C, false);
		var retriesClaim = new DocumentClaim(
				"claim-retries", "doc-cfg", "v1", SHA_A, SHA_C,
				"retries default 3", path, Optional.of(retriesKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Default changed from 3 to 5",
				Optional.of(new VerifiedImpactContext(Severity.MEDIUM, "Config default changed", "cfg")));
		var retriesBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-cfg", "v1", SHA_A, "bind-retries",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(retriesKey), Optional.empty(), List.of(retriesClaim));

		var orderClaim = new DocumentClaim(
				"claim-order", "doc-cfg", "v1", SHA_A, SHA_C,
				"Config members consistent", path, Optional.of(configKey), Optional.of(FacetKind.MEMBER_ORDER),
				ClaimVerdict.DECLARATION_CONSISTENT, "Member order does not impact doc", Optional.empty());
		var orderBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-cfg", "v1", SHA_A, "bind-order",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(configKey), Optional.empty(), List.of(orderClaim));

		var lookups = ImpactClassifier.buildLookupKeys(ctx, List.of(annDiff)).stream()
				.map(k -> {
					if (k.sideRole() == SideRole.AFTER && k.symbolKey().equals(Optional.of(retriesKey))) {
						return new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(retriesBinding), Optional.empty());
					}
					if (k.sideRole() == SideRole.AFTER && k.symbolKey().equals(Optional.of(configKey))) {
						return new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(orderBinding), Optional.empty());
					}
					return new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty());
				}).toList();
		var report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(annDiff), lookups, List.of(), List.of(), List.of()));
		var proofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, path, afterSrc));
		EvidenceResult result = mapper.map(emptyInput(ctx, report, List.of(annDiff), proofs, lookups));

		String retriesFindingId = report.findings().stream()
				.filter(f -> f.symbolKey().equals(Optional.of(retriesKey))).findFirst().orElseThrow().findingId();
		var retriesFinding = result.findings().stream()
				.filter(f -> f.findingId().equals(retriesFindingId)).findFirst().orElseThrow();
		assertEquals(EvidenceStatus.VERIFIED, retriesFinding.status());
		assertTrue(retriesFinding.witnesses().stream().flatMap(w -> w.sourceLocators().stream())
				.anyMatch(l -> l.sideRole() == SideRole.AFTER && l.ranges().contains(expectedDefaultRange)));

		String orderFindingId = report.findings().stream()
				.filter(f -> f.symbolKey().equals(Optional.of(configKey))).findFirst().orElseThrow().findingId();
		var orderFinding = result.findings().stream()
				.filter(f -> f.findingId().equals(orderFindingId)).findFirst().orElseThrow();
		assertEquals(EvidenceStatus.VERIFIED, orderFinding.status());
		assertEquals(ImpactOutcome.NO_IMPACT, orderFinding.outcome());
		assertTrue(orderFinding.witnesses().stream().flatMap(w -> w.sourceLocators().stream())
				.anyMatch(l -> l.sideRole() == SideRole.AFTER
						&& l.facetKind().equals(Optional.of(FacetKind.MEMBER_ORDER))
						&& l.ranges().equals(expectedOrderRanges)));

		// Add/remove field initializer -> FACET_ABSENT ONLY on absent side.
		String iPath = "src/InitField.java";
		String iBefore = "class InitField { int count; }\n";
		String iAfter = "class InitField { int count = 10; }\n";
		var iDiff = compareJava(iPath, SHA_B, iBefore, iPath, SHA_C, iAfter);
		SymbolKey countKey = changedSymbolKey(iDiff, "count", ChangeKind.MODIFIED);
		var initClaim = new DocumentClaim(
				"claim-init", "doc-i", "v1", SHA_A, SHA_C,
				"count initialized to 10", iPath, Optional.of(countKey), Optional.of(FacetKind.INITIALIZER),
				ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN, "Initializer added",
				Optional.of(new VerifiedImpactContext(Severity.LOW, "Init", "init")));
		var initBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-i", "v1", SHA_A, "bind-init",
				BindingGranularity.EXACT_SYMBOL, iPath, Optional.of(countKey), Optional.empty(), List.of(initClaim));
		var iLookups = completeRevisionLookups(
				ctx, iDiff, symbolLookupKey(ctx, SideRole.AFTER, SHA_C, iPath, countKey), initBinding);
		var iReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(iDiff), iLookups, List.of(), List.of(), List.of()));
		var iProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, iPath, iBefore),
				presentProof(SideRole.AFTER, SHA_C, iPath, iAfter));
		EvidenceResult iResult = mapper.map(emptyInput(ctx, iReport, List.of(iDiff), iProofs, iLookups));
		assertEquals(EvidenceStatus.VERIFIED, iResult.status());
		List<SourceLocator> iLocs = iResult.findings().getFirst().witnesses().stream()
				.flatMap(w -> w.sourceLocators().stream()).toList();
		assertTrue(iLocs.stream().anyMatch(l -> l.sideRole() == SideRole.BEFORE
				&& l.basis() == SourceBasis.FACET_ABSENT && l.facetKind().equals(Optional.of(FacetKind.INITIALIZER))
				&& l.ranges().isEmpty()));
		assertTrue(iLocs.stream().anyMatch(l -> l.sideRole() == SideRole.AFTER
				&& l.basis() == SourceBasis.FACET_PRESENT && l.facetKind().equals(Optional.of(FacetKind.INITIALIZER))
				&& !l.ranges().isEmpty()));
	}

	@Test
	@DisplayName("M4-RV-07: Context changes (package, imports, module, initializer block, scope order), empty imports, and comment-only file locators")
	void rv07ContextProjectionsEmptyImportsScopeOrderAndComments() {
		var ctx = branchContext(SHA_B, SHA_C, false);

		// 1. Imports deleted (AFTER imports empty -> CONTEXT_EMPTY) + initializer block + scope order + body change.
		String path = "src/OrderDemo.java";
		String beforeSrc = "package com.v1;\nimport java.util.List;\nimport java.util.Map;\nclass A {}\nclass B {\n    static { int x = 1; }\n    int m() { return 1; }\n}\n";
		String afterSrc = "package com.v1;\nclass B {\n    static { int x = 2; }\n    int m() { return 2; }\n}\nclass A {}\n";
		var diffRes = compareJava(path, SHA_B, beforeSrc, path, SHA_C, afterSrc);
		SymbolKey mKey = changedSymbolKey(diffRes, "m", ChangeKind.MODIFIED);

		var mClaim = new DocumentClaim(
				"claim-m", "doc-o", "v1", SHA_A, SHA_C,
				"B#m() returns 1", path, Optional.of(mKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN, "m changed",
				Optional.of(new VerifiedImpactContext(Severity.LOW, "Body", "o")));
		var mBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-o", "v1", SHA_A, "bind-m",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(mKey), Optional.empty(), List.of(mClaim));
		var fileClaim = new DocumentClaim(
				"claim-file", "doc-o", "v1", SHA_A, SHA_C,
				"OrderDemo context", path, Optional.empty(), Optional.empty(),
				ClaimVerdict.DECLARATION_CONSISTENT, "Context changes consistent", Optional.empty());
		var fileBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-o", "v1", SHA_A, "bind-f",
				BindingGranularity.FILE_ONLY, path, Optional.empty(), Optional.empty(), List.of(fileClaim));

		var lookups = ImpactClassifier.buildLookupKeys(ctx, List.of(diffRes)).stream()
				.map(k -> {
					if (k.sideRole() == SideRole.AFTER && k.symbolKey().equals(Optional.of(mKey))) {
						return new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(mBinding), Optional.empty());
					}
					if (k.sideRole() == SideRole.AFTER && k.granularity() == LookupGranularity.FILE) {
						return new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(fileBinding), Optional.empty());
					}
					return new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty());
				}).toList();
		var report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(diffRes), lookups, List.of(), List.of(), List.of()));
		var proofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, path, afterSrc));
		EvidenceResult result = mapper.map(emptyInput(ctx, report, List.of(diffRes), proofs, lookups));
		assertEquals(EvidenceStatus.VERIFIED, result.status());

		List<SourceLocator> allLocs = result.findings().stream()
				.flatMap(f -> f.witnesses().stream())
				.flatMap(w -> w.sourceLocators().stream())
				.toList();

		// Imports deleted on AFTER -> CONTEXT_EMPTY with empty ranges.
		assertTrue(allLocs.stream().anyMatch(l -> l.sideRole() == SideRole.AFTER
				&& l.basis() == SourceBasis.CONTEXT_EMPTY
				&& l.contextChangeKind().equals(Optional.of(ContextChangeKind.IMPORTS_CHANGED))
				&& l.ranges().isEmpty()));
		// Imports present on BEFORE -> 2 ranges.
		assertTrue(allLocs.stream().anyMatch(l -> l.sideRole() == SideRole.BEFORE
				&& l.basis() == SourceBasis.CONTEXT_PRESENT
				&& l.contextChangeKind().equals(Optional.of(ContextChangeKind.IMPORTS_CHANGED))
				&& l.ranges().size() == 2));
		// Scope order -> CONTEXT_PRESENT with empty ranges.
		assertTrue(allLocs.stream().anyMatch(l -> l.basis() == SourceBasis.CONTEXT_PRESENT
				&& l.contextChangeKind().equals(Optional.of(ContextChangeKind.SCOPE_ORDER_CHANGED))
				&& l.ranges().isEmpty()));
		// Initializer block -> CONTEXT_PRESENT with non-empty ranges.
		assertTrue(allLocs.stream().anyMatch(l -> l.basis() == SourceBasis.CONTEXT_PRESENT
				&& l.contextChangeKind().equals(Optional.of(ContextChangeKind.INITIALIZER_BLOCK_CHANGED))
				&& !l.ranges().isEmpty()));

		// 1b. Package declaration change.
		String pkgPath = "src/PkgDemo.java";
		String pkgBefore = "package com.v1;\nclass PkgDemo {}\n";
		String pkgAfter = "package com.v2;\nclass PkgDemo {}\n";
		var pkgDiff = compareJava(pkgPath, SHA_B, pkgBefore, pkgPath, SHA_C, pkgAfter);
		var pkgLookups = ImpactClassifier.buildLookupKeys(ctx, List.of(pkgDiff)).stream()
				.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty())).toList();
		var pkgReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(pkgDiff), pkgLookups, List.of(), List.of(), List.of()));
		var pkgProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, pkgPath, pkgBefore),
				presentProof(SideRole.AFTER, SHA_C, pkgPath, pkgAfter));
		EvidenceResult pkgRes = mapper.map(emptyInput(ctx, pkgReport, List.of(pkgDiff), pkgProofs, pkgLookups));
		assertEquals(EvidenceStatus.VERIFIED, pkgRes.status());
		assertTrue(pkgRes.findings().stream().flatMap(f -> f.witnesses().stream())
				.flatMap(w -> w.sourceLocators().stream())
				.anyMatch(l -> l.basis() == SourceBasis.CONTEXT_PRESENT
						&& l.contextChangeKind().equals(Optional.of(ContextChangeKind.PACKAGE_CHANGED))
						&& !l.ranges().isEmpty()));

		// 2. Module declaration change.
		String modPath = "src/module-info.java";
		var modDiff = compareJava(modPath, SHA_B, "module app.core { requires java.base; }\n",
				modPath, SHA_C, "module app.core { requires java.sql; }\n");
		var modLookups = ImpactClassifier.buildLookupKeys(ctx, List.of(modDiff)).stream()
				.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty())).toList();
		var modReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(modDiff), modLookups, List.of(), List.of(), List.of()));
		var modProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, modPath, "module app.core { requires java.base; }\n"),
				presentProof(SideRole.AFTER, SHA_C, modPath, "module app.core { requires java.sql; }\n"));
		EvidenceResult modRes = mapper.map(emptyInput(ctx, modReport, List.of(modDiff), modProofs, modLookups));
		assertEquals(EvidenceStatus.VERIFIED, modRes.status());
		assertTrue(modRes.findings().stream().flatMap(f -> f.witnesses().stream())
				.flatMap(w -> w.sourceLocators().stream())
				.anyMatch(l -> l.contextChangeKind().equals(Optional.of(ContextChangeKind.MODULE_CHANGED))
						&& l.basis() == SourceBasis.CONTEXT_PRESENT && !l.ranges().isEmpty()));

		// 3. Comment-only change uses file-level FILE_CONTENT locators.
		String cPath = "src/CommentOnly.java";
		String cBefore = "// old comment\nclass CommentOnly {}\n";
		String cAfter = "// new comment\nclass CommentOnly {}\n";
		var cDiff = compareJava(cPath, SHA_B, cBefore, cPath, SHA_C, cAfter);
		var cLookups = ImpactClassifier.buildLookupKeys(ctx, List.of(cDiff)).stream()
				.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty())).toList();
		var cReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(cDiff), cLookups, List.of(), List.of(), List.of()));
		var cProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, cPath, cBefore),
				presentProof(SideRole.AFTER, SHA_C, cPath, cAfter));
		EvidenceResult cRes = mapper.map(emptyInput(ctx, cReport, List.of(cDiff), cProofs, cLookups));
		assertEquals(EvidenceStatus.VERIFIED, cRes.status());
		assertTrue(cRes.findings().stream().flatMap(f -> f.witnesses().stream())
				.flatMap(w -> w.sourceLocators().stream())
				.anyMatch(l -> l.basis() == SourceBasis.FILE_CONTENT && l.ranges().isEmpty()));
	}

	@Test
	@DisplayName("M4-RV-08: Multi-claim S05 grounding isolation, two-tier downgrade, and tamper matrix")
	void rv08SemanticGroundingIsolationAndTwoTierDowngrade() {
		String path = "src/FeeService.java";
		String beforeSrc = "class FeeService {\n    int fee(int d) { return d * 5; }\n}\n";
		String afterSrc = "class FeeService {\n    int fee(int d) { return d * 10; }\n}\n";
		var diffRes = compareJava(path, SHA_B, beforeSrc, path, SHA_C, afterSrc);
		SymbolKey feeKey = changedSymbolKey(diffRes, "fee");
		var afterBodyRange = entitySide(diffRes, SideRole.AFTER, feeKey)
				.facetReferences().get(FacetKind.BODY).getFirst().reference().orElseThrow().range();

		var ctx = branchContext(SHA_B, SHA_C, false);
		var claimA = new DocumentClaim(
				"claim-A", "doc-fee", "v1", SHA_A, SHA_C,
				"Fee formula d*5", path, Optional.of(feeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY, "Initial potential mismatch on claim A", Optional.empty());
		var claimB = new DocumentClaim(
				"claim-B", "doc-fee", "v1", SHA_A, SHA_C,
				"Fee note", path, Optional.of(feeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY, "Initial potential mismatch on claim B", Optional.empty());
		var binding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-fee", "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(feeKey), Optional.of("sec-fee"), List.of(claimA, claimB));

		var lookups = completeRevisionLookups(
				ctx, diffRes, symbolLookupKey(ctx, SideRole.AFTER, SHA_C, path, feeKey), binding);

		var validSemA = new SemanticAssessmentInput(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", SHA_C, path, feeKey,
				"doc-fee", "v1", SHA_A, SemanticStatus.BEHAVIOR_MISMATCH, true,
				List.of(new GroundedSourceFacetRef(SideRole.AFTER, SHA_C, path, feeKey, FacetKind.BODY, Optional.of(afterBodyRange))),
				List.of(new GroundedDocumentClaimRef("doc-fee", "v1", SHA_A, "bind-fee", "claim-A", Optional.of("sec-fee"))),
				Optional.of(0.91), Optional.of("Multiplier changed 5 -> 10"),
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Fee doubled", "pricing")));
		var validSemB = new SemanticAssessmentInput(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", SHA_C, path, feeKey,
				"doc-fee", "v1", SHA_A, SemanticStatus.BEHAVIOR_MISMATCH, true,
				List.of(new GroundedSourceFacetRef(SideRole.AFTER, SHA_C, path, feeKey, FacetKind.BODY, Optional.of(afterBodyRange))),
				List.of(new GroundedDocumentClaimRef("doc-fee", "v1", SHA_A, "bind-fee", "claim-B", Optional.of("sec-fee"))),
				Optional.of(0.88), Optional.of("Claim B mismatch"),
				Optional.of(new VerifiedImpactContext(Severity.MEDIUM, "Note outdated", "pricing")));

		var report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(diffRes), lookups, List.of(validSemA, validSemB), List.of(), List.of()));
		assertEquals(2, report.findings().size());
		var proofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, path, afterSrc));

		// 1. Both A and B valid -> both VERIFIED ACTIONABLE.
		EvidenceResult bothOk = mapper.map(new EvidenceInput(
				Optional.of(ctx), report, List.of(diffRes), proofs, lookups,
				List.of(validSemA, validSemB), List.of(), List.of(), List.of()));
		assertEquals(EvidenceStatus.VERIFIED, bothOk.status());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), bothOk.overallOutcome());

		// 2. Only A has valid S05; B's S05 dropped -> A stays ACTIONABLE + VERIFIED,
		//    B downgrades to INCONCLUSIVE + SUPPORTED_BY_INITIAL_EVIDENCE (two-tier)!
		EvidenceResult onlyA = mapper.map(new EvidenceInput(
				Optional.of(ctx), report, List.of(diffRes), proofs, lookups,
				List.of(validSemA), List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), onlyA.overallOutcome());
		var fA = onlyA.findings().stream().filter(f -> f.outcome() == ImpactOutcome.ACTIONABLE).findFirst().orElseThrow();
		var fB = onlyA.findings().stream().filter(f -> f.outcome() == ImpactOutcome.INCONCLUSIVE).findFirst().orElseThrow();
		assertEquals(EvidenceStatus.VERIFIED, fA.status());
		assertEquals(IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE, fB.admission());
		assertEquals(ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED, fB.primaryReason());

		// 3. Initial proof fails too (tamper binding claim-B -> missing) -> B becomes INSUFFICIENT_EVIDENCE!
		var docOnlyA = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-fee", "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(feeKey), Optional.of("sec-fee"), List.of(claimA));
		var lookupsOnlyA = completeRevisionLookups(
				ctx, diffRes, symbolLookupKey(ctx, SideRole.AFTER, SHA_C, path, feeKey), docOnlyA);
		EvidenceResult initialFailB = mapper.map(new EvidenceInput(
				Optional.of(ctx), report, List.of(diffRes), proofs, lookupsOnlyA,
				List.of(validSemA), List.of(), List.of(), List.of()));
		var postFailB = initialFailB.findings().stream()
				.filter(f -> f.outcome() == ImpactOutcome.INCONCLUSIVE).findFirst().orElseThrow();
		assertEquals(IssueAdmission.INSUFFICIENT_EVIDENCE, postFailB.admission());

		// 4. Tamper S05 candidate across every dimension -> S05 rejected while initial admission survives.
		List<SemanticAssessmentInput> tamperedSems = List.of(
				// wrong doc version
				new SemanticAssessmentInput(TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
						"job-1", "attempt-1", SHA_C, path, feeKey, "doc-fee", "v2", SHA_A,
						SemanticStatus.BEHAVIOR_MISMATCH, true, validSemA.groundedSourceRefs(),
						validSemA.groundedDocumentRefs(), validSemA.confidence(), validSemA.rationale(), validSemA.impactContext()),
				// wrong boundSha
				new SemanticAssessmentInput(TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
						"job-1", "attempt-1", SHA_C, path, feeKey, "doc-fee", "v1", "sha-wrong",
						SemanticStatus.BEHAVIOR_MISMATCH, true, validSemA.groundedSourceRefs(),
						validSemA.groundedDocumentRefs(), validSemA.confidence(), validSemA.rationale(), validSemA.impactContext()),
				// wrong job/attempt
				new SemanticAssessmentInput(TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
						"job-1", "attempt-999", SHA_C, path, feeKey, "doc-fee", "v1", SHA_A,
						SemanticStatus.BEHAVIOR_MISMATCH, true, validSemA.groundedSourceRefs(),
						validSemA.groundedDocumentRefs(), validSemA.confidence(), validSemA.rationale(), validSemA.impactContext()),
				// deadline false
				new SemanticAssessmentInput(TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
						"job-1", "attempt-1", SHA_C, path, feeKey, "doc-fee", "v1", SHA_A,
						SemanticStatus.BEHAVIOR_MISMATCH, false, validSemA.groundedSourceRefs(),
						validSemA.groundedDocumentRefs(), validSemA.confidence(), validSemA.rationale(), validSemA.impactContext()),
				// sub-range instead of exact M2 facet range
				new SemanticAssessmentInput(TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
						"job-1", "attempt-1", SHA_C, path, feeKey, "doc-fee", "v1", SHA_A,
						SemanticStatus.BEHAVIOR_MISMATCH, true,
						List.of(new GroundedSourceFacetRef(SideRole.AFTER, SHA_C, path, feeKey, FacetKind.BODY,
								Optional.of(new SourceRange(afterBodyRange.startLine(), afterBodyRange.startColumn(),
										afterBodyRange.startLine(), afterBodyRange.startColumn())))),
						validSemA.groundedDocumentRefs(), validSemA.confidence(), validSemA.rationale(), validSemA.impactContext()),
				// wrong S05 status
				new SemanticAssessmentInput(TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
						"job-1", "attempt-1", SHA_C, path, feeKey, "doc-fee", "v1", SHA_A,
						SemanticStatus.BEHAVIOR_CONSISTENT, true, validSemA.groundedSourceRefs(),
						validSemA.groundedDocumentRefs(), validSemA.confidence(), validSemA.rationale(), validSemA.impactContext()),
				// wrong sectionRef
				new SemanticAssessmentInput(TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
						"job-1", "attempt-1", SHA_C, path, feeKey, "doc-fee", "v1", SHA_A,
						SemanticStatus.BEHAVIOR_MISMATCH, true, validSemA.groundedSourceRefs(),
						List.of(new GroundedDocumentClaimRef("doc-fee", "v1", SHA_A, "bind-fee", "claim-A", Optional.of("sec-wrong"))),
						validSemA.confidence(), validSemA.rationale(), validSemA.impactContext())
		);
		for (var badSem : tamperedSems) {
			EvidenceResult badSemRes = mapper.map(new EvidenceInput(
					Optional.of(ctx), report, List.of(diffRes), proofs, lookups,
					List.of(badSem), List.of(), List.of(), List.of()));
			assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), badSemRes.overallOutcome());
			assertTrue(badSemRes.findings().stream().allMatch(f -> f.admission() == IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE));
		}
	}

	@Test
	@DisplayName("M4-RV-09: Scope gate rejection, foreign authority isolation, and canary redaction across outputs/toString")
	void rv09ScopeGateForeignMixingAndCanaryRedaction() {
		String canarySource = "CANARY_SECRET_RAW_SOURCE_XYZ";
		String canaryDocTerm = "CANARY_DOC_TERM_XYZ";
		String canaryRationale = "CANARY_RATIONALE_XYZ";
		String canaryExclReason = "CANARY_EXCLUSION_REASON_XYZ";

		String path = "src/Redacted.java";
		String beforeSrc = "class Redacted { int f() { return 1; } }\n";
		String afterSrc = "class Redacted { int f() { return 2; } /* " + canarySource + " */ }\n";
		var diffRes = compareJava(path, SHA_B, beforeSrc, path, SHA_C, afterSrc);
		SymbolKey fKey = changedSymbolKey(diffRes, "f");
		var ctx = branchContext(SHA_B, SHA_C, false);

		var claim = new DocumentClaim(
				"claim-red", "doc-r", "v1", SHA_A, SHA_C,
				canaryDocTerm, path, Optional.of(fKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, canaryRationale,
				Optional.of(new VerifiedImpactContext(Severity.LOW, "CANARY_CONSEQUENCE_XYZ", "crit")));
		var binding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-r", "v1", SHA_A, "bind-r",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(fKey), Optional.empty(), List.of(claim));
		var lookups = completeRevisionLookups(
				ctx, diffRes, symbolLookupKey(ctx, SideRole.AFTER, SHA_C, path, fKey), binding);
		var report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(diffRes), lookups, List.of(), List.of(), List.of()));

		// 1. Empty authorizedContext, DENIED context, mismatched tenant/workspace/repo/target/job -> REJECTED with SCOPE_DENIED.
		EvidenceResult emptyAuth = mapper.map(new EvidenceInput(
				Optional.empty(), report, List.of(diffRes), List.of(), lookups,
				List.of(), List.of(), List.of(), List.of()));
		assertEquals(EvidenceStatus.REJECTED, emptyAuth.status());
		assertTrue(emptyAuth.processingStatus().isEmpty());
		assertTrue(emptyAuth.overallOutcome().isEmpty());
		assertTrue(emptyAuth.coverage().isEmpty());
		assertTrue(emptyAuth.findings().isEmpty());
		assertEquals(EvidenceReason.SCOPE_DENIED, emptyAuth.diagnostics().getFirst().evidenceReason().orElseThrow());

		var deniedCtx = new AssessmentContext(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", Optional.of("cfg-1"), SHA_C, Optional.of(SHA_B),
				Intent.REVISION_COMPARISON, ScopeCompleteness.DENIED, false);
		assertEquals(EvidenceStatus.REJECTED, mapper.map(emptyInput(deniedCtx, report, List.of(), List.of(), List.of())).status());

		var foreignCtx = new AssessmentContext(
				"tenant-FOREIGN", WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", Optional.of("cfg-1"), SHA_C, Optional.of(SHA_B),
				Intent.REVISION_COMPARISON, ScopeCompleteness.COMPLETE, false);
		assertEquals(EvidenceStatus.REJECTED, mapper.map(emptyInput(foreignCtx, report, List.of(), List.of(), List.of())).status());

		// 2. Mix foreign authority items with valid local ones -> SCOPE_DENIED diagnostic without location, local stays VERIFIED, no canary leaks!
		var foreignDiff = diff.compare(new StructuralDiffInput(
				"repo-FOREIGN", Intent.REVISION_COMPARISON,
				Optional.of(new Present(new SnapshotRef("repo-FOREIGN", SHA_B), new SourceInput("src/F.java", "class F {}"))),
				new Present(new SnapshotRef("repo-FOREIGN", SHA_C), new SourceInput("src/F.java", "class F {}")),
				Optional.empty()));
		var foreignProof = new SnapshotProof(SideRole.AFTER,
				new SnapshotProvenance(new SnapshotRef("repo-FOREIGN", SHA_C), "src/F.java", SidePresence.PRESENT,
						Optional.empty(), Optional.empty()),
				Optional.of(canarySource));
		var foreignExclusion = new CallerExclusion("repo-FOREIGN", SHA_C, "src/F.java", "FOREIGN_RULE_ID", canaryExclReason);

		var mixedInput = new EvidenceInput(
				Optional.of(ctx), report, List.of(diffRes, foreignDiff),
				List.of(presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
						presentProof(SideRole.AFTER, SHA_C, path, afterSrc), foreignProof),
				lookups, List.of(), List.of(), List.of(foreignExclusion), List.of());

		assertEquals("EvidenceInput[redacted]", mixedInput.toString());
		assertEquals("SnapshotProof[redacted]", foreignProof.toString());

		EvidenceResult mixedResult = mapper.map(mixedInput);
		assertEquals(EvidenceStatus.VERIFIED, mixedResult.status());
		assertTrue(mixedResult.diagnostics().stream()
				.anyMatch(d -> d.evidenceReason().equals(Optional.of(EvidenceReason.SCOPE_DENIED)) && d.location().isEmpty()));

		String dumped = mixedResult.toString();
		assertFalse(dumped.contains(canarySource));
		assertFalse(dumped.contains(canaryDocTerm));
		assertFalse(dumped.contains(canaryRationale));
		assertFalse(dumped.contains(canaryExclReason));
		assertFalse(dumped.contains("CANARY_CONSEQUENCE_XYZ"));
		assertFalse(dumped.contains("FOREIGN_RULE_ID"));
		assertFalse(dumped.contains("repo-FOREIGN"));
	}

	@Test
	@DisplayName("M4-RV-10: Multiline LF/CRLF, end-inclusive bounds, bad slices, standalone CR, tabs, surrogates, Unicode escapes, and optional witness tolerance")
	void rv10RangeCheckerEdgeCasesAndOptionalWitnessTolerance() {
		var ctx = branchContext(SHA_B, SHA_C, false);

		// 1. CRLF multiline + end-inclusive last char on line.
		String path = "src/Multi.java";
		String beforeSrc = "class Multi {\r\n    int calc() {\r\n        return 1;\r\n    }\r\n}\r\n";
		String afterSrc = "class Multi {\r\n    int calc() {\r\n        return 2;\r\n    }\r\n}\r\n";
		var crlfDiff = compareJava(path, SHA_B, beforeSrc, path, SHA_C, afterSrc);
		SymbolKey calcKey = changedSymbolKey(crlfDiff, "calc");
		var claim = new DocumentClaim(
				"claim-calc", "doc-m", "v1", SHA_A, SHA_C,
				"calc() returns 1", path, Optional.of(calcKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN, "Changed return value",
				Optional.of(new VerifiedImpactContext(Severity.LOW, "Return changed", "m")));
		var binding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-m", "v1", SHA_A, "bind-calc",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(calcKey), Optional.empty(), List.of(claim));
		var lookups = completeRevisionLookups(
				ctx, crlfDiff, symbolLookupKey(ctx, SideRole.AFTER, SHA_C, path, calcKey), binding);
		var report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(crlfDiff), lookups, List.of(), List.of(), List.of()));
		var proofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, path, afterSrc));

		EvidenceResult crlfVerified = mapper.map(emptyInput(ctx, report, List.of(crlfDiff), proofs, lookups));
		assertEquals(EvidenceStatus.VERIFIED, crlfVerified.status());

		// Inverted SourceRange rejected by constructor.
		assertThrows(IllegalArgumentException.class, () -> new SourceRange(2, 5, 2, 3));

		// Out-of-bounds range on AFTER BODY -> RANGE_MISMATCH.
		var oobDiff = mutateEntityRange(crlfDiff, SideRole.AFTER, calcKey, FacetKind.BODY,
				ProvenanceRef.of(new SourceReference("{ return 2; }", new SourceRange(3, 1, 3, 99))));
		EvidenceResult oobRes = mapper.map(emptyInput(ctx, report, List.of(oobDiff), proofs, lookups));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), oobRes.overallOutcome());
		assertTrue(oobRes.findings().getFirst().witnesses().stream()
				.anyMatch(w -> w.reasons().contains(EvidenceReason.RANGE_MISMATCH)));

		// Standalone \r -> RANGE_UNAVAILABLE.
		String standaloneCrAfter = "class Multi {\r    int calc() {\r        return 2;\r    }\r}\r";
		var crDiff = compareJava(path, SHA_B, beforeSrc, path, SHA_C, standaloneCrAfter);
		var crProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, path, standaloneCrAfter));
		EvidenceResult crRes = mapper.map(emptyInput(ctx, report, List.of(crDiff), crProofs, lookups));
		assertTrue(crRes.findings().getFirst().witnesses().stream()
				.anyMatch(w -> w.reasons().contains(EvidenceReason.RANGE_UNAVAILABLE)));

		// Tab, surrogate pair emoji, and Java Unicode escape \u0061 -> RANGE_UNAVAILABLE.
		for (String advSrc : List.of(
				"class Multi {\n\tint calc() { return 2; }\n}\n",
				"class Multi {\n    /* 😀 */ int calc() { return 2; }\n}\n",
				"class Multi {\n    /* \\u0061 */ int calc() { return 2; }\n}\n",
				"class Multi {\n    /* \\uu0061 */ int calc() { return 2; }\n}\n")) {
			var advDiff = compareJava(path, SHA_B, beforeSrc, path, SHA_C, advSrc);
			var advProofs = List.of(
					presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
					presentProof(SideRole.AFTER, SHA_C, path, advSrc));
			EvidenceResult advRes = mapper.map(emptyInput(ctx, report, List.of(advDiff), advProofs, lookups));
			assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), advRes.overallOutcome());
			assertTrue(advRes.findings().getFirst().witnesses().stream()
					.anyMatch(w -> w.reasons().contains(EvidenceReason.RANGE_UNAVAILABLE)));
		}

		// Optional witness failure keeps outcome (PARTIAL) while required FACET failure downgrades even when FILE_IDENTITY verified.
		var optDocWitness = new DocumentWitnessSelector(
				"witness:optional-doc", "doc-m", "v1", SHA_A, SHA_C, "bind-calc",
				Optional.of("sec-opt"), Optional.empty(), Optional.empty());
		var baseFinding = report.findings().getFirst();
		var withOptional = new ArrayList<>(baseFinding.witnesses());
		withOptional.add(optDocWitness);
		var reportWithOpt = new ImpactReport(
				report.classifierVersion(), report.processingStatus(), report.overallOutcome(),
				report.context(),
				List.of(new ImpactFinding(
						baseFinding.findingId(), baseFinding.disposition(), baseFinding.outcome(),
						baseFinding.classification(), baseFinding.severity(), baseFinding.severityStatus(),
						baseFinding.severityRationale(), baseFinding.semanticConfidence(),
						baseFinding.admission(), baseFinding.admissionReason(), baseFinding.primaryReason(),
						baseFinding.secondaryReasons(), baseFinding.explanation(),
						baseFinding.beforePath(), baseFinding.afterPath(), baseFinding.symbolKey(),
						baseFinding.evaluatedFacets(), baseFinding.documentId(), baseFinding.documentVersion(),
						baseFinding.documentBoundSha(), baseFinding.bindingId(), baseFinding.claimId(),
						withOptional, baseFinding.requiredImpactWitnessIds(),
						baseFinding.requiredAdmissionWitnessIds(), baseFinding.initialAssessment())),
				report.coverage(), report.diagnostics());

		EvidenceResult optFailResult = mapper.map(emptyInput(ctx, reportWithOpt, List.of(crlfDiff), proofs, lookups));
		assertEquals(EvidenceStatus.PARTIAL, optFailResult.status());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), optFailResult.overallOutcome());
	}

	@Test
	@DisplayName("M4-RV-11: COMPLETE_EMPTY_LOOKUP with buildLookupKeys, unrelated symbol binding allowed on FILE, RV08 unchanged symbol enforcement")
	void rv11CompleteEmptyLookupAndUnchangedSymbolEnforcement() {
		String path = "src/Ledger.java";
		String beforeSrc = "package com.example;\npublic class Ledger {\n    public void post(int amt) { int x = amt; }\n    public void audit() {}\n}\n";
		String afterSrc = "package com.example;\npublic class Ledger {\n    public void post(int amt) { int x = amt + 1; }\n    public void audit() {}\n}\n";
		var diffRes = compareJava(path, SHA_B, beforeSrc, path, SHA_C, afterSrc);
		SymbolKey postKey = changedSymbolKey(diffRes, "post");
		SymbolKey auditKey = new SymbolKey(Kind.METHOD, Optional.of("com.example"), List.of("Ledger"), "audit", List.of());

		var ctx = branchContext(SHA_B, SHA_C, false);
		// FILE lookup holds binding for ANOTHER symbol (auditKey), while postKey lookups are empty -> NO_IMPACT for postKey!
		var auditBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-audit", "v1", SHA_A, "bind-audit",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(auditKey), Optional.empty(), List.of());
		var validLookups = ImpactClassifier.buildLookupKeys(ctx, List.of(diffRes)).stream()
				.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE,
						k.granularity() == LookupGranularity.FILE && k.sideRole() == SideRole.AFTER
								? List.of(auditBinding) : List.of(),
						Optional.empty()))
				.toList();
		var report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(diffRes), validLookups, List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), report.overallOutcome());

		var proofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, beforeSrc),
				presentProof(SideRole.AFTER, SHA_C, path, afterSrc));
		EvidenceResult verified = mapper.map(emptyInput(ctx, report, List.of(diffRes), proofs, validLookups));
		assertEquals(EvidenceStatus.VERIFIED, verified.status());
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), verified.overallOutcome());
		assertEquals(IssueAdmission.NOT_APPLICABLE, verified.findings().getFirst().admission());

		// Drop BEFORE FILE lookup -> downgraded!
		var missingBeforeFile = validLookups.stream()
				.filter(r -> !(r.key().sideRole() == SideRole.BEFORE && r.key().granularity() == LookupGranularity.FILE))
				.toList();
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE),
				mapper.map(emptyInput(ctx, report, List.of(diffRes), proofs, missingBeforeFile)).overallOutcome());

		// Set one lookup to DENIED -> downgraded!
		var deniedLookup = validLookups.stream()
				.map(r -> r.key().sideRole() == SideRole.AFTER && r.key().granularity() == LookupGranularity.SYMBOL
						&& r.key().symbolKey().equals(Optional.of(postKey))
						? new DocumentLookupResponse(r.key(), LookupCompleteness.DENIED, List.of(), Optional.of("denied"))
						: r)
				.toList();
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE),
				mapper.map(emptyInput(ctx, report, List.of(diffRes), proofs, deniedLookup)).overallOutcome());

		// RV08 file-axis case: path rename with unchanged symbol; removing unchanged symbol lookup downgrades!
		String oldPath = "src/OldLedger.java";
		String newPath = "src/NewLedger.java";
		String sameSrc = "package com.example;\npublic class Ledger {\n    public void audit() {}\n}\n";
		var moveDiff = compareJava(oldPath, SHA_B, sameSrc, newPath, SHA_C, sameSrc);
		var moveLookups = ImpactClassifier.buildLookupKeys(ctx, List.of(moveDiff)).stream()
				.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty())).toList();
		var moveReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(moveDiff), moveLookups, List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), moveReport.overallOutcome());
		var moveProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, oldPath, sameSrc),
				presentProof(SideRole.AFTER, SHA_C, newPath, sameSrc));
		assertEquals(EvidenceStatus.VERIFIED,
				mapper.map(emptyInput(ctx, moveReport, List.of(moveDiff), moveProofs, moveLookups)).status());

		// Drop unchanged symbol lookup from moveLookups -> downgraded!
		var missingUnchangedSymbol = moveLookups.stream()
				.filter(r -> !(r.key().granularity() == LookupGranularity.SYMBOL
						&& r.key().symbolKey().map(k -> k.name().equals("audit")).orElse(false)))
				.toList();
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE),
				mapper.map(emptyInput(ctx, moveReport, List.of(moveDiff), moveProofs, missingUnchangedSymbol)).overallOutcome());

		for (String targetPath : List.of(oldPath, newPath)) {
			assertFileAxisLookups(ctx, oldPath, targetPath);
		}
	}

	private void assertFileAxisLookups(AssessmentContext ctx, String oldPath, String newPath) {
		String before = "import one.T; class Ledger { T keep; int post() { return 1; } } // old";
		String after = "import two.T; class Ledger { T keep; int post() { return 2; } } // new";
		var result = compareJava(oldPath, SHA_B, before, newPath, SHA_C, after);
		var full = ImpactClassifier.buildLookupKeys(ctx, List.of(result)).stream()
				.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty()))
				.toList();
		var report = classifier.classify(new ImpactClassificationInput(ctx, List.of(result), full,
				List.of(), List.of(), List.of()));
		var reduced = full.stream().filter(r -> r.key().symbolKey().stream()
				.noneMatch(k -> k.name().equals("post"))).toList();
		var reducedKeys = reduced.stream().map(DocumentLookupResponse::key).toList();
		var snapshots = List.of(presentProof(SideRole.BEFORE, SHA_B, oldPath, before),
				presentProof(SideRole.AFTER, SHA_C, newPath, after));
		int verifiedMoves = 0;
		int rejectedAxes = 0;
		for (var f : report.findings()) {
			if (f.symbolKey().isPresent()) {
				continue;
			}
			var neg = f.witnesses().stream().filter(NegativeBasisWitnessSelector.class::isInstance)
					.map(NegativeBasisWitnessSelector.class::cast).findFirst().orElseThrow();
			boolean requiresAll = Set.copyOf(neg.verifiedEmptyLookupKeys()).size() == full.size();
			var truncated = new NegativeBasisWitnessSelector(neg.witnessId(), neg.kind(), reducedKeys,
					neg.beforeSha(), neg.beforePath(), neg.afterSha(), neg.afterPath(), neg.provenanceRefId(),
					neg.scopeManifest(), neg.expectedBeforeRawSha256(), neg.expectedTargetRawSha256());
			var mutated = new ImpactFinding(f.findingId(), f.disposition(), f.outcome(), f.classification(),
					f.severity(), f.severityStatus(), f.severityRationale(), f.semanticConfidence(), f.admission(),
					f.admissionReason(), f.primaryReason(), f.secondaryReasons(), f.explanation(), f.beforePath(),
					f.afterPath(), f.symbolKey(), f.evaluatedFacets(), f.documentId(), f.documentVersion(),
					f.documentBoundSha(), f.bindingId(), f.claimId(),
					f.witnesses().stream().map(w -> w.equals(neg) ? truncated : w).toList(),
					f.requiredImpactWitnessIds(), f.requiredAdmissionWitnessIds(), f.initialAssessment());
			var alteredReport = new ImpactReport(report.classifierVersion(), report.processingStatus(),
					report.overallOutcome(), ctx, report.findings().stream()
							.map(original -> original.equals(f) ? mutated : original).toList(),
					report.coverage(), report.diagnostics());
			var ev = mapper.map(emptyInput(ctx, alteredReport, List.of(result), snapshots, reduced))
					.findings().stream().filter(e -> e.findingId().equals(f.findingId())).findFirst().orElseThrow();
			if (requiresAll) {
				assertEquals(ImpactOutcome.INCONCLUSIVE, ev.outcome());
				assertEquals(ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED, ev.primaryReason());
				rejectedAxes++;
			} else {
				assertEquals(ImpactOutcome.NO_IMPACT, ev.outcome());
				assertEquals(EvidenceStatus.VERIFIED, ev.status());
				verifiedMoves++;
			}
		}
		assertEquals(2, rejectedAxes); // imports and comments
		assertEquals(oldPath.equals(newPath) ? 0 : 1, verifiedMoves);
	}

	@Test
	@DisplayName("M4-RV-12: Permutation invariance, duplicate collapse, conflict rejection, immutability, concurrency, and malformed report rejection")
	void rv12DeterminismPermutationConflictsImmutabilityAndMalformedGate() throws Exception {
		String path = "src/Det.java";
		String sameText = "class Det { int id(int x) { return x; } }\n";
		String afterText = "class Det { int id(int x) { return x + 1; } }\n";
		var diffRes = compareJava(path, SHA_B, sameText, path, SHA_C, afterText);
		SymbolKey idKey = changedSymbolKey(diffRes, "id");

		var ctx = branchContext(SHA_B, SHA_C, false);
		var claim = new DocumentClaim(
				"claim-det", "doc-det", "v1", SHA_A, SHA_C,
				"Det#id(int)", path, Optional.of(idKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.BEHAVIOR_MISMATCH_PROVEN, "Mismatch",
				Optional.of(new VerifiedImpactContext(Severity.LOW, "Low", "det")));
		var binding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-det", "v1", SHA_A, "bind-det",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(idKey), Optional.empty(), List.of(claim));
		var lookups = completeRevisionLookups(
				ctx, diffRes, symbolLookupKey(ctx, SideRole.AFTER, SHA_C, path, idKey), binding);
		var report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(diffRes), lookups, List.of(), List.of(), List.of()));
		var proofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, sameText),
				presentProof(SideRole.AFTER, SHA_C, path, afterText));

		EvidenceResult baseline = mapper.map(emptyInput(ctx, report, List.of(diffRes), proofs, lookups));
		assertEquals(EvidenceStatus.VERIFIED, baseline.status());

		// Defensive copies check.
		assertThrows(UnsupportedOperationException.class, () -> baseline.findings().add(baseline.findings().getFirst()));
		assertThrows(UnsupportedOperationException.class, () -> baseline.findings().getFirst().witnesses().clear());

		// Permuted + equal duplicates produce exact equal EvidenceResult.
		var revProofs = new ArrayList<>(proofs);
		Collections.reverse(revProofs);
		revProofs.add(proofs.getFirst());
		var revLookups = new ArrayList<>(lookups);
		Collections.reverse(revLookups);
		revLookups.add(lookups.getFirst());
		EvidenceResult permuted = mapper.map(emptyInput(ctx, report, List.of(diffRes, diffRes), revProofs, revLookups));
		assertEquals(baseline, permuted);

		// Concurrent invocations produce identical equal results.
		try (var pool = Executors.newFixedThreadPool(4)) {
			List<Callable<EvidenceResult>> tasks = Collections.nCopies(8,
					() -> mapper.map(emptyInput(ctx, report, List.of(diffRes), proofs, lookups)));
			List<Future<EvidenceResult>> futures = pool.invokeAll(tasks);
			for (var fut : futures) {
				assertEquals(baseline, fut.get());
			}
		}

		// Conflicting same-key SnapshotProof rejects affected witness.
		var conflictingProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, sameText),
				presentProof(SideRole.AFTER, SHA_C, path, afterText),
				presentProof(SideRole.AFTER, SHA_C, path, "class Det { int id(int x) { return 0; } }\n"));
		EvidenceResult conflictRes = mapper.map(emptyInput(ctx, report, List.of(diffRes), conflictingProofs, lookups));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), conflictRes.overallOutcome());
		assertTrue(conflictRes.findings().getFirst().witnesses().stream()
				.anyMatch(w -> w.reasons().contains(EvidenceReason.CONFLICTING_PROOF)));

		// Malformed report checks -> REJECTED + INVALID_INPUT.
		var origFinding = report.findings().getFirst();
		// 1) Duplicate findingId with differing content.
		var altFinding = new ImpactFinding(
				origFinding.findingId(), origFinding.disposition(), origFinding.outcome(),
				origFinding.classification(), origFinding.severity(), origFinding.severityStatus(),
				origFinding.severityRationale(), origFinding.semanticConfidence(),
				origFinding.admission(), origFinding.admissionReason(), origFinding.primaryReason(),
				origFinding.secondaryReasons(), "DIFFERENT EXPLANATION",
				origFinding.beforePath(), origFinding.afterPath(), origFinding.symbolKey(),
				origFinding.evaluatedFacets(), origFinding.documentId(), origFinding.documentVersion(),
				origFinding.documentBoundSha(), origFinding.bindingId(), origFinding.claimId(),
				origFinding.witnesses(), origFinding.requiredImpactWitnessIds(),
				origFinding.requiredAdmissionWitnessIds(), origFinding.initialAssessment());
		var dupDiffReport = new ImpactReport(
				report.classifierVersion(), report.processingStatus(), report.overallOutcome(),
				ctx, List.of(origFinding, altFinding), report.coverage(), report.diagnostics());
		EvidenceResult dupReject = mapper.map(emptyInput(ctx, dupDiffReport, List.of(diffRes), proofs, lookups));
		assertEquals(EvidenceStatus.REJECTED, dupReject.status());
		assertEquals(EvidenceReason.INVALID_INPUT, dupReject.diagnostics().getFirst().evidenceReason().orElseThrow());

		// 2) Required witness ID missing from witnesses list.
		var missingReqReport = new ImpactReport(
				report.classifierVersion(), report.processingStatus(), report.overallOutcome(),
				ctx, List.of(new ImpactFinding(
						origFinding.findingId(), origFinding.disposition(), origFinding.outcome(),
						origFinding.classification(), origFinding.severity(), origFinding.severityStatus(),
						origFinding.severityRationale(), origFinding.semanticConfidence(),
						origFinding.admission(), origFinding.admissionReason(), origFinding.primaryReason(),
						origFinding.secondaryReasons(), origFinding.explanation(),
						origFinding.beforePath(), origFinding.afterPath(), origFinding.symbolKey(),
						origFinding.evaluatedFacets(), origFinding.documentId(), origFinding.documentVersion(),
						origFinding.documentBoundSha(), origFinding.bindingId(), origFinding.claimId(),
						origFinding.witnesses(), Set.of("witness:does-not-exist"),
						origFinding.requiredAdmissionWitnessIds(), origFinding.initialAssessment())),
				report.coverage(), report.diagnostics());
		assertEquals(EvidenceStatus.REJECTED,
				mapper.map(emptyInput(ctx, missingReqReport, List.of(diffRes), proofs, lookups)).status());

		// 3) Empty requiredImpactWitnessIds on ACTIONABLE finding.
		var emptyReqReport = new ImpactReport(
				report.classifierVersion(), report.processingStatus(), report.overallOutcome(),
				ctx, List.of(new ImpactFinding(
						origFinding.findingId(), origFinding.disposition(), origFinding.outcome(),
						origFinding.classification(), origFinding.severity(), origFinding.severityStatus(),
						origFinding.severityRationale(), origFinding.semanticConfidence(),
						origFinding.admission(), origFinding.admissionReason(), origFinding.primaryReason(),
						origFinding.secondaryReasons(), origFinding.explanation(),
						origFinding.beforePath(), origFinding.afterPath(), origFinding.symbolKey(),
						origFinding.evaluatedFacets(), origFinding.documentId(), origFinding.documentVersion(),
						origFinding.documentBoundSha(), origFinding.bindingId(), origFinding.claimId(),
						origFinding.witnesses(), Set.of(),
						origFinding.requiredAdmissionWitnessIds(), origFinding.initialAssessment())),
				report.coverage(), report.diagnostics());
		assertEquals(EvidenceStatus.REJECTED,
				mapper.map(emptyInput(ctx, emptyReqReport, List.of(diffRes), proofs, lookups)).status());
	}

	@Test
	@DisplayName("M4-RV-13: Parse FAILED, SNAPSHOT_UNAVAILABLE, residual diagnostic range, and INPUT diagnostic safe projection")
	void rv13ParseFailedUnavailableResidualAndInputDiagnostics() {
		var ctx = branchContext(SHA_B, SHA_C, false);

		// 1. Parse FAILED report keeps processingStatus FAILED and empty overallOutcome, plus safe identity trace.
		String badPath = "src/Broken.java";
		String goodBefore = "class Broken {}\n";
		String badAfter = "class Broken { int x = ; }\n";
		var failedDiff = compareJava(badPath, SHA_B, goodBefore, badPath, SHA_C, badAfter);
		assertEquals(ProcessingStatus.FAILED, failedDiff.status());
		var failedReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(failedDiff), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.FAILED, failedReport.processingStatus());
		assertTrue(failedReport.overallOutcome().isEmpty());

		var failedProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, badPath, goodBefore),
				presentProof(SideRole.AFTER, SHA_C, badPath, badAfter));
		EvidenceResult failedEv = mapper.map(emptyInput(ctx, failedReport, List.of(failedDiff), failedProofs, List.of()));
		assertEquals(Optional.of(ReportProcessingStatus.FAILED), failedEv.processingStatus());
		assertTrue(failedEv.overallOutcome().isEmpty());
		assertTrue(failedEv.diagnostics().stream().anyMatch(d -> d.diffCode().equals(Optional.of(DiagnosticCode.PARSE_FAILED))
				&& d.location().map(l -> l.basis() == SourceBasis.FILE_IDENTITY && l.ranges().isEmpty()).orElse(false)));
		// Also verify parse FAILED diagnostic carrying candidate range drops range and marks RANGE_UNAVAILABLE.
		var failedWithCandidateRange = new StructuralDiffResult(
				failedDiff.status(), failedDiff.intent(), failedDiff.repository(),
				failedDiff.normalizationVersion(), failedDiff.parserVersion(), failedDiff.languagePolicy(),
				failedDiff.beforeSnapshot(), failedDiff.afterSnapshot(), failedDiff.entityFacts(),
				failedDiff.initialInventory(), failedDiff.beforeContext(), failedDiff.afterContext(),
				failedDiff.fileComparison(), failedDiff.coverage(),
				List.of(new DiffDiagnostic(SideRole.AFTER, DiagnosticCode.PARSE_FAILED,
						com.livingdocs.analysis.ast.JavaParseResult.Severity.ERROR, "Syntax error",
						Optional.of(new SnapshotRef(REPO, SHA_C)), Optional.of(badPath),
						Optional.of(new SourceRange(1, 1, 1, 5)), Optional.empty())));
		EvidenceResult failedRangeEv = mapper.map(emptyInput(
				ctx, failedReport, List.of(failedWithCandidateRange), failedProofs, List.of()));
		assertTrue(failedRangeEv.diagnostics().stream().anyMatch(d ->
				d.diffCode().equals(Optional.of(DiagnosticCode.PARSE_FAILED))
						&& d.evidenceReason().equals(Optional.of(EvidenceReason.RANGE_UNAVAILABLE))
						&& d.location().map(l -> l.basis() == SourceBasis.FILE_IDENTITY && l.ranges().isEmpty()).orElse(false)));

		// 2. SNAPSHOT_UNAVAILABLE + residual INCOMPLETE with verified DIAGNOSTIC_RANGE + INPUT diagnostic without location.
		String unavailPath = "src/Missing.java";
		var unavailDiff = diff.compare(new StructuralDiffInput(
				REPO, Intent.REVISION_COMPARISON,
				Optional.of(new Present(new SnapshotRef(REPO, SHA_B), new SourceInput(unavailPath, "class Missing {}\n"))),
				new Unavailable(new SnapshotRef(REPO, SHA_C), unavailPath, "s02-503"),
				Optional.empty()));
		String resPath = "src/Res.java";
		String resBefore = "class Res { int a = 1; }\n";
		String resAfter = "class Res { int a = 1;\n    int b = 2; }\n";
		var resDiff = compareJava(resPath, SHA_B, resBefore, resPath, SHA_C, resAfter);
		// Inject a grounded AFTER residual diagnostic to verify DIAGNOSTIC_RANGE + an INPUT diagnostic.
		var residualWithDiag = new StructuralDiffResult(
				ProcessingStatus.INCOMPLETE, resDiff.intent(), resDiff.repository(),
				resDiff.normalizationVersion(), resDiff.parserVersion(), resDiff.languagePolicy(),
				resDiff.beforeSnapshot(), resDiff.afterSnapshot(), resDiff.entityFacts(),
				resDiff.initialInventory(), resDiff.beforeContext(), resDiff.afterContext(),
				resDiff.fileComparison(), new StructuralDiff.CoverageSummary(false, List.of("residual gap")),
				List.of(
						new DiffDiagnostic(SideRole.AFTER, DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE,
								com.livingdocs.analysis.ast.JavaParseResult.Severity.INFO, "Residual span",
								Optional.of(new SnapshotRef(REPO, SHA_C)), Optional.of(resPath),
								Optional.of(new SourceRange(1, 1, 1, 9)), Optional.empty()),
						new DiffDiagnostic(SideRole.INPUT, DiagnosticCode.MIXED_LANGUAGE_SCOPE,
								com.livingdocs.analysis.ast.JavaParseResult.Severity.INFO, "Input diag",
								Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));

		var mixedLookups = ImpactClassifier.buildLookupKeys(ctx, List.of(residualWithDiag)).stream()
				.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty())).toList();
		var mixedReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(unavailDiff, residualWithDiag), mixedLookups, List.of(), List.of(), List.of()));
		var mixedProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, unavailPath, "class Missing {}\n"),
				unavailableProof(SideRole.AFTER, SHA_C, unavailPath, "s02-503"),
				presentProof(SideRole.BEFORE, SHA_B, resPath, resBefore),
				presentProof(SideRole.AFTER, SHA_C, resPath, resAfter));

		EvidenceResult mixedEv = mapper.map(emptyInput(
				ctx, mixedReport, List.of(unavailDiff, residualWithDiag), mixedProofs, mixedLookups));
		assertTrue(mixedEv.diagnostics().stream().anyMatch(d ->
				d.diffCode().equals(Optional.of(DiagnosticCode.SNAPSHOT_UNAVAILABLE))
						&& d.location().map(l -> l.basis() == SourceBasis.SNAPSHOT_UNAVAILABLE).orElse(false)));
		assertTrue(mixedEv.diagnostics().stream().anyMatch(d ->
				d.diffCode().equals(Optional.of(DiagnosticCode.RESIDUAL_STRUCTURAL_CHANGE))
						&& d.location().map(l -> l.basis() == SourceBasis.DIAGNOSTIC_RANGE
								&& l.ranges().equals(List.of(new SourceRange(1, 1, 1, 9)))).orElse(false)));
		assertTrue(mixedEv.diagnostics().stream().anyMatch(d ->
				d.diffCode().equals(Optional.of(DiagnosticCode.MIXED_LANGUAGE_SCOPE))
						&& d.location().isEmpty()));
	}

	@Test
	@DisplayName("M4-RV-14: UNCHANGED_VERIFIED, WHITESPACE_ONLY_VERIFIED, and EMPTY_VERIFIED_SCOPE positive and negative checks")
	void rv14UnchangedWhitespaceAndEmptyVerifiedScope() {
		String path = "src/Same.java";
		String sameSrc = "class Same { int x() { return 1; } }\n";
		String wsSrc = "class Same {   int x() { return 1; }   }\n";
		var ctxPolicyOn = branchContext(SHA_B, SHA_C, true);
		var ctxPolicyOff = branchContext(SHA_B, SHA_C, false);

		// 1. UNCHANGED_VERIFIED.
		var unchangedDiff = compareJava(path, SHA_B, sameSrc, path, SHA_C, sameSrc);
		var fileLookups = List.of(
				completeEmptyFileLookup(ctxPolicyOn, SideRole.BEFORE, SHA_B, path),
				completeEmptyFileLookup(ctxPolicyOn, SideRole.AFTER, SHA_C, path));
		var unchangedReport = classifier.classify(new ImpactClassificationInput(
				ctxPolicyOn, List.of(unchangedDiff), fileLookups, List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), unchangedReport.overallOutcome());
		var unchangedProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, sameSrc),
				presentProof(SideRole.AFTER, SHA_C, path, sameSrc));
		EvidenceResult unchVerified = mapper.map(emptyInput(
				ctxPolicyOn, unchangedReport, List.of(unchangedDiff), unchangedProofs, fileLookups));
		assertEquals(EvidenceStatus.VERIFIED, unchVerified.status());
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), unchVerified.overallOutcome());

		// Stale claim present on SYMBOL lookup -> UNCHANGED_VERIFIED rejected!
		SymbolKey xKey = new SymbolKey(Kind.METHOD, Optional.empty(), List.of("Same"), "x", List.of());
		var staleClaim = new DocumentClaim(
				"claim-stale", "doc-s", "v1", SHA_A, SHA_C,
				"Same#x()", path, Optional.of(xKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.STALE_CLAIM_AGAINST_TARGET, "Stale", Optional.empty());
		var staleBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-s", "v1", SHA_A, "bind-s",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(xKey), Optional.empty(), List.of(staleClaim));
		var withStaleSymbolLookup = List.of(
				fileLookups.get(0), fileLookups.get(1),
				completeSymbolLookup(ctxPolicyOn, SideRole.AFTER, SHA_C, path, xKey, List.of(staleBinding)));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE),
				mapper.map(emptyInput(ctxPolicyOn, unchangedReport, List.of(unchangedDiff),
						unchangedProofs, withStaleSymbolLookup)).overallOutcome());

		// Missing FILE lookup -> rejected!
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE),
				mapper.map(emptyInput(ctxPolicyOn, unchangedReport, List.of(unchangedDiff),
						unchangedProofs, List.of(fileLookups.get(0)))).overallOutcome());

		// 2. WHITESPACE_ONLY_VERIFIED.
		var wsDiff = compareJava(path, SHA_B, sameSrc, path, SHA_C, wsSrc);
		var wsReport = classifier.classify(new ImpactClassificationInput(
				ctxPolicyOn, List.of(wsDiff), fileLookups, List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), wsReport.overallOutcome());
		var wsProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, path, sameSrc),
				presentProof(SideRole.AFTER, SHA_C, path, wsSrc));
		assertEquals(EvidenceStatus.VERIFIED,
				mapper.map(emptyInput(ctxPolicyOn, wsReport, List.of(wsDiff), wsProofs, fileLookups)).status());

		// Policy off report vs WS selector rejected!
		var policyOffReport = new ImpactReport(
				wsReport.classifierVersion(), wsReport.processingStatus(), wsReport.overallOutcome(),
				ctxPolicyOff, wsReport.findings(), wsReport.coverage(), wsReport.diagnostics());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE),
				mapper.map(emptyInput(ctxPolicyOff, policyOffReport, List.of(wsDiff), wsProofs, fileLookups)).overallOutcome());

		// 3. EMPTY_VERIFIED_SCOPE.
		var manifest = new ScopeManifestRef(
				"manifest-1", REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				Intent.REVISION_COMPARISON, Optional.of(SHA_B), SHA_C, 0);
		var emptyCtx = new AssessmentContext(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", Optional.of("cfg-1"), SHA_C, Optional.of(SHA_B),
				Intent.REVISION_COMPARISON, ScopeCompleteness.EMPTY_VERIFIED, false, Optional.of(manifest));
		var emptyScopeReport = classifier.classify(new ImpactClassificationInput(
				emptyCtx, List.of(), List.of(), List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), emptyScopeReport.overallOutcome());

		EvidenceResult emptyScopeOk = mapper.map(new EvidenceInput(
				Optional.of(emptyCtx), emptyScopeReport, List.of(), List.of(), List.of(),
				List.of(), List.of(manifest), List.of(), List.of()));
		assertEquals(EvidenceStatus.VERIFIED, emptyScopeOk.status());
		assertTrue(emptyScopeOk.findings().getFirst().witnesses().getFirst().sourceLocators().isEmpty());

		// Missing manifest / nonzero eligibleFileCount / non-empty diffs -> downgraded!
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE),
				mapper.map(emptyInput(emptyCtx, emptyScopeReport, List.of(), List.of(), List.of())).overallOutcome());
		var badManifest = new ScopeManifestRef(
				"manifest-1", REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				Intent.REVISION_COMPARISON, Optional.of(SHA_B), SHA_C, 2);
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE),
				mapper.map(new EvidenceInput(Optional.of(emptyCtx), emptyScopeReport, List.of(), List.of(),
						List.of(), List.of(), List.of(badManifest), List.of(), List.of())).overallOutcome());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE),
				mapper.map(new EvidenceInput(Optional.of(emptyCtx), emptyScopeReport, List.of(unchangedDiff),
						unchangedProofs, List.of(), List.of(), List.of(manifest), List.of(), List.of())).overallOutcome());
	}

	@Test
	@DisplayName("M4-RV-15: Exclusions (NON_JAVA_OUT_OF_SCOPE, CALLER_EXCLUSION_RULE, CONTROLLED_EXPORT_PROVENANCE symbol & whole-file) and coverage recomputation")
	void rv15ExclusionsAndCoverageRecomputation() throws Exception {
		var ctx = branchContext(SHA_B, SHA_C, false);

		// 1. NON_JAVA_OUT_OF_SCOPE verified on both non-Java sides; mixed Java/non-Java cannot verify!
		String pyPath = "scripts/build.py";
		var pyDiff = diff.compare(new StructuralDiffInput(
				REPO, Intent.REVISION_COMPARISON,
				Optional.of(new Present(new SnapshotRef(REPO, SHA_B), new SourceInput(pyPath, "print(1)"))),
				new Present(new SnapshotRef(REPO, SHA_C), new SourceInput(pyPath, "print(2)")),
				Optional.empty()));
		var pyReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(pyDiff), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.OUT_OF_SCOPE, pyReport.processingStatus());
		var pyProofs = List.of(
				presentIdentityProof(SideRole.BEFORE, SHA_B, pyPath),
				presentIdentityProof(SideRole.AFTER, SHA_C, pyPath));
		EvidenceResult pyVerified = mapper.map(emptyInput(ctx, pyReport, List.of(pyDiff), pyProofs, List.of()));
		assertEquals(EvidenceStatus.VERIFIED, pyVerified.status());
		assertEquals(Optional.of(ReportProcessingStatus.OUT_OF_SCOPE), pyVerified.processingStatus());
		assertTrue(pyVerified.overallOutcome().isEmpty());

		// Mixed Java -> non-Java diff swapped in -> NON_JAVA_OUT_OF_SCOPE rejected!
		var mixedDiff = compareJava("src/A.java", SHA_B, "class A {}", pyPath, SHA_C, "print(2)");
		var mixedProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, "src/A.java", "class A {}"),
				presentIdentityProof(SideRole.AFTER, SHA_C, pyPath));
		EvidenceResult mixedRejected = mapper.map(emptyInput(ctx, pyReport, List.of(mixedDiff), mixedProofs, List.of()));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), mixedRejected.overallOutcome());

		// 2. CALLER_EXCLUSION_RULE (two files excluded, mutate 2nd rule -> excluded=1, failedOrIncomplete=1).
		String gen1 = "src/Gen1.java";
		String gen2 = "src/Gen2.java";
		String genSrc1B = "class Gen1 {}\n";
		String genSrc1A = "class Gen1 { int a; }\n";
		String genSrc2B = "class Gen2 {}\n";
		String genSrc2A = "class Gen2 { int b; }\n";
		var d1 = compareJava(gen1, SHA_B, genSrc1B, gen1, SHA_C, genSrc1A);
		var d2 = compareJava(gen2, SHA_B, genSrc2B, gen2, SHA_C, genSrc2A);
		var rule1 = new CallerExclusion(REPO, SHA_C, gen1, "rule-1", "SECRET_REASON_1");
		var rule2 = new CallerExclusion(REPO, SHA_C, gen2, "rule-2", "SECRET_REASON_2");
		var exclReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(d1, d2), List.of(), List.of(), List.of(), List.of(rule1, rule2)));
		var exclProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, gen1, genSrc1B),
				presentProof(SideRole.AFTER, SHA_C, gen1, genSrc1A),
				presentProof(SideRole.BEFORE, SHA_B, gen2, genSrc2B),
				presentProof(SideRole.AFTER, SHA_C, gen2, genSrc2A));
		EvidenceResult exclPartial = mapper.map(new EvidenceInput(
				Optional.of(ctx), exclReport, List.of(d1, d2), exclProofs, List.of(),
				List.of(), List.of(), List.of(rule1), List.of()));
		assertEquals(EvidenceStatus.PARTIAL, exclPartial.status());
		assertEquals(Optional.of(new Coverage(0, 1, 1, false)), exclPartial.coverage());
		assertFalse(exclPartial.toString().contains("SECRET_REASON_1"));

		// 3. CONTROLLED_EXPORT_PROVENANCE — whole file (both present & file add) and symbol-level.
		String wfPath = "src/Exported.java";
		String wfB = "class Exported { int x = 1; }\n";
		String wfA = "class Exported { int x = 2; }\n";
		var wfDiff = compareJava(wfPath, SHA_B, wfB, wfPath, SHA_C, wfA);
		var wfExport = ControlledExportUnit.forWholeFile(
				REPO, Optional.of(SHA_B), SHA_C, wfPath,
				Optional.of(sha256(wfB)), Optional.of(sha256(wfA)), "exp-wf-1");
		var wfReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(wfDiff), List.of(), List.of(), List.of(wfExport), List.of()));
		var wfProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, wfPath, wfB),
				presentProof(SideRole.AFTER, SHA_C, wfPath, wfA));
		EvidenceResult wfVerified = mapper.map(new EvidenceInput(
				Optional.of(ctx), wfReport, List.of(wfDiff), wfProofs, List.of(),
				List.of(), List.of(), List.of(), List.of(wfExport)));
		assertEquals(EvidenceStatus.VERIFIED, wfVerified.status());
		assertEquals(Optional.of(ReportProcessingStatus.OUT_OF_SCOPE), wfVerified.processingStatus());

		// Wrong hash (manual edit) -> downgraded!
		var badWfExport = ControlledExportUnit.forWholeFile(
				REPO, Optional.of(SHA_B), SHA_C, wfPath,
				Optional.of(sha256(wfB)), Optional.of(sha256("class Exported { int x = 999; }\n")), "exp-wf-1");
		EvidenceResult wfBadHash = mapper.map(new EvidenceInput(
				Optional.of(ctx), wfReport, List.of(wfDiff), wfProofs, List.of(),
				List.of(), List.of(), List.of(), List.of(badWfExport)));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), wfBadHash.overallOutcome());
		assertEquals(Optional.of(new Coverage(0, 0, 1, false)), wfBadHash.coverage());

		// Whole-file added export (BEFORE ABSENT).
		String addExpPath = "src/AddedExport.java";
		String addExpSrc = "class AddedExport {}\n";
		var addExpDiff = diff.compare(new StructuralDiffInput(
				REPO, Intent.REVISION_COMPARISON,
				Optional.of(new Absent(new SnapshotRef(REPO, SHA_B), addExpPath, "absent")),
				new Present(new SnapshotRef(REPO, SHA_C), new SourceInput(addExpPath, addExpSrc)),
				Optional.empty()));
		var addExp = ControlledExportUnit.forWholeFile(
				REPO, Optional.of(SHA_B), SHA_C, addExpPath,
				Optional.empty(), Optional.of(sha256(addExpSrc)), "exp-add-1");
		var addExpReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(addExpDiff), List.of(), List.of(), List.of(addExp), List.of()));
		var addExpProofs = List.of(
				absentProof(SideRole.BEFORE, SHA_B, addExpPath, "absent"),
				presentProof(SideRole.AFTER, SHA_C, addExpPath, addExpSrc));
		assertEquals(EvidenceStatus.VERIFIED, mapper.map(new EvidenceInput(
				Optional.of(ctx), addExpReport, List.of(addExpDiff), addExpProofs, List.of(),
				List.of(), List.of(), List.of(), List.of(addExp))).status());

		// Symbol-level export alongside manual edit on another symbol.
		String symExpPath = "src/MixedExport.java";
		String symB = "class MixedExport {\n    int generated() { return 1; }\n    int manual() { return 1; }\n}\n";
		String symA = "class MixedExport {\n    int generated() { return 2; }\n    int manual() { return 2; }\n}\n";
		var symDiff = compareJava(symExpPath, SHA_B, symB, symExpPath, SHA_C, symA);
		SymbolKey genKey = changedSymbolKey(symDiff, "generated");
		var symExport = ControlledExportUnit.forSymbol(REPO, SHA_C, symExpPath, genKey, "exp-sym-1");
		var symLookups = ImpactClassifier.buildLookupKeys(ctx, List.of(symDiff)).stream()
				.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty())).toList();
		var symReport = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(symDiff), symLookups, List.of(), List.of(symExport), List.of()));
		var symProofs = List.of(
				presentProof(SideRole.BEFORE, SHA_B, symExpPath, symB),
				presentProof(SideRole.AFTER, SHA_C, symExpPath, symA));
		EvidenceResult symVerified = mapper.map(new EvidenceInput(
				Optional.of(ctx), symReport, List.of(symDiff), symProofs, symLookups,
				List.of(), List.of(), List.of(), List.of(symExport)));
		assertEquals(EvidenceStatus.VERIFIED, symVerified.status());
		assertTrue(symVerified.findings().stream().anyMatch(f -> f.disposition() == UnitDisposition.EXCLUDED));
		assertTrue(symVerified.findings().stream().anyMatch(f -> f.disposition() == UnitDisposition.EVALUATED
				&& f.outcome() == ImpactOutcome.NO_IMPACT));
	}

	// ---- Test Helpers ----

	private EvidenceInput emptyInput(
			AssessmentContext ctx,
			ImpactReport report,
			List<StructuralDiffResult> diffs,
			List<SnapshotProof> proofs,
			List<DocumentLookupResponse> lookups) {
		return new EvidenceInput(
				Optional.of(ctx), report, diffs, proofs, lookups,
				List.of(), List.of(), List.of(), List.of());
	}

	private StructuralDiffResult compareJava(
			String beforePath, String beforeSha, String beforeSource,
			String afterPath, String afterSha, String afterSource) {
		Optional<FileAssociation> assoc = beforePath.equals(afterPath)
				? Optional.empty()
				: Optional.of(new FileAssociation(beforePath, afterPath));
		return diff.compare(new StructuralDiffInput(
				REPO,
				Intent.REVISION_COMPARISON,
				Optional.of(new Present(new SnapshotRef(REPO, beforeSha), new SourceInput(beforePath, beforeSource))),
				new Present(new SnapshotRef(REPO, afterSha), new SourceInput(afterPath, afterSource)),
				assoc));
	}

	private static AssessmentContext branchContext(String beforeSha, String afterSha, boolean whitespacePolicy) {
		return new AssessmentContext(
				TENANT, WORKSPACE, REPO,
				TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", Optional.of("cfg-1"),
				afterSha, Optional.of(beforeSha),
				Intent.REVISION_COMPARISON, ScopeCompleteness.COMPLETE, whitespacePolicy);
	}

	private static AssessmentContext initialContext(String targetSha, boolean whitespacePolicy) {
		return new AssessmentContext(
				TENANT, WORKSPACE, REPO,
				TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", Optional.of("cfg-1"),
				targetSha, Optional.empty(),
				Intent.INITIAL_ASSESSMENT, ScopeCompleteness.COMPLETE, whitespacePolicy);
	}

	private static AssessmentContext contextWith(AssessmentContext ctx) {
		return ctx;
	}

	private static SnapshotProof presentProof(SideRole role, String sha, String path, String content) {
		return new SnapshotProof(
				role,
				new SnapshotProvenance(new SnapshotRef(REPO, sha), path, SidePresence.PRESENT,
						Optional.empty(), Optional.empty()),
				Optional.of(content));
	}

	private static SnapshotProof presentIdentityProof(SideRole role, String sha, String path) {
		return new SnapshotProof(
				role,
				new SnapshotProvenance(new SnapshotRef(REPO, sha), path, SidePresence.PRESENT,
						Optional.empty(), Optional.empty()),
				Optional.empty());
	}

	private static SnapshotProof absentProof(SideRole role, String sha, String path, String reason) {
		return new SnapshotProof(
				role,
				new SnapshotProvenance(new SnapshotRef(REPO, sha), path, SidePresence.ABSENT,
						Optional.of(reason), Optional.empty()),
				Optional.empty());
	}

	private static SnapshotProof unavailableProof(SideRole role, String sha, String path, String diag) {
		return new SnapshotProof(
				role,
				new SnapshotProvenance(new SnapshotRef(REPO, sha), path, SidePresence.UNAVAILABLE,
						Optional.empty(), Optional.of(diag)),
				Optional.empty());
	}

	private static SymbolKey changedSymbolKey(StructuralDiffResult result, String name) {
		return result.entityFacts().stream()
				.filter(f -> f.change() != ChangeKind.UNCHANGED)
				.map(f -> f.before().map(EntitySide::key).orElseGet(() -> f.after().orElseThrow().key()))
				.filter(k -> k.name().equals(name))
				.findFirst()
				.orElseThrow();
	}

	private static SymbolKey changedSymbolKey(StructuralDiffResult result, String name, ChangeKind change) {
		return result.entityFacts().stream()
				.filter(f -> f.change() == change)
				.map(f -> f.before().map(EntitySide::key).orElseGet(() -> f.after().orElseThrow().key()))
				.filter(k -> k.name().equals(name))
				.findFirst()
				.orElseThrow();
	}

	private static EntitySide entitySide(StructuralDiffResult result, SideRole role, SymbolKey key) {
		return result.entityFacts().stream()
				.map(f -> role == SideRole.BEFORE ? f.before() : f.after())
				.flatMap(Optional::stream)
				.filter(s -> s.key().equals(key))
				.findFirst()
				.orElseThrow();
	}

	private static StructuralDiffResult mutateEntityRange(
			StructuralDiffResult diffRes, SideRole role, SymbolKey key, FacetKind facet, ProvenanceRef replacement) {
		var mutatedFacts = diffRes.entityFacts().stream().map(fact -> {
			Optional<EntitySide> sideOpt = role == SideRole.BEFORE ? fact.before() : fact.after();
			if (sideOpt.isEmpty() || !sideOpt.get().key().equals(key)) {
				return fact;
			}
			EntitySide s = sideOpt.get();
			var refs = new java.util.EnumMap<FacetKind, List<ProvenanceRef>>(s.facetReferences());
			refs.put(facet, List.of(replacement));
			var next = new EntitySide(
					s.snapshot(), s.path(), s.key(), s.declarationRef(), s.headerRef(),
					s.sharedDeclarationRef(), s.sharedPrefixRef(), s.bodyRef(),
					s.initializerRef(), s.annotationDefaultRef(), refs, s.facetFingerprints());
			return role == SideRole.BEFORE
					? new EntityFact(fact.change(), Optional.of(next), fact.after(),
							fact.changedFacets(), fact.matchBasis(), fact.unmatchedReason(), fact.normalizationVersion())
					: new EntityFact(fact.change(), fact.before(), Optional.of(next),
							fact.changedFacets(), fact.matchBasis(), fact.unmatchedReason(), fact.normalizationVersion());
		}).toList();
		return new StructuralDiffResult(
				diffRes.status(), diffRes.intent(), diffRes.repository(),
				diffRes.normalizationVersion(), diffRes.parserVersion(), diffRes.languagePolicy(),
				diffRes.beforeSnapshot(), diffRes.afterSnapshot(), mutatedFacts,
				diffRes.initialInventory(), diffRes.beforeContext(), diffRes.afterContext(),
				diffRes.fileComparison(), diffRes.coverage(), diffRes.diagnostics());
	}

	private static DocumentLookupKey fileLookupKey(AssessmentContext ctx, SideRole role, String sha, String path) {
		return new DocumentLookupKey(ctx.tenantId(), ctx.workspaceId(), ctx.repository(),
				ctx.targetKind(), ctx.targetRef(), role, sha, path, LookupGranularity.FILE, Optional.empty());
	}

	private static DocumentLookupKey symbolLookupKey(
			AssessmentContext ctx, SideRole role, String sha, String path, SymbolKey key) {
		return new DocumentLookupKey(ctx.tenantId(), ctx.workspaceId(), ctx.repository(),
				ctx.targetKind(), ctx.targetRef(), role, sha, path, LookupGranularity.SYMBOL, Optional.of(key));
	}

	private static List<DocumentLookupResponse> completeRevisionLookups(
			AssessmentContext context, StructuralDiffResult result,
			DocumentLookupKey keyWithBinding, DocumentBinding binding) {
		return ImpactClassifier.buildLookupKeys(context, List.of(result)).stream()
				.map(key -> new DocumentLookupResponse(key, LookupCompleteness.COMPLETE,
						key.equals(keyWithBinding) ? List.of(binding) : List.of(), Optional.empty()))
				.toList();
	}

	private static DocumentLookupResponse completeSymbolLookup(
			AssessmentContext ctx, SideRole role, String sha, String path,
			SymbolKey key, List<DocumentBinding> bindings) {
		return new DocumentLookupResponse(
				symbolLookupKey(ctx, role, sha, path, key),
				LookupCompleteness.COMPLETE, bindings, Optional.empty());
	}

	private static DocumentLookupResponse completeFileLookup(
			AssessmentContext ctx, SideRole role, String sha, String path, List<DocumentBinding> bindings) {
		return new DocumentLookupResponse(
				fileLookupKey(ctx, role, sha, path),
				LookupCompleteness.COMPLETE, bindings, Optional.empty());
	}

	private static DocumentLookupResponse completeEmptyFileLookup(
			AssessmentContext ctx, SideRole role, String sha, String path) {
		return completeFileLookup(ctx, role, sha, path, List.of());
	}

	private static String sha256(String content) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest);
	}
}
