package com.livingdocs.analysis.impact;

import static com.livingdocs.analysis.impact.ImpactClassifier.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.livingdocs.analysis.ast.JavaParseResult.Kind;
import com.livingdocs.analysis.ast.JavaParseResult.SourceRange;
import com.livingdocs.analysis.ast.JavaParseResult.SymbolKey;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FacetKind;
import com.livingdocs.analysis.ast.SourceInput;
import com.livingdocs.analysis.diff.StructuralDiff;
import com.livingdocs.analysis.diff.StructuralDiff.ChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.ContextChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.ProcessingStatus;
import com.livingdocs.analysis.diff.StructuralDiff.RawChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.SideRole;
import com.livingdocs.analysis.diff.StructuralDiff.StructuralDiffResult;
import com.livingdocs.analysis.diff.StructuralDiff.StructuralEquality;
import com.livingdocs.analysis.diff.StructuralDiffInput;
import com.livingdocs.analysis.diff.StructuralDiffInput.Absent;
import com.livingdocs.analysis.diff.StructuralDiffInput.FileAssociation;
import com.livingdocs.analysis.diff.StructuralDiffInput.Intent;
import com.livingdocs.analysis.diff.StructuralDiffInput.Present;
import com.livingdocs.analysis.diff.StructuralDiffInput.SnapshotRef;
import com.livingdocs.analysis.diff.StructuralDiffInput.Unavailable;

class ImpactClassifierTest {

	private static final String TENANT = "tenant-1";
	private static final String WORKSPACE = "ws-1";
	private static final String REPO = "repo-payments";
	private static final String SHA_A = "sha-aaa";
	private static final String SHA_B = "sha-bbb";
	private static final String SHA_C = "sha-ccc";

	private final StructuralDiff diff = new StructuralDiff();
	private final ImpactClassifier classifier = new ImpactClassifier();

	@Test
	void rv01ChangedSignatureWithMappingLinkOnlyIsInconclusiveWhileVerifiedClaimProducesSignatureActionable() {
		String beforeSrc = """
				package com.example;
				public class PriceService {
				    public Number price(String sku) { return null; }
				}
				""";
		String afterSrc = """
				package com.example;
				public class PriceService {
				    public String price(String sku) { return null; }
				}
				""";
		var diffResult = compareJava("src/PriceService.java", SHA_B, beforeSrc, "src/PriceService.java", SHA_C, afterSrc);
		assertEquals(ProcessingStatus.COMPLETE, diffResult.status());
		SymbolKey priceKey = changedSymbolKey(diffResult, "price");

		var context = branchContext(SHA_B, SHA_C, false);
		var beforeFileEmpty = completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/PriceService.java");
		var afterFileEmpty = completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/PriceService.java");

		// 1. Mapping link only (missing verified claim at target SHA_C) -> INCONCLUSIVE, no DETECTED admission.
		var linkOnlyBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-pricing", "v3", SHA_A, "bind-1",
				BindingGranularity.EXACT_SYMBOL, "src/PriceService.java", Optional.of(priceKey),
				Optional.of("sec-api"), List.of());
		var lookupLinkOnly = completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/PriceService.java",
				priceKey, List.of(linkOnlyBinding));
		var beforeEmpty = completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/PriceService.java", priceKey);

		var linkOnlyReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffResult),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, lookupLinkOnly),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, linkOnlyReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), linkOnlyReport.overallOutcome());
		assertEquals(1, linkOnlyReport.findings().size());
		var linkFinding = linkOnlyReport.findings().getFirst();
		assertEquals(ImpactOutcome.INCONCLUSIVE, linkFinding.outcome());
		assertTrue(linkFinding.classification().isEmpty());
		assertEquals(IssueAdmission.INSUFFICIENT_EVIDENCE, linkFinding.admission());
		assertEquals(ReasonCode.DOCUMENT_CLAIM_MISSING_OR_AMBIGUOUS, linkFinding.primaryReason());

		// 2. Verified declaration claim at target SHA_C -> SIGNATURE / ACTIONABLE / SUPPORTED_BY_INITIAL_EVIDENCE.
		var verifiedClaim = new DocumentClaim(
				"claim-price-return", "doc-pricing", "v3", SHA_A, SHA_C,
				"PriceService#price returns Number", "src/PriceService.java",
				Optional.of(priceKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE,
				"Document v3 states price(String) returns Number, incompatible with String at target",
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Breaking return type change for consumers", "public-api")));
		var verifiedBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-pricing", "v3", SHA_A, "bind-1",
				BindingGranularity.EXACT_SYMBOL, "src/PriceService.java", Optional.of(priceKey),
				Optional.of("sec-api"), List.of(verifiedClaim));
		var lookupVerified = completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/PriceService.java",
				priceKey, List.of(verifiedBinding));

		var verifiedReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffResult),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, lookupVerified),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, verifiedReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), verifiedReport.overallOutcome());
		assertEquals(1, verifiedReport.findings().size());
		var sigFinding = verifiedReport.findings().getFirst();
		assertEquals(ImpactOutcome.ACTIONABLE, sigFinding.outcome());
		assertEquals(Optional.of(DriftClassification.SIGNATURE), sigFinding.classification());
		assertEquals(Optional.of(Severity.HIGH), sigFinding.severity());
		assertTrue(sigFinding.semanticConfidence().isEmpty());
		assertEquals(IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE, sigFinding.admission());
		assertEquals(Optional.of("doc-pricing"), sigFinding.documentId());
		assertEquals(Optional.of("v3"), sigFinding.documentVersion());
		assertEquals(Optional.of(SHA_A), sigFinding.documentBoundSha());
		assertEquals(Optional.of("bind-1"), sigFinding.bindingId());
		assertEquals(Optional.of("claim-price-return"), sigFinding.claimId());
		assertEquals(3, sigFinding.requiredImpactWitnessIds().size());
	}

	@Test
	void rv02RemovedAndRenamedSymbolsLookupBothSidesAndHandleOverloadAmbiguityConservatively() {
		String beforeSrc = """
				package com.example;
				public class BillingService {
				    public void charge(int cents) { }
				    public void legacyRefund() { }
				}
				""";
		String afterSrc = """
				package com.example;
				public class BillingService {
				    public void charge(long cents) { }
				}
				""";
		var diffResult = compareJava("src/OldBillingService.java", SHA_B, beforeSrc,
				"src/NewBillingService.java", SHA_C, afterSrc);
		var context = branchContext(SHA_B, SHA_C, false);

		var generatedKeys = ImpactClassifier.buildLookupKeys(context, List.of(diffResult));
		assertTrue(generatedKeys.stream().anyMatch(k -> k.sideRole() == SideRole.BEFORE
				&& k.path().equals("src/OldBillingService.java") && k.granularity() == LookupGranularity.FILE));
		assertTrue(generatedKeys.stream().anyMatch(k -> k.sideRole() == SideRole.AFTER
				&& k.path().equals("src/NewBillingService.java") && k.granularity() == LookupGranularity.FILE));

		SymbolKey billingClassKey = changedSymbolKey(diffResult, "BillingService", ChangeKind.MODIFIED);
		SymbolKey oldChargeKey = changedSymbolKey(diffResult, "charge", ChangeKind.REMOVED);
		SymbolKey newChargeKey = changedSymbolKey(diffResult, "charge", ChangeKind.ADDED);
		SymbolKey legacyRefundKey = changedSymbolKey(diffResult, "legacyRefund", ChangeKind.REMOVED);
		assertEquals(10, generatedKeys.size());

		assertTrue(generatedKeys.stream().anyMatch(k -> k.sideRole() == SideRole.BEFORE
				&& k.symbolKey().equals(Optional.of(oldChargeKey))));
		assertTrue(generatedKeys.stream().anyMatch(k -> k.sideRole() == SideRole.AFTER
				&& k.symbolKey().equals(Optional.of(oldChargeKey))));

		// 1. legacyRefund is unambiguously removed and referenced by doc -> REFERENTIAL ACTIONABLE.
		var refundClaim = new DocumentClaim(
				"claim-refund", "doc-billing", "v2", SHA_A, SHA_C,
				"BillingService#legacyRefund()", "src/OldBillingService.java",
				Optional.of(legacyRefundKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.REFERENCED_ENTITY_REQUIRED,
				"Document references legacyRefund() which no longer exists at target",
				Optional.of(new VerifiedImpactContext(Severity.MEDIUM, "Dead API reference in guide", "billing-guide")));
		var refundBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-billing", "v2", SHA_A, "bind-refund",
				BindingGranularity.EXACT_SYMBOL, "src/OldBillingService.java", Optional.of(legacyRefundKey),
				Optional.empty(), List.of(refundClaim));

		// 2. charge(int) removed while charge(long) added; claim lacks exact symbol disambiguation -> AMBIGUOUS_SYMBOL_COUNTERPART.
		var ambiguousChargeClaim = new DocumentClaim(
				"claim-charge-ambig", "doc-billing", "v2", SHA_A, SHA_C,
				"BillingService#charge", "src/OldBillingService.java",
				Optional.empty(), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.REFERENCED_ENTITY_REQUIRED,
				"Document names charge method without disambiguating overload replacement",
				Optional.empty());
		var chargeBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-billing", "v2", SHA_A, "bind-charge",
				BindingGranularity.EXACT_SYMBOL, "src/OldBillingService.java", Optional.of(oldChargeKey),
				Optional.empty(), List.of(ambiguousChargeClaim));

		var responses = List.of(
				completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/OldBillingService.java"),
				completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/NewBillingService.java"),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/OldBillingService.java", billingClassKey),
				completeEmptySymbolLookup(context, SideRole.AFTER, SHA_C, "src/NewBillingService.java", billingClassKey),
				completeSymbolLookup(context, SideRole.BEFORE, SHA_B, "src/OldBillingService.java",
						legacyRefundKey, List.of(refundBinding)),
				completeEmptySymbolLookup(context, SideRole.AFTER, SHA_C, "src/NewBillingService.java", legacyRefundKey),
				completeSymbolLookup(context, SideRole.BEFORE, SHA_B, "src/OldBillingService.java",
						oldChargeKey, List.of(chargeBinding)),
				completeEmptySymbolLookup(context, SideRole.AFTER, SHA_C, "src/NewBillingService.java", oldChargeKey),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/OldBillingService.java", newChargeKey),
				completeEmptySymbolLookup(context, SideRole.AFTER, SHA_C, "src/NewBillingService.java", newChargeKey)
		);

		var report = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffResult), responses, List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, report.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), report.overallOutcome());

		var refundFinding = report.findings().stream()
				.filter(f -> f.symbolKey().equals(Optional.of(legacyRefundKey)))
				.findFirst().orElseThrow();
		assertEquals(ImpactOutcome.ACTIONABLE, refundFinding.outcome());
		assertEquals(Optional.of(DriftClassification.REFERENTIAL), refundFinding.classification());
		assertEquals(ReasonCode.REFERENTIAL_MISSING_TARGET_SYMBOL, refundFinding.primaryReason());

		var chargeFinding = report.findings().stream()
				.filter(f -> f.symbolKey().equals(Optional.of(oldChargeKey)))
				.findFirst().orElseThrow();
		assertEquals(ImpactOutcome.INCONCLUSIVE, chargeFinding.outcome());
		assertEquals(ReasonCode.AMBIGUOUS_SYMBOL_COUNTERPART, chargeFinding.primaryReason());
		assertEquals(IssueAdmission.INSUFFICIENT_EVIDENCE, chargeFinding.admission());
	}

	@Test
	void rv03BodyOnlyChangeRequiresExactClaimMatchedSemanticAssessmentAndCoexistsWithSignatureMismatch() {
		String beforeSrc = """
				package com.example;
				public class FeeCalculator {
				    public int fee(int distance) { return distance * 5; }
				}
				""";
		String bodyOnlyAfterSrc = """
				package com.example;
				public class FeeCalculator {
				    public int fee(int distance) { return distance * 10; }
				}
				""";
		var bodyOnlyDiff = compareJava("src/FeeCalculator.java", SHA_B, beforeSrc,
				"src/FeeCalculator.java", SHA_C, bodyOnlyAfterSrc);
		SymbolKey feeKey = changedSymbolKey(bodyOnlyDiff, "fee");
		var context = branchContext(SHA_B, SHA_C, false);
		var beforeFileEmpty = completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/FeeCalculator.java");
		var afterFileEmpty = completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/FeeCalculator.java");

		var bodyFormulaClaim = new DocumentClaim(
				"claim-fee-formula", "doc-fee", "v1", SHA_A, SHA_C,
				"Fee formula: distance * 5", "src/FeeCalculator.java",
				Optional.of(feeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.UNVERIFIED_OR_AMBIGUOUS,
				"Formula stated in prose requires semantic check against target body",
				Optional.empty());
		var docBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-fee", "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, "src/FeeCalculator.java", Optional.of(feeKey),
				Optional.of("sec-fee"), List.of(bodyFormulaClaim));
		var beforeEmpty = completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/FeeCalculator.java", feeKey);
		var afterLookup = completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/FeeCalculator.java",
				feeKey, List.of(docBinding));

		// 1. No S05 input -> INCONCLUSIVE (never auto NO_IMPACT!).
		var noSemReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(bodyOnlyDiff),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, afterLookup),
				List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), noSemReport.overallOutcome());
		assertEquals(ReasonCode.BODY_CHANGED_NEEDS_SEMANTIC, noSemReport.findings().getFirst().primaryReason());

		// 2. Late / wrong SHA / ungrounded / mismatched claimId S05 -> all rejected -> INCONCLUSIVE.
		var lateSem = new SemanticAssessmentInput(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", SHA_C, "src/FeeCalculator.java", feeKey,
				"doc-fee", "v1", SHA_A, SemanticStatus.BEHAVIOR_MISMATCH, false,
				List.of(new GroundedSourceFacetRef(SideRole.AFTER, SHA_C, "src/FeeCalculator.java",
						feeKey, FacetKind.BODY, Optional.of(new SourceRange(3, 35, 3, 59)))),
				List.of(new GroundedDocumentClaimRef("doc-fee", "v1", SHA_A, "bind-fee", "claim-fee-formula", Optional.of("sec-fee"))),
				Optional.of(0.91), Optional.of("Late assessment"), Optional.empty());
		var lateReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(bodyOnlyDiff),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, afterLookup),
				List.of(lateSem), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.PARTIAL, lateReport.processingStatus());
		assertEquals(ReasonCode.SEMANTIC_LATE_OR_WRONG_ATTEMPT, lateReport.findings().getFirst().primaryReason());

		var wrongShaSem = new SemanticAssessmentInput(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", SHA_B, "src/FeeCalculator.java", feeKey,
				"doc-fee", "v1", SHA_A, SemanticStatus.BEHAVIOR_MISMATCH, true,
				List.of(new GroundedSourceFacetRef(SideRole.AFTER, SHA_B, "src/FeeCalculator.java",
						feeKey, FacetKind.BODY, Optional.empty())),
				List.of(new GroundedDocumentClaimRef("doc-fee", "v1", SHA_A, "bind-fee", "claim-fee-formula", Optional.empty())),
				Optional.of(0.90), Optional.of("Stale SHA"), Optional.empty());
		var wrongShaReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(bodyOnlyDiff),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, afterLookup),
				List.of(wrongShaSem), List.of(), List.of()));
		assertEquals(ReasonCode.SEMANTIC_WRONG_SHA_OR_VERSION, wrongShaReport.findings().getFirst().primaryReason());

		var wrongClaimIdSem = new SemanticAssessmentInput(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", SHA_C, "src/FeeCalculator.java", feeKey,
				"doc-fee", "v1", SHA_A, SemanticStatus.BEHAVIOR_MISMATCH, true,
				List.of(new GroundedSourceFacetRef(SideRole.AFTER, SHA_C, "src/FeeCalculator.java",
						feeKey, FacetKind.BODY, Optional.empty())),
				List.of(new GroundedDocumentClaimRef("doc-fee", "v1", SHA_A, "bind-fee", "non-existent-claim", Optional.empty())),
				Optional.of(0.90), Optional.of("Wrong claimId"), Optional.empty());
		var wrongClaimIdReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(bodyOnlyDiff),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, afterLookup),
				List.of(wrongClaimIdSem), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), wrongClaimIdReport.overallOutcome());
		assertEquals(ReasonCode.SEMANTIC_UNGROUNDED, wrongClaimIdReport.findings().getFirst().primaryReason());

		// 3. [P1 #1 Regression] Two behavioral claims on the SAME binding: S05 result grounded to claim-A
		// MUST NOT be misattributed to claim-B, and claim-A's finding witness MUST bind to claim-A!
		var claimA = new DocumentClaim(
				"claim-A-rate", "doc-fee", "v1", SHA_A, SHA_C,
				"Base rate multiplier is 5", "src/FeeCalculator.java",
				Optional.of(feeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.UNVERIFIED_OR_AMBIGUOUS, "Rate claim A", Optional.empty());
		var claimB = new DocumentClaim(
				"claim-B-rounding", "doc-fee", "v1", SHA_A, SHA_C,
				"Rounding mode rounds down", "src/FeeCalculator.java",
				Optional.of(feeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY, "Rounding claim B initial mismatch", Optional.empty());
		var twoClaimBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-fee", "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, "src/FeeCalculator.java", Optional.of(feeKey),
				Optional.of("sec-fee"), List.of(claimB, claimA));
		var twoClaimLookup = completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/FeeCalculator.java",
				feeKey, List.of(twoClaimBinding));
		var semOnlyForClaimA = new SemanticAssessmentInput(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", SHA_C, "src/FeeCalculator.java", feeKey,
				"doc-fee", "v1", SHA_A, SemanticStatus.BEHAVIOR_MISMATCH, true,
				List.of(new GroundedSourceFacetRef(SideRole.AFTER, SHA_C, "src/FeeCalculator.java",
						feeKey, FacetKind.BODY, Optional.of(new SourceRange(3, 35, 3, 59)))),
				List.of(new GroundedDocumentClaimRef("doc-fee", "v1", SHA_A, "bind-fee", "claim-A-rate", Optional.of("sec-fee"))),
				Optional.of(0.95), Optional.of("Multiplier changed from 5 to 10"), Optional.empty());

		var twoClaimReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(bodyOnlyDiff),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, twoClaimLookup),
				List.of(semOnlyForClaimA), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, twoClaimReport.processingStatus());
		assertEquals(2, twoClaimReport.findings().size());

		var findingForA = twoClaimReport.findings().stream()
				.filter(f -> f.claimId().equals(Optional.of("claim-A-rate")))
				.findFirst().orElseThrow();
		assertEquals(ImpactOutcome.ACTIONABLE, findingForA.outcome());
		assertEquals(Optional.of(DriftClassification.SEMANTIC), findingForA.classification());
		assertTrue(findingForA.witnesses().stream().anyMatch(w ->
				w instanceof DocumentWitnessSelector dw && dw.claimId().equals(Optional.of("claim-A-rate"))));
		assertTrue(findingForA.witnesses().stream().anyMatch(w ->
				w instanceof SemanticWitnessSelector sw && sw.claimId().equals("claim-A-rate")));

		var findingForB = twoClaimReport.findings().stream()
				.filter(f -> f.claimId().equals(Optional.of("claim-B-rounding")))
				.findFirst().orElseThrow();
		assertEquals(ImpactOutcome.INCONCLUSIVE, findingForB.outcome()); // Never hijacked by claim-A's S05 verdict!
		assertEquals(IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE, findingForB.admission());
		assertTrue(findingForB.witnesses().stream().noneMatch(w -> w instanceof SemanticWitnessSelector));

		// 4. Simultaneous DECLARATION + BODY change: DECLARATION_CONSISTENT never masks BODY, and both mismatches coexist.
		String bothChangedAfterSrc = """
				package com.example;
				public class FeeCalculator {
				    public long fee(int distance) { return distance * 10L; }
				}
				""";
		var bothChangedDiff = compareJava("src/FeeCalculator.java", SHA_B, beforeSrc,
				"src/FeeCalculator.java", SHA_C, bothChangedAfterSrc);
		var declConsistentClaim = new DocumentClaim(
				"claim-decl-ok", "doc-fee", "v1", SHA_A, SHA_C,
				"FeeCalculator#fee(int) declaration", "src/FeeCalculator.java",
				Optional.of(feeKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_CONSISTENT,
				"Declaration already updated to long", Optional.empty());
		var declIncompatibleClaim = new DocumentClaim(
				"claim-decl-mismatch", "doc-fee", "v1", SHA_A, SHA_C,
				"FeeCalculator#fee(int) returns int", "src/FeeCalculator.java",
				Optional.of(feeKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE,
				"Doc still states return type int",
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Incompatible return type", "fee-api")));

		var declConsistentBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-fee", "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, "src/FeeCalculator.java", Optional.of(feeKey),
				Optional.of("sec-fee"), List.of(declConsistentClaim));
		var declConsistentLookup = completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/FeeCalculator.java",
				feeKey, List.of(declConsistentBinding));
		var maskedCheckReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(bothChangedDiff),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, declConsistentLookup),
				List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), maskedCheckReport.overallOutcome());
		assertTrue(maskedCheckReport.findings().stream()
				.anyMatch(f -> f.evaluatedFacets().contains(FacetKind.DECLARATION) && f.outcome() == ImpactOutcome.NO_IMPACT));
		assertTrue(maskedCheckReport.findings().stream()
				.anyMatch(f -> f.evaluatedFacets().contains(FacetKind.BODY) && f.outcome() == ImpactOutcome.INCONCLUSIVE));

		var declAndBodyBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-fee", "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, "src/FeeCalculator.java", Optional.of(feeKey),
				Optional.of("sec-fee"), List.of(declIncompatibleClaim, bodyFormulaClaim));
		var declMismatchLookup = completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/FeeCalculator.java",
				feeKey, List.of(declAndBodyBinding));
		var validSem = new SemanticAssessmentInput(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", SHA_C, "src/FeeCalculator.java", feeKey,
				"doc-fee", "v1", SHA_A, SemanticStatus.BEHAVIOR_MISMATCH, true,
				List.of(new GroundedSourceFacetRef(SideRole.AFTER, SHA_C, "src/FeeCalculator.java",
						feeKey, FacetKind.BODY, Optional.of(new SourceRange(3, 36, 3, 61)))),
				List.of(new GroundedDocumentClaimRef("doc-fee", "v1", SHA_A, "bind-fee", "claim-fee-formula", Optional.of("sec-fee"))),
				Optional.of(0.92), Optional.of("Multiplier changed from 5 to 10 against documented formula"),
				Optional.of(new VerifiedImpactContext(Severity.CRITICAL, "Billing multiplier mismatch", "billing-core")));

		var bothReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(bothChangedDiff),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, declMismatchLookup),
				List.of(validSem), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, bothReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), bothReport.overallOutcome());
		assertEquals(2, bothReport.findings().size());
		assertTrue(bothReport.findings().stream()
				.anyMatch(f -> f.classification().equals(Optional.of(DriftClassification.SIGNATURE))
						&& f.claimId().equals(Optional.of("claim-decl-mismatch"))
						&& f.semanticConfidence().isEmpty()));
		assertTrue(bothReport.findings().stream()
				.anyMatch(f -> f.classification().equals(Optional.of(DriftClassification.SEMANTIC))
						&& f.claimId().equals(Optional.of("claim-fee-formula"))
						&& f.semanticConfidence().equals(Optional.of(0.92))
						&& f.severity().equals(Optional.of(Severity.CRITICAL))));
	}

	@Test
	void rv04InitialEvidenceSupportsDetectedWhileDeeperAnalysisIncompleteAndRevertDoesNotEmitLifecycleTransition() {
		String beforeSrc = """
				package com.example;
				public class TaxRule {
				    public int compute(int amount) { return amount / 10; }
				}
				""";
		String afterSrc = """
				package com.example;
				public class TaxRule {
				    public int compute(int amount) { return amount / 5; }
				}
				""";
		var bodyDiff = compareJava("src/TaxRule.java", SHA_B, beforeSrc, "src/TaxRule.java", SHA_C, afterSrc);
		SymbolKey computeKey = changedSymbolKey(bodyDiff, "compute");
		var context = branchContext(SHA_B, SHA_C, false);
		var beforeFileEmpty = completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/TaxRule.java");
		var afterFileEmpty = completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/TaxRule.java");

		// 1. Initial mismatch proof sufficient for DETECTED while deeper semantic is INCONCLUSIVE.
		var initialClaim = new DocumentClaim(
				"claim-tax-init", "doc-tax", "v2", SHA_A, SHA_C,
				"Tax rate documented as 10% in section 4", "src/TaxRule.java",
				Optional.of(computeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY,
				"Body divisor changed where doc describes fixed 10% rate; deeper semantic analysis pending",
				Optional.empty());
		var binding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-tax", "v2", SHA_A, "bind-tax",
				BindingGranularity.EXACT_SYMBOL, "src/TaxRule.java", Optional.of(computeKey),
				Optional.of("sec-4"), List.of(initialClaim));
		var beforeEmpty = completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/TaxRule.java", computeKey);
		var afterLookup = completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/TaxRule.java",
				computeKey, List.of(binding));
		var incompleteSem = new SemanticAssessmentInput(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", SHA_C, "src/TaxRule.java", computeKey,
				"doc-tax", "v2", SHA_A, SemanticStatus.INCONCLUSIVE, true,
				List.of(new GroundedSourceFacetRef(SideRole.AFTER, SHA_C, "src/TaxRule.java",
						computeKey, FacetKind.BODY, Optional.empty())),
				List.of(new GroundedDocumentClaimRef("doc-tax", "v2", SHA_A, "bind-tax", "claim-tax-init", Optional.of("sec-4"))),
				Optional.empty(), Optional.of("Context window truncated in S05"), Optional.empty());

		var initialReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(bodyDiff),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, afterLookup),
				List.of(incompleteSem), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), initialReport.overallOutcome());
		var initFinding = initialReport.findings().getFirst();
		assertEquals(ImpactOutcome.INCONCLUSIVE, initFinding.outcome());
		assertEquals(IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE, initFinding.admission());
		assertEquals(3, initFinding.requiredAdmissionWitnessIds().size());

		// 2. Revert source back to consistency (R2): records scoped NO_IMPACT observation, NOT_APPLICABLE admission, no lifecycle transition.
		var revertedClaim = new DocumentClaim(
				"claim-tax-reverted", "doc-tax", "v2", SHA_A, SHA_C,
				"Tax rate documented as 10% in section 4", "src/TaxRule.java",
				Optional.of(computeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.BEHAVIOR_CONSISTENT_PROVEN,
				"Source reverted to match documented 10% tax rate at target",
				Optional.empty());
		var revertedBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-tax", "v2", SHA_A, "bind-tax",
				BindingGranularity.EXACT_SYMBOL, "src/TaxRule.java", Optional.of(computeKey),
				Optional.of("sec-4"), List.of(revertedClaim));
		var revertedLookup = completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/TaxRule.java",
				computeKey, List.of(revertedBinding));

		var revertedReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(bodyDiff),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, revertedLookup),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, revertedReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), revertedReport.overallOutcome());
		var revFinding = revertedReport.findings().getFirst();
		assertEquals(ReasonCode.REVERTED_TO_CONSISTENT_NO_IMPACT, revFinding.primaryReason());
		assertEquals(IssueAdmission.NOT_APPLICABLE, revFinding.admission());
	}

	@Test
	void rv05EvaluatesOneToManyAndManyToOneBindingsPreservesDistinctClaimsRejectsFileOnlyAndSanitizesForeignScope() {
		String beforeSrc = """
				package com.example;
				public class OrderApi {
				    public Object submit(String id) { return null; }
				    public Object cancel(String id) { return null; }
				}
				""";
		String afterSrc = """
				package com.example;
				public class OrderApi {
				    public String submit(String id) { return null; }
				    public String cancel(String id) { return null; }
				}
				""";
		var diffResult = compareJava("src/OrderApi.java", SHA_B, beforeSrc, "src/OrderApi.java", SHA_C, afterSrc);
		SymbolKey submitKey = changedSymbolKey(diffResult, "submit");
		SymbolKey cancelKey = changedSymbolKey(diffResult, "cancel");
		var context = branchContext(SHA_B, SHA_C, false);

		// [P1 #2 Regression] Two distinct claims on the SAME binding, document, version, symbol, facet, and outcome:
		// Both findings MUST be preserved (never collapsed via putIfAbsent), and reversing claim order yields identical report!
		var submitDocAClaim1 = new DocumentClaim(
				"c-submit-a1", "doc-A", "v1", SHA_A, SHA_C, "OrderApi#submit returns Object in table 1",
				"src/OrderApi.java", Optional.of(submitKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Table 1 expects Object", Optional.empty());
		var submitDocAClaim2 = new DocumentClaim(
				"c-submit-a2", "doc-A", "v1", SHA_A, SHA_C, "OrderApi#submit returns Object in appendix B",
				"src/OrderApi.java", Optional.of(submitKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Appendix B expects Object", Optional.empty());
		var submitDocBClaim = new DocumentClaim(
				"c-submit-b", "doc-B", "v4", SHA_A, SHA_C, "OrderApi#submit returns String",
				"src/OrderApi.java", Optional.of(submitKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_CONSISTENT, "Doc-B v4 already matches String", Optional.empty());
		var cancelDocAClaim = new DocumentClaim(
				"c-cancel-a", "doc-A", "v1", SHA_A, SHA_C, "OrderApi#cancel returns Object",
				"src/OrderApi.java", Optional.of(cancelKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Doc-A v1 expects Object on cancel", Optional.empty());

		var bindingSubmitAForward = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-A", "v1", SHA_A, "b-1",
				BindingGranularity.EXACT_SYMBOL, "src/OrderApi.java", Optional.of(submitKey),
				Optional.empty(), List.of(submitDocAClaim1, submitDocAClaim2));
		var bindingSubmitAReversed = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-A", "v1", SHA_A, "b-1",
				BindingGranularity.EXACT_SYMBOL, "src/OrderApi.java", Optional.of(submitKey),
				Optional.empty(), List.of(submitDocAClaim2, submitDocAClaim1));
		var bindingSubmitB = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-B", "v4", SHA_A, "b-2",
				BindingGranularity.EXACT_SYMBOL, "src/OrderApi.java", Optional.of(submitKey),
				Optional.empty(), List.of(submitDocBClaim));
		var bindingCancelA = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-A", "v1", SHA_A, "b-3",
				BindingGranularity.EXACT_SYMBOL, "src/OrderApi.java", Optional.of(cancelKey),
				Optional.empty(), List.of(cancelDocAClaim));
		var fileOnlyBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-file-only", "v1", SHA_A, "b-file",
				BindingGranularity.FILE_ONLY, "src/OrderApi.java", Optional.empty(),
				Optional.empty(), List.of(submitDocAClaim1));

		var foreignBinding = new DocumentBinding(
				"secret-foreign-tenant", WORKSPACE, REPO, "secret-doc-id", "v99", "secret-sha", "b-foreign",
				BindingGranularity.EXACT_SYMBOL, "secret/SecretFile.java", Optional.of(submitKey),
				Optional.empty(), List.of());
		var foreignLookup = new DocumentLookupResponse(
				new DocumentLookupKey("secret-foreign-tenant", WORKSPACE, REPO,
						TargetKind.TRACKED_BRANCH, "refs/heads/main",
						SideRole.AFTER, SHA_C, "secret/SecretFile.java", LookupGranularity.FILE, Optional.empty()),
				LookupCompleteness.COMPLETE, List.of(foreignBinding), Optional.empty());

		var responsesForward = List.of(
				completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/OrderApi.java"),
				completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/OrderApi.java"),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/OrderApi.java", submitKey),
				completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/OrderApi.java", submitKey,
						List.of(bindingSubmitAForward, bindingSubmitB, foreignBinding)),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/OrderApi.java", cancelKey),
				completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/OrderApi.java", cancelKey,
						List.of(bindingCancelA, fileOnlyBinding)),
				foreignLookup
		);
		var responsesReversed = List.of(
				completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/OrderApi.java"),
				completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/OrderApi.java"),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/OrderApi.java", submitKey),
				completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/OrderApi.java", submitKey,
						List.of(bindingSubmitAReversed, bindingSubmitB, foreignBinding)),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/OrderApi.java", cancelKey),
				completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/OrderApi.java", cancelKey,
						List.of(bindingCancelA, fileOnlyBinding)),
				foreignLookup
		);

		var report = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffResult), responsesForward, List.of(), List.of(), List.of()));
		var reportReversedClaims = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffResult), responsesReversed, List.of(), List.of(), List.of()));
		assertEquals(report, reportReversedClaims);

		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), report.overallOutcome());
		assertEquals(3, report.coverage().actionableCount()); // c-submit-a1 + c-submit-a2 + c-cancel-a
		assertEquals(1, report.coverage().noImpactCount());
		assertEquals(1, report.coverage().inconclusiveCount());
		assertTrue(report.findings().stream().anyMatch(f -> f.claimId().equals(Optional.of("c-submit-a1"))));
		assertTrue(report.findings().stream().anyMatch(f -> f.claimId().equals(Optional.of("c-submit-a2"))));
		assertTrue(report.findings().stream().anyMatch(f ->
				f.symbolKey().equals(Optional.of(cancelKey))
						&& f.primaryReason() == ReasonCode.WEAK_FILE_LEVEL_MAPPING
						&& f.outcome() == ImpactOutcome.INCONCLUSIVE));

		String serializedDiagnostics = report.diagnostics().stream()
				.map(ImpactDiagnostic::message)
				.collect(Collectors.joining(" "));
		assertFalse(serializedDiagnostics.contains("secret-foreign-tenant"));
		assertFalse(serializedDiagnostics.contains("secret-doc-id"));
		assertFalse(serializedDiagnostics.contains("SecretFile.java"));
		assertFalse(serializedDiagnostics.contains("secret-sha"));
		assertTrue(report.findings().stream().noneMatch(f ->
				f.documentId().equals(Optional.of("secret-doc-id"))));
	}

	@Test
	void rv06CompleteEmptyLookupRequiresBothFileAndSymbolKeysWhileMissingOrNonCompleteStaysInconclusive() {
		String beforeSrc = "package com.example; public class Helper { public void run() { int x = 1; } }";
		String afterSrc = "package com.example; public class Helper { public void run() { int x = 2; } }";
		var diffResult = compareJava("src/Helper.java", SHA_B, beforeSrc, "src/Helper.java", SHA_C, afterSrc);
		SymbolKey runKey = changedSymbolKey(diffResult, "run");
		var context = branchContext(SHA_B, SHA_C, false);

		var beforeFileEmpty = completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/Helper.java");
		var afterFileEmpty = completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/Helper.java");
		var beforeSymEmpty = completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/Helper.java", runKey);
		var afterSymEmpty = completeEmptySymbolLookup(context, SideRole.AFTER, SHA_C, "src/Helper.java", runKey);

		// 1. All required FILE + SYMBOL keys on both BEFORE and AFTER complete empty -> scoped NO_IMPACT.
		var cleanReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffResult),
				List.of(beforeFileEmpty, afterFileEmpty, beforeSymEmpty, afterSymEmpty),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, cleanReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), cleanReport.overallOutcome());
		var noImpactFinding = cleanReport.findings().getFirst();
		assertEquals(ReasonCode.COMPLETE_EMPTY_MAPPING_NO_IMPACT, noImpactFinding.primaryReason());
		var negWitness = noImpactFinding.witnesses().stream()
				.filter(w -> w instanceof NegativeBasisWitnessSelector)
				.map(w -> (NegativeBasisWitnessSelector) w)
				.findFirst().orElseThrow();
		assertEquals(4, negWitness.verifiedEmptyLookupKeys().size());

		// 2. [P1 #4 Regression] Two empty SYMBOL lookups WITHOUT required FILE lookups MUST NOT return NO_IMPACT!
		var missingFileLookupReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffResult),
				List.of(beforeSymEmpty, afterSymEmpty),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.PARTIAL, missingFileLookupReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), missingFileLookupReport.overallOutcome());
		assertEquals(ReasonCode.MISSING_OLD_OR_NEW_LOOKUP_KEY,
				missingFileLookupReport.findings().getFirst().primaryReason());
		assertTrue(missingFileLookupReport.coverage().coverageGaps().stream()
				.anyMatch(g -> g.contains("FILE lookup")));

		// 3. Missing AFTER symbol lookup -> INCONCLUSIVE + gap.
		var missingAfterReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffResult),
				List.of(beforeFileEmpty, afterFileEmpty, beforeSymEmpty),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.PARTIAL, missingAfterReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), missingAfterReport.overallOutcome());
		assertEquals(ReasonCode.MISSING_OLD_OR_NEW_LOOKUP_KEY,
				missingAfterReport.findings().getFirst().primaryReason());

		// 4. INCOMPLETE (paginated), AMBIGUOUS, UNAVAILABLE, DENIED -> never NO_IMPACT.
		for (var completeness : List.of(
				LookupCompleteness.INCOMPLETE, LookupCompleteness.AMBIGUOUS,
				LookupCompleteness.UNAVAILABLE, LookupCompleteness.DENIED)) {
			var afterResp = new DocumentLookupResponse(
					new DocumentLookupKey(TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
							SideRole.AFTER, SHA_C, "src/Helper.java", LookupGranularity.SYMBOL, Optional.of(runKey)),
					completeness, List.of(), Optional.of("Lookup status " + completeness));
			var r = classifier.classify(new ImpactClassificationInput(
					context, List.of(diffResult),
					List.of(beforeFileEmpty, afterFileEmpty, beforeSymEmpty, afterResp),
					List.of(), List.of(), List.of()));
			assertNotEquals(Optional.of(ImpactOutcome.NO_IMPACT), r.overallOutcome());
			assertFalse(r.coverage().coverageGaps().isEmpty());
		}
	}

	@Test
	void rv07DistinguishesComparisonShaFromDocumentBoundShaAndIsolatesPrFromTrackedBranchContext() {
		String beforeSrc = "package com.example; public class Api { public Object v() { return null; } }";
		String afterSrc = "package com.example; public class Api { public String v() { return null; } }";
		var diffResult = compareJava("src/Api.java", SHA_B, beforeSrc, "src/Api.java", SHA_C, afterSrc);
		SymbolKey vKey = changedSymbolKey(diffResult, "v");

		// Event B -> C while doc is bound at A, and claim was only evaluated against A (not C!).
		var branchCtx = branchContext(SHA_B, SHA_C, false);
		var staleTargetClaim = new DocumentClaim(
				"c-bound-a", "doc-api", "v1", SHA_A, SHA_A,
				"Api#v returns Object", "src/Api.java",
				Optional.of(vKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_CONSISTENT, "Verified only against A", Optional.empty());
		var binding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-api", "v1", SHA_A, "b-api",
				BindingGranularity.EXACT_SYMBOL, "src/Api.java", Optional.of(vKey),
				Optional.empty(), List.of(staleTargetClaim));
		var beforeFileEmpty = completeEmptyFileLookup(branchCtx, SideRole.BEFORE, SHA_B, "src/Api.java");
		var afterFileEmpty = completeEmptyFileLookup(branchCtx, SideRole.AFTER, SHA_C, "src/Api.java");
		var beforeEmpty = completeEmptySymbolLookup(branchCtx, SideRole.BEFORE, SHA_B, "src/Api.java", vKey);
		var afterLookup = completeSymbolLookup(branchCtx, SideRole.AFTER, SHA_C, "src/Api.java", vKey, List.of(binding));

		var missingWitnessReport = classifier.classify(new ImpactClassificationInput(
				branchCtx, List.of(diffResult),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, afterLookup),
				List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), missingWitnessReport.overallOutcome());
		assertEquals(ReasonCode.DOCUMENT_TARGET_WITNESS_MISSING,
				missingWitnessReport.findings().getFirst().primaryReason());

		// Supply a PR context with the SAME SHA_C; branch lookup responses must NOT satisfy PR context!
		var prCtx = new AssessmentContext(
				TENANT, WORKSPACE, REPO, TargetKind.PULL_REQUEST, "pr/42",
				"job-pr", "attempt-1", Optional.of("cfg-1"),
				SHA_C, Optional.of(SHA_B), Intent.REVISION_COMPARISON,
				ScopeCompleteness.COMPLETE, false);
		var prReportWithBranchLookups = classifier.classify(new ImpactClassificationInput(
				prCtx, List.of(diffResult),
				List.of(beforeFileEmpty, afterFileEmpty, beforeEmpty, afterLookup),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.PARTIAL, prReportWithBranchLookups.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), prReportWithBranchLookups.overallOutcome());
		assertEquals(ReasonCode.MISSING_OLD_OR_NEW_LOOKUP_KEY,
				prReportWithBranchLookups.findings().getFirst().primaryReason());
	}

	@Test
	void rv08EvaluatesWhitespaceCommentAnnotationAndStructuralEqualWithStaleDocumentClaimConservatively() {
		String base = "package com.example;\npublic class WhitespaceTarget {\n    public int ping() { return 1; }\n}\n";
		String whitespaceOnly = "package   com.example;\n\npublic class WhitespaceTarget {\n\tpublic int ping() { return 1; }\n}\n";
		String commentOnly = "package com.example;\npublic class WhitespaceTarget {\n    /** Updated SLA: 50ms */ public int ping() { return 1; }\n}\n";

		// 1. Whitespace-only SAME_PATH with policy enabled -> NO_IMPACT.
		var wsDiff = compareJava("src/WhitespaceTarget.java", SHA_B, base, "src/WhitespaceTarget.java", SHA_C, whitespaceOnly);
		var policyOnCtx = branchContext(SHA_B, SHA_C, true);
		var wsReport = classifier.classify(new ImpactClassificationInput(
				policyOnCtx, List.of(wsDiff), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, wsReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), wsReport.overallOutcome());
		assertEquals(ReasonCode.WHITESPACE_ONLY_NO_IMPACT, wsReport.findings().getFirst().primaryReason());

		// 2. Whitespace-only with policy OFF -> cannot auto NO_IMPACT without lookup!
		var policyOffCtx = branchContext(SHA_B, SHA_C, false);
		var wsPolicyOffReport = classifier.classify(new ImpactClassificationInput(
				policyOffCtx, List.of(wsDiff), List.of(), List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), wsPolicyOffReport.overallOutcome());

		// 3. StructuralEquality.EQUAL with PATH_CHANGED must NOT auto NO_IMPACT even when whitespaceOnlyNoImpactPolicyEnabled=true!
		var renamedEqualDiff = compareJava("src/OldPath.java", SHA_B, base, "src/NewPath.java", SHA_C, base);
		assertEquals(StructuralEquality.EQUAL, renamedEqualDiff.fileComparison().structuralEquality());
		var renamedNoLookupReport = classifier.classify(new ImpactClassificationInput(
				policyOnCtx, List.of(renamedEqualDiff), List.of(), List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), renamedNoLookupReport.overallOutcome());

		// 4. Comment/Javadoc-only with a binding lacking target claim -> INCONCLUSIVE (RAW_COMMENT_OR_JAVADOC_CHANGED_NEEDS_REVIEW).
		var commentDiff = compareJava("src/WhitespaceTarget.java", SHA_B, base, "src/WhitespaceTarget.java", SHA_C, commentOnly);
		var commentBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-sla", "v1", SHA_A, "b-sla",
				BindingGranularity.FILE_ONLY, "src/WhitespaceTarget.java", Optional.empty(),
				Optional.empty(), List.of());
		var commentBeforeEmpty = completeEmptyFileLookup(policyOnCtx, SideRole.BEFORE, SHA_B, "src/WhitespaceTarget.java");
		var commentAfterLookup = completeFileLookup(policyOnCtx, SideRole.AFTER, SHA_C, "src/WhitespaceTarget.java",
				List.of(commentBinding));
		var commentReport = classifier.classify(new ImpactClassificationInput(
				policyOnCtx, List.of(commentDiff), completeRevisionLookups(
						policyOnCtx, commentDiff, commentAfterLookup.key(), commentBinding),
				List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), commentReport.overallOutcome());
		assertEquals(ReasonCode.RAW_COMMENT_OR_JAVADOC_CHANGED_NEEDS_REVIEW,
				commentReport.findings().getFirst().primaryReason());

		// 5. Identical source (StructuralEquality.EQUAL, no raw change) BUT known stale document claim at C -> ACTIONABLE!
		var identicalDiff = compareJava("src/WhitespaceTarget.java", SHA_B, base, "src/WhitespaceTarget.java", SHA_C, base);
		var staleClaim = new DocumentClaim(
				"c-stale-equal", "doc-sla", "v1", SHA_A, SHA_C,
				"WhitespaceTarget#ping returns void", "src/WhitespaceTarget.java",
				Optional.empty(), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.STALE_CLAIM_AGAINST_TARGET,
				"Document claim is already stale against target SHA even though B and C are identical",
				Optional.empty());
		var staleBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-sla", "v1", SHA_A, "b-sla",
				BindingGranularity.FILE_ONLY, "src/WhitespaceTarget.java", Optional.empty(),
				Optional.empty(), List.of(staleClaim));
		var staleAfterLookup = completeFileLookup(policyOnCtx, SideRole.AFTER, SHA_C, "src/WhitespaceTarget.java",
				List.of(staleBinding));
		var staleReport = classifier.classify(new ImpactClassificationInput(
				policyOnCtx, List.of(identicalDiff), List.of(commentBeforeEmpty, staleAfterLookup),
				List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), staleReport.overallOutcome());

		// 6. [Round 2 P1 Regression] Import change (`import one.T` -> `import two.T`), declaration order change,
		// and comment change MUST be evaluated as independent file-level changes even when method m() also changes!
		String importAndMethodBefore = """
				package com.example;
				import one.T;
				public class Holder {
				    public T field;
				    public void m() { int x = 1; }
				}
				""";
		String importAndMethodAfter = """
				package com.example;
				import two.T;
				public class Holder {
				    public T field;
				    public void m() { int x = 2; }
				}
				""";
		var importAndMethodDiff = compareJava("src/Holder.java", SHA_B, importAndMethodBefore,
				"src/Holder.java", SHA_C, importAndMethodAfter);
		assertTrue(importAndMethodDiff.fileComparison().contextChanges().contains(ContextChangeKind.IMPORTS_CHANGED));
		SymbolKey mHolderKey = changedSymbolKey(importAndMethodDiff, "m");
		SymbolKey fieldKey = importAndMethodDiff.entityFacts().stream()
				.filter(f -> f.change() == ChangeKind.UNCHANGED)
				.map(f -> f.after().orElseThrow().key())
				.filter(k -> k.name().equals("field"))
				.findFirst().orElseThrow();

		var fieldLinkBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-holder", "v1", SHA_A, "bind-field-T",
				BindingGranularity.EXACT_SYMBOL, "src/Holder.java", Optional.of(fieldKey),
				Optional.of("sec-field"), List.of());
		var beforeFieldLookup = completeEmptySymbolLookup(policyOnCtx, SideRole.BEFORE, SHA_B, "src/Holder.java", fieldKey);
		var afterFieldLookup = completeSymbolLookup(
				policyOnCtx, SideRole.AFTER, SHA_C, "src/Holder.java", fieldKey, List.of(fieldLinkBinding));
		var requestedKeys = ImpactClassifier.buildLookupKeys(policyOnCtx, List.of(importAndMethodDiff));
		assertTrue(requestedKeys.contains(beforeFieldLookup.key()), "Import change must request the unchanged field before");
		assertTrue(requestedKeys.contains(afterFieldLookup.key()), "Import change must request the unchanged field after");

		// 6a. A file lookup can return a binding for the unchanged field.
		var targetFileLookup = completeFileLookup(policyOnCtx, SideRole.AFTER, SHA_C, "src/Holder.java", List.of(fieldLinkBinding));
		var importViaFileLookupReport = classifier.classify(new ImpactClassificationInput(
				policyOnCtx, List.of(importAndMethodDiff), completeRevisionLookups(
						policyOnCtx, importAndMethodDiff, targetFileLookup.key(), fieldLinkBinding),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, importViaFileLookupReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), importViaFileLookupReport.overallOutcome());
		assertTrue(importViaFileLookupReport.findings().stream().anyMatch(f ->
				f.symbolKey().equals(Optional.of(mHolderKey)) && f.outcome() == ImpactOutcome.NO_IMPACT));
		assertTrue(importViaFileLookupReport.findings().stream().anyMatch(f ->
				f.symbolKey().isEmpty()
						&& f.outcome() == ImpactOutcome.INCONCLUSIVE
						&& f.primaryReason() == ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW
						&& f.witnesses().stream().anyMatch(w -> w instanceof SourceEvidenceSelector sw
								&& sw.granularity() == SelectorGranularity.CONTEXT
								&& sw.contextChangeKind().equals(Optional.of(ContextChangeKind.IMPORTS_CHANGED)))));

		// 6b. The normal lookup flow finds the unchanged-field binding even with empty file lookups.
		var symbolLookups = completeRevisionLookups(
				policyOnCtx, importAndMethodDiff, afterFieldLookup.key(), fieldLinkBinding);
		var importViaSymbolLookupReport = classifier.classify(new ImpactClassificationInput(
				policyOnCtx, List.of(importAndMethodDiff), symbolLookups, List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, importViaSymbolLookupReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), importViaSymbolLookupReport.overallOutcome());
		assertEquals(1, importViaSymbolLookupReport.coverage().inconclusiveCount());

		// Omitting the field response on either side leaves an explicit gap for the import change.
		for (var missingKey : List.of(beforeFieldLookup.key(), afterFieldLookup.key())) {
			var incompleteLookups = symbolLookups.stream().filter(response -> !response.key().equals(missingKey)).toList();
			var incompleteReport = classifier.classify(new ImpactClassificationInput(
					policyOnCtx, List.of(importAndMethodDiff), incompleteLookups, List.of(), List.of(), List.of()));
			assertEquals(ReportProcessingStatus.PARTIAL, incompleteReport.processingStatus());
			assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), incompleteReport.overallOutcome());
			assertTrue(incompleteReport.coverage().coverageGaps().stream()
					.anyMatch(gap -> gap.contains("Missing required SYMBOL lookup response for " + missingKey.sideRole())));
			assertTrue(incompleteReport.findings().stream()
					.anyMatch(finding -> finding.primaryReason() == ReasonCode.MISSING_OLD_OR_NEW_LOOKUP_KEY));
		}

		// 6c. Simultaneous IMPORTS_CHANGED + SCOPE_ORDER_CHANGED + COMMENT change alongside method m() body edit:
		// All three file-level change axes emit distinct findings alongside m()!
		String multiAxisBefore = """
				package com.example;
				import one.T;
				public class Holder {
				    /** Old SLA v1 */
				    public T firstField;
				    public int secondField;
				    public void m() { int x = 1; }
				}
				""";
		String multiAxisAfter = """
				package com.example;
				import two.T;
				public class Holder {
				    /** New SLA v2 */
				    public int secondField;
				    public T firstField;
				    public void m() { int x = 2; }
				}
				""";
		var multiAxisDiff = compareJava("src/Holder.java", SHA_B, multiAxisBefore,
				"src/Holder.java", SHA_C, multiAxisAfter);
		assertTrue(multiAxisDiff.fileComparison().contextChanges().contains(ContextChangeKind.IMPORTS_CHANGED));
		assertTrue(multiAxisDiff.fileComparison().contextChanges().contains(ContextChangeKind.SCOPE_ORDER_CHANGED));
		assertTrue(multiAxisDiff.fileComparison().rawChanges().contains(RawChangeKind.COMMENT));
		SymbolKey firstFieldKey = multiAxisDiff.entityFacts().stream()
				.filter(f -> f.change() == ChangeKind.UNCHANGED)
				.map(f -> f.after().orElseThrow().key())
				.filter(k -> k.name().equals("firstField"))
				.findFirst().orElseThrow();
		var firstFieldBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-holder", "v1", SHA_A, "bind-first-field",
				BindingGranularity.EXACT_SYMBOL, "src/Holder.java", Optional.of(firstFieldKey),
				Optional.of("sec-field"), List.of());

		var fileLookupWithFirstField = completeFileLookup(
				policyOnCtx, SideRole.AFTER, SHA_C, "src/Holder.java", List.of(firstFieldBinding));
		var multiAxisReport = classifier.classify(new ImpactClassificationInput(
				policyOnCtx, List.of(multiAxisDiff), completeRevisionLookups(
						policyOnCtx, multiAxisDiff, fileLookupWithFirstField.key(), firstFieldBinding),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, multiAxisReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), multiAxisReport.overallOutcome());
		assertTrue(multiAxisReport.findings().stream().anyMatch(f ->
				f.primaryReason() == ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW
						&& f.witnesses().stream().anyMatch(w -> w instanceof SourceEvidenceSelector sw
								&& sw.contextChangeKind().equals(Optional.of(ContextChangeKind.IMPORTS_CHANGED)))));
		assertTrue(multiAxisReport.findings().stream().anyMatch(f ->
				f.primaryReason() == ReasonCode.CONTEXT_OR_ORDER_CHANGED_NEEDS_REVIEW
						&& f.witnesses().stream().anyMatch(w -> w instanceof SourceEvidenceSelector sw
								&& sw.contextChangeKind().equals(Optional.of(ContextChangeKind.SCOPE_ORDER_CHANGED)))));
		assertTrue(multiAxisReport.findings().stream().anyMatch(f ->
				f.primaryReason() == ReasonCode.RAW_COMMENT_OR_JAVADOC_CHANGED_NEEDS_REVIEW));
	}

	@Test
	void rv09PreservesM2ParseFailuresMixedScopeNonJavaExclusionsUnavailableSnapshotsAndInitialInventory() {
		var context = branchContext(SHA_B, SHA_C, false);

		// 1. Parse failed Java file -> FAILED when alone, no fake deletions.
		var parseFailedDiff = compareJava("src/Broken.java", SHA_B, "class Broken { int x = 1; }",
				"src/Broken.java", SHA_C, "class Broken { void bad( }");
		assertEquals(ProcessingStatus.FAILED, parseFailedDiff.status());
		var failedOnlyReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(parseFailedDiff), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.FAILED, failedOnlyReport.processingStatus());
		assertTrue(failedOnlyReport.overallOutcome().isEmpty());
		assertEquals(ReasonCode.JAVA_PARSE_FAILED, failedOnlyReport.findings().getFirst().primaryReason());

		// 2. Mixed Java/non-Java transition -> PARTIAL / INCONCLUSIVE with MIXED_LANGUAGE_TRANSITION_INCOMPLETE.
		var mixedDiff = diff.compare(new StructuralDiffInput(REPO, Intent.REVISION_COMPARISON,
				Optional.of(new Present(new SnapshotRef(REPO, SHA_B), new SourceInput("src/App.java", "class App {}"))),
				new Present(new SnapshotRef(REPO, SHA_C), new SourceInput("src/App.kt", "class App")),
				Optional.of(new FileAssociation("src/App.java", "src/App.kt"))));
		assertEquals(ProcessingStatus.INCOMPLETE, mixedDiff.status());
		var mixedReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(mixedDiff), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.PARTIAL, mixedReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), mixedReport.overallOutcome());
		assertEquals(ReasonCode.MIXED_LANGUAGE_TRANSITION_INCOMPLETE,
				mixedReport.findings().getFirst().primaryReason());

		// 3. Verified absent before + present after (added file) vs unavailable before -> distinct outcomes!
		var addedFileDiff = diff.compare(new StructuralDiffInput(REPO, Intent.REVISION_COMPARISON,
				Optional.of(new Absent(new SnapshotRef(REPO, SHA_B), "src/NewService.java", "Not created yet")),
				new Present(new SnapshotRef(REPO, SHA_C), new SourceInput("src/NewService.java", "class NewService {}")),
				Optional.empty()));
		var unavailBeforeDiff = diff.compare(new StructuralDiffInput(REPO, Intent.REVISION_COMPARISON,
				Optional.of(new Unavailable(new SnapshotRef(REPO, SHA_B), "src/Lost.java", "S02 timeout")),
				new Present(new SnapshotRef(REPO, SHA_C), new SourceInput("src/Lost.java", "class Lost {}")),
				Optional.empty()));
		SymbolKey newSvcKey = addedFileDiff.entityFacts().getFirst().after().orElseThrow().key();
		var addedReport = classifier.classify(new ImpactClassificationInput(
				context,
				List.of(addedFileDiff, unavailBeforeDiff),
				List.of(
						completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/NewService.java"),
						completeEmptySymbolLookup(context, SideRole.AFTER, SHA_C, "src/NewService.java", newSvcKey)),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.PARTIAL, addedReport.processingStatus());
		assertTrue(addedReport.findings().stream().anyMatch(f ->
				f.primaryReason() == ReasonCode.SNAPSHOT_UNAVAILABLE_INCOMPLETE));
		assertTrue(addedReport.findings().stream().anyMatch(f ->
				f.primaryReason() == ReasonCode.COMPLETE_EMPTY_MAPPING_NO_IMPACT));

		// 4. Initial assessment with residual INCOMPLETE still evaluates trustworthy TargetInventory (F05)!
		var initCtx = new AssessmentContext(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-init", "attempt-1", Optional.empty(), SHA_C, Optional.empty(),
				Intent.INITIAL_ASSESSMENT, ScopeCompleteness.COMPLETE, false);
		var initWithResidualDiff = diff.compare(new StructuralDiffInput(REPO, Intent.INITIAL_ASSESSMENT,
				Optional.empty(),
				new Present(new SnapshotRef(REPO, SHA_C),
						new SourceInput("src/InitService.java", "class InitService { int v() { return 1; } } ;")),
				Optional.empty()));
		assertEquals(ProcessingStatus.INCOMPLETE, initWithResidualDiff.status());
		assertTrue(initWithResidualDiff.initialInventory().isPresent());
		SymbolKey vKey = initWithResidualDiff.initialInventory().orElseThrow().entities().stream()
				.filter(e -> e.key().name().equals("v")).findFirst().orElseThrow().key();
		var initClaim = new DocumentClaim(
				"c-init-v", "doc-init", "v1", SHA_A, SHA_C,
				"InitService#v returns String", "src/InitService.java",
				Optional.of(vKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Init target returns int instead of documented String",
				Optional.empty());
		var initBinding = new DocumentBinding(
				TENANT, WORKSPACE, REPO, "doc-init", "v1", SHA_A, "b-init",
				BindingGranularity.EXACT_SYMBOL, "src/InitService.java", Optional.of(vKey),
				Optional.empty(), List.of(initClaim));
		SymbolKey clsKey = initWithResidualDiff.initialInventory().orElseThrow().entities().stream()
				.filter(e -> e.key().kind() == Kind.CLASS).findFirst().orElseThrow().key();

		var initReport = classifier.classify(new ImpactClassificationInput(
				initCtx,
				List.of(initWithResidualDiff),
				List.of(
						completeEmptyFileLookup(initCtx, SideRole.TARGET, SHA_C, "src/InitService.java"),
						completeEmptySymbolLookup(initCtx, SideRole.TARGET, SHA_C, "src/InitService.java", clsKey),
						completeSymbolLookup(initCtx, SideRole.TARGET, SHA_C, "src/InitService.java", vKey, List.of(initBinding))),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.PARTIAL, initReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), initReport.overallOutcome());
		assertTrue(initReport.findings().stream().allMatch(ImpactFinding::initialAssessment));
		assertTrue(initReport.findings().stream().anyMatch(f -> f.primaryReason() == ReasonCode.RESIDUAL_OR_GROUPING_INCOMPLETE));
		assertTrue(initReport.findings().stream().anyMatch(f -> f.classification().equals(Optional.of(DriftClassification.SIGNATURE))));
	}

	@Test
	void rv10AggregatesMixedUnitsAndVerifiesEmptyScopeManifestContractForM4() {
		var context = branchContext(SHA_B, SHA_C, true);

		// 1. Mixed job: ACTIONABLE + NO_IMPACT + INCONCLUSIVE + FAILED + non-Java OUT_OF_SCOPE.
		var actionableDiff = compareJava("src/A.java", SHA_B, "class A { Object f() { return null; } }",
				"src/A.java", SHA_C, "class A { String f() { return null; } }");
		var unchangedDiff = compareJava("src/B.java", SHA_B, "class B {}", "src/B.java", SHA_C, "class B {}");
		var bodyDiff = compareJava("src/C.java", SHA_B, "class C { void g() { int x=1; } }",
				"src/C.java", SHA_C, "class C { void g() { int x=2; } }");
		var failedDiff = compareJava("src/D.java", SHA_B, "class D {}", "src/D.java", SHA_C, "class D { void ( }");
		var nonJavaDiff = compareJava("docs/README.md", SHA_B, "# Old", "docs/README.md", SHA_C, "# New");

		SymbolKey fKey = changedSymbolKey(actionableDiff, "f");
		SymbolKey gKey = changedSymbolKey(bodyDiff, "g");
		var fClaim = new DocumentClaim("c-f", "doc-a", "v1", SHA_A, SHA_C, "A#f returns Object",
				"src/A.java", Optional.of(fKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Mismatch", Optional.empty());
		var fBinding = new DocumentBinding(TENANT, WORKSPACE, REPO, "doc-a", "v1", SHA_A, "b-a",
				BindingGranularity.EXACT_SYMBOL, "src/A.java", Optional.of(fKey), Optional.empty(), List.of(fClaim));
		var gBinding = new DocumentBinding(TENANT, WORKSPACE, REPO, "doc-c", "v1", SHA_A, "b-c",
				BindingGranularity.EXACT_SYMBOL, "src/C.java", Optional.of(gKey), Optional.empty(), List.of());

		var mixedReport = classifier.classify(new ImpactClassificationInput(
				context,
				List.of(actionableDiff, unchangedDiff, bodyDiff, failedDiff, nonJavaDiff),
				List.of(
						completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/A.java"),
						completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/A.java"),
						completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/A.java", fKey),
						completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/A.java", fKey, List.of(fBinding)),
						completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/C.java"),
						completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/C.java"),
						completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/C.java", gKey),
						completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/C.java", gKey, List.of(gBinding))),
				List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.PARTIAL, mixedReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), mixedReport.overallOutcome());
		assertFalse(mixedReport.coverage().completeCoverage());
		assertEquals(1, mixedReport.coverage().actionableCount());
		assertEquals(1, mixedReport.coverage().noImpactCount());
		assertEquals(2, mixedReport.coverage().inconclusiveCount()); // bodyDiff + failedDiff
		assertEquals(1, mixedReport.coverage().excludedCount()); // README.md
		assertFalse(mixedReport.coverage().coverageGaps().isEmpty());
		assertEquals(1, mixedReport.coverage().excludedScopeReasons().size());

		// 2. EMPTY_VERIFIED WITH matching ScopeManifestRef -> COMPLETE + NO_IMPACT + manifest bound in NegativeBasisWitnessSelector for M4!
		var emptyManifest = new ScopeManifestRef(
				"manifest-empty-99", REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				Intent.REVISION_COMPARISON, Optional.of(SHA_B), SHA_C, 0);
		var emptyVerifiedCtx = new AssessmentContext(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-empty-ok", "attempt-1", Optional.empty(), SHA_C, Optional.of(SHA_B),
				Intent.REVISION_COMPARISON, ScopeCompleteness.EMPTY_VERIFIED, true,
				Optional.of(emptyManifest));
		var emptyVerifiedReport = classifier.classify(new ImpactClassificationInput(
				emptyVerifiedCtx, List.of(), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, emptyVerifiedReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), emptyVerifiedReport.overallOutcome());
		assertTrue(emptyVerifiedReport.coverage().completeCoverage());
		var emptyFinding = emptyVerifiedReport.findings().getFirst();
		var manifestWitness = (NegativeBasisWitnessSelector) emptyFinding.witnesses().getFirst();
		assertEquals(Optional.of("manifest-empty-99"), manifestWitness.provenanceRefId());
		assertEquals(Optional.of(emptyManifest), manifestWitness.scopeManifest());
		assertEquals(Optional.of(SHA_B), manifestWitness.beforeSha());
		assertEquals(Optional.of(SHA_C), manifestWitness.afterSha());

		// If M4 rejects the ScopeManifestRef witness, applyVerifiedWitnesses downgrades EMPTY_VERIFIED to INCONCLUSIVE!
		var downgradedEmpty = classifier.applyVerifiedWitnesses(emptyVerifiedReport, Map.of());
		assertEquals(ReportProcessingStatus.PARTIAL, downgradedEmpty.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), downgradedEmpty.overallOutcome());

		// 2b. EMPTY_VERIFIED WITHOUT ScopeManifestRef -> PARTIAL + INCONCLUSIVE (EMPTY_SCOPE_MANIFEST_MISSING)!
		var emptyWithoutManifestCtx = new AssessmentContext(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-empty-no-manifest", "attempt-1", Optional.empty(), SHA_C, Optional.of(SHA_B),
				Intent.REVISION_COMPARISON, ScopeCompleteness.EMPTY_VERIFIED, true, Optional.empty());
		var emptyWithoutManifestReport = classifier.classify(new ImpactClassificationInput(
				emptyWithoutManifestCtx, List.of(), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.PARTIAL, emptyWithoutManifestReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), emptyWithoutManifestReport.overallOutcome());
		assertEquals(ReasonCode.EMPTY_SCOPE_MANIFEST_MISSING,
				emptyWithoutManifestReport.findings().getFirst().primaryReason());

		// 3. EMPTY_UNVERIFIED -> PARTIAL + INCONCLUSIVE.
		var emptyUnverifiedCtx = new AssessmentContext(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-empty-unk", "attempt-1", Optional.empty(), SHA_C, Optional.of(SHA_B),
				Intent.REVISION_COMPARISON, ScopeCompleteness.EMPTY_UNVERIFIED, true);
		var emptyUnverifiedReport = classifier.classify(new ImpactClassificationInput(
				emptyUnverifiedCtx, List.of(), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.PARTIAL, emptyUnverifiedReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), emptyUnverifiedReport.overallOutcome());
		assertFalse(emptyUnverifiedReport.coverage().completeCoverage());

		// 4. All OUT_OF_SCOPE -> OUT_OF_SCOPE, empty overallOutcome, zero coverage gaps.
		var allOutOfScopeReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(nonJavaDiff), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.OUT_OF_SCOPE, allOutOfScopeReport.processingStatus());
		assertTrue(allOutOfScopeReport.overallOutcome().isEmpty());
		assertTrue(allOutOfScopeReport.coverage().completeCoverage());
		assertTrue(allOutOfScopeReport.coverage().coverageGaps().isEmpty());
		assertEquals(1, allOutOfScopeReport.coverage().excludedCount());
	}

	@Test
	void rv11AssignsSeverityFromVerifiedConsequenceOnlyLeavesUnknownUnevaluatedAndNeverFabricatesConfidence() {
		String beforeSrc = "class S { public Object a() { return null; } public Object b() { return null; } public Object c() { return null; } }";
		String afterSrc = "class S { public String a() { return null; } public String b() { return null; } public String c() { return null; } }";
		var diffResult = compareJava("src/S.java", SHA_B, beforeSrc, "src/S.java", SHA_C, afterSrc);
		SymbolKey keyA = changedSymbolKey(diffResult, "a");
		SymbolKey keyB = changedSymbolKey(diffResult, "b");
		SymbolKey keyC = changedSymbolKey(diffResult, "c");
		var context = branchContext(SHA_B, SHA_C, false);

		var claimA = new DocumentClaim("ca", "doc-s", "v1", SHA_A, SHA_C, "S#a returns Object", "src/S.java",
				Optional.of(keyA), Optional.of(FacetKind.DECLARATION), ClaimVerdict.DECLARATION_INCOMPATIBLE,
				"Minor utility return type",
				Optional.of(new VerifiedImpactContext(Severity.LOW, "Internal utility", "internal-util")));
		var claimB = new DocumentClaim("cb", "doc-s", "v1", SHA_A, SHA_C, "S#b returns Object", "src/S.java",
				Optional.of(keyB), Optional.of(FacetKind.DECLARATION), ClaimVerdict.DECLARATION_INCOMPATIBLE,
				"Critical auth flow return type",
				Optional.of(new VerifiedImpactContext(Severity.CRITICAL, "Auth token validation contract broken", "auth-critical")));
		var claimC = new DocumentClaim("cc", "doc-s", "v1", SHA_A, SHA_C, "S#c returns Object", "src/S.java",
				Optional.of(keyC), Optional.of(FacetKind.DECLARATION), ClaimVerdict.DECLARATION_INCOMPATIBLE,
				"No impact criticality data", Optional.empty());

		var responses = List.of(
				completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/S.java"),
				completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/S.java"),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/S.java", keyA),
				completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/S.java", keyA, List.of(
						new DocumentBinding(TENANT, WORKSPACE, REPO, "doc-s", "v1", SHA_A, "ba",
								BindingGranularity.EXACT_SYMBOL, "src/S.java", Optional.of(keyA), Optional.empty(), List.of(claimA)))),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/S.java", keyB),
				completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/S.java", keyB, List.of(
						new DocumentBinding(TENANT, WORKSPACE, REPO, "doc-s", "v1", SHA_A, "bb",
								BindingGranularity.EXACT_SYMBOL, "src/S.java", Optional.of(keyB), Optional.empty(), List.of(claimB)))),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/S.java", keyC),
				completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/S.java", keyC, List.of(
						new DocumentBinding(TENANT, WORKSPACE, REPO, "doc-s", "v1", SHA_A, "bc",
								BindingGranularity.EXACT_SYMBOL, "src/S.java", Optional.of(keyC), Optional.empty(), List.of(claimC))))
		);

		var report = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffResult), responses, List.of(), List.of(), List.of()));
		assertEquals(3, report.findings().size());
		for (var f : report.findings()) {
			assertEquals(Optional.of(DriftClassification.SIGNATURE), f.classification());
			assertTrue(f.semanticConfidence().isEmpty());
		}
		var findingA = report.findings().stream().filter(f -> f.symbolKey().equals(Optional.of(keyA))).findFirst().orElseThrow();
		var findingB = report.findings().stream().filter(f -> f.symbolKey().equals(Optional.of(keyB))).findFirst().orElseThrow();
		var findingC = report.findings().stream().filter(f -> f.symbolKey().equals(Optional.of(keyC))).findFirst().orElseThrow();

		assertEquals(Optional.of(Severity.LOW), findingA.severity());
		assertEquals(SeverityStatus.EVALUATED, findingA.severityStatus());
		assertEquals(Optional.of(Severity.CRITICAL), findingB.severity());
		assertEquals(SeverityStatus.EVALUATED, findingB.severityStatus());
		assertTrue(findingC.severity().isEmpty());
		assertEquals(SeverityStatus.SEVERITY_NOT_EVALUATED, findingC.severityStatus());
	}

	@Test
	void rv12ExcludesOnlyProvenControlledExportUnitsAndNeverMasksManualEditsInSameFile() {
		String beforeSrc = "class Mixed { Object exported() { return null; } Object manualEdit() { return null; } }";
		String exportOnlyAfterSrc = "class Mixed { String exported() { return null; } Object manualEdit() { return null; } }";
		String mixedAfterSrc = "class Mixed { String exported() { return null; } String manualEdit() { return null; } }";

		var exportOnlyDiff = compareJava("src/Mixed.java", SHA_B, beforeSrc, "src/Mixed.java", SHA_C, exportOnlyAfterSrc);
		var mixedDiff = compareJava("src/Mixed.java", SHA_B, beforeSrc, "src/Mixed.java", SHA_C, mixedAfterSrc);
		SymbolKey exportedKey = changedSymbolKey(mixedDiff, "exported");
		SymbolKey manualKey = changedSymbolKey(mixedDiff, "manualEdit");
		var context = branchContext(SHA_B, SHA_C, false);

		var manualClaim = new DocumentClaim("c-manual", "doc-m", "v1", SHA_A, SHA_C,
				"Mixed#manualEdit returns Object", "src/Mixed.java",
				Optional.of(manualKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Unrelated manual edit breaks doc", Optional.empty());
		var manualBinding = new DocumentBinding(TENANT, WORKSPACE, REPO, "doc-m", "v1", SHA_A, "b-manual",
				BindingGranularity.EXACT_SYMBOL, "src/Mixed.java", Optional.of(manualKey),
				Optional.empty(), List.of(manualClaim));
		var mixedLookups = List.of(
				completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/Mixed.java"),
				completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/Mixed.java"),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/Mixed.java", exportedKey),
				completeEmptySymbolLookup(context, SideRole.AFTER, SHA_C, "src/Mixed.java", exportedKey),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/Mixed.java", manualKey),
				completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/Mixed.java", manualKey, List.of(manualBinding))
		);

		// 1. Symbol-level controlled export excludes exported() while manualEdit() remains ACTIONABLE.
		var symbolExport = ControlledExportUnit.forSymbol(
				REPO, SHA_C, "src/Mixed.java", exportedKey, "export-run-77");
		var symbolReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(mixedDiff), mixedLookups, List.of(), List.of(symbolExport), List.of()));
		assertEquals(ReportProcessingStatus.COMPLETE, symbolReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), symbolReport.overallOutcome());
		assertEquals(1, symbolReport.coverage().excludedCount());
		assertEquals(1, symbolReport.coverage().actionableCount());

		// 2. [P1 #3 Regression] File-level controlled export WITHOUT matching whole-file raw delta (either missing hashes
		// or carrying exportOnlyAfterSrc's raw hash while manualEdit() was also changed in mixedDiff) MUST NOT skip the file!
		String beforeRawSha = exportOnlyDiff.beforeContext().orElseThrow().rawFacts().rawSha256();
		String exportOnlyRawSha = exportOnlyDiff.afterContext().orElseThrow().rawFacts().rawSha256();

		var unverifiedWholeFileExport = ControlledExportUnit.forWholeFile(
				REPO, Optional.empty(), SHA_C, "src/Mixed.java",
				Optional.empty(), Optional.empty(), "export-unverified-file");
		var mismatchedWholeFileExport = ControlledExportUnit.forWholeFile(
				REPO, Optional.of(SHA_B), SHA_C, "src/Mixed.java",
				Optional.of(beforeRawSha), Optional.of(exportOnlyRawSha), "export-partial-file");

		for (var nonCoveringExport : List.of(unverifiedWholeFileExport, mismatchedWholeFileExport)) {
			var guardedReport = classifier.classify(new ImpactClassificationInput(
					context, List.of(mixedDiff), mixedLookups, List.of(), List.of(nonCoveringExport), List.of()));
			assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), guardedReport.overallOutcome());
			assertEquals(0, guardedReport.coverage().excludedCount());
			var manualFinding = guardedReport.findings().stream()
					.filter(f -> f.symbolKey().equals(Optional.of(manualKey))).findFirst().orElseThrow();
			assertEquals(ImpactOutcome.ACTIONABLE, manualFinding.outcome());
		}

		// 3. Whole-file controlled export WITH verified before/after raw hashes matching exportOnlyDiff excludes whole file!
		var verifiedWholeFileExport = ControlledExportUnit.forWholeFile(
				REPO, Optional.of(SHA_B), SHA_C, "src/Mixed.java",
				Optional.of(beforeRawSha), Optional.of(exportOnlyRawSha), "export-verified-whole-file");
		var wholeFileExcludedReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(exportOnlyDiff), List.of(), List.of(), List.of(verifiedWholeFileExport), List.of()));
		assertEquals(ReportProcessingStatus.OUT_OF_SCOPE, wholeFileExcludedReport.processingStatus());
		assertEquals(1, wholeFileExcludedReport.coverage().excludedCount());
		assertEquals(ReasonCode.CONTROLLED_EXPORT_EXCLUDED,
				wholeFileExcludedReport.findings().getFirst().primaryReason());
	}

	@Test
	void rv13EnforcesInputConsistencyDefensiveImmutabilityDeterministicOrderingAndM4WitnessDowngradeContract() {
		var context = branchContext(SHA_B, SHA_C, false);

		// 1. Contradictory repository/SHA/intent or DENIED scope -> FAILED.
		var deniedCtx = new AssessmentContext(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", Optional.empty(), SHA_C, Optional.of(SHA_B),
				Intent.REVISION_COMPARISON, ScopeCompleteness.DENIED, false);
		var deniedReport = classifier.classify(new ImpactClassificationInput(
				deniedCtx, List.of(), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.FAILED, deniedReport.processingStatus());
		assertEquals(ReasonCode.SCOPE_DENIED, deniedReport.diagnostics().getFirst().code());

		var wrongRepoDiff = diff.compare(new StructuralDiffInput("other-repo", Intent.REVISION_COMPARISON,
				Optional.of(new Present(new SnapshotRef("other-repo", SHA_B), new SourceInput("A.java", "class A {}"))),
				new Present(new SnapshotRef("other-repo", SHA_C), new SourceInput("A.java", "class A {}")),
				Optional.empty()));
		var mismatchRepoReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(wrongRepoDiff), List.of(), List.of(), List.of(), List.of()));
		assertEquals(ReportProcessingStatus.FAILED, mismatchRepoReport.processingStatus());
		assertEquals(ReasonCode.CONTRADICTORY_DIFF_CONTEXT, mismatchRepoReport.diagnostics().getFirst().code());

		// 2. Order-independence: shuffling diffResults & lookupResponses produces identical findings, findingIds & witnessIds!
		var diff1 = compareJava("src/Alpha.java", SHA_B, "class Alpha { int m() { return 1; } }",
				"src/Alpha.java", SHA_C, "class Alpha { int m() { return 2; } }");
		var diff2 = compareJava("src/Beta.java", SHA_B, "class Beta { Object n() { return null; } }",
				"src/Beta.java", SHA_C, "class Beta { String n() { return null; } }");
		SymbolKey mKey = changedSymbolKey(diff1, "m");
		SymbolKey nKey = changedSymbolKey(diff2, "n");

		var mInitClaim = new DocumentClaim("cm", "doc-m", "v1", SHA_A, SHA_C,
				"Alpha#m behavior", "src/Alpha.java", Optional.of(mKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY, "Initial potential mismatch on m", Optional.empty());
		var mBinding = new DocumentBinding(TENANT, WORKSPACE, REPO, "doc-m", "v1", SHA_A, "bm",
				BindingGranularity.EXACT_SYMBOL, "src/Alpha.java", Optional.of(mKey), Optional.empty(), List.of(mInitClaim));
		var nClaim = new DocumentClaim("cn", "doc-n", "v1", SHA_A, SHA_C,
				"Beta#n returns Object", "src/Beta.java", Optional.of(nKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Mismatch on n", Optional.empty());
		var nBinding = new DocumentBinding(TENANT, WORKSPACE, REPO, "doc-n", "v1", SHA_A, "bn",
				BindingGranularity.EXACT_SYMBOL, "src/Beta.java", Optional.of(nKey), Optional.empty(), List.of(nClaim));

		var semM = new SemanticAssessmentInput(
				TENANT, WORKSPACE, REPO, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-1", "attempt-1", SHA_C, "src/Alpha.java", mKey, "doc-m", "v1", SHA_A,
				SemanticStatus.BEHAVIOR_MISMATCH, true,
				List.of(new GroundedSourceFacetRef(SideRole.AFTER, SHA_C, "src/Alpha.java", mKey, FacetKind.BODY, Optional.empty())),
				List.of(new GroundedDocumentClaimRef("doc-m", "v1", SHA_A, "bm", "cm", Optional.empty())),
				Optional.of(0.88), Optional.of("Confirmed mismatch on m"), Optional.empty());

		var lookups = new ArrayList<>(List.of(
				completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/Alpha.java"),
				completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/Alpha.java"),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/Alpha.java", mKey),
				completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/Alpha.java", mKey, List.of(mBinding)),
				completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/Beta.java"),
				completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/Beta.java"),
				completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/Beta.java", nKey),
				completeSymbolLookup(context, SideRole.AFTER, SHA_C, "src/Beta.java", nKey, List.of(nBinding))
		));
		var reportA = classifier.classify(new ImpactClassificationInput(
				context, List.of(diff1, diff2), lookups, List.of(semM), List.of(), List.of()));
		var reversedLookups = new ArrayList<>(lookups);
		Collections.reverse(reversedLookups);
		var reportB = classifier.classify(new ImpactClassificationInput(
				context, List.of(diff2, diff1), reversedLookups, List.of(semM), List.of(), List.of()));
		assertEquals(reportA, reportB);
		assertThrows(UnsupportedOperationException.class, () -> reportA.findings().add(reportA.findings().getFirst()));

		// 3. Two-tier M4 handoff via applyVerifiedWitnesses:
		var findingM = reportA.findings().stream().filter(f -> f.symbolKey().equals(Optional.of(mKey))).findFirst().orElseThrow();
		var findingN = reportA.findings().stream().filter(f -> f.symbolKey().equals(Optional.of(nKey))).findFirst().orElseThrow();

		var downgraded = classifier.applyVerifiedWitnesses(reportA, Map.of(
				findingM.findingId(), findingM.requiredAdmissionWitnessIds(),
				findingN.findingId(), Set.of()
		));
		assertEquals(ReportProcessingStatus.PARTIAL, downgraded.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), downgraded.overallOutcome());
		var postM = downgraded.findings().stream().filter(f -> f.findingId().equals(findingM.findingId())).findFirst().orElseThrow();
		var postN = downgraded.findings().stream().filter(f -> f.findingId().equals(findingN.findingId())).findFirst().orElseThrow();

		assertEquals(ImpactOutcome.INCONCLUSIVE, postM.outcome());
		assertTrue(postM.classification().isEmpty());
		assertTrue(postM.semanticConfidence().isEmpty());
		assertEquals(IssueAdmission.SUPPORTED_BY_INITIAL_EVIDENCE, postM.admission());
		assertEquals(ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED, postM.primaryReason());

		assertEquals(ImpactOutcome.INCONCLUSIVE, postN.outcome());
		assertEquals(IssueAdmission.INSUFFICIENT_EVIDENCE, postN.admission());
		assertEquals(ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED, postN.primaryReason());
		assertFalse(postN.witnesses().isEmpty());

		// 4. Rejecting NegativeBasisWitnessSelector on NO_IMPACT downgrades NO_IMPACT -> INCONCLUSIVE!
		var diffClean = compareJava("src/Clean.java", SHA_B, "class Clean { void k() { int x=1; } }",
				"src/Clean.java", SHA_C, "class Clean { void k() { int x=2; } }");
		SymbolKey kKey = changedSymbolKey(diffClean, "k");
		var noImpactReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(diffClean),
				List.of(
						completeEmptyFileLookup(context, SideRole.BEFORE, SHA_B, "src/Clean.java"),
						completeEmptyFileLookup(context, SideRole.AFTER, SHA_C, "src/Clean.java"),
						completeEmptySymbolLookup(context, SideRole.BEFORE, SHA_B, "src/Clean.java", kKey),
						completeEmptySymbolLookup(context, SideRole.AFTER, SHA_C, "src/Clean.java", kKey)),
				List.of(), List.of(), List.of()));
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), noImpactReport.overallOutcome());
		var downgradedNoImpact = classifier.applyVerifiedWitnesses(noImpactReport, Map.of());
		assertEquals(ReportProcessingStatus.PARTIAL, downgradedNoImpact.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), downgradedNoImpact.overallOutcome());
		assertEquals(ReasonCode.REQUIRED_EVIDENCE_UNVERIFIED_DOWNGRADED,
				downgradedNoImpact.findings().getFirst().primaryReason());

		// 5. [Round 2 P2 Regression] Two files proposed as EXCLUDED where M4 verifies ONLY file 1's witness:
		// Surviving report MUST recompute excludedFileCount = 1, failedOrIncompleteFileCount = 1,
		// excludedCount = 1, and excludedScopeReasons listing ONLY file 1 (never retaining rejected file 2)!
		var exclFile1Diff = compareJava("src/GeneratedOne.java", SHA_B, "class GeneratedOne {}",
				"src/GeneratedOne.java", SHA_C, "class GeneratedOne { int a; }");
		var exclFile2Diff = compareJava("src/GeneratedTwo.java", SHA_B, "class GeneratedTwo {}",
				"src/GeneratedTwo.java", SHA_C, "class GeneratedTwo { int b; }");
		var callerExcl1 = new CallerExclusion(REPO, SHA_C, "src/GeneratedOne.java", "rule-gen-1", "Generated code 1");
		var callerExcl2 = new CallerExclusion(REPO, SHA_C, "src/GeneratedTwo.java", "rule-gen-2", "Generated code 2");

		var twoExcludedReport = classifier.classify(new ImpactClassificationInput(
				context, List.of(exclFile1Diff, exclFile2Diff),
				List.of(), List.of(), List.of(), List.of(callerExcl1, callerExcl2)));
		assertEquals(ReportProcessingStatus.OUT_OF_SCOPE, twoExcludedReport.processingStatus());
		assertEquals(2, twoExcludedReport.coverage().excludedFileCount());
		assertEquals(0, twoExcludedReport.coverage().failedOrIncompleteFileCount());
		assertEquals(2, twoExcludedReport.coverage().excludedCount());
		assertEquals(2, twoExcludedReport.coverage().excludedScopeReasons().size());

		var exclFinding1 = twoExcludedReport.findings().stream()
				.filter(f -> f.afterPath().equals(Optional.of("src/GeneratedOne.java")))
				.findFirst().orElseThrow();
		var exclFinding2 = twoExcludedReport.findings().stream()
				.filter(f -> f.afterPath().equals(Optional.of("src/GeneratedTwo.java")))
				.findFirst().orElseThrow();

		var partialVerifiedExclusionReport = classifier.applyVerifiedWitnesses(twoExcludedReport, Map.of(
				exclFinding1.findingId(), exclFinding1.requiredImpactWitnessIds(),
				exclFinding2.findingId(), Set.of()
		));
		assertEquals(ReportProcessingStatus.PARTIAL, partialVerifiedExclusionReport.processingStatus());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), partialVerifiedExclusionReport.overallOutcome());
		assertEquals(1, partialVerifiedExclusionReport.coverage().excludedCount());
		assertEquals(1, partialVerifiedExclusionReport.coverage().inconclusiveCount());
		assertEquals(1, partialVerifiedExclusionReport.coverage().excludedFileCount());
		assertEquals(1, partialVerifiedExclusionReport.coverage().failedOrIncompleteFileCount());
		assertEquals(0, partialVerifiedExclusionReport.coverage().evaluatedFileCount());
		assertEquals(1, partialVerifiedExclusionReport.coverage().excludedScopeReasons().size());
		assertTrue(partialVerifiedExclusionReport.coverage().excludedScopeReasons().getFirst().contains("src/GeneratedOne.java"));
		assertFalse(partialVerifiedExclusionReport.coverage().excludedScopeReasons().getFirst().contains("src/GeneratedTwo.java"));
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

	private static SymbolKey changedSymbolKey(StructuralDiffResult result, String name) {
		return result.entityFacts().stream()
				.filter(f -> f.change() != ChangeKind.UNCHANGED)
				.map(f -> f.before().map(s -> s.key()).orElseGet(() -> f.after().orElseThrow().key()))
				.filter(k -> k.name().equals(name))
				.findFirst()
				.orElseThrow();
	}

	private static SymbolKey changedSymbolKey(StructuralDiffResult result, String name, ChangeKind change) {
		return result.entityFacts().stream()
				.filter(f -> f.change() == change)
				.map(f -> f.before().map(s -> s.key()).orElseGet(() -> f.after().orElseThrow().key()))
				.filter(k -> k.name().equals(name))
				.findFirst()
				.orElseThrow();
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
				new DocumentLookupKey(ctx.tenantId(), ctx.workspaceId(), ctx.repository(),
						ctx.targetKind(), ctx.targetRef(), role, sha, path,
						LookupGranularity.SYMBOL, Optional.of(key)),
				LookupCompleteness.COMPLETE, bindings, Optional.empty());
	}

	private static DocumentLookupResponse completeEmptySymbolLookup(
			AssessmentContext ctx, SideRole role, String sha, String path, SymbolKey key) {
		return completeSymbolLookup(ctx, role, sha, path, key, List.of());
	}

	private static DocumentLookupResponse completeFileLookup(
			AssessmentContext ctx, SideRole role, String sha, String path, List<DocumentBinding> bindings) {
		return new DocumentLookupResponse(
				new DocumentLookupKey(ctx.tenantId(), ctx.workspaceId(), ctx.repository(),
						ctx.targetKind(), ctx.targetRef(), role, sha, path,
						LookupGranularity.FILE, Optional.empty()),
				LookupCompleteness.COMPLETE, bindings, Optional.empty());
	}

	private static DocumentLookupResponse completeEmptyFileLookup(
			AssessmentContext ctx, SideRole role, String sha, String path) {
		return completeFileLookup(ctx, role, sha, path, List.of());
	}
}
