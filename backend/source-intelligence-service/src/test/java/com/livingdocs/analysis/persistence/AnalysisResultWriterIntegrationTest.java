package com.livingdocs.analysis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.livingdocs.analysis.ast.JavaParseResult.Kind;
import com.livingdocs.analysis.ast.JavaParseResult.SymbolKey;
import com.livingdocs.analysis.ast.JavaStructuralRepresentation.FacetKind;
import com.livingdocs.analysis.ast.SourceInput;
import com.livingdocs.analysis.diff.StructuralDiff;
import com.livingdocs.analysis.diff.StructuralDiff.ChangeKind;
import com.livingdocs.analysis.diff.StructuralDiff.EntitySide;
import com.livingdocs.analysis.diff.StructuralDiff.SidePresence;
import com.livingdocs.analysis.diff.StructuralDiff.SideRole;
import com.livingdocs.analysis.diff.StructuralDiff.SnapshotProvenance;
import com.livingdocs.analysis.diff.StructuralDiff.StructuralDiffResult;
import com.livingdocs.analysis.diff.StructuralDiffInput;
import com.livingdocs.analysis.diff.StructuralDiffInput.Intent;
import com.livingdocs.analysis.diff.StructuralDiffInput.Present;
import com.livingdocs.analysis.diff.StructuralDiffInput.SnapshotRef;
import com.livingdocs.analysis.entity.AnalysisFindingEntity;
import com.livingdocs.analysis.entity.AnalysisJobEntity;
import com.livingdocs.analysis.entity.DriftEvidenceEntity;
import com.livingdocs.analysis.entity.DriftIssueEntity;
import com.livingdocs.analysis.entity.TargetAnalysisFenceEntity;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceInput;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceResult;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.EvidenceStatus;
import com.livingdocs.analysis.evidence.SourceEvidenceMapper.SnapshotProof;
import com.livingdocs.analysis.impact.ImpactClassifier;
import com.livingdocs.analysis.impact.ImpactClassifier.AssessmentContext;
import com.livingdocs.analysis.impact.ImpactClassifier.BindingGranularity;
import com.livingdocs.analysis.impact.ImpactClassifier.ClaimVerdict;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentBinding;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentClaim;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentLookupKey;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentLookupResponse;
import com.livingdocs.analysis.impact.ImpactClassifier.DocumentWitnessSelector;
import com.livingdocs.analysis.impact.ImpactClassifier.GroundedDocumentClaimRef;
import com.livingdocs.analysis.impact.ImpactClassifier.GroundedSourceFacetRef;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactClassificationInput;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactFinding;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactOutcome;
import com.livingdocs.analysis.impact.ImpactClassifier.ImpactReport;
import com.livingdocs.analysis.impact.ImpactClassifier.LookupCompleteness;
import com.livingdocs.analysis.impact.ImpactClassifier.LookupGranularity;
import com.livingdocs.analysis.impact.ImpactClassifier.ScopeCompleteness;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticAssessmentInput;
import com.livingdocs.analysis.impact.ImpactClassifier.SemanticStatus;
import com.livingdocs.analysis.impact.ImpactClassifier.Severity;
import com.livingdocs.analysis.impact.ImpactClassifier.TargetKind;
import com.livingdocs.analysis.impact.ImpactClassifier.VerifiedImpactContext;
import com.livingdocs.analysis.model.AnalysisPersistenceModels;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.ApplyResultStatus;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.AuthorizedJobWriteContext;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.BindingConflictException;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.CurrentnessProof;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.CurrentnessReceiptState;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.PersistAnalysisCommand;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.PersistAnalysisOutcome;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.ScopeUnauthorizedException;
import com.livingdocs.analysis.model.AnalysisPersistenceModels.StaleAttemptException;
import com.livingdocs.analysis.port.SourceAuthorityPort.ValidationReceipt;
import com.livingdocs.analysis.repository.AnalysisFindingRepository;
import com.livingdocs.analysis.repository.AnalysisJobRepository;
import com.livingdocs.analysis.repository.DriftEvidenceRepository;
import com.livingdocs.analysis.repository.DriftIssueRepository;
import com.livingdocs.analysis.repository.TargetAnalysisFenceRepository;
import com.livingdocs.analysis.security.S03TenantContext;
import com.livingdocs.analysis.service.AnalysisResultWriterService;
import com.livingdocs.analysis.service.impl.AnalysisResultWriterServiceImpl;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers
class AnalysisResultWriterIntegrationTest {

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	private static final String SHA_C = "cccccccccccccccccccccccccccccccccccccccc";
	private static final String SHA_D = "dddddddddddddddddddddddddddddddddddddddd";
	private static final String SHA_E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
	private static final String CFG_V1 = "cfg-rev-1";

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
		// Constrain pool size to force frequent connection reuse across tenant transactions (M5-RV-12).
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
	}

	@Autowired
	private AnalysisResultWriterService writerService;

	@Autowired
	private S03TenantContext tenantContext;

	@Autowired
	private AnalysisJobRepository jobRepository;

	@Autowired
	private TargetAnalysisFenceRepository fenceRepository;

	@Autowired
	private DriftIssueRepository issueRepository;

	@Autowired
	private AnalysisFindingRepository findingRepository;

	@Autowired
	private DriftEvidenceRepository evidenceRepository;

	@Autowired
	private JdbcTemplate runtimeJdbc;

	@Autowired
	private JsonMapper jsonMapper;

	private final StructuralDiff structuralDiff = new StructuralDiff();
	private final ImpactClassifier classifier = new ImpactClassifier();
	private final SourceEvidenceMapper evidenceMapper = new SourceEvidenceMapper();

	@Test
	@DisplayName("M5-RV-01: Clean V1->V2 install, runtime role restrictions (flyway_schema_history & append-only drift_evidence), and V1->V2 upgrade with pre-existing job")
	void rv01CleanInstallRoleRestrictionsAndV1ToV2Upgrade() throws Exception {
		// 1. Runtime role is ld_s03_app without BYPASSRLS, and all 5 tables enforce RLS.
		assertEquals("ld_s03_app", runtimeJdbc.queryForObject("SELECT current_user", String.class));

		JdbcTemplate adminJdbc = adminJdbc(POSTGRES.getJdbcUrl());
		Boolean bypassRls = adminJdbc.queryForObject(
				"SELECT rolbypassrls FROM pg_roles WHERE rolname = 'ld_s03_app'", Boolean.class);
		assertEquals(Boolean.FALSE, bypassRls);

		Integer rlsTableCount = adminJdbc.queryForObject("""
				SELECT count(*) FROM pg_class c
				JOIN pg_namespace n ON n.oid = c.relnamespace
				WHERE n.nspname = 'intelligence'
					AND c.relkind = 'r'
					AND c.relname <> 'flyway_schema_history'
					AND c.relrowsecurity
				""", Integer.class);
		assertEquals(5, rlsTableCount);

		// 2. Runtime role cannot read flyway_schema_history and lacks UPDATE/DELETE on drift_evidence.
		assertThrows(DataAccessException.class, () -> runtimeJdbc.queryForObject(
				"SELECT count(*) FROM intelligence.flyway_schema_history", Integer.class));
		assertEquals(Boolean.FALSE, runtimeJdbc.queryForObject(
				"SELECT has_table_privilege('ld_s03_app', 'intelligence.drift_evidence', 'UPDATE')", Boolean.class));
		assertEquals(Boolean.FALSE, runtimeJdbc.queryForObject(
				"SELECT has_table_privilege('ld_s03_app', 'intelligence.drift_evidence', 'DELETE')", Boolean.class));

		// 3. Upgrade test: create isolated DB, migrate ONLY V1, insert a CLAIMED job as ld_s03_app, then upgrade to V2.
		String baseJdbcPrefix = POSTGRES.getJdbcUrl().substring(0, POSTGRES.getJdbcUrl().indexOf("/livingdocs"));
		String querySuffix = POSTGRES.getJdbcUrl().contains("?")
				? POSTGRES.getJdbcUrl().substring(POSTGRES.getJdbcUrl().indexOf("?"))
				: "";
		try (Connection conn = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE DATABASE livingdocs_v1_upgrade");
		}
		String upgradeUrl = baseJdbcPrefix + "/livingdocs_v1_upgrade" + querySuffix;
		JdbcTemplate upgradeAdmin = adminJdbc(upgradeUrl);
		upgradeAdmin.execute("CREATE SCHEMA intelligence AUTHORIZATION postgres");
		upgradeAdmin.execute("GRANT USAGE ON SCHEMA intelligence TO ld_s03_app");

		Flyway flywayV1 = Flyway.configure()
				.dataSource(upgradeUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
				.schemas("intelligence")
				.defaultSchema("intelligence")
				.locations("classpath:db/migration")
				.target("1")
				.load();
		assertEquals(1, flywayV1.migrate().migrationsExecuted);

		UUID upgradeTenant = UUID.randomUUID();
		UUID upgradeWorkspace = UUID.randomUUID();
		UUID upgradeRepo = UUID.randomUUID();
		UUID seededJobId = UUID.randomUUID();
		DriverManagerDataSource upgradeDs = new DriverManagerDataSource(
				upgradeUrl, "ld_s03_app", "test_app_password");
		JdbcTemplate upgradeApp = new JdbcTemplate(upgradeDs);
		TransactionTemplate upgradeTx = new TransactionTemplate(new DataSourceTransactionManager(upgradeDs));
		upgradeTx.execute(status -> {
			upgradeApp.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, upgradeTenant.toString());
			upgradeApp.update("""
					INSERT INTO intelligence.analysis_jobs (
						job_id, tenant_id, workspace_id, repository_connection_id, external_job_key,
						target_kind, target_ref, comparison_intent, comparison_before_sha,
						authoritative_target_sha, config_revision, declared_scope_completeness,
						whitespace_no_impact_enabled, status, current_attempt_id, lease_expires_at
					) VALUES (?, ?, ?, ?, 'v1-seeded-job', 'TRACKED_BRANCH', 'refs/heads/main',
						'REVISION_COMPARISON', ?, ?, ?, 'COMPLETE', false, 'CLAIMED', 'attempt-1', ?)
					""",
					seededJobId, upgradeTenant, upgradeWorkspace, upgradeRepo,
					SHA_B, SHA_C, CFG_V1, Timestamp.from(Instant.now().plusSeconds(600)));
			return null;
		});

		// Upgrade V1 -> V2 and prove composite FK from V2 analysis_findings links to the V1 job.
		Flyway flywayV2 = Flyway.configure()
				.dataSource(upgradeUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
				.schemas("intelligence")
				.defaultSchema("intelligence")
				.locations("classpath:db/migration")
				.load();
		assertEquals(1, flywayV2.migrate().migrationsExecuted);

		upgradeTx.execute(status -> {
			upgradeApp.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, upgradeTenant.toString());
			int inserted = upgradeApp.update("""
					INSERT INTO intelligence.analysis_findings (
						finding_id, tenant_id, job_id, deterministic_finding_key, unit_disposition,
						impact_outcome, severity_status, issue_admission, primary_reason,
						admission_reason, evidence_status, evidence_schema_version, evidence_payload
					) VALUES (?, ?, ?, 'finding:v1-upgrade-check', 'EVALUATED', 'NO_IMPACT',
						'SEVERITY_NOT_EVALUATED', 'NOT_APPLICABLE', 'COMPLETE_EMPTY_MAPPING',
						'NOT_APPLICABLE', 'VERIFIED', 'm5-finding-evidence-v1', '{"schemaVersion":"m5-finding-evidence-v1"}'::jsonb)
					""", UUID.randomUUID(), upgradeTenant, seededJobId);
			assertEquals(1, inserted);
			return null;
		});
	}

	@Test
	@DisplayName("M5-RV-02: ACTIONABLE signature mismatch (including PARTIAL optional witness) opens ANALYZED DriftIssue, stores JSONB once on finding, and links DriftEvidence")
	void rv02ActionableAndOptionalPartialWitnessOpenAnalyzedIssue() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant now = Instant.now();

		var run = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv02-1", "attempt-1", SHA_B, SHA_C, docId, "claim-sig-1", true);
		assertEquals(EvidenceStatus.PARTIAL, run.evidenceResult().status());
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), run.evidenceResult().overallOutcome());

		PersistAnalysisOutcome outcome = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), run.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 10L, now),
				run.evidenceResult(),
				now));

		assertEquals(ApplyResultStatus.APPLIED_CURRENT, outcome.status());
		assertEquals(1, outcome.findingsPersisted());
		assertEquals(1, outcome.issuesOpened());
		assertEquals(0, outcome.issuesUpdated());
		assertEquals(1, outcome.observationsRecorded());
		assertTrue(outcome.coverageCheckpointEligible());

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<DriftIssueEntity> issues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main");
			assertEquals(1, issues.size());
			DriftIssueEntity issue = issues.getFirst();
			assertEquals("ANALYZED", issue.getState());
			assertEquals("SIGNATURE", issue.getDriftClassification());
			assertEquals("EVALUATED", issue.getSeverityStatus());
			assertEquals("HIGH", issue.getSeverity());
			assertEquals(SHA_C, issue.getOpenedAtSha());
			assertEquals(SHA_C, issue.getLastObservedSha());
			assertFalse(issue.isRevertObserved());

			List<AnalysisFindingEntity> findings = findingRepository.findScopedByJobId(scope.tenantId, outcome.jobId());
			assertEquals(1, findings.size());
			AnalysisFindingEntity finding = findings.getFirst();
			assertEquals(issue.getDriftIssueId(), finding.getDriftIssueId());
			assertEquals(AnalysisPersistenceModels.EVIDENCE_SCHEMA_VERSION, finding.getEvidenceSchemaVersion());
			assertTrue(finding.getEvidencePayload().contains("\"m5-finding-evidence-v1\""));

			List<DriftEvidenceEntity> obs = evidenceRepository.findScopedByIssueId(scope.tenantId, issue.getDriftIssueId());
			assertEquals(1, obs.size());
			assertEquals("MISMATCH_ANALYZED", obs.getFirst().getObservationKind());
			assertEquals(finding.getFindingId(), obs.getFirst().getFindingId());
			assertEquals(SHA_C, obs.getFirst().getEvaluatedTargetSha());
			return null;
		});
	}

	@Test
	@DisplayName("M5-RV-03: INCONCLUSIVE + SUPPORTED_BY_INITIAL_EVIDENCE opens DETECTED, upgrades to ANALYZED on S05 confirmation, and never regresses state on later INCONCLUSIVE or SUGGESTED")
	void rv03InconclusiveOpensDetectedPromotesToAnalyzedAndNeverRegressesState() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-26T08:00:00Z");

		// 1. Body change without S05 -> INCONCLUSIVE + SUPPORTED_BY_INITIAL_EVIDENCE -> DETECTED.
		var initialRun = buildSemanticBodyPipeline(
				scope, "job-rv03-1", "attempt-1", SHA_B, SHA_C,
				"class Fee { int fee(int d) { return d * 5; } }\n",
				"class Fee { int fee(int d) { return d * 10; } }\n",
				docId, "claim-fee", Optional.empty());
		assertEquals(Optional.of(ImpactOutcome.INCONCLUSIVE), initialRun.evidenceResult().overallOutcome());

		PersistAnalysisOutcome out1 = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), initialRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 1L, Instant.now()),
				initialRun.evidenceResult(),
				t1));
		assertEquals(1, out1.issuesOpened());

		UUID issueId = tenantContext.readInTenant(scope.tenantId, () -> {
			DriftIssueEntity issue = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").getFirst();
			assertEquals("DETECTED", issue.getState());
			assertNull(issue.getDriftClassification());
			assertEquals("SEVERITY_NOT_EVALUATED", issue.getSeverityStatus());
			return issue.getDriftIssueId();
		});

		// 2. Deeper S05 confirmation on SHA_D -> promotes SAME DriftIssue from DETECTED to ANALYZED + SEMANTIC.
		var confirmedRun = buildSemanticBodyPipeline(
				scope, "job-rv03-2", "attempt-1", SHA_C, SHA_D,
				"class Fee { int fee(int d) { return d * 10; } }\n",
				"class Fee { int fee(int d) { return d * 20; } }\n",
				docId, "claim-fee", Optional.of(SemanticStatus.BEHAVIOR_MISMATCH));
		assertEquals(Optional.of(ImpactOutcome.ACTIONABLE), confirmedRun.evidenceResult().overallOutcome());

		PersistAnalysisOutcome out2 = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), confirmedRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_D, 2L, Instant.now()),
				confirmedRun.evidenceResult(),
				t1.plusSeconds(60)));
		assertEquals(0, out2.issuesOpened());
		assertEquals(1, out2.issuesUpdated());

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<DriftIssueEntity> issues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main");
			assertEquals(1, issues.size());
			DriftIssueEntity issue = issues.getFirst();
			assertEquals(issueId, issue.getDriftIssueId());
			assertEquals("ANALYZED", issue.getState());
			assertEquals("SEMANTIC", issue.getDriftClassification());
			assertEquals(SHA_D, issue.getLastObservedSha());
			return null;
		});

		// 3. Subsequent S05 timeout on SHA_E (INCONCLUSIVE + SUPPORTED) keeps ANALYZED, never regresses to DETECTED!
		var laterTimeoutRun = buildSemanticBodyPipeline(
				scope, "job-rv03-3", "attempt-1", SHA_D, SHA_E,
				"class Fee { int fee(int d) { return d * 20; } }\n",
				"class Fee { int fee(int d) { return d * 30; } }\n",
				docId, "claim-fee", Optional.empty());
		writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), laterTimeoutRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_E, 3L, Instant.now()),
				laterTimeoutRun.evidenceResult(),
				t1.plusSeconds(120)));

		// Move state to SUGGESTED and verify another ACTIONABLE observation keeps SUGGESTED without regressing to ANALYZED.
		tenantContext.writeInTenant(scope.tenantId, () -> {
			runtimeJdbc.update(
					"UPDATE intelligence.drift_issues SET state = 'SUGGESTED' WHERE drift_issue_id = ?", issueId);
			return null;
		});
		var whileSuggestedRun = buildSemanticBodyPipeline(
				scope, "job-rv03-4", "attempt-1", SHA_B, SHA_E,
				"class Fee { int fee(int d) { return d * 5; } }\n",
				"class Fee { int fee(int d) { return d * 30; } }\n",
				docId, "claim-fee", Optional.of(SemanticStatus.BEHAVIOR_MISMATCH));
		writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), whileSuggestedRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_E, 4L, Instant.now()),
				whileSuggestedRun.evidenceResult(),
				t1.plusSeconds(180)));

		tenantContext.readInTenant(scope.tenantId, () -> {
			DriftIssueEntity issue = issueRepository.findById(issueId).orElseThrow();
			assertEquals("SUGGESTED", issue.getState());
			assertEquals("SEMANTIC", issue.getDriftClassification());
			assertEquals(SHA_E, issue.getLastObservedSha());
			assertEquals(4, evidenceRepository.findScopedByIssueId(scope.tenantId, issueId).size());
			return null;
		});
	}

	@Test
	@DisplayName("M5-RV-04: Selective persistence keeps NO_IMPACT, EXCLUDED, and INSUFFICIENT_EVIDENCE findings with JSONB and null drift_issue_id without fabricating issues or unchanged-symbol rows")
	void rv04SelectivePersistenceNeverFabricatesIssues() {
		ScopeIds scope = ScopeIds.fresh();
		String repoStr = scope.repoId.toString();
		AssessmentContext ctx = new AssessmentContext(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				TargetKind.TRACKED_BRANCH, "refs/heads/main", "job-rv04", "attempt-1",
				Optional.of(CFG_V1), SHA_C, Optional.of(SHA_B),
				Intent.REVISION_COMPARISON, ScopeCompleteness.COMPLETE, false, Optional.empty());

		// File 1: Java class with 1 modified method (empty lookup -> NO_IMPACT) and 3 unchanged methods.
		String javaPath = "src/Util.java";
		String javaBefore = "class Util {\n    void changed() { int a = 1; }\n    void u1() {}\n    void u2() {}\n    void u3() {}\n}\n";
		String javaAfter = "class Util {\n    void changed() { int a = 2; }\n    void u1() {}\n    void u2() {}\n    void u3() {}\n}\n";
		StructuralDiffResult javaDiff = compareJava(repoStr, javaPath, SHA_B, javaBefore, javaPath, SHA_C, javaAfter);

		// File 2: Java class with weak FILE_ONLY mapping and no claim -> INCONCLUSIVE + INSUFFICIENT_EVIDENCE.
		String weakPath = "src/WeakMapped.java";
		String weakBefore = "class WeakMapped { int run() { return 1; } }\n";
		String weakAfter = "class WeakMapped { int run() { return 2; } }\n";
		StructuralDiffResult weakDiff = compareJava(repoStr, weakPath, SHA_B, weakBefore, weakPath, SHA_C, weakAfter);

		// File 3: Non-Java file -> EXCLUDED (NON_JAVA_OUT_OF_SCOPE).
		String mdPath = "docs/README.md";
		StructuralDiffResult mdDiff = compareJava(repoStr, mdPath, SHA_B, "# v1\n", mdPath, SHA_C, "# v2\n");

		DocumentBinding weakBinding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				UUID.randomUUID().toString(), "v1", SHA_A, "bind-weak",
				BindingGranularity.FILE_ONLY, weakPath, Optional.empty(), Optional.empty(), List.of());

		List<DocumentLookupResponse> lookups = ImpactClassifier.buildLookupKeys(ctx, List.of(javaDiff, weakDiff, mdDiff))
				.stream()
				.map(k -> new DocumentLookupResponse(
						k,
						LookupCompleteness.COMPLETE,
						k.path().equals(weakPath) && k.granularity() == LookupGranularity.FILE
								? List.of(weakBinding)
								: List.of(),
						Optional.empty()))
				.toList();

		ImpactReport report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(javaDiff, weakDiff, mdDiff), lookups, List.of(), List.of(), List.of()));
		List<SnapshotProof> proofs = List.of(
				presentProof(repoStr, SideRole.BEFORE, SHA_B, javaPath, javaBefore),
				presentProof(repoStr, SideRole.AFTER, SHA_C, javaPath, javaAfter),
				presentProof(repoStr, SideRole.BEFORE, SHA_B, weakPath, weakBefore),
				presentProof(repoStr, SideRole.AFTER, SHA_C, weakPath, weakAfter),
				presentProof(repoStr, SideRole.BEFORE, SHA_B, mdPath, "# v1\n"),
				presentProof(repoStr, SideRole.AFTER, SHA_C, mdPath, "# v2\n"));
		EvidenceResult evResult = evidenceMapper.map(new EvidenceInput(
				Optional.of(ctx), report, List.of(javaDiff, weakDiff, mdDiff),
				proofs, lookups, List.of(), List.of(), List.of(), List.of()));

		Instant now = Instant.now();
		PersistAnalysisOutcome outcome = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), ctx, true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 5L, now),
				evResult,
				now));

		assertEquals(ApplyResultStatus.APPLIED_CURRENT, outcome.status());
		assertEquals(3, outcome.findingsPersisted()); // 1 NO_IMPACT, 1 INCONCLUSIVE, 1 EXCLUDED; 0 for u1/u2/u3!
		assertEquals(0, outcome.issuesOpened());
		assertEquals(0, outcome.observationsRecorded());

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<AnalysisFindingEntity> findings = findingRepository.findScopedByJobId(scope.tenantId, outcome.jobId());
			assertEquals(3, findings.size());
			assertTrue(findings.stream().allMatch(f -> f.getDriftIssueId() == null));
			assertEquals(0, issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").size());
			return null;
		});
	}

	@Test
	@DisplayName("M5-RV-05: UNKNOWN currentness defers effects, retry via new attemptId reuses finding and applies issue, finalized replays are idempotent across attempts, and binding/stale attempts fail closed")
	void rv05UnknownDeferRetryNewAttemptIdempotentReplayAndAttemptBindingGuards() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant now = Instant.now();
		UUID jobUuid = UUID.randomUUID();

		// 1. Attempt 1 encounters UNKNOWN currentness -> DEFERRED_UNKNOWN_CURRENTNESS, 0 issues, 0 fences.
		var runAttempt1 = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv05", "attempt-1", SHA_B, SHA_C, docId, "claim-rv05", false);
		PersistAnalysisOutcome deferred = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(jobUuid, now.plusSeconds(300), runAttempt1.context(), true),
				new CurrentnessProof(CurrentnessReceiptState.UNKNOWN, Optional.empty(), Optional.of("S02_TIMEOUT")),
				runAttempt1.evidenceResult(),
				now));
		assertEquals(ApplyResultStatus.DEFERRED_UNKNOWN_CURRENTNESS, deferred.status());
		assertEquals(0, deferred.issuesOpened());
		assertFalse(deferred.coverageCheckpointEligible());

		UUID persistedFindingId = tenantContext.readInTenant(scope.tenantId, () -> {
			assertEquals(0, issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").size());
			List<AnalysisFindingEntity> findings = findingRepository.findScopedByJobId(scope.tenantId, jobUuid);
			assertEquals(1, findings.size());
			assertNull(findings.getFirst().getDriftIssueId());
			return findings.getFirst().getFindingId();
		});

		// 2. Retry same logical job via attempt-2 with CURRENT receipt -> reuses finding PK, opens issue & observation.
		var runAttempt2 = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv05", "attempt-2", SHA_B, SHA_C, docId, "claim-rv05", false);
		PersistAnalysisOutcome applied = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(jobUuid, now.plusSeconds(600), runAttempt2.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 11L, now),
				runAttempt2.evidenceResult(),
				now.plusSeconds(5)));
		assertEquals(ApplyResultStatus.APPLIED_CURRENT, applied.status());
		assertEquals(1, applied.issuesOpened());
		assertEquals(1, applied.observationsRecorded());

		// 3. Replay with attempt-2 AND replay after crash with attempt-3 -> IDEMPOTENT_REPLAY, no duplicates!
		var runAttempt3 = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv05", "attempt-3", SHA_B, SHA_C, docId, "claim-rv05", false);
		assertEquals(ApplyResultStatus.IDEMPOTENT_REPLAY, writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(jobUuid, now.plusSeconds(600), runAttempt2.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 11L, now),
				runAttempt2.evidenceResult(),
				now.plusSeconds(10))).status());
		assertEquals(ApplyResultStatus.IDEMPOTENT_REPLAY, writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(jobUuid, now.plusSeconds(900), runAttempt3.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 11L, now),
				runAttempt3.evidenceResult(),
				now.plusSeconds(15))).status());

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<AnalysisFindingEntity> findings = findingRepository.findScopedByJobId(scope.tenantId, jobUuid);
			assertEquals(1, findings.size());
			assertEquals(persistedFindingId, findings.getFirst().getFindingId());
			List<DriftIssueEntity> issues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main");
			assertEquals(1, issues.size());
			assertEquals(1, evidenceRepository.findScopedByIssueId(
					scope.tenantId, issues.getFirst().getDriftIssueId()).size());
			return null;
		});

		// 4. Same externalJobKey ("job-rv05") with mutated target SHA -> BindingConflictException!
		var mutatedBindingRun = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv05", "attempt-4", SHA_B, SHA_D, docId, "claim-rv05", false);
		assertThrows(BindingConflictException.class, () -> writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(600), mutatedBindingRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_D, 12L, now),
				mutatedBindingRun.evidenceResult(),
				now.plusSeconds(20))));

		// 5. Active unexpired CLAIMED attempt cannot be superseded by another attempt, and expired initial lease is rejected.
		UUID activeJobUuid = UUID.randomUUID();
		tenantContext.writeInTenant(scope.tenantId, () -> {
			jobRepository.saveAndFlush(new AnalysisJobEntity(
					activeJobUuid, scope.tenantId, scope.workspaceId, scope.repoId,
					"job-active-lease", "TRACKED_BRANCH", "refs/heads/main",
					"REVISION_COMPARISON", SHA_B, SHA_C, null, CFG_V1, "COMPLETE",
					false, "attempt-holder", now.plusSeconds(600), now));
			return null;
		});
		var hijackerRun = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-active-lease", "attempt-intruder", SHA_B, SHA_C, docId, "claim-rv05", false);
		assertThrows(StaleAttemptException.class, () -> writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(activeJobUuid, now.plusSeconds(600), hijackerRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 11L, now),
				hijackerRun.evidenceResult(),
				now)));

		var expiredInitRun = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-expired-init", "attempt-1", SHA_B, SHA_C, docId, "claim-rv05", false);
		assertThrows(StaleAttemptException.class, () -> writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.minusSeconds(1), expiredInitRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 11L, now),
				expiredInitRun.evidenceResult(),
				now)));
	}

	@Test
	@DisplayName("M5-RV-06: Concurrent race of 2 distinct jobs on same SHA converges to 1 open DriftIssue with 2 observations, and concurrent same-job race yields 1 apply + 1 idempotent replay")
	void rv06ConcurrentOpenIssueRaceAndSameJobRace() throws Exception {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant now = Instant.now();

		var job1Run = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-race-A", "attempt-1", SHA_B, SHA_C, docId, "claim-race", false);
		var job2Run = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-race-B", "attempt-1", SHA_B, SHA_C, docId, "claim-race", false);

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Callable<PersistAnalysisOutcome> task1 = () -> {
				ready.countDown();
				assertTrue(start.await(5, TimeUnit.SECONDS));
				return writerService.applyResult(new PersistAnalysisCommand(
						new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), job1Run.context(), true),
						currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 20L, now),
						job1Run.evidenceResult(),
						now));
			};
			Callable<PersistAnalysisOutcome> task2 = () -> {
				ready.countDown();
				assertTrue(start.await(5, TimeUnit.SECONDS));
				return writerService.applyResult(new PersistAnalysisCommand(
						new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), job2Run.context(), true),
						currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 20L, now),
						job2Run.evidenceResult(),
						now.plusSeconds(1)));
			};
			Future<PersistAnalysisOutcome> f1 = pool.submit(task1);
			Future<PersistAnalysisOutcome> f2 = pool.submit(task2);
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();

			PersistAnalysisOutcome o1 = f1.get(15, TimeUnit.SECONDS);
			PersistAnalysisOutcome o2 = f2.get(15, TimeUnit.SECONDS);
			assertEquals(ApplyResultStatus.APPLIED_CURRENT, o1.status());
			assertEquals(ApplyResultStatus.APPLIED_CURRENT, o2.status());
			assertEquals(1, o1.issuesOpened() + o2.issuesOpened());
			assertEquals(1, o1.issuesUpdated() + o2.issuesUpdated());
		}

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<DriftIssueEntity> issues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main");
			assertEquals(1, issues.size());
			List<DriftEvidenceEntity> obs = evidenceRepository.findScopedByIssueId(
					scope.tenantId, issues.getFirst().getDriftIssueId());
			assertEquals(2, obs.size());
			assertNotEquals(obs.get(0).getFindingId(), obs.get(1).getFindingId());
			return null;
		});

		// Subcase B: Two threads racing the exact same brand-new externalJobKey.
		var dupRun = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/release",
				"job-dup-race", "attempt-1", SHA_B, SHA_C, docId, "claim-dup", false);
		UUID sharedJobUuid = UUID.randomUUID();
		CountDownLatch dupReady = new CountDownLatch(2);
		CountDownLatch dupStart = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Callable<PersistAnalysisOutcome> dupTask = () -> {
				dupReady.countDown();
				assertTrue(dupStart.await(5, TimeUnit.SECONDS));
				return writerService.applyResult(new PersistAnalysisCommand(
						new AuthorizedJobWriteContext(sharedJobUuid, now.plusSeconds(300), dupRun.context(), true),
						currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/release", SHA_C, 1L, now),
						dupRun.evidenceResult(),
						now));
			};
			Future<PersistAnalysisOutcome> d1 = pool.submit(dupTask);
			Future<PersistAnalysisOutcome> d2 = pool.submit(dupTask);
			assertTrue(dupReady.await(5, TimeUnit.SECONDS));
			dupStart.countDown();

			List<ApplyResultStatus> statuses = List.of(
					d1.get(15, TimeUnit.SECONDS).status(),
					d2.get(15, TimeUnit.SECONDS).status());
			assertTrue(statuses.contains(ApplyResultStatus.APPLIED_CURRENT));
			assertTrue(statuses.contains(ApplyResultStatus.IDEMPOTENT_REPLAY));
		}
	}

	@Test
	@DisplayName("M5-RV-07: Unrelated later commit advances target fence without mutating open issue lastObservedSha or lastObservedAt")
	void rv07UnrelatedCommitDoesNotMutateLastObserved() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-26T09:00:00Z");

		var openRun = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv07-1", "attempt-1", SHA_B, SHA_C, docId, "claim-svc-a", false);
		writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), openRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 30L, Instant.now()),
				openRun.evidenceResult(),
				t1));

		// Unrelated file modified at SHA_D with empty lookup -> NO_IMPACT.
		String repoStr = scope.repoId.toString();
		AssessmentContext ctx2 = new AssessmentContext(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				TargetKind.TRACKED_BRANCH, "refs/heads/main", "job-rv07-2", "attempt-1",
				Optional.of(CFG_V1), SHA_D, Optional.of(SHA_C),
				Intent.REVISION_COMPARISON, ScopeCompleteness.COMPLETE, false, Optional.empty());
		String otherPath = "src/UnrelatedLogger.java";
		StructuralDiffResult otherDiff = compareJava(
				repoStr, otherPath, SHA_C, "class UnrelatedLogger { void log() {} }\n",
				otherPath, SHA_D, "class UnrelatedLogger { void log() { int k = 1; } }\n");
		List<DocumentLookupResponse> emptyLookups = ImpactClassifier.buildLookupKeys(ctx2, List.of(otherDiff)).stream()
				.map(k -> new DocumentLookupResponse(k, LookupCompleteness.COMPLETE, List.of(), Optional.empty()))
				.toList();
		ImpactReport rep2 = classifier.classify(new ImpactClassificationInput(
				ctx2, List.of(otherDiff), emptyLookups, List.of(), List.of(), List.of()));
		EvidenceResult ev2 = evidenceMapper.map(new EvidenceInput(
				Optional.of(ctx2), rep2, List.of(otherDiff),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_C, otherPath, "class UnrelatedLogger { void log() {} }\n"),
						presentProof(repoStr, SideRole.AFTER, SHA_D, otherPath, "class UnrelatedLogger { void log() { int k = 1; } }\n")),
				emptyLookups, List.of(), List.of(), List.of(), List.of()));

		writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), ctx2, true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_D, 31L, Instant.now()),
				ev2,
				t1.plusSeconds(300)));

		tenantContext.readInTenant(scope.tenantId, () -> {
			DriftIssueEntity issue = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").getFirst();
			assertEquals(SHA_C, issue.getLastObservedSha());
			assertEquals(t1, issue.getLastObservedAt());
			TargetAnalysisFenceEntity fence = fenceRepository.findScopedByTarget(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").orElseThrow();
			assertEquals(31L, fence.getLatestSourceRevision());
			return null;
		});
	}

	@Test
	@DisplayName("M5-RV-08: Closed RESOLVED and DISMISSED issues never reopen on replay, while genuine recurrence creates a new DriftIssue UUID")
	void rv08ClosedIssuesStayHistoricalAndRecurrenceCreatesNewIssue() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant now = Instant.now();

		var run1 = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv08-1", "attempt-1", SHA_B, SHA_C, docId, "claim-rv08", false);
		writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), run1.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 40L, now),
				run1.evidenceResult(),
				now));

		UUID closedResolvedId = tenantContext.writeInTenant(scope.tenantId, () -> {
			UUID id = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").getFirst().getDriftIssueId();
			runtimeJdbc.update("UPDATE intelligence.drift_issues SET state = 'RESOLVED' WHERE drift_issue_id = ?", id);
			return id;
		});

		// Replay old job -> IDEMPOTENT_REPLAY, resolved issue untouched.
		assertEquals(ApplyResultStatus.IDEMPOTENT_REPLAY, writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), run1.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 40L, now),
				run1.evidenceResult(),
				now.plusSeconds(10))).status());

		// Genuine recurrence on SHA_D -> opens NEW issue2 while closedResolvedId remains RESOLVED.
		var recurrence1 = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv08-2", "attempt-1", SHA_C, SHA_D, docId, "claim-rv08", false);
		PersistAnalysisOutcome recOut1 = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), recurrence1.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_D, 41L, now),
				recurrence1.evidenceResult(),
				now.plusSeconds(20)));
		assertEquals(1, recOut1.issuesOpened());

		// Dismiss issue2, then trigger another recurrence on SHA_E -> opens issue3 while both closed rows stay intact.
		UUID closedDismissedId = tenantContext.writeInTenant(scope.tenantId, () -> {
			UUID openId = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").stream()
					.filter(DriftIssueEntity::isOpen)
					.findFirst().orElseThrow().getDriftIssueId();
			assertNotEquals(closedResolvedId, openId);
			runtimeJdbc.update("UPDATE intelligence.drift_issues SET state = 'DISMISSED' WHERE drift_issue_id = ?", openId);
			return openId;
		});

		var recurrence2 = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv08-3", "attempt-1", SHA_D, SHA_E, docId, "claim-rv08", false);
		PersistAnalysisOutcome recOut2 = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), recurrence2.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_E, 42L, now),
				recurrence2.evidenceResult(),
				now.plusSeconds(30)));
		assertEquals(1, recOut2.issuesOpened());

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<DriftIssueEntity> allIssues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main");
			assertEquals(3, allIssues.size());
			assertEquals("RESOLVED", issueRepository.findById(closedResolvedId).orElseThrow().getState());
			assertEquals("DISMISSED", issueRepository.findById(closedDismissedId).orElseThrow().getState());
			assertEquals(1L, allIssues.stream().filter(DriftIssueEntity::isOpen).count());
			return null;
		});
	}

	@Test
	@DisplayName("M5-RV-09: Out-of-order stale revision is suppressed by target fence, and receipt expiring while blocked on target lock defers without poisoning fence")
	void rv09OutOfOrderFenceSuppressionAndReceiptExpiredWhileWaitingOnLock() throws Exception {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant baseTime = Instant.parse("2026-11-26T10:00:00Z");

		// 1. Receipt expiring while waiting on PostgreSQL row lock of target_analysis_fences!
		MutableClock mutableClock = new MutableClock(baseTime);
		AnalysisResultWriterService controlledWriter = new AnalysisResultWriterServiceImpl(
				tenantContext, jobRepository, fenceRepository, issueRepository,
				findingRepository, evidenceRepository, runtimeJdbc, jsonMapper, mutableClock);

		CountDownLatch lockAcquired = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		CountDownLatch enteredWait = new CountDownLatch(1);

		var expiringJobRun = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-lock-expire", "attempt-1", SHA_B, SHA_D, docId, "claim-rv09", false);
		ValidationReceipt shortReceipt = new ValidationReceipt(
				UUID.randomUUID(), scope.tenantId, scope.repoId,
				TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_D, CFG_V1,
				99L, Optional.of("fence-99"), baseTime, baseTime.plusSeconds(10));

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> blocker = pool.submit(() -> tenantContext.writeInTenant(scope.tenantId, () -> {
				runtimeJdbc.update("""
						INSERT INTO intelligence.target_analysis_fences (
							fence_id, tenant_id, repository_connection_id, target_kind, target_ref,
							latest_source_revision, latest_validated_sha, latest_config_revision,
							last_fenced_job_id, last_validated_at, updated_at, row_version
						) VALUES (?, ?, ?, 'TRACKED_BRANCH', 'refs/heads/main', 0, 'UNINITIALIZED', 'UNINITIALIZED', NULL, ?, ?, 0)
						""", UUID.randomUUID(), scope.tenantId, scope.repoId,
						Timestamp.from(baseTime), Timestamp.from(baseTime));
				fenceRepository.findForUpdateByTarget(
						scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").orElseThrow();
				lockAcquired.countDown();
				try {
					assertTrue(releaseLock.await(10, TimeUnit.SECONDS));
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
				return null;
			}));

			assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));
			Future<PersistAnalysisOutcome> waiter = pool.submit(() -> {
				enteredWait.countDown();
				return controlledWriter.applyResult(new PersistAnalysisCommand(
						new AuthorizedJobWriteContext(UUID.randomUUID(), baseTime.plusSeconds(300), expiringJobRun.context(), true),
						new CurrentnessProof(CurrentnessReceiptState.CURRENT, Optional.of(shortReceipt), Optional.empty()),
						expiringJobRun.evidenceResult(),
						baseTime));
			});
			assertTrue(enteredWait.await(5, TimeUnit.SECONDS));
			Thread.sleep(150); // Allow waiter to reach PostgreSQL SELECT FOR UPDATE on target_analysis_fences.

			// Advance clock past shortReceipt.expiresAt (baseTime + 10s) BEFORE releasing lock!
			mutableClock.set(baseTime.plusSeconds(15));
			releaseLock.countDown();

			blocker.get(10, TimeUnit.SECONDS);
			PersistAnalysisOutcome expiredOutcome = waiter.get(10, TimeUnit.SECONDS);
			assertEquals(ApplyResultStatus.DEFERRED_UNKNOWN_CURRENTNESS, expiredOutcome.status());
		}

		// Verify fence was NOT poisoned with revision 99 (last_fenced_job_id is still null!).
		// Job C with revision 55 now commits cleanly and initializes the fence to 55.
		var jobCRun = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv09-C", "attempt-1", SHA_B, SHA_C, docId, "claim-rv09", false);
		PersistAnalysisOutcome outC = controlledWriter.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), baseTime.plusSeconds(300), jobCRun.context(), true),
				new CurrentnessProof(
						CurrentnessReceiptState.CURRENT,
						Optional.of(new ValidationReceipt(
								UUID.randomUUID(), scope.tenantId, scope.repoId,
								TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, CFG_V1,
								55L, Optional.of("fence-55"), baseTime.plusSeconds(15), baseTime.plusSeconds(120))),
						Optional.empty()),
				jobCRun.evidenceResult(),
				baseTime.plusSeconds(15)));
		assertEquals(ApplyResultStatus.APPLIED_CURRENT, outC.status());
		assertEquals(1, outC.issuesOpened());

		// 2. Late out-of-order Job B (revision 54 < 55) arrives with unexpired receipt -> SUPPRESSED_STALE!
		var jobBRun = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-rv09-B", "attempt-1", SHA_A, SHA_B, docId, "claim-rv09", false);
		PersistAnalysisOutcome outB = controlledWriter.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), baseTime.plusSeconds(300), jobBRun.context(), true),
				new CurrentnessProof(
						CurrentnessReceiptState.CURRENT,
						Optional.of(new ValidationReceipt(
								UUID.randomUUID(), scope.tenantId, scope.repoId,
								TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_B, CFG_V1,
								54L, Optional.of("fence-54"), baseTime.plusSeconds(14), baseTime.plusSeconds(120))),
						Optional.empty()),
				jobBRun.evidenceResult(),
				baseTime.plusSeconds(16)));
		assertEquals(ApplyResultStatus.SUPPRESSED_STALE, outB.status());
		assertEquals(1, outB.findingsPersisted());
		assertEquals(0, outB.issuesOpened());
		assertEquals(0, outB.issuesUpdated());

		tenantContext.readInTenant(scope.tenantId, () -> {
			TargetAnalysisFenceEntity fence = fenceRepository.findScopedByTarget(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").orElseThrow();
			assertEquals(55L, fence.getLatestSourceRevision());
			assertEquals(SHA_C, fence.getLatestValidatedSha());
			DriftIssueEntity issue = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").getFirst();
			assertEquals(SHA_C, issue.getLastObservedSha());
			List<AnalysisFindingEntity> bFindings = findingRepository.findScopedByJobId(scope.tenantId, outB.jobId());
			assertEquals(1, bFindings.size());
			assertNull(bFindings.getFirst().getDriftIssueId());
			return null;
		});
	}

	@Test
	@DisplayName("M5-RV-10: PULL_REQUEST and TRACKED_BRANCH with identical SHA and mismatch keep isolated DriftIssues and fences")
	void rv10PullRequestAndTrackedBranchSameShaStayIsolated() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant now = Instant.now();

		var prRun = buildSignatureMismatchPipeline(
				scope, TargetKind.PULL_REQUEST, "refs/pull/42/head",
				"job-pr-42", "attempt-1", SHA_B, SHA_C, docId, "claim-shared", false);
		var branchRun = buildSignatureMismatchPipeline(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-branch-main", "attempt-1", SHA_B, SHA_C, docId, "claim-shared", false);

		PersistAnalysisOutcome prOut = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), prRun.context(), true),
				currentProof(scope, TargetKind.PULL_REQUEST, "refs/pull/42/head", SHA_C, 1L, now),
				prRun.evidenceResult(),
				now));
		PersistAnalysisOutcome branchOut = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), branchRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 1L, now),
				branchRun.evidenceResult(),
				now));

		assertEquals(1, prOut.issuesOpened());
		assertEquals(1, branchOut.issuesOpened());

		tenantContext.readInTenant(scope.tenantId, () -> {
			List<DriftIssueEntity> prIssues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "PULL_REQUEST", "refs/pull/42/head");
			List<DriftIssueEntity> branchIssues = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main");
			assertEquals(1, prIssues.size());
			assertEquals(1, branchIssues.size());
			assertNotEquals(prIssues.getFirst().getDriftIssueId(), branchIssues.getFirst().getDriftIssueId());
			return null;
		});
	}

	@Test
	@DisplayName("M5-RV-11: R2 revert records CONSISTENCY_REVERTED only when exact claim & subject match, ignoring whitespace or other-claim NO_IMPACT, and preserves state & lastObservedSha")
	void rv11RevertRecordsExactClaimConsistencyWithoutAutoResolvingOrFalseRevert() {
		ScopeIds scope = ScopeIds.fresh();
		UUID docId = UUID.randomUUID();
		Instant t0 = Instant.parse("2026-11-26T11:00:00Z");
		String repoStr = scope.repoId.toString();
		String path = "src/Calc.java";
		String consistentSrc = "public class Calc {\n    public int price() { return 10; }\n}\n";
		String brokenSrc = "public class Calc {\n    public long price() { return 10L; }\n}\n";
		String brokenWsSrc = "public class Calc {\n    public long price() {   return 10L;   }\n}\n";

		// 1. Open ANALYZED signature issue on claim-price at SHA_B.
		var mismatchRun = buildSignatureMismatchCustomSourcePipeline(
				scope, "job-rv11-open", "attempt-1", path, SHA_A, consistentSrc, SHA_B, brokenSrc,
				docId, "bind-price", "claim-price");
		writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), mismatchRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_B, 10L, Instant.now()),
				mismatchRun.evidenceResult(),
				t0));

		// 2. Whitespace-only NO_IMPACT at SHA_C -> must NOT mark revertObserved!
		AssessmentContext wsCtx = new AssessmentContext(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				TargetKind.TRACKED_BRANCH, "refs/heads/main", "job-rv11-ws", "attempt-1",
				Optional.of(CFG_V1), SHA_C, Optional.of(SHA_B),
				Intent.REVISION_COMPARISON, ScopeCompleteness.COMPLETE, true, Optional.empty());
		StructuralDiffResult wsDiff = compareJava(repoStr, path, SHA_B, brokenSrc, path, SHA_C, brokenWsSrc);
		List<DocumentLookupResponse> wsLookups = List.of(
				new DocumentLookupResponse(
						new DocumentLookupKey(wsCtx.tenantId(), wsCtx.workspaceId(), repoStr,
								TargetKind.TRACKED_BRANCH, "refs/heads/main", SideRole.BEFORE, SHA_B, path,
								LookupGranularity.FILE, Optional.empty()),
						LookupCompleteness.COMPLETE, List.of(), Optional.empty()),
				new DocumentLookupResponse(
						new DocumentLookupKey(wsCtx.tenantId(), wsCtx.workspaceId(), repoStr,
								TargetKind.TRACKED_BRANCH, "refs/heads/main", SideRole.AFTER, SHA_C, path,
								LookupGranularity.FILE, Optional.empty()),
						LookupCompleteness.COMPLETE, List.of(), Optional.empty()));
		ImpactReport wsReport = classifier.classify(new ImpactClassificationInput(
				wsCtx, List.of(wsDiff), wsLookups, List.of(), List.of(), List.of()));
		EvidenceResult wsEv = evidenceMapper.map(new EvidenceInput(
				Optional.of(wsCtx), wsReport, List.of(wsDiff),
				List.of(
						presentProof(repoStr, SideRole.BEFORE, SHA_B, path, brokenSrc),
						presentProof(repoStr, SideRole.AFTER, SHA_C, path, brokenWsSrc)),
				wsLookups, List.of(), List.of(), List.of(), List.of()));
		PersistAnalysisOutcome wsOut = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), wsCtx, true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 11L, Instant.now()),
				wsEv,
				t0.plusSeconds(30)));
		assertEquals(0, wsOut.revertsRecorded());

		// 3. NO_IMPACT on a DIFFERENT claim ("claim-other") -> must NOT mark revertObserved on "claim-price"!
		var otherClaimConsistentRun = buildConsistentDeclarationPipeline(
				scope, "job-rv11-other-claim", "attempt-1", path, SHA_C, brokenWsSrc, SHA_D, consistentSrc,
				docId, "bind-price", "claim-other");
		PersistAnalysisOutcome otherClaimOut = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), otherClaimConsistentRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_D, 12L, Instant.now()),
				otherClaimConsistentRun.evidenceResult(),
				t0.plusSeconds(60)));
		assertEquals(0, otherClaimOut.revertsRecorded());

		tenantContext.readInTenant(scope.tenantId, () -> {
			DriftIssueEntity issue = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").getFirst();
			assertFalse(issue.isRevertObserved());
			assertNull(issue.getLastConsistentSha());
			return null;
		});

		// 4. Exact-claim revert ("claim-price") back to int price() at SHA_E -> records CONSISTENCY_REVERTED, keeps ANALYZED & SHA_B!
		var exactRevertRun = buildConsistentDeclarationPipeline(
				scope, "job-rv11-revert", "attempt-1", path, SHA_B, brokenSrc, SHA_E, consistentSrc,
				docId, "bind-price", "claim-price");
		PersistAnalysisOutcome revertOut = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), exactRevertRun.context(), true),
				currentProof(scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_E, 13L, Instant.now()),
				exactRevertRun.evidenceResult(),
				t0.plusSeconds(120)));
		assertEquals(1, revertOut.revertsRecorded());
		assertEquals(1, revertOut.observationsRecorded());

		tenantContext.readInTenant(scope.tenantId, () -> {
			DriftIssueEntity issue = issueRepository.findByTargetScope(
					scope.tenantId, scope.repoId, "TRACKED_BRANCH", "refs/heads/main").getFirst();
			assertEquals("ANALYZED", issue.getState()); // Never auto-resolved or dismissed!
			assertEquals(SHA_B, issue.getLastObservedSha()); // Last mismatch SHA preserved!
			assertEquals(t0, issue.getLastObservedAt());
			assertTrue(issue.isRevertObserved());
			assertEquals(SHA_E, issue.getLastConsistentSha());
			assertEquals(t0.plusSeconds(120), issue.getLastConsistentAt());

			List<DriftEvidenceEntity> obs = evidenceRepository.findScopedByIssueId(scope.tenantId, issue.getDriftIssueId());
			assertEquals(2, obs.size());
			assertTrue(obs.stream().anyMatch(e -> "CONSISTENCY_REVERTED".equals(e.getObservationKind())
					&& SHA_E.equals(e.getEvaluatedTargetSha())));
			return null;
		});

		// 5. Semantic R2 revert (S05 BEHAVIOR_CONSISTENT on open DETECTED semantic issue) also records CONSISTENCY_REVERTED.
		ScopeIds semScope = ScopeIds.fresh();
		UUID semDocId = UUID.randomUUID();
		var semOpenRun = buildSemanticBodyPipeline(
				semScope, "job-sem-open", "attempt-1", SHA_B, SHA_C,
				"class Fee { int fee(int d) { return d * 5; } }\n",
				"class Fee { int fee(int d) { return d * 10; } }\n",
				semDocId, "claim-sem-r2", Optional.empty());
		writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), semOpenRun.context(), true),
				currentProof(semScope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 1L, Instant.now()),
				semOpenRun.evidenceResult(),
				t0));

		var semRevertRun = buildSemanticBodyPipeline(
				semScope, "job-sem-revert", "attempt-1", SHA_C, SHA_D,
				"class Fee { int fee(int d) { return d * 10; } }\n",
				"class Fee { int fee(int d) { return d * 5; } }\n",
				semDocId, "claim-sem-r2", Optional.of(SemanticStatus.BEHAVIOR_CONSISTENT));
		assertEquals(Optional.of(ImpactOutcome.NO_IMPACT), semRevertRun.evidenceResult().overallOutcome());
		PersistAnalysisOutcome semRevertOut = writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), Instant.now().plusSeconds(300), semRevertRun.context(), true),
				currentProof(semScope, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_D, 2L, Instant.now()),
				semRevertRun.evidenceResult(),
				t0.plusSeconds(60)));
		assertEquals(1, semRevertOut.revertsRecorded());
	}

	@Test
	@DisplayName("M5-RV-12: PostgreSQL RLS isolation, HikariCP pool reuse reset, cross-tenant denial, unauthorized gate rejection, and cross-issue composite FK enforcement")
	void rv12RlsIsolationPoolReuseCrossTenantAndCrossIssueCompositeFk() {
		ScopeIds tenantA = ScopeIds.fresh();
		ScopeIds tenantB = ScopeIds.fresh();
		UUID docA1 = UUID.randomUUID();
		UUID docA2 = UUID.randomUUID();
		Instant now = Instant.now();

		// 1. Unauthorized command / DENIED scope is rejected at service gate without writing rows.
		var deniedRun = buildSignatureMismatchPipeline(
				tenantA, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				"job-denied", "attempt-1", SHA_B, SHA_C, docA1, "claim-1", false);
		assertThrows(ScopeUnauthorizedException.class, () -> writerService.applyResult(new PersistAnalysisCommand(
				new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), deniedRun.context(), false),
				currentProof(tenantA, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 1L, now),
				deniedRun.evidenceResult(),
				now)));

		// 2. Write two distinct issues in Tenant A (and loop multiple times to exercise all 4 pooled connections!).
		for (int i = 0; i < 6; i++) {
			UUID docId = (i % 2 == 0) ? docA1 : docA2;
			String claimId = (i % 2 == 0) ? "claim-A1" : "claim-A2";
			var runA = buildSignatureMismatchPipeline(
					tenantA, TargetKind.TRACKED_BRANCH, "refs/heads/main",
					"job-tenantA-" + i, "attempt-1", SHA_B, SHA_C, docId, claimId, false);
			writerService.applyResult(new PersistAnalysisCommand(
					new AuthorizedJobWriteContext(UUID.randomUUID(), now.plusSeconds(300), runA.context(), true),
					currentProof(tenantA, TargetKind.TRACKED_BRANCH, "refs/heads/main", SHA_C, 10L + i, now),
					runA.evidenceResult(),
					now.plusSeconds(i)));
		}

		// 3. Borrow pooled connections WITHOUT setting app.tenant_id -> 0 rows visible across ALL 5 tables, INSERT denied!
		for (int i = 0; i < 6; i++) {
			for (String table : List.of(
					"analysis_jobs", "target_analysis_fences", "drift_issues", "analysis_findings", "drift_evidence")) {
				assertEquals(0, runtimeJdbc.queryForObject("SELECT count(*) FROM intelligence." + table, Integer.class),
						"Pooled connection leaked tenant context on table " + table);
			}
		}
		assertThrows(DataAccessException.class, () -> runtimeJdbc.update("""
				INSERT INTO intelligence.analysis_jobs (
					job_id, tenant_id, workspace_id, repository_connection_id, external_job_key,
					target_kind, target_ref, comparison_intent, comparison_before_sha,
					authoritative_target_sha, config_revision, declared_scope_completeness,
					whitespace_no_impact_enabled, status, current_attempt_id, lease_expires_at
				) VALUES (?, ?, ?, ?, 'unscoped-insert', 'TRACKED_BRANCH', 'refs/heads/main',
					'REVISION_COMPARISON', ?, ?, 'cfg', 'COMPLETE', false, 'CLAIMED', 'a1', ?)
				""", UUID.randomUUID(), tenantA.tenantId, tenantA.workspaceId, tenantA.repoId,
				SHA_B, SHA_C, Timestamp.from(now.plusSeconds(300))));

		// 4. Tenant B sees 0 rows of Tenant A and cannot insert a row with tenant_id = Tenant A.
		tenantContext.writeInTenant(tenantB.tenantId, () -> {
			assertEquals(0, issueRepository.findAll().size());
			assertEquals(0, findingRepository.findAll().size());
			assertEquals(0, jobRepository.findAll().size());
			assertThrows(DataAccessException.class, () -> runtimeJdbc.update("""
					INSERT INTO intelligence.analysis_jobs (
						job_id, tenant_id, workspace_id, repository_connection_id, external_job_key,
						target_kind, target_ref, comparison_intent, comparison_before_sha,
						authoritative_target_sha, config_revision, declared_scope_completeness,
						whitespace_no_impact_enabled, status, current_attempt_id, lease_expires_at
					) VALUES (?, ?, ?, ?, 'cross-tenant-insert', 'TRACKED_BRANCH', 'refs/heads/main',
						'REVISION_COMPARISON', ?, ?, 'cfg', 'COMPLETE', false, 'CLAIMED', 'a1', ?)
					""", UUID.randomUUID(), tenantA.tenantId, tenantA.workspaceId, tenantA.repoId,
					SHA_B, SHA_C, Timestamp.from(now.plusSeconds(300))));
			return null;
		});

		// 5. Within Tenant A, composite FK fk_drift_evidence_finding_issue rejects linking Issue A2 to Finding of Issue A1!
		tenantContext.writeInTenant(tenantA.tenantId, () -> {
			List<DriftIssueEntity> issuesA = issueRepository.findByTargetScope(
					tenantA.tenantId, tenantA.repoId, "TRACKED_BRANCH", "refs/heads/main");
			assertEquals(2, issuesA.size());
			UUID issue1Id = issuesA.get(0).getDriftIssueId();
			UUID issue2Id = issuesA.get(1).getDriftIssueId();
			UUID findingLinkedToIssue1 = evidenceRepository.findScopedByIssueId(tenantA.tenantId, issue1Id)
					.getFirst().getFindingId();

			assertThrows(DataAccessException.class, () -> runtimeJdbc.update("""
					INSERT INTO intelligence.drift_evidence (
						drift_evidence_id, tenant_id, drift_issue_id, finding_id,
						observation_kind, evaluated_target_sha, document_version,
						document_bound_sha, content_fingerprint, observed_at
					) VALUES (?, ?, ?, ?, 'MISMATCH_ANALYZED', ?, 'v1', ?, 'fp-bad', ?)
					""", UUID.randomUUID(), tenantA.tenantId, issue2Id, findingLinkedToIssue1,
					SHA_C, SHA_A, Timestamp.from(now)));
			return null;
		});
	}

	// ---- Pipeline Fixture Builders (driving real M2 -> M3 -> M4) ----

	private PipelineRun buildSignatureMismatchPipeline(
			ScopeIds scope,
			TargetKind targetKind,
			String targetRef,
			String jobKey,
			String attemptId,
			String beforeSha,
			String targetSha,
			UUID docId,
			String claimId,
			boolean addUnverifiedOptionalWitness) {
		String path = "src/OrderService.java";
		String beforeSrc = "public class OrderService {\n    public int total(int qty) { return qty; }\n}\n";
		String afterSrc = "public class OrderService {\n    public long total(int qty) { return qty; }\n}\n";
		return buildSignatureMismatchWithTarget(
				scope, targetKind, targetRef, jobKey, attemptId, path,
				beforeSha, beforeSrc, targetSha, afterSrc, docId, "bind-order", claimId, addUnverifiedOptionalWitness);
	}

	private PipelineRun buildSignatureMismatchCustomSourcePipeline(
			ScopeIds scope,
			String jobKey,
			String attemptId,
			String path,
			String beforeSha,
			String beforeSrc,
			String targetSha,
			String afterSrc,
			UUID docId,
			String bindingId,
			String claimId) {
		return buildSignatureMismatchWithTarget(
				scope, TargetKind.TRACKED_BRANCH, "refs/heads/main", jobKey, attemptId, path,
				beforeSha, beforeSrc, targetSha, afterSrc, docId, bindingId, claimId, false);
	}

	private PipelineRun buildSignatureMismatchWithTarget(
			ScopeIds scope,
			TargetKind targetKind,
			String targetRef,
			String jobKey,
			String attemptId,
			String path,
			String beforeSha,
			String beforeSrc,
			String targetSha,
			String afterSrc,
			UUID docId,
			String bindingId,
			String claimId,
			boolean addUnverifiedOptionalWitness) {
		String repoStr = scope.repoId.toString();
		StructuralDiffResult diffRes = compareJava(repoStr, path, beforeSha, beforeSrc, path, targetSha, afterSrc);
		SymbolKey symKey = changedSymbolKey(diffRes);

		AssessmentContext ctx = new AssessmentContext(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				targetKind, targetRef, jobKey, attemptId,
				Optional.of(CFG_V1), targetSha, Optional.of(beforeSha),
				Intent.REVISION_COMPARISON, ScopeCompleteness.COMPLETE, false, Optional.empty());

		DocumentClaim claim = new DocumentClaim(
				claimId, docId.toString(), "v1", SHA_A, targetSha,
				symKey.name() + " signature contract", path, Optional.of(symKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_INCOMPATIBLE, "Return type changed incompatibly",
				Optional.of(new VerifiedImpactContext(Severity.HIGH, "Public API contract broken", "core-api")));
		DocumentBinding binding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, bindingId,
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(symKey), Optional.of("sec-1"), List.of(claim));

		DocumentLookupKey targetSymLookup = new DocumentLookupKey(
				ctx.tenantId(), ctx.workspaceId(), repoStr, targetKind, targetRef,
				SideRole.AFTER, targetSha, path, LookupGranularity.SYMBOL, Optional.of(symKey));
		List<DocumentLookupResponse> lookups = ImpactClassifier.buildLookupKeys(ctx, List.of(diffRes)).stream()
				.map(k -> new DocumentLookupResponse(
						k, LookupCompleteness.COMPLETE,
						k.equals(targetSymLookup) ? List.of(binding) : List.of(),
						Optional.empty()))
				.toList();

		ImpactReport report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(diffRes), lookups, List.of(), List.of(), List.of()));
		if (addUnverifiedOptionalWitness) {
			List<ImpactFinding> updatedFindings = report.findings().stream()
					.map(f -> {
						if (f.outcome() != ImpactOutcome.ACTIONABLE) {
							return f;
						}
						var extWitnesses = new ArrayList<>(f.witnesses());
						extWitnesses.add(new DocumentWitnessSelector(
								"witness:optional-missing-sec", docId.toString(), "v1", SHA_A, targetSha, bindingId,
								Optional.of("sec-nonexistent"), Optional.empty(), Optional.empty()));
						return new ImpactFinding(
								f.findingId(), f.disposition(), f.outcome(), f.classification(), f.severity(),
								f.severityStatus(), f.severityRationale(), f.semanticConfidence(), f.admission(),
								f.admissionReason(), f.primaryReason(), f.secondaryReasons(), f.explanation(),
								f.beforePath(), f.afterPath(), f.symbolKey(), f.evaluatedFacets(),
								f.documentId(), f.documentVersion(), f.documentBoundSha(), f.bindingId(), f.claimId(),
								extWitnesses, f.requiredImpactWitnessIds(), f.requiredAdmissionWitnessIds(),
								f.initialAssessment());
					})
					.toList();
			report = new ImpactReport(
					report.classifierVersion(), report.processingStatus(), report.overallOutcome(),
					report.context(), updatedFindings, report.coverage(), report.diagnostics());
		}

		List<SnapshotProof> proofs = List.of(
				presentProof(repoStr, SideRole.BEFORE, beforeSha, path, beforeSrc),
				presentProof(repoStr, SideRole.AFTER, targetSha, path, afterSrc));
		EvidenceResult evResult = evidenceMapper.map(new EvidenceInput(
				Optional.of(ctx), report, List.of(diffRes), proofs, lookups,
				List.of(), List.of(), List.of(), List.of()));
		return new PipelineRun(ctx, evResult);
	}

	private PipelineRun buildConsistentDeclarationPipeline(
			ScopeIds scope,
			String jobKey,
			String attemptId,
			String path,
			String beforeSha,
			String beforeSrc,
			String targetSha,
			String afterSrc,
			UUID docId,
			String bindingId,
			String claimId) {
		String repoStr = scope.repoId.toString();
		StructuralDiffResult diffRes = compareJava(repoStr, path, beforeSha, beforeSrc, path, targetSha, afterSrc);
		SymbolKey symKey = changedSymbolKey(diffRes);

		AssessmentContext ctx = new AssessmentContext(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				TargetKind.TRACKED_BRANCH, "refs/heads/main", jobKey, attemptId,
				Optional.of(CFG_V1), targetSha, Optional.of(beforeSha),
				Intent.REVISION_COMPARISON, ScopeCompleteness.COMPLETE, false, Optional.empty());

		DocumentClaim claim = new DocumentClaim(
				claimId, docId.toString(), "v1", SHA_A, targetSha,
				symKey.name() + " signature contract", path, Optional.of(symKey), Optional.of(FacetKind.DECLARATION),
				ClaimVerdict.DECLARATION_CONSISTENT, "Declaration matches documented int return type",
				Optional.empty());
		DocumentBinding binding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, bindingId,
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(symKey), Optional.of("sec-1"), List.of(claim));

		DocumentLookupKey targetSymLookup = new DocumentLookupKey(
				ctx.tenantId(), ctx.workspaceId(), repoStr, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				SideRole.AFTER, targetSha, path, LookupGranularity.SYMBOL, Optional.of(symKey));
		List<DocumentLookupResponse> lookups = ImpactClassifier.buildLookupKeys(ctx, List.of(diffRes)).stream()
				.map(k -> new DocumentLookupResponse(
						k, LookupCompleteness.COMPLETE,
						k.equals(targetSymLookup) ? List.of(binding) : List.of(),
						Optional.empty()))
				.toList();

		ImpactReport report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(diffRes), lookups, List.of(), List.of(), List.of()));
		List<SnapshotProof> proofs = List.of(
				presentProof(repoStr, SideRole.BEFORE, beforeSha, path, beforeSrc),
				presentProof(repoStr, SideRole.AFTER, targetSha, path, afterSrc));
		EvidenceResult evResult = evidenceMapper.map(new EvidenceInput(
				Optional.of(ctx), report, List.of(diffRes), proofs, lookups,
				List.of(), List.of(), List.of(), List.of()));
		return new PipelineRun(ctx, evResult);
	}

	private PipelineRun buildSemanticBodyPipeline(
			ScopeIds scope,
			String jobKey,
			String attemptId,
			String beforeSha,
			String targetSha,
			String beforeSrc,
			String afterSrc,
			UUID docId,
			String claimId,
			Optional<SemanticStatus> semanticOutcome) {
		String repoStr = scope.repoId.toString();
		String path = "src/Fee.java";
		StructuralDiffResult diffRes = compareJava(repoStr, path, beforeSha, beforeSrc, path, targetSha, afterSrc);
		SymbolKey feeKey = changedSymbolKey(diffRes);
		var afterBodyRange = diffRes.entityFacts().stream()
				.map(StructuralDiff.EntityFact::after)
				.flatMap(Optional::stream)
				.filter(s -> s.key().equals(feeKey))
				.findFirst().orElseThrow()
				.facetReferences().get(FacetKind.BODY).getFirst().reference().orElseThrow().range();

		AssessmentContext ctx = new AssessmentContext(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				TargetKind.TRACKED_BRANCH, "refs/heads/main", jobKey, attemptId,
				Optional.of(CFG_V1), targetSha, Optional.of(beforeSha),
				Intent.REVISION_COMPARISON, ScopeCompleteness.COMPLETE, false, Optional.empty());

		DocumentClaim claim = new DocumentClaim(
				claimId, docId.toString(), "v1", SHA_A, targetSha,
				"Fee#fee(int) multiplies d by 5", path, Optional.of(feeKey), Optional.of(FacetKind.BODY),
				ClaimVerdict.POTENTIAL_MISMATCH_INITIAL_ONLY, "Executable body changed on documented formula",
				Optional.empty());
		DocumentBinding binding = new DocumentBinding(
				scope.tenantId.toString(), scope.workspaceId.toString(), repoStr,
				docId.toString(), "v1", SHA_A, "bind-fee",
				BindingGranularity.EXACT_SYMBOL, path, Optional.of(feeKey), Optional.of("sec-fee"), List.of(claim));

		DocumentLookupKey targetSymLookup = new DocumentLookupKey(
				ctx.tenantId(), ctx.workspaceId(), repoStr, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				SideRole.AFTER, targetSha, path, LookupGranularity.SYMBOL, Optional.of(feeKey));
		List<DocumentLookupResponse> lookups = ImpactClassifier.buildLookupKeys(ctx, List.of(diffRes)).stream()
				.map(k -> new DocumentLookupResponse(
						k, LookupCompleteness.COMPLETE,
						k.equals(targetSymLookup) ? List.of(binding) : List.of(),
						Optional.empty()))
				.toList();

		List<SemanticAssessmentInput> semantics = semanticOutcome.map(status -> List.of(new SemanticAssessmentInput(
				ctx.tenantId(), ctx.workspaceId(), repoStr, TargetKind.TRACKED_BRANCH, "refs/heads/main",
				jobKey, attemptId, targetSha, path, feeKey,
				docId.toString(), "v1", SHA_A, status,
				true,
				List.of(new GroundedSourceFacetRef(SideRole.AFTER, targetSha, path, feeKey, FacetKind.BODY, Optional.of(afterBodyRange))),
				List.of(new GroundedDocumentClaimRef(docId.toString(), "v1", SHA_A, "bind-fee", claimId, Optional.of("sec-fee"))),
				status == SemanticStatus.BEHAVIOR_MISMATCH ? Optional.of(0.91) : Optional.of(0.88),
				Optional.of("Grounded semantic evaluation"),
				status == SemanticStatus.BEHAVIOR_MISMATCH
						? Optional.of(new VerifiedImpactContext(Severity.MEDIUM, "Formula diverged", "billing"))
						: Optional.empty()))).orElseGet(List::of);

		ImpactReport report = classifier.classify(new ImpactClassificationInput(
				ctx, List.of(diffRes), lookups, semantics, List.of(), List.of()));
		List<SnapshotProof> proofs = List.of(
				presentProof(repoStr, SideRole.BEFORE, beforeSha, path, beforeSrc),
				presentProof(repoStr, SideRole.AFTER, targetSha, path, afterSrc));
		EvidenceResult evResult = evidenceMapper.map(new EvidenceInput(
				Optional.of(ctx), report, List.of(diffRes), proofs, lookups,
				semantics, List.of(), List.of(), List.of()));
		return new PipelineRun(ctx, evResult);
	}

	private StructuralDiffResult compareJava(
			String repo, String beforePath, String beforeSha, String beforeSrc,
			String afterPath, String afterSha, String afterSrc) {
		return structuralDiff.compare(new StructuralDiffInput(
				repo,
				Intent.REVISION_COMPARISON,
				Optional.of(new Present(new SnapshotRef(repo, beforeSha), new SourceInput(beforePath, beforeSrc))),
				new Present(new SnapshotRef(repo, afterSha), new SourceInput(afterPath, afterSrc)),
				Optional.empty()));
	}

	private static SymbolKey changedSymbolKey(StructuralDiffResult diffRes) {
		return diffRes.entityFacts().stream()
				.filter(f -> f.change() != ChangeKind.UNCHANGED
						&& f.after().map(s -> s.key().kind() == Kind.METHOD).orElse(false))
				.map(f -> f.after().map(EntitySide::key).orElseThrow())
				.findFirst()
				.orElseThrow();
	}

	private static SnapshotProof presentProof(String repo, SideRole role, String sha, String path, String src) {
		return new SnapshotProof(
				role,
				new SnapshotProvenance(new SnapshotRef(repo, sha), path, SidePresence.PRESENT,
						Optional.empty(), Optional.empty()),
				Optional.of(src));
	}

	private static CurrentnessProof currentProof(
			ScopeIds scope, TargetKind targetKind, String targetRef, String sha, long revision, Instant now) {
		return new CurrentnessProof(
				CurrentnessReceiptState.CURRENT,
				Optional.of(new ValidationReceipt(
						UUID.randomUUID(), scope.tenantId, scope.repoId,
						targetKind, targetRef, sha, CFG_V1, revision,
						Optional.of("fence-" + revision), now, now.plus(Duration.ofMinutes(5)))),
				Optional.empty());
	}

	private static JdbcTemplate adminJdbc(String jdbcUrl) {
		return new JdbcTemplate(new DriverManagerDataSource(
				jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	private record ScopeIds(UUID tenantId, UUID workspaceId, UUID repoId) {
		static ScopeIds fresh() {
			return new ScopeIds(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		}
	}

	private record PipelineRun(AssessmentContext context, EvidenceResult evidenceResult) {
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
}
