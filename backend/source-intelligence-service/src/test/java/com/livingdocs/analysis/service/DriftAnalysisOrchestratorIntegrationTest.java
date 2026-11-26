package com.livingdocs.analysis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.livingdocs.analysis.ast.JavaParseResult.Kind;
import com.livingdocs.analysis.ast.JavaParseResult.SymbolKey;
import com.livingdocs.analysis.ast.JavaParseResult.SyntacticTypeKey;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FacetKind;
import com.livingdocs.analysis.ast.SourceInput;
import com.livingdocs.analysis.diff.StructuralDiff.SidePresence;
import com.livingdocs.analysis.diff.StructuralDiff.SideRole;
import com.livingdocs.analysis.diff.StructuralDiff.SnapshotProvenance;
import com.livingdocs.analysis.diff.StructuralDiffInput.Absent;
import com.livingdocs.analysis.diff.StructuralDiffInput.Intent;
import com.livingdocs.analysis.diff.StructuralDiffInput.Present;
import com.livingdocs.analysis.diff.StructuralDiffInput.SnapshotRef;
import com.livingdocs.analysis.diff.StructuralDiffInput.Unavailable;
import com.livingdocs.analysis.entity.AnalysisFindingEntity;
import com.livingdocs.analysis.entity.AnalysisJobEntity;
import com.livingdocs.analysis.entity.DriftEvidenceEntity;
import com.livingdocs.analysis.entity.DriftIssueEntity;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.SnapshotProof;
import com.livingdocs.analysis.impact.ImpactClassifier.BindingGranularity;
import com.livingdocs.analysis.impact.ImpactClassifier.ClaimVerdict;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentBinding;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentClaim;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentLookupKey;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentLookupResponse;
import com.livingdocs.analysis.impact.ImpactClassifier.LookupCompleteness;
import com.livingdocs.analysis.impact.ImpactClassifier.LookupGranularity;
import com.livingdocs.analysis.impact.ImpactClassifier.ScopeManifestRef;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticAssessmentInput;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticStatus;
import com.livingdocs.analysis.impact.ImpactClassifier.Severity;
import com.livingdocs.analysis.impact.ImpactClassifier.TargetKind;
import com.livingdocs.analysis.impact.ImpactClassifier.VerifiedImpactContext;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.ApplyResultStatus;
import com.livingdocs.analysis.port.DocumentMappingPort;
import com.livingdocs.analysis.port.ScopeAuthorizationPort;
import com.livingdocs.analysis.port.SemanticAssessmentPort;
import com.livingdocs.analysis.port.SourceAuthorityPort;
import com.livingdocs.analysis.repository.AnalysisFindingRepository;
import com.livingdocs.analysis.repository.AnalysisJobRepository;
import com.livingdocs.analysis.repository.DriftEvidenceRepository;
import com.livingdocs.analysis.repository.DriftIssueRepository;
import com.livingdocs.analysis.security.S03TenantContext;
import com.livingdocs.analysis.service.DriftAnalysisOrchestratorService.AnalyzeTargetCommand;
import com.livingdocs.analysis.service.DriftAnalysisOrchestratorService.CheckpointDecision;
import com.livingdocs.analysis.service.DriftAnalysisOrchestratorService.OrchestrationResult;
import com.livingdocs.analysis.service.DriftAnalysisOrchestratorService.OrchestrationStatus;
import com.livingdocs.analysis.service.impl.DriftAnalysisOrchestratorServiceImpl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers
class DriftAnalysisOrchestratorIntegrationTest {

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	private static final String SHA_C = "cccccccccccccccccccccccccccccccccccccccc";
	private static final String SHA_D = "dddddddddddddddddddddddddddddddddddddddd";
	private static final String CFG_V1 = "cfg-rev-1";
	private static final String BRANCH_MAIN = "refs/heads/main";

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
			.withDatabaseName("livingdocs")
			.withUsername("postgres")
			.withPassword("postgres")
			.withInitScript("s03-test-schema.sql");

	@DynamicPropertySource
	static void isolatedDatabase(DynamicPropertyRegistry registry) {
		registry.add("DB_URL", POSTGRES::getJdbcUrl);
		registry.add("DB_SCHEMA", () -> "intelligence");
		registry.add("DB_USERNAME", () -> "ld_s03_app");
		registry.add("DB_PASSWORD", () -> "test_app_password");
		registry.add("FLYWAY_USERNAME", POSTGRES::getUsername);
		registry.add("FLYWAY_PASSWORD", POSTGRES::getPassword);
	}

	@Autowired
	private AnalysisResultWriterService writerService;

	@Autowired
	private S03TenantContext tenantContext;

	@Autowired
	private AnalysisJobRepository jobRepository;

	@Autowired
	private AnalysisFindingRepository findingRepository;

	@Autowired
	private DriftIssueRepository issueRepository;

	@Autowired
	private DriftEvidenceRepository evidenceRepository;

	@Autowired
	private JsonMapper jsonMapper;

	@Test
	@DisplayName("M6-RV-01: Non-transactional port execution, active-TX guard rejection, and end-to-end SIGNATURE persistence")
	void rv01NonTransactionalPortExecutionActiveTxGuardAndSignatureMismatchEndToEnd() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant t0 = Instant.now();
		MutableClock clock = new MutableClock(t0);
		StubPorts ports = new StubPorts(clock);

		String path = "src/OrderService.java";
		String beforeSrc = "public class OrderService {\n    public int total(int qty) { return qty; }\n}\n";
		String afterSrc = "public class OrderService {\n    public long total(int qty) { return qty; }\n}\n";
		SymbolKey totalKey = methodKey("OrderService", "total", "int");
		String repoStr = scope.repoId.toString();

		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(path, beforeSrc))),
						new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(path, afterSrc)),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, path, beforeSrc),
						presentProof(repoStr, SideRole.AFTER, SHA_C, path, afterSrc)),
				List.of(),
				Optional.empty()));

		DocumentClaim claim = new DocumentClaim(
				"claim-order-sig", docId.toString(), "v1", SHA_A, SHA_C,
				"total(int) returns int", path, Optional.of(totalKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Return type changed from int to long",
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Breaking return type change", "core-api")));
		DocumentBinding binding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-order",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(totalKey), Optional.of("sec-1"), List.of(claim));
		ports.mappingResponder = req -> {
			List<DocumentLookupResponse> res = req.lookupKeys().stream()
					.map(k -> new DocumentLookupResponse(
							k, LookupCompleteness.COMPLETE,
							k.sideRole() == SideRole.AFTER && k.granularity() == LookupGranularity.SYMBOL
									&& k.symbolKey().equals(Optional.of(totalKey))
									? List.of(binding) : List.of(),
							Optional.empty()))
					.toList();
			return new DocumentMappingPort.MappingLookupResult(
					DocumentMappingPort.MappingLookupStatus.COMPLETE, res, Optional.empty());
		};

		DriftAnalysisOrchestratorService orchestrator = newOrchestrator(ports, clock);
		AnalyzeTargetCommand cmd = revisionCommand(scope, "job-m6-rv01", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, true);

		// Calling inside an active DB transaction must fail closed immediately.
		assertThrows(IllegalStateException.class, () -> tenantContext.writeInTenant(scope.tenantId, () -> {
			orchestrator.executeAnalysis(cmd);
			return null;
		}));

		OrchestrationResult result = orchestrator.executeAnalysis(cmd);
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, result.status());
		assertEquals(CheckpointDecision.ADVANCE_CHECKPOINT, result.checkpointDecision());
		assertFalse(result.retryable());
		assertEquals(1, ports.authCalls.get());
		assertEquals(2, ports.currentnessCalls.get()); // pre-flight + post-evaluation
		assertEquals(1, ports.snapshotCalls.get());
		assertEquals(1, ports.mappingCalls.get());
		assertEquals(0, ports.semanticEvalCalls.get());

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<DriftIssueEntity> issues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", BRANCH_MAIN);
			assertEquals(1, issues.size());
			assertEquals("ANALYZED", issues.getFirst().getState());
			assertEquals("SIGNATURE", issues.getFirst().getDriftClassification());
			List<AnalysisFindingEntity> findings = findingRepository.findScopedByJobId(
					scope.tenantId, result.persistOutcome().orElseThrow().jobId());
			assertEquals(1, findings.size());
			assertEquals(1, evidenceRepository.findScopedByIssueId(
					scope.tenantId, issues.getFirst().getDriftIssueId()).size());
			return null;
		});
	}

	@Test
	@DisplayName("M6-RV-02: Pinned-SHA guard rejects spoofed SHA, verified ABSENT deletion opens REFERENTIAL issue, and INITIAL_ASSESSMENT completes")
	void rv02PinnedShaGuardDeletedFileReferentialAndInitialAssessment() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant t0 = Instant.now();
		MutableClock clock = new MutableClock(t0);
		StubPorts ports = new StubPorts(clock);
		String repoStr = scope.repoId.toString();

		// 1. S02 returns wrong/unpinned SHA_D while command targets SHA_C -> FAILED_CONTRACT_VIOLATION, 0 DB rows!
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B),
								new SourceInput("src/A.java", "class A {}\n"))),
						new Present(new SnapshotRef(repoStr, SHA_D),
								new SourceInput("src/A.java", "class A {}\n")),
						Optional.empty())),
				List.of(),
				List.of(),
				Optional.empty()));
		DriftAnalysisOrchestratorService orchestrator = newOrchestrator(ports, clock);

		OrchestrationResult spoofedResult = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-spoof-sha", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, false));
		assertEquals(OrchestrationStatus.FAILED_CONTRACT_VIOLATION, spoofedResult.status());
		assertEquals(CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED, spoofedResult.checkpointDecision());
		assertEquals(Optional.of("UNPINNED_OR_WRONG_SNAPSHOT_SHA"), spoofedResult.diagnosticReason());
		assertTrue(spoofedResult.persistOutcome().isEmpty());
		assertEquals(0, ports.mappingCalls.get());
		tenantContext.readInTenant(scope.tenantId, () -> {
			assertTrue(jobRepository.findScopedByExternalKey(scope.tenantId, "job-spoof-sha").isEmpty());
			return null;
		});

		// 2. Verified ABSENT deletion of src/LegacyApi.java -> opens ANALYZED REFERENTIAL issue!
		String delPath = "src/LegacyApi.java";
		String delBeforeSrc = "public class LegacyApi {\n    public void run() {}\n}\n";
		SymbolKey runKey = methodKey("LegacyApi", "run");
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(delPath, delBeforeSrc))),
						new Absent(new SnapshotRef(repoStr, SHA_C), delPath, "DELETED_IN_TARGET_COMMIT"),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, delPath, delBeforeSrc),
						new SnapshotProof(
								SideRole.AFTER,
								new SnapshotProvenance(new SnapshotRef(repoStr, SHA_C), delPath, SidePresence.ABSENT,
										Optional.of("DELETED_IN_TARGET_COMMIT"), Optional.empty()),
								Optional.empty())),
				List.of(),
				Optional.empty()));

		DocumentClaim delClaim = new DocumentClaim(
				"claim-del-run", docId.toString(), "v1", SHA_A, SHA_C,
				"LegacyApi#run() documented endpoint", delPath, Optional.of(runKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.REFERENCED_ENTITY_REQUIRED, "Referenced method was removed with file deletion",
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Documented API deleted", "public-api")));
		DocumentBinding delBinding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-del",
				BindingGranularity.EXACT_SYMBOL, delPath, Optional.of(runKey), Optional.empty(), List.of(delClaim));
		ports.mappingResponder = req -> {
			List<DocumentLookupResponse> res = req.lookupKeys().stream()
					.map(k -> new DocumentLookupResponse(
							k, LookupCompleteness.COMPLETE,
							k.sideRole() == SideRole.BEFORE && k.granularity() == LookupGranularity.SYMBOL
									&& k.symbolKey().equals(Optional.of(runKey))
									? List.of(delBinding) : List.of(),
							Optional.empty()))
					.toList();
			return new DocumentMappingPort.MappingLookupResult(
					DocumentMappingPort.MappingLookupStatus.COMPLETE, res, Optional.empty());
		};

		OrchestrationResult delRes = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-del-ref", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, false));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, delRes.status());
		assertEquals(CheckpointDecision.ADVANCE_CHECKPOINT, delRes.checkpointDecision());
		assertEquals(1, delRes.persistOutcome().orElseThrow().issuesOpened());

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<DriftIssueEntity> issues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", BRANCH_MAIN);
			assertEquals(1, issues.size());
			assertEquals("REFERENTIAL", issues.getFirst().getDriftClassification());
			assertEquals("ANALYZED", issues.getFirst().getState());
			return null;
		});

		// 3. INITIAL_ASSESSMENT with empty S04 lookup completes cleanly -> ADVANCE_CHECKPOINT, 0 new issues!
		ScopeIds initScope = ScopeIds.fresh();
		String initRepoStr = initScope.repoId.toString();
		String initPath = "src/InitService.java";
		String initSrc = "public class InitService {\n    public int getValue() { return 1; }\n}\n";
		ports.currentRevision.set(1L);
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.empty(),
						new Present(new SnapshotRef(initRepoStr, SHA_C), new SourceInput(initPath, initSrc)),
						Optional.empty())),
				List.of(presentProof(initRepoStr, SideRole.TARGET, SHA_C, initPath, initSrc)),
				List.of(),
				Optional.empty()));
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.COMPLETE,
				req.lookupKeys().stream()
						.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty()))
						.toList(),
				Optional.empty());

		OrchestrationResult initRes = orchestrator.executeAnalysis(new AnalyzeTargetCommand(
				UUID.randomUUID(), "s03-worker", initScope.tenantId, initScope.workspaceId, initScope.repoId,
				"job-init-1", "attempt-1", t0.plusSeconds(300),
				TargetKind.TRACKED_BRANCH, BRANCH_MAIN, Intent.INITIAL_ASSESSMENT,
				Optional.empty(), SHA_C, CFG_V1, false, List.of(), false,
				Duration.ofSeconds(5), Optional.empty(), Optional.empty()));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, initRes.status());
		assertEquals(CheckpointDecision.ADVANCE_CHECKPOINT, initRes.checkpointDecision());
		assertEquals(0, initRes.persistOutcome().orElseThrow().issuesOpened());
	}

	@Test
	@DisplayName("M6-RV-03: Port-level UNAVAILABLE blocks retryably with 0 writes, mixed Present/Unavailable holds checkpoint, and EMPTY_VERIFIED skips S04/S05")
	void rv03PortUnavailablePerFileUnavailablePartialCoverageAndEmptyVerifiedSkip() {
		ScopeIds scope = ScopeIds.fresh();
		Instant t0 = Instant.now();
		MutableClock clock = new MutableClock(t0);
		StubPorts ports = new StubPorts(clock);
		String repoStr = scope.repoId.toString();

		// 1. Whole resolveSnapshots UNAVAILABLE -> BLOCKED_DEPENDENCY_UNAVAILABLE (retryable = true, 0 DB writes).
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.UNAVAILABLE,
				Optional.empty(), List.of(), List.of(), List.of(), Optional.of("GITHUB_RATE_LIMITED")));
		DriftAnalysisOrchestratorService orchestrator = newOrchestrator(ports, clock);

		OrchestrationResult blocked = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-unavail", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, false));
		assertEquals(OrchestrationStatus.BLOCKED_DEPENDENCY_UNAVAILABLE, blocked.status());
		assertEquals(CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED, blocked.checkpointDecision());
		assertTrue(blocked.retryable());
		assertTrue(blocked.persistOutcome().isEmpty());
		assertEquals(1, ports.currentnessCalls.get()); // post-check not called!

		// 2. Mixed 2-file run: FileA has verified SIGNATURE mismatch, FileB is Unavailable -> 1 ANALYZED issue + HOLD_CHECKPOINT_INCOMPLETE_COVERAGE.
		UUID docId = UUID.randomUUID();
		String pathA = "src/FileA.java";
		String beforeA = "public class FileA {\n    public int price() { return 1; }\n}\n";
		String afterA = "public class FileA {\n    public long price() { return 1L; }\n}\n";
		String pathB = "src/FileB.java";
		SymbolKey priceKey = methodKey("FileA", "price");

		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(
						new SourceAuthorityPort.SnapshotPair(
								Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(pathA, beforeA))),
								new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(pathA, afterA)),
								Optional.empty()),
						new SourceAuthorityPort.SnapshotPair(
								Optional.of(new Present(new SnapshotRef(repoStr, SHA_B),
										new SourceInput(pathB, "class FileB {}\n"))),
								new Unavailable(new SnapshotRef(repoStr, SHA_C), pathB, "BLOB_TIMEOUT"),
								Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, pathA, beforeA),
						presentProof(repoStr, SideRole.AFTER, SHA_C, pathA, afterA),
						presentProof(repoStr, SideRole.BEFORE, SHA_B, pathB, "class FileB {}\n"),
						new SnapshotProof(
								SideRole.AFTER,
								new SnapshotProvenance(new SnapshotRef(repoStr, SHA_C), pathB, SidePresence.UNAVAILABLE,
										Optional.empty(), Optional.of("BLOB_TIMEOUT")),
								Optional.empty())),
				List.of(),
				Optional.empty()));
		DocumentClaim claimA = new DocumentClaim(
				"claim-fileA", docId.toString(), "v1", SHA_A, SHA_C,
				"FileA#price()", pathA, Optional.of(priceKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Return type changed",
				Optional.of(new VerifiedImpactContext(Severity.MEDIUM, "Contract mismatch", "billing")));
		DocumentBinding bindA = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-A",
				BindingGranularity.EXACT_SYMBOL, pathA, Optional.of(priceKey), Optional.empty(), List.of(claimA));
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.COMPLETE,
				req.lookupKeys().stream()
						.map(k -> new DocumentLookupResponse(
								k, LookupCompleteness.COMPLETE,
								k.path().equals(pathA) && k.sideRole() == SideRole.AFTER
										&& k.granularity() == LookupGranularity.SYMBOL ? List.of(bindA) : List.of(),
								Optional.empty()))
						.toList(),
				Optional.empty());

		OrchestrationResult partialRes = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-partial-blob", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, false));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, partialRes.status());
		assertEquals(CheckpointDecision.HOLD_CHECKPOINT_INCOMPLETE_COVERAGE, partialRes.checkpointDecision());
		assertFalse(partialRes.persistOutcome().orElseThrow().coverageCheckpointEligible());
		assertEquals(1, partialRes.persistOutcome().orElseThrow().issuesOpened());

		// 3. EMPTY_VERIFIED (eligibleFileCount == 0 manifest) -> 0 calls to S04 and S05, ADVANCE_CHECKPOINT!
		ports.mappingCalls.set(0);
		ports.semanticEvalCalls.set(0);
		ports.currentRevision.set(2L);
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.of(new ScopeManifestRef(
						"manifest-empty-1", repoStr, TargetKind.TRACKED_BRANCH, BRANCH_MAIN,
						Intent.REVISION_COMPARISON, Optional.of(SHA_C), SHA_D, 0)),
				List.of(),
				List.of(),
				List.of(),
				Optional.empty()));

		OrchestrationResult emptyRes = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-empty-verified", "attempt-1", t0.plusSeconds(300), SHA_C, SHA_D, false));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, emptyRes.status());
		assertEquals(CheckpointDecision.ADVANCE_CHECKPOINT, emptyRes.checkpointDecision());
		assertEquals(0, ports.mappingCalls.get());
		assertEquals(0, ports.semanticEvalCalls.get());
		assertEquals(1, emptyRes.persistOutcome().orElseThrow().findingsPersisted());
	}

	@Test
	@DisplayName("M6-RV-04: Two-pass S05 semantic grounding opens ANALYZED SEMANTIC issue, and S05 BEHAVIOR_CONSISTENT revert marks CONSISTENCY_REVERTED")
	void rv04TwoPassSemanticGroundingPromotionAndSemanticR2Revert() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant t0 = Instant.now();
		MutableClock clock = new MutableClock(t0);
		StubPorts ports = new StubPorts(clock);
		String repoStr = scope.repoId.toString();

		String path = "src/Fee.java";
		String srcV1 = "class Fee { int fee(int d) { return d * 5; } }\n";
		String srcV2 = "class Fee { int fee(int d) { return d * 10; } }\n";
		SymbolKey feeKey = methodKey("Fee", "fee", "int");

		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(path, srcV1))),
						new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(path, srcV2)),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, path, srcV1),
						presentProof(repoStr, SideRole.AFTER, SHA_C, path, srcV2)),
				List.of(),
				Optional.empty()));

		DocumentClaim claim = new DocumentClaim(
				"claim-fee-formula", docId.toString(), "v1", SHA_A, SHA_C,
				"Fee#fee(int) multiplies d by 5", path, Optional.of(feeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY, "Body changed on documented formula", Optional.empty());
		DocumentBinding binding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(feeKey), Optional.of("sec-fee"), List.of(claim));
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.COMPLETE,
				req.lookupKeys().stream()
						.map(k -> new DocumentLookupResponse(
								k, LookupCompleteness.COMPLETE,
								k.sideRole() == SideRole.AFTER && k.granularity() == LookupGranularity.SYMBOL
										&& k.symbolKey().equals(Optional.of(feeKey)) ? List.of(binding) : List.of(),
								Optional.empty()))
						.toList(),
				Optional.empty());

		AtomicReference<List<SemanticAssessmentPort.SemanticCandidate>> capturedCandidates = new AtomicReference<>(List.of());
		ports.semanticEvaluator = req -> {
			capturedCandidates.set(req.candidates());
			List<SemanticAssessmentInput> inputs = req.candidates().stream()
					.map(cand -> new SemanticAssessmentInput(
							req.context().tenantId(), req.context().workspaceId(), repoStr,
							TargetKind.TRACKED_BRANCH, BRANCH_MAIN,
							req.context().jobId(), req.context().attemptId(),
							req.context().authoritativeTargetSha(),
							cand.path(), cand.symbolKey(), cand.documentId(), cand.documentVersion(),
							cand.documentBoundSha(), SemanticStatus.BEHAVIOR_MISMATCH, true,
							cand.groundedSourceRefs(), cand.groundedDocumentRefs(),
							Optional.of(0.94), Optional.of("Multiplier changed from 5 to 10"),
							Optional.of(new VerifiedImpactContext(Severity.MEDIUM, "Formula changed", "billing"))))
					.toList();
			return new SemanticAssessmentPort.SemanticExecutionResult(
					SemanticAssessmentPort.ExecutionStatus.COMPLETED,
					Optional.of("s05-exec-1"), inputs, Optional.empty());
		};

		DriftAnalysisOrchestratorService orchestrator = newOrchestrator(ports, clock);
		OrchestrationResult mismatchRes = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-sem-1", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, true));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, mismatchRes.status());
		assertEquals(CheckpointDecision.ADVANCE_CHECKPOINT, mismatchRes.checkpointDecision());
		assertEquals(1, ports.semanticEvalCalls.get());
		assertEquals(1, capturedCandidates.get().size());
		assertFalse(capturedCandidates.get().getFirst().groundedSourceRefs().isEmpty());
		assertTrue(capturedCandidates.get().getFirst().groundedSourceRefs().getFirst().range().isPresent());

		UUID issueId = tenantContext.readInTenant(scope.tenantId, () -> {
			List<DriftIssueEntity> issues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", BRANCH_MAIN);
			assertEquals(1, issues.size());
			DriftIssueEntity issue = issues.getFirst();
			assertEquals("ANALYZED", issue.getState());
			assertEquals("SEMANTIC", issue.getDriftClassification());
			return issue.getDriftIssueId();
		});

		// Subsequent revert commit C -> D returning to srcV1 with S05 BEHAVIOR_CONSISTENT -> records CONSISTENCY_REVERTED!
		clock.set(t0.plusSeconds(60));
		ports.currentRevision.set(2L);
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(path, srcV2))),
						new Present(new SnapshotRef(repoStr, SHA_D), new SourceInput(path, srcV1)),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_C, path, srcV2),
						presentProof(repoStr, SideRole.AFTER, SHA_D, path, srcV1)),
				List.of(),
				Optional.empty()));
		DocumentClaim revertClaim = new DocumentClaim(
				"claim-fee-formula", docId.toString(), "v1", SHA_A, SHA_D,
				"Fee#fee(int) multiplies d by 5", path, Optional.of(feeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY, "Body changed back", Optional.empty());
		DocumentBinding revertBinding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(feeKey), Optional.of("sec-fee"), List.of(revertClaim));
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.COMPLETE,
				req.lookupKeys().stream()
						.map(k -> new DocumentLookupResponse(
								k, LookupCompleteness.COMPLETE,
								k.sideRole() == SideRole.AFTER && k.granularity() == LookupGranularity.SYMBOL
										&& k.symbolKey().equals(Optional.of(feeKey)) ? List.of(revertBinding) : List.of(),
								Optional.empty()))
						.toList(),
				Optional.empty());
		ports.semanticEvaluator = req -> new SemanticAssessmentPort.SemanticExecutionResult(
				SemanticAssessmentPort.ExecutionStatus.COMPLETED,
				Optional.of("s05-exec-2"),
				req.candidates().stream()
						.map(cand -> new SemanticAssessmentInput(
								req.context().tenantId(), req.context().workspaceId(), repoStr,
								TargetKind.TRACKED_BRANCH, BRANCH_MAIN,
								req.context().jobId(), req.context().attemptId(),
								req.context().authoritativeTargetSha(),
								cand.path(), cand.symbolKey(), cand.documentId(), cand.documentVersion(),
								cand.documentBoundSha(), SemanticStatus.BEHAVIOR_CONSISTENT, true,
								cand.groundedSourceRefs(), cand.groundedDocumentRefs(),
								Optional.of(0.92), Optional.of("Reverted to documented 5x multiplier"),
								Optional.empty()))
						.toList(),
				Optional.empty());

		OrchestrationResult revertRes = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-sem-revert", "attempt-1", t0.plusSeconds(360), SHA_C, SHA_D, true));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, revertRes.status());
		assertEquals(1, revertRes.persistOutcome().orElseThrow().revertsRecorded());

		tenantContext.readInTenant(scope.tenantId, () -> {
			DriftIssueEntity issue = issueRepository.findById(issueId).orElseThrow();
			assertEquals("ANALYZED", issue.getState()); // Preserved!
			assertEquals(SHA_C, issue.getLastObservedSha()); // Last mismatch SHA preserved!
			assertTrue(issue.isRevertObserved());
			assertEquals(SHA_D, issue.getLastConsistentSha());
			List<DriftEvidenceEntity> obs = evidenceRepository.findScopedByIssueId(scope.tenantId, issueId);
			assertTrue(obs.stream().anyMatch(e -> "CONSISTENCY_REVERTED".equals(e.getObservationKind())));
			return null;
		});
	}

	@Test
	@DisplayName("M6-RV-05: S05 TIMED_OUT_PENDING_RECONCILE opens DETECTED with incomplete coverage, and follow-up reconciles via executionId without re-calling evaluate")
	void rv05SemanticTimeoutOpensDetectedHoldsCheckpointAndReconcilesPriorExecution() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant t0 = Instant.now();
		MutableClock clock = new MutableClock(t0);
		StubPorts ports = new StubPorts(clock);
		String repoStr = scope.repoId.toString();

		String path = "src/Fee.java";
		String srcV1 = "class Fee { int fee(int d) { return d * 5; } }\n";
		String srcV2 = "class Fee { int fee(int d) { return d * 10; } }\n";
		SymbolKey feeKey = methodKey("Fee", "fee", "int");

		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(path, srcV1))),
						new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(path, srcV2)),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, path, srcV1),
						presentProof(repoStr, SideRole.AFTER, SHA_C, path, srcV2)),
				List.of(),
				Optional.empty()));

		DocumentClaim claim = new DocumentClaim(
				"claim-fee-to", docId.toString(), "v1", SHA_A, SHA_C,
				"Fee#fee(int) formula", path, Optional.of(feeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY, "Executable body changed", Optional.empty());
		DocumentBinding binding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(feeKey), Optional.of("sec-fee"), List.of(claim));
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.COMPLETE,
				req.lookupKeys().stream()
						.map(k -> new DocumentLookupResponse(
								k, LookupCompleteness.COMPLETE,
								k.sideRole() == SideRole.AFTER && k.granularity() == LookupGranularity.SYMBOL
										&& k.symbolKey().equals(Optional.of(feeKey)) ? List.of(binding) : List.of(),
								Optional.empty()))
						.toList(),
				Optional.empty());

		AtomicReference<List<SemanticAssessmentPort.SemanticCandidate>> timedOutCandidates = new AtomicReference<>(List.of());
		ports.semanticEvaluator = req -> {
			timedOutCandidates.set(req.candidates());
			return new SemanticAssessmentPort.SemanticExecutionResult(
					SemanticAssessmentPort.ExecutionStatus.TIMED_OUT_PENDING_RECONCILE,
					Optional.of("s05-exec-77"),
					List.of(),
					Optional.of("S05_DEADLINE_EXCEEDED"));
		};

		DriftAnalysisOrchestratorService orchestrator = newOrchestrator(ports, clock);
		OrchestrationResult timeoutRes = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-sem-to-1", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, true));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, timeoutRes.status());
		assertEquals(CheckpointDecision.HOLD_CHECKPOINT_INCOMPLETE_COVERAGE, timeoutRes.checkpointDecision());
		assertEquals(Optional.of("s05-exec-77"), timeoutRes.semanticExecutionId());
		assertFalse(timeoutRes.persistOutcome().orElseThrow().coverageCheckpointEligible());

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<DriftIssueEntity> issues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", BRANCH_MAIN);
			assertEquals(1, issues.size());
			assertEquals("DETECTED", issues.getFirst().getState());
			List<AnalysisFindingEntity> findings = findingRepository.findScopedByJobId(
					scope.tenantId, timeoutRes.persistOutcome().orElseThrow().jobId());
			assertEquals("SEMANTIC_UNAVAILABLE_OR_FAILED", findings.getFirst().getPrimaryReason());
			return null;
		});

		// Follow-up reconciliation run passes priorTimedOutSemanticExecutionId = "s05-exec-77".
		ports.semanticEvalCalls.set(0);
		ports.semanticReconcileCalls.set(0);
		ports.currentRevision.set(2L);
		ports.semanticReconciler = req -> {
			assertEquals("s05-exec-77", req.executionId());
			List<SemanticAssessmentInput> reconciledInputs = timedOutCandidates.get().stream()
					.map(cand -> new SemanticAssessmentInput(
							req.context().tenantId(), req.context().workspaceId(), repoStr,
							TargetKind.TRACKED_BRANCH, BRANCH_MAIN,
							req.context().jobId(), req.context().attemptId(),
							req.context().authoritativeTargetSha(),
							cand.path(), cand.symbolKey(), cand.documentId(), cand.documentVersion(),
							cand.documentBoundSha(), SemanticStatus.BEHAVIOR_MISMATCH, true,
							cand.groundedSourceRefs(), cand.groundedDocumentRefs(),
							Optional.of(0.95), Optional.of("Reconciled S05 mismatch"),
							Optional.of(new VerifiedImpactContext(Severity.HIGH, "Reconciled formula drift", "billing"))))
					.toList();
			return new SemanticAssessmentPort.SemanticExecutionResult(
					SemanticAssessmentPort.ExecutionStatus.COMPLETED,
					Optional.of("s05-exec-77"),
					reconciledInputs,
					Optional.empty());
		};

		OrchestrationResult reconcileRes = orchestrator.executeAnalysis(new AnalyzeTargetCommand(
				UUID.randomUUID(), "s03-worker", scope.tenantId, scope.workspaceId, scope.repoId,
				"job-sem-to-reconcile", "attempt-1", t0.plusSeconds(600),
				TargetKind.TRACKED_BRANCH, BRANCH_MAIN, Intent.REVISION_COMPARISON,
				Optional.of(SHA_B), SHA_C, CFG_V1, false, List.of(), true,
				Duration.ofSeconds(5), Optional.of("s05-exec-77"), Optional.empty()));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, reconcileRes.status());
		assertEquals(CheckpointDecision.ADVANCE_CHECKPOINT, reconcileRes.checkpointDecision());
		assertEquals(1, ports.semanticReconcileCalls.get());
		assertEquals(0, ports.semanticEvalCalls.get()); // Must NOT re-call evaluate!

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<DriftIssueEntity> issues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", BRANCH_MAIN);
			assertEquals(1, issues.size());
			assertEquals("ANALYZED", issues.getFirst().getState());
			assertEquals("SEMANTIC", issues.getFirst().getDriftClassification());
			return null;
		});
	}

	@Test
	@DisplayName("M6-RV-06: S04 UNAVAILABLE and INCOMPLETE lookup results degrade fail-closed to INCONCLUSIVE, 0 issues, HOLD_CHECKPOINT_INCOMPLETE_COVERAGE")
	void rv06MappingIncompleteAndUnavailableDegradeFailClosed() {
		ScopeIds scope = ScopeIds.fresh();
		Instant t0 = Instant.now();
		MutableClock clock = new MutableClock(t0);
		StubPorts ports = new StubPorts(clock);
		String repoStr = scope.repoId.toString();

		String path = "src/Calc.java";
		String beforeSrc = "class Calc { int sum(int a) { return a; } }\n";
		String afterSrc = "class Calc { long sum(int a) { return a; } }\n";
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(path, beforeSrc))),
						new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(path, afterSrc)),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, path, beforeSrc),
						presentProof(repoStr, SideRole.AFTER, SHA_C, path, afterSrc)),
				List.of(),
				Optional.empty()));

		DriftAnalysisOrchestratorService orchestrator = newOrchestrator(ports, clock);

		// 1. S04 UNAVAILABLE with empty responses -> synthesized UNAVAILABLE lookup keys.
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.UNAVAILABLE, List.of(), Optional.of("S04_TIMEOUT"));
		OrchestrationResult unavailRes = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-s04-unavail", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, true));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, unavailRes.status());
		assertEquals(CheckpointDecision.HOLD_CHECKPOINT_INCOMPLETE_COVERAGE, unavailRes.checkpointDecision());
		assertEquals(0, ports.semanticEvalCalls.get());
		assertEquals(0, unavailRes.persistOutcome().orElseThrow().issuesOpened());

		// 2. S04 INCOMPLETE returning partial list -> missing keys synthesized as INCOMPLETE.
		ports.currentRevision.set(2L);
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.INCOMPLETE,
				List.of(new DocumentLookupResponse(
						req.lookupKeys().getFirst(), LookupCompleteness.COMPLETE, List.of(), Optional.empty())),
				Optional.of("PAGE_TRUNCATED"));
		OrchestrationResult incompleteRes = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-s04-inc", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, true));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, incompleteRes.status());
		assertEquals(CheckpointDecision.HOLD_CHECKPOINT_INCOMPLETE_COVERAGE, incompleteRes.checkpointDecision());
		assertEquals(0, ports.semanticEvalCalls.get());

		tenantContext.readInTenant(scope.tenantId, () -> {
			assertTrue(issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", BRANCH_MAIN).isEmpty());
			assertTrue(findingRepository.findScopedByJobId(
					scope.tenantId, incompleteRes.persistOutcome().orElseThrow().jobId()).stream()
					.allMatch(f -> "INCONCLUSIVE".equals(f.getImpactOutcome())));
			return null;
		});
	}

	@Test
	@DisplayName("M6-RV-07: Pre-flight STALE short-circuits cleanly, post-check STALE writes historical findings, UNKNOWN -> retry CURRENT opens issue, and retry -> pre-flight STALE transitions RETRY_PENDING -> SUPPRESSED")
	void rv07PreAndPostCurrentnessStaleUnknownAndDeferredRetryFlows() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant t0 = Instant.now();
		MutableClock clock = new MutableClock(t0);
		StubPorts ports = new StubPorts(clock);
		String repoStr = scope.repoId.toString();

		String path = "src/OrderService.java";
		String beforeSrc = "public class OrderService {\n    public int total(int qty) { return qty; }\n}\n";
		String afterSrc = "public class OrderService {\n    public long total(int qty) { return qty; }\n}\n";
		SymbolKey totalKey = methodKey("OrderService", "total", "int");
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(path, beforeSrc))),
						new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(path, afterSrc)),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, path, beforeSrc),
						presentProof(repoStr, SideRole.AFTER, SHA_C, path, afterSrc)),
				List.of(),
				Optional.empty()));
		DocumentClaim claim = new DocumentClaim(
				"claim-rv07", docId.toString(), "v1", SHA_A, SHA_C,
				"total(int)", path, Optional.of(totalKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Signature mismatch",
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Breaking return type", "api")));
		DocumentBinding binding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-rv07",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(totalKey), Optional.empty(), List.of(claim));
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.COMPLETE,
				req.lookupKeys().stream()
						.map(k -> new DocumentLookupResponse(
								k, LookupCompleteness.COMPLETE,
								k.sideRole() == SideRole.AFTER && k.granularity() == LookupGranularity.SYMBOL
										&& k.symbolKey().equals(Optional.of(totalKey)) ? List.of(binding) : List.of(),
								Optional.empty()))
						.toList(),
				Optional.empty());

		DriftAnalysisOrchestratorService orchestrator = newOrchestrator(ports, clock);

		// 1. Pre-flight STALE on new job -> 0 calls to resolveSnapshots/S04/S05, 0 DB rows!
		ports.currentnessResponder = callIdx -> new SourceAuthorityPort.CurrentnessCheckResult(
				SourceAuthorityPort.CurrentnessVerdict.STALE, Optional.empty(), Optional.of("HEAD_MOVED"));
		OrchestrationResult preStale = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-pre-stale", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, false));
		assertEquals(OrchestrationStatus.SUPPRESSED_STALE, preStale.status());
		assertEquals(CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED, preStale.checkpointDecision());
		assertEquals(0, ports.snapshotCalls.get());
		assertEquals(0, ports.mappingCalls.get());
		assertTrue(preStale.persistOutcome().isEmpty());

		// 2. Post-evaluation STALE -> persists findings historically with 0 issues.
		ports.currentnessCalls.set(0);
		ports.currentnessResponder = callIdx -> callIdx == 1
				? currentResult(scope, SHA_C, 10L, t0)
				: new SourceAuthorityPort.CurrentnessCheckResult(
						SourceAuthorityPort.CurrentnessVerdict.STALE,
						Optional.of(receipt(scope, SHA_D, 11L, t0)),
						Optional.of("HEAD_MOVED_DURING_ANALYSIS"));
		OrchestrationResult postStale = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-post-stale", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, false));
		assertEquals(OrchestrationStatus.SUPPRESSED_STALE, postStale.status());
		assertEquals(CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED, postStale.checkpointDecision());
		assertEquals(1, postStale.persistOutcome().orElseThrow().findingsPersisted());
		assertEquals(0, postStale.persistOutcome().orElseThrow().issuesOpened());

		// 3. Post-evaluation UNKNOWN -> DEFERRED_UNKNOWN_CURRENTNESS, Attempt 2 CURRENT -> APPLIED_CURRENT reusing finding PK!
		ports.currentnessCalls.set(0);
		ports.currentnessResponder = callIdx -> callIdx == 1
				? currentResult(scope, SHA_C, 10L, t0)
				: new SourceAuthorityPort.CurrentnessCheckResult(
						SourceAuthorityPort.CurrentnessVerdict.UNKNOWN, Optional.empty(), Optional.of("S02_TIMEOUT"));
		UUID deferredJobId = UUID.randomUUID();
		OrchestrationResult deferred = orchestrator.executeAnalysis(new AnalyzeTargetCommand(
				deferredJobId, "s03-worker", scope.tenantId, scope.workspaceId, scope.repoId,
				"job-def-retry", "attempt-1", t0.plusSeconds(300),
				TargetKind.TRACKED_BRANCH, BRANCH_MAIN, Intent.REVISION_COMPARISON,
				Optional.of(SHA_B), SHA_C, CFG_V1, false, List.of(), false,
				Duration.ofSeconds(5), Optional.empty(), Optional.empty()));
		assertEquals(OrchestrationStatus.DEFERRED_UNKNOWN_CURRENTNESS, deferred.status());
		assertTrue(deferred.retryable());

		UUID firstFindingId = tenantContext.readInTenant(scope.tenantId, () ->
				findingRepository.findScopedByJobId(scope.tenantId, deferredJobId).getFirst().getFindingId());

		ports.currentnessResponder = callIdx -> currentResult(scope, SHA_C, 12L, t0);
		OrchestrationResult retried = orchestrator.executeAnalysis(new AnalyzeTargetCommand(
				deferredJobId, "s03-worker", scope.tenantId, scope.workspaceId, scope.repoId,
				"job-def-retry", "attempt-2", t0.plusSeconds(600),
				TargetKind.TRACKED_BRANCH, BRANCH_MAIN, Intent.REVISION_COMPARISON,
				Optional.of(SHA_B), SHA_C, CFG_V1, false, List.of(), false,
				Duration.ofSeconds(5), Optional.empty(), Optional.empty()));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, retried.status());
		assertEquals(CheckpointDecision.ADVANCE_CHECKPOINT, retried.checkpointDecision());
		assertEquals(1, retried.persistOutcome().orElseThrow().issuesOpened());

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<AnalysisFindingEntity> findings = findingRepository.findScopedByJobId(scope.tenantId, deferredJobId);
			assertEquals(1, findings.size());
			assertEquals(firstFindingId, findings.getFirst().getFindingId());
			return null;
		});

		// 4. Another deferred job seeing pre-flight STALE on Attempt 2 -> transitions RETRY_PENDING -> SUPPRESSED!
		ports.currentnessCalls.set(0);
		ports.currentnessResponder = callIdx -> callIdx == 1
				? currentResult(scope, SHA_C, 13L, t0)
				: new SourceAuthorityPort.CurrentnessCheckResult(
						SourceAuthorityPort.CurrentnessVerdict.UNKNOWN, Optional.empty(), Optional.of("S02_TIMEOUT"));
		UUID defStaleJobId = UUID.randomUUID();
		OrchestrationResult defFirst = orchestrator.executeAnalysis(new AnalyzeTargetCommand(
				defStaleJobId, "s03-worker", scope.tenantId, scope.workspaceId, scope.repoId,
				"job-def-then-stale", "attempt-1", t0.plusSeconds(300),
				TargetKind.TRACKED_BRANCH, BRANCH_MAIN, Intent.REVISION_COMPARISON,
				Optional.of(SHA_B), SHA_C, CFG_V1, false, List.of(), false,
				Duration.ofSeconds(5), Optional.empty(), Optional.empty()));
		assertEquals(OrchestrationStatus.DEFERRED_UNKNOWN_CURRENTNESS, defFirst.status());

		ports.snapshotCalls.set(0);
		ports.currentnessResponder = callIdx -> new SourceAuthorityPort.CurrentnessCheckResult(
				SourceAuthorityPort.CurrentnessVerdict.STALE,
				Optional.of(receipt(scope, SHA_D, 14L, t0)),
				Optional.of("SUPERSEDED_BY_NEWER_HEAD"));
		OrchestrationResult defThenStale = orchestrator.executeAnalysis(new AnalyzeTargetCommand(
				defStaleJobId, "s03-worker", scope.tenantId, scope.workspaceId, scope.repoId,
				"job-def-then-stale", "attempt-2", t0.plusSeconds(600),
				TargetKind.TRACKED_BRANCH, BRANCH_MAIN, Intent.REVISION_COMPARISON,
				Optional.of(SHA_B), SHA_C, CFG_V1, false, List.of(), false,
				Duration.ofSeconds(5), Optional.empty(), Optional.empty()));
		assertEquals(OrchestrationStatus.SUPPRESSED_STALE, defThenStale.status());
		assertEquals(CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED, defThenStale.checkpointDecision());
		assertEquals(0, ports.snapshotCalls.get()); // Short-circuited without re-resolving snapshots!
		assertTrue(defThenStale.persistOutcome().isPresent());

		tenantContext.readInTenant(scope.tenantId, () -> {
			AnalysisJobEntity job = jobRepository.findScopedByExternalKey(scope.tenantId, "job-def-then-stale").orElseThrow();
			assertEquals("SUPPRESSED", job.getStatus());
			assertEquals("SUPPRESSED_STALE", job.getResultDisposition());
			assertEquals(14L, job.getAppliedSourceRevision());
			return null;
		});
	}

	@Test
	@DisplayName("M6-RV-08: Crash recovery after writer commit uses fast-path DB read to return IDEMPOTENT_REPLAY with 0 calls to S02/S04/S05 even when head moved")
	void rv08CrashAfterWriterCommitFastPathReplaySkipsAllDownstreamPorts() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant t0 = Instant.now();
		MutableClock clock = new MutableClock(t0);
		StubPorts ports = new StubPorts(clock);
		String repoStr = scope.repoId.toString();

		String path = "src/OrderService.java";
		String beforeSrc = "public class OrderService {\n    public int total(int qty) { return qty; }\n}\n";
		String afterSrc = "public class OrderService {\n    public long total(int qty) { return qty; }\n}\n";
		SymbolKey totalKey = methodKey("OrderService", "total", "int");
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(path, beforeSrc))),
						new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(path, afterSrc)),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, path, beforeSrc),
						presentProof(repoStr, SideRole.AFTER, SHA_C, path, afterSrc)),
				List.of(),
				Optional.empty()));
		DocumentClaim claim = new DocumentClaim(
				"claim-rv08", docId.toString(), "v1", SHA_A, SHA_C,
				"total(int)", path, Optional.of(totalKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Mismatch",
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Breaking return", "api")));
		DocumentBinding binding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-rv08",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(totalKey), Optional.empty(), List.of(claim));
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.COMPLETE,
				req.lookupKeys().stream()
						.map(k -> new DocumentLookupResponse(
								k, LookupCompleteness.COMPLETE,
								k.sideRole() == SideRole.AFTER && k.granularity() == LookupGranularity.SYMBOL
										&& k.symbolKey().equals(Optional.of(totalKey)) ? List.of(binding) : List.of(),
								Optional.empty()))
						.toList(),
				Optional.empty());

		DriftAnalysisOrchestratorService orchestrator = newOrchestrator(ports, clock);
		OrchestrationResult first = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-crash-replay", "attempt-1", t0.plusSeconds(300), SHA_B, SHA_C, false));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, first.status());

		// Simulate crash before checkpoint closed, AND move head to newer SHA + fail if S02/S04/S05 are touched!
		ports.currentnessCalls.set(0);
		ports.snapshotCalls.set(0);
		ports.mappingCalls.set(0);
		ports.semanticEvalCalls.set(0);
		ports.currentnessResponder = callIdx -> {
			throw new AssertionError("Fast-path finalized replay must not call SourceAuthorityPort.validateCurrentness");
		};
		ports.snapshotResponder = req -> {
			throw new AssertionError("Fast-path finalized replay must not call SourceAuthorityPort.resolveSnapshots");
		};
		ports.mappingResponder = req -> {
			throw new AssertionError("Fast-path finalized replay must not call DocumentMappingPort");
		};

		OrchestrationResult replay = orchestrator.executeAnalysis(
				revisionCommand(scope, "job-crash-replay", "attempt-2", t0.plusSeconds(600), SHA_B, SHA_C, false));
		assertEquals(OrchestrationStatus.IDEMPOTENT_REPLAY, replay.status());
		assertEquals(CheckpointDecision.ADVANCE_CHECKPOINT, replay.checkpointDecision());
		assertFalse(replay.retryable());
		assertEquals(1, replay.persistOutcome().orElseThrow().findingsPersisted());
		assertEquals(0, ports.currentnessCalls.get());
		assertEquals(0, ports.snapshotCalls.get());
		assertEquals(0, ports.mappingCalls.get());
	}

	@Test
	@DisplayName("M6-RV-09: Skipped-commit catch-up gap persists current SHA_C drift and advances fence while returning REQUIRES_CATCH_UP_RECONCILIATION")
	void rv09SkippedCommitCatchUpGapHoldsCheckpointWhileApplyingCurrentTargetDrift() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant t0 = Instant.now();
		MutableClock clock = new MutableClock(t0);
		StubPorts ports = new StubPorts(clock);
		String repoStr = scope.repoId.toString();

		String path = "src/OrderService.java";
		String beforeSrc = "public class OrderService {\n    public int total(int qty) { return qty; }\n}\n";
		String afterSrc = "public class OrderService {\n    public long total(int qty) { return qty; }\n}\n";
		SymbolKey totalKey = methodKey("OrderService", "total", "int");
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(path, beforeSrc))),
						new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(path, afterSrc)),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, path, beforeSrc),
						presentProof(repoStr, SideRole.AFTER, SHA_C, path, afterSrc)),
				List.of(),
				Optional.empty()));
		DocumentClaim claim = new DocumentClaim(
				"claim-rv09", docId.toString(), "v1", SHA_A, SHA_C,
				"total(int)", path, Optional.of(totalKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Mismatch",
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Breaking return", "api")));
		DocumentBinding binding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-rv09",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(totalKey), Optional.empty(), List.of(claim));
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.COMPLETE,
				req.lookupKeys().stream()
						.map(k -> new DocumentLookupResponse(
								k, LookupCompleteness.COMPLETE,
								k.sideRole() == SideRole.AFTER && k.granularity() == LookupGranularity.SYMBOL
										&& k.symbolKey().equals(Optional.of(totalKey)) ? List.of(binding) : List.of(),
								Optional.empty()))
						.toList(),
				Optional.empty());

		DriftAnalysisOrchestratorService orchestrator = newOrchestrator(ports, clock);
		// Previous checkpoint was SHA_A, but job analyzes SHA_B -> SHA_C (gap SHA_A -> SHA_B skipped!).
		OrchestrationResult res = orchestrator.executeAnalysis(new AnalyzeTargetCommand(
				UUID.randomUUID(), "s03-worker", scope.tenantId, scope.workspaceId, scope.repoId,
				"job-gap-catchup", "attempt-1", t0.plusSeconds(300),
				TargetKind.TRACKED_BRANCH, BRANCH_MAIN, Intent.REVISION_COMPARISON,
				Optional.of(SHA_B), SHA_C, CFG_V1, false, List.of(), false,
				Duration.ofSeconds(5), Optional.empty(), Optional.of(SHA_A)));
		assertEquals(OrchestrationStatus.APPLIED_CURRENT, res.status());
		assertEquals(CheckpointDecision.REQUIRES_CATCH_UP_RECONCILIATION, res.checkpointDecision());
		assertEquals(1, res.persistOutcome().orElseThrow().issuesOpened());

		tenantContext.readInTenant(scope.tenantId, () -> {
			assertEquals(1, issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", BRANCH_MAIN).size());
			return null;
		});
	}

	@Test
	@DisplayName("M6-RV-10: Scope DENIED at every port boundary returns DENIED_SCOPE with 0 DB rows persisted")
	void rv10ScopeDenialAcrossAllBoundariesWritesZeroRows() {
		Instant t0 = Instant.now();

		// Boundary 1: ScopeAuthorizationPort DENIED
		verifyDeniedWritesZeroRows(t0, "auth-denied", ports -> ports.authDecision = new ScopeAuthorizationPort.AuthorizationDecision(
				ScopeAuthorizationPort.DecisionStatus.DENIED, Optional.of("AUTHZ_DENIED"), Optional.empty()));

		// Boundary 2: Pre-flight validateCurrentness DENIED
		verifyDeniedWritesZeroRows(t0, "preflight-denied", ports -> ports.currentnessResponder = idx ->
				new SourceAuthorityPort.CurrentnessCheckResult(
						SourceAuthorityPort.CurrentnessVerdict.DENIED, Optional.empty(), Optional.of("REPO_REVOKED")));

		// Boundary 3: resolveSnapshots DENIED
		verifyDeniedWritesZeroRows(t0, "snapshots-denied", ports -> ports.setResolved(
				new SourceAuthorityPort.ResolvedSnapshotsResult(
						SourceAuthorityPort.ResolutionStatus.DENIED, Optional.empty(), List.of(), List.of(), List.of(),
						Optional.of("SNAPSHOT_DENIED"))));

		// Boundary 4: DocumentMappingPort DENIED
		ScopeIds s4 = ScopeIds.fresh();
		MutableClock c4 = new MutableClock(t0);
		StubPorts p4 = setupSingleFileStub(s4, c4, false);
		p4.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.DENIED, List.of(), Optional.of("S04_DENIED"));
		assertDeniedEmptyTenant(s4, c4, p4, "s04-denied", false);

		// Boundary 5: SemanticAssessmentPort DENIED
		ScopeIds s5 = ScopeIds.fresh();
		MutableClock c5 = new MutableClock(t0);
		StubPorts p5 = setupSemanticCandidateStub(s5, c5);
		p5.semanticEvaluator = req -> new SemanticAssessmentPort.SemanticExecutionResult(
				SemanticAssessmentPort.ExecutionStatus.DENIED, Optional.empty(), List.of(), Optional.of("S05_DENIED"));
		assertDeniedEmptyTenant(s5, c5, p5, "s05-denied", true);

		// Boundary 6: Post-evaluation validateCurrentness DENIED
		ScopeIds s6 = ScopeIds.fresh();
		MutableClock c6 = new MutableClock(t0);
		StubPorts p6 = setupSingleFileStub(s6, c6, true);
		p6.currentnessResponder = idx -> idx == 1
				? currentResult(s6, SHA_C, 1L, t0)
				: new SourceAuthorityPort.CurrentnessCheckResult(
						SourceAuthorityPort.CurrentnessVerdict.DENIED, Optional.empty(), Optional.of("TOKEN_REVOKED"));
		assertDeniedEmptyTenant(s6, c6, p6, "postcheck-denied", false);
	}

	private void verifyDeniedWritesZeroRows(Instant t0, String jobKey, java.util.function.Consumer<StubPorts> setup) {
		ScopeIds scope = ScopeIds.fresh();
		MutableClock clock = new MutableClock(t0);
		StubPorts ports = new StubPorts(clock);
		setup.accept(ports);
		assertDeniedEmptyTenant(scope, clock, ports, jobKey, false);
	}

	private void assertDeniedEmptyTenant(
			ScopeIds scope, MutableClock clock, StubPorts ports, String jobKey, boolean semanticEnabled) {
		OrchestrationResult res = newOrchestrator(ports, clock).executeAnalysis(revisionCommand(
				scope, jobKey, "attempt-1", clock.instant().plusSeconds(300), SHA_B, SHA_C, semanticEnabled));
		assertEquals(OrchestrationStatus.DENIED_SCOPE, res.status());
		assertEquals(CheckpointDecision.HOLD_CHECKPOINT_NOT_APPLIED, res.checkpointDecision());
		assertFalse(res.retryable());
		assertTrue(res.persistOutcome().isEmpty());
		tenantContext.readInTenant(scope.tenantId, () -> {
			assertTrue(jobRepository.findAll().isEmpty());
			assertTrue(issueRepository.findAll().isEmpty());
			assertTrue(findingRepository.findAll().isEmpty());
			return null;
		});
	}

	private StubPorts setupSingleFileStub(ScopeIds scope, MutableClock clock, boolean emptyMappingOk) {
		StubPorts ports = new StubPorts(clock);
		String repoStr = scope.repoId.toString();
		String path = "src/Denial.java";
		String beforeSrc = "class Denial { int x() { return 1; } }\n";
		String afterSrc = "class Denial { int x() { return 2; } }\n";
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(path, beforeSrc))),
						new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(path, afterSrc)),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, path, beforeSrc),
						presentProof(repoStr, SideRole.AFTER, SHA_C, path, afterSrc)),
				List.of(),
				Optional.empty()));
		if (emptyMappingOk) {
			ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
					DocumentMappingPort.MappingLookupStatus.COMPLETE,
					req.lookupKeys().stream()
							.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty()))
							.toList(),
					Optional.empty());
		}
		return ports;
	}

	private StubPorts setupSemanticCandidateStub(ScopeIds scope, MutableClock clock) {
		StubPorts ports = new StubPorts(clock);
		String repoStr = scope.repoId.toString();
		String path = "src/Fee.java";
		String srcV1 = "class Fee { int fee(int d) { return d * 5; } }\n";
		String srcV2 = "class Fee { int fee(int d) { return d * 10; } }\n";
		SymbolKey feeKey = methodKey("Fee", "fee", "int");
		ports.setResolved(new SourceAuthorityPort.ResolvedSnapshotsResult(
				SourceAuthorityPort.ResolutionStatus.RESOLVED,
				Optional.empty(),
				List.of(new SourceAuthorityPort.SnapshotPair(
						Optional.of(new Present(new SnapshotRef(repoStr, SHA_B), new SourceInput(path, srcV1))),
						new Present(new SnapshotRef(repoStr, SHA_C), new SourceInput(path, srcV2)),
						Optional.empty())),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, path, srcV1),
						presentProof(repoStr, SideRole.AFTER, SHA_C, path, srcV2)),
				List.of(),
				Optional.empty()));
		UUID docId = UUID.randomUUID();
		DocumentClaim claim = new DocumentClaim(
				"claim-fee-deny", docId.toString(), "v1", SHA_A, SHA_C,
				"Fee#fee(int)", path, Optional.of(feeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY, "Formula body changed", Optional.empty());
		DocumentBinding binding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(feeKey), Optional.of("sec-fee"), List.of(claim));
		ports.mappingResponder = req -> new DocumentMappingPort.MappingLookupResult(
				DocumentMappingPort.MappingLookupStatus.COMPLETE,
				req.lookupKeys().stream()
						.map(k -> new DocumentLookupResponse(
								k, LookupCompleteness.COMPLETE,
								k.sideRole() == SideRole.AFTER && k.granularity() == LookupGranularity.SYMBOL
										&& k.symbolKey().equals(Optional.of(feeKey)) ? List.of(binding) : List.of(),
								Optional.empty()))
						.toList(),
				Optional.empty());
		return ports;
	}

	private DriftAnalysisOrchestratorService newOrchestrator(StubPorts ports, Clock clock) {
		return new DriftAnalysisOrchestratorServiceImpl(
				ports.authPort(),
				ports.sourcePort(),
				ports.mappingPort(),
				ports.semanticPort(),
				writerService,
				tenantContext,
				jobRepository,
				findingRepository,
				jsonMapper,
				clock);
	}

	private static AnalyzeTargetCommand revisionCommand(
			ScopeIds scope,
			String externalJobKey,
			String attemptId,
			Instant leaseExpiresAt,
			String beforeSha,
			String targetSha,
			boolean semanticEnabled) {
		return new AnalyzeTargetCommand(
				UUID.randomUUID(),
				"s03-worker",
				scope.tenantId,
				scope.workspaceId,
				scope.repoId,
				externalJobKey,
				attemptId,
				leaseExpiresAt,
				TargetKind.TRACKED_BRANCH,
				BRANCH_MAIN,
				Intent.REVISION_COMPARISON,
				Optional.of(beforeSha),
				targetSha,
				CFG_V1,
				false,
				List.of(),
				semanticEnabled,
				Duration.ofSeconds(5),
				Optional.empty(),
				Optional.empty());
	}

	private static SourceAuthorityPort.CurrentnessCheckResult currentResult(
			ScopeIds scope, String sha, long rev, Instant now) {
		return new SourceAuthorityPort.CurrentnessCheckResult(
				SourceAuthorityPort.CurrentnessVerdict.CURRENT,
				Optional.of(receipt(scope, sha, rev, now)),
				Optional.empty());
	}

	private static SourceAuthorityPort.ValidationReceipt receipt(
			ScopeIds scope, String sha, long rev, Instant now) {
		return new SourceAuthorityPort.ValidationReceipt(
				UUID.randomUUID(),
				scope.tenantId,
				scope.repoId,
				TargetKind.TRACKED_BRANCH,
				BRANCH_MAIN,
				sha,
				CFG_V1,
				rev,
				Optional.of("fence-" + rev),
				now,
				now.plus(Duration.ofMinutes(5)));
	}

	private static SymbolKey methodKey(String ownerType, String name, String... paramTokens) {
		List<SyntacticTypeKey> params = java.util.Arrays.stream(paramTokens)
				.map(t -> new SyntacticTypeKey(List.of(t), false))
				.toList();
		return new SymbolKey(Kind.METHOD, Optional.empty(), List.of(ownerType), name, params);
	}

	private static SnapshotProof presentProof(String repo, SideRole role, String sha, String path, String src) {
		return new SnapshotProof(
				role,
				new SnapshotProvenance(new SnapshotRef(repo, sha), path, SidePresence.PRESENT,
						Optional.empty(), Optional.empty()),
				Optional.of(src));
	}

	private record ScopeIds(UUID tenantId, UUID workspaceId, UUID repoId) {
		static ScopeIds fresh() {
			return new ScopeIds(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		}
	}

	private static final class MutableClock extends Clock {
		private final AtomicReference<Instant> current;

		MutableClock(Instant initial) {
			this.current = new AtomicReference<>(initial);
		}

		void set(Instant next) {
			this.current.set(next);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current.get();
		}
	}

	private static final class StubPorts {
		private final Clock clock;
		final AtomicInteger authCalls = new AtomicInteger();
		final AtomicInteger currentnessCalls = new AtomicInteger();
		final AtomicInteger snapshotCalls = new AtomicInteger();
		final AtomicInteger mappingCalls = new AtomicInteger();
		final AtomicInteger semanticEvalCalls = new AtomicInteger();
		final AtomicInteger semanticReconcileCalls = new AtomicInteger();
		final AtomicReference<Long> currentRevision = new AtomicReference<>(1L);

		volatile ScopeAuthorizationPort.AuthorizationDecision authDecision =
				new ScopeAuthorizationPort.AuthorizationDecision(
						ScopeAuthorizationPort.DecisionStatus.AUTHORIZED, Optional.empty(), Optional.of(1L));

		volatile Function<Integer, SourceAuthorityPort.CurrentnessCheckResult> currentnessResponder;
		volatile Function<SourceAuthorityPort.SnapshotResolutionRequest, SourceAuthorityPort.ResolvedSnapshotsResult> snapshotResponder;
		volatile Function<DocumentMappingPort.MappingLookupRequest, DocumentMappingPort.MappingLookupResult> mappingResponder =
				req -> new DocumentMappingPort.MappingLookupResult(
						DocumentMappingPort.MappingLookupStatus.COMPLETE, List.of(), Optional.empty());
		volatile Function<SemanticAssessmentPort.SemanticExecutionRequest, SemanticAssessmentPort.SemanticExecutionResult> semanticEvaluator =
				req -> new SemanticAssessmentPort.SemanticExecutionResult(
						SemanticAssessmentPort.ExecutionStatus.COMPLETED, Optional.empty(), List.of(), Optional.empty());
		volatile Function<SemanticAssessmentPort.SemanticReconcileRequest, SemanticAssessmentPort.SemanticExecutionResult> semanticReconciler =
				req -> new SemanticAssessmentPort.SemanticExecutionResult(
						SemanticAssessmentPort.ExecutionStatus.UNAVAILABLE, Optional.of(req.executionId()), List.of(), Optional.of("NOT_READY"));

		StubPorts(Clock clock) {
			this.clock = clock;
		}

		void setResolved(SourceAuthorityPort.ResolvedSnapshotsResult resolved) {
			this.snapshotResponder = req -> resolved;
		}

		ScopeAuthorizationPort authPort() {
			return new ScopeAuthorizationPort() {
				@Override
				public AuthorizationDecision verifyUserScope(UserScopeRequest request) {
					assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
					return authDecision;
				}

				@Override
				public AuthorizationDecision verifyWorkerScope(WorkerScopeRequest request) {
					assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
					authCalls.incrementAndGet();
					return authDecision;
				}
			};
		}

		SourceAuthorityPort sourcePort() {
			return new SourceAuthorityPort() {
				@Override
				public CurrentnessCheckResult validateCurrentness(CurrentnessCheckRequest request) {
					assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
					int call = currentnessCalls.incrementAndGet();
					if (currentnessResponder != null) {
						return currentnessResponder.apply(call);
					}
					Instant now = clock.instant();
					return new CurrentnessCheckResult(
							CurrentnessVerdict.CURRENT,
							Optional.of(new ValidationReceipt(
									UUID.randomUUID(), request.tenantId(), request.repositoryConnectionId(),
									request.targetKind(), request.targetRef(), request.targetSha(),
									request.configRevision(), currentRevision.get(),
									Optional.of("fence-" + currentRevision.get()),
									now, now.plus(Duration.ofMinutes(5)))),
							Optional.empty());
				}

				@Override
				public ResolvedSnapshotsResult resolveSnapshots(SnapshotResolutionRequest request) {
					assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
					snapshotCalls.incrementAndGet();
					return snapshotResponder.apply(request);
				}
			};
		}

		DocumentMappingPort mappingPort() {
			return request -> {
				assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
				mappingCalls.incrementAndGet();
				return mappingResponder.apply(request);
			};
		}

		SemanticAssessmentPort semanticPort() {
			return new SemanticAssessmentPort() {
				@Override
				public SemanticExecutionResult evaluate(SemanticExecutionRequest request) {
					assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
					semanticEvalCalls.incrementAndGet();
					return semanticEvaluator.apply(request);
				}

				@Override
				public SemanticExecutionResult reconcileTimedOutExecution(SemanticReconcileRequest request) {
					assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
					semanticReconcileCalls.incrementAndGet();
					return semanticReconciler.apply(request);
				}
			};
		}
	}
}
