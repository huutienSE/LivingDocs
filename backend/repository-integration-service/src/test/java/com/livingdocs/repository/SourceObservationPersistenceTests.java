package com.livingdocs.repository;

import com.livingdocs.client.GitHubInstallationTokenProvider;
import com.livingdocs.client.GitHubInstallationTokenProvider.RepositoryAccess;
import com.livingdocs.client.GitHubSourceClient;
import com.livingdocs.client.GitHubSourceClient.CommitObservation;
import com.livingdocs.entity.GithubConnectionEntity;
import com.livingdocs.entity.RepositoryConnectionEntity;
import com.livingdocs.entity.SourceTargetHeadEntity.HeadState;
import com.livingdocs.entity.SourceTargetKey;
import com.livingdocs.entity.TrackedBranchEntity;
import com.livingdocs.exception.SourceApiException;
import com.livingdocs.exception.SourceApiException.UnavailableReason;
import com.livingdocs.model.SourceScope;
import com.livingdocs.model.SourceResolveRequest;
import com.livingdocs.model.SourceValidationRequest;
import com.livingdocs.model.TargetKind;
import com.livingdocs.model.ValidationReason;
import com.livingdocs.repository.JpaSourceObservationStore.Observation;
import com.livingdocs.repository.JpaSourceObservationStore.Outcome;
import com.livingdocs.service.SourceValidationService;
import com.livingdocs.service.SourceResolveService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "livingdocs.source.lookup.github-ready-statuses=CONNECTED")
@ActiveProfiles("db")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql:.*")
class SourceObservationPersistenceTests {
    @Test
    void databaseLockWaitExpiresAndKeepsTheAcceptedObservation() throws Exception {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        write(target, read(target), Observation.present(AUTHORITY, A));
        var before = read(target);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = workers.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                tenant(target.getTenantId());
                entityManager.createNativeQuery("""
                        select repository_connection_id from repository.repository_connections
                        where repository_connection_id = :id for no key update
                        """).setParameter("id", target.getRepositoryConnectionId()).getSingleResult();
                locked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            try {
                assertTrue(locked.await(2, TimeUnit.SECONDS));
                try (var deadline = new com.livingdocs.model.SourceRequestDeadline(
                        java.time.Duration.ofMillis(250), System::nanoTime)) {
                    deadline.bind();
                    org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(2), () ->
                            assertThrows(org.springframework.dao.DataAccessException.class,
                                    () -> write(target, before, Observation.present(AUTHORITY, B))));
                }
            } finally {
                release.countDown();
            }
            holder.get(2, TimeUnit.SECONDS);
        }
        assertEquals(A, read(target).getObservedHead().getHeadSha());
        assertEquals(before.getObservationVersion(), read(target).getObservationVersion());
    }
    private static final String AUTHORITY = "123456";
    private static final String FORK = "123457";
    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);
    private static final String REF = "refs/heads/release/v3";

    @PersistenceContext private EntityManager entityManager;
    @Autowired private JpaConnectionLookup lookup;
    @Autowired private JpaSourceObservationStore store;
    @Autowired private SourceTargetHeadRepository heads;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private Flyway flyway;
    @Autowired private SourceValidationService validation;
    @Autowired private SourceResolveService resolver;
    @MockitoBean private GitHubInstallationTokenProvider tokens;
    @MockitoBean private GitHubSourceClient github;
    private final List<UUID> testTenants = new ArrayList<>();

    @Test
    void resolveReturnsTheFreshBranchShaAndTheCommittedSourceRevision() {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        var input = validationRequest(target, A);
        var access = githubAccess(target, input);
        var firstRead = Optional.of(commit(A));
        var changedRead = Optional.of(commit(B));
        when(github.readBranch(access, REF)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return firstRead;
        }).thenReturn(changedRead);

        var request = new SourceResolveRequest(target.getRepositoryConnectionId(), TargetKind.BRANCH, REF, null);
        var first = resolver.resolve(scope(target), request);
        assertEquals(ValidationReason.CURRENT, first.reason());
        assertEquals(scope(target), first.scope());
        assertEquals(target.getRepositoryConnectionId(), first.repositoryConnectionId());
        assertEquals(TargetKind.BRANCH, first.targetKind());
        assertEquals(REF, first.targetRef());
        assertEquals(A, first.resolvedSha());
        assertEquals(input.expectedConfigRevision(), first.configRevision());
        assertEquals("1", first.sourceRevision());
        assertEquals(A, read(target).getObservedHead().getHeadSha());

        var next = resolver.resolve(scope(target), request);
        assertEquals(ValidationReason.CURRENT, next.reason());
        assertEquals(B, next.resolvedSha());
        assertEquals("2", next.sourceRevision());
        assertEquals(B, read(target).getObservedHead().getHeadSha());
        assertEquals(2, read(target).getObservationVersion());
    }

    @Test
    void resolveComparesAnExplicitShaAfterCommittingTheObservedBranchHead() {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        var access = githubAccess(target, validationRequest(target, A));
        var currentHead = Optional.of(commit(A));
        var changedHead = Optional.of(commit(B));
        when(github.readBranch(access, REF)).thenReturn(currentHead, changedHead);

        var request = new SourceResolveRequest(target.getRepositoryConnectionId(), TargetKind.BRANCH, REF, A.toUpperCase());
        assertEquals(ValidationReason.CURRENT, resolver.resolve(scope(target), request).reason());

        var stale = resolver.resolve(scope(target), request);
        assertEquals(ValidationReason.STALE_SHA, stale.reason());
        assertEquals(B, stale.resolvedSha());
        assertEquals("2", stale.sourceRevision());
        assertEquals(B, read(target).getObservedHead().getHeadSha());
    }

    @Test
    void resolveFailureWithholdsShaAndDoesNotCreateAnObservation() {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        var access = githubAccess(target, validationRequest(target, A));
        var failure = SourceApiException.unavailable(UnavailableReason.GITHUB_SOURCE_UNVERIFIED);
        when(github.readBranch(access, REF)).thenThrow(failure);

        var result = resolver.resolve(scope(target),
                new SourceResolveRequest(target.getRepositoryConnectionId(), TargetKind.BRANCH, REF, null));
        assertEquals(ValidationReason.SOURCE_UNAVAILABLE, result.reason());
        assertNull(result.resolvedSha());
        assertNull(result.sourceRevision());
        assertFalse(result.toString().contains(failure.getMessage()));
        assertEquals(0, read(target).getObservationVersion());
    }

    @Test
    void validationReturnsTheCommittedRevisionAndRecordsAChangedShaEvenWhenItIsStale() {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        var input = validationRequest(target, A);
        RepositoryAccess access = githubAccess(target, input);
        var firstObservation = Optional.of(commit(A));
        var changedObservation = Optional.of(commit(B));
        when(github.readBranch(access, REF)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return firstObservation;
        }).thenReturn(firstObservation).thenReturn(changedObservation);

        var first = validation.validate(scope(target), input);
        var repeated = validation.validate(scope(target), input);
        var stale = validation.validate(scope(target), input);

        assertEquals(ValidationReason.CURRENT, first.reason());
        assertEquals("1", first.sourceRevision());
        assertEquals("1", repeated.sourceRevision());
        assertNotEquals(first.validationId(), repeated.validationId());
        assertEquals(ValidationReason.STALE_SHA, stale.reason());
        assertEquals(A, stale.expectedHeadSha());
        assertEquals(B, stale.validatedHeadSha());
        assertEquals("2", stale.sourceRevision());
        var saved = read(target).getObservedHead();
        assertEquals(B, saved.getHeadSha());
        assertEquals(3L, saved.getObservationVersion());
        assertEquals(2L, saved.getSourceRevision());
    }

    @Test
    void ambiguousProviderFailurePreservesTheHeadAndConfirmedAbsenceUpdatesIt() {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        var input = validationRequest(target, A);
        var access = githubAccess(target, input);
        var initial = Optional.of(commit(A));
        when(github.readBranch(access, REF)).thenReturn(initial)
                .thenThrow(SourceApiException.unavailable(UnavailableReason.GITHUB_SOURCE_UNVERIFIED))
                .thenReturn(Optional.empty());

        assertEquals(ValidationReason.CURRENT, validation.validate(scope(target), input).reason());
        var unavailable = validation.validate(scope(target), input);
        assertEquals(ValidationReason.SOURCE_UNAVAILABLE, unavailable.reason());
        assertNull(unavailable.sourceRevision());
        assertEquals(1, read(target).getObservationVersion());

        var absent = validation.validate(scope(target), input);
        assertEquals(ValidationReason.UNKNOWN_REF, absent.reason());
        assertNull(absent.sourceRevision());
        var stored = read(target).getObservedHead();
        assertEquals(HeadState.ABSENT, stored.getHeadState());
        assertEquals(2, stored.getSourceRevision());
    }

    @Test
    void competingWriteRejectsTheEarlierObservationWithoutReturningEvidence() {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        var input = validationRequest(target, A);
        var access = githubAccess(target, input);
        var oldHead = Optional.of(commit(A));
        when(github.readBranch(access, REF)).thenAnswer(invocation -> {
            assertEquals(Outcome.ACCEPTED, store.writeObservation(target, AUTHORITY, input.expectedConfigRevision(),
                    0, Observation.present(AUTHORITY, B)).getOutcome());
            return oldHead;
        });

        var result = validation.validate(scope(target), input);
        assertEquals(ValidationReason.SOURCE_OBSERVATION_CONFLICT, result.reason());
        assertNull(result.validatedHeadSha());
        assertNull(result.sourceRevision());
        assertEquals(B, read(target).getObservedHead().getHeadSha());
    }

    @Test
    void configurationChangeWhileReadingGithubRejectsTheWrite() {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        var input = validationRequest(target, A);
        var access = githubAccess(target, input);
        var oldHead = Optional.of(commit(A));
        when(github.readBranch(access, REF)).thenAnswer(invocation -> {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                tenant(target.getTenantId());
                entityManager.createNativeQuery("""
                        update repository.repository_connections set default_branch = 'stable'
                        where repository_connection_id = :id
                        """).setParameter("id", target.getRepositoryConnectionId()).executeUpdate();
            });
            return oldHead;
        });

        var result = validation.validate(scope(target), input);
        assertEquals(ValidationReason.CONFIG_CHANGED, result.reason());
        assertNull(result.sourceRevision());
        assertEquals(0, read(target).getObservationVersion());
    }

    private SourceValidationRequest validationRequest(SourceTargetKey target, String sha) {
        return new SourceValidationRequest(target.getRepositoryConnectionId(), target.getTargetKind(), target.getTargetRef(),
                sha, read(target).getConnection().configRevision());
    }

    private RepositoryAccess githubAccess(SourceTargetKey target, SourceValidationRequest input) {
        RepositoryAccess access = mock(RepositoryAccess.class);
        when(tokens.openRepository(scope(target), "42", AUTHORITY, "test-key.pem", input.expectedConfigRevision()))
                .thenReturn(access);
        return access;
    }

    private CommitObservation commit(String sha) {
        var commit = mock(CommitObservation.class);
        when(commit.getQueriedRepositoryId()).thenReturn(Long.parseLong(AUTHORITY));
        when(commit.getSourceRepositoryId()).thenReturn(Long.parseLong(AUTHORITY));
        when(commit.getSha()).thenReturn(sha);
        return commit;
    }

    private static SourceScope scope(SourceTargetKey target) {
        return new SourceScope(target.getTenantId(), target.getWorkspaceId());
    }

    @Test
    @Transactional(readOnly = true)
    void v3EnablesRowIsolationAndLimitsTheAppRolesWritePrivileges() {
        assertEquals(1L, ((Number) entityManager.createNativeQuery("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'repository' and c.relrowsecurity and c.relforcerowsecurity
                  and c.relname = 'source_target_heads'
                """).getSingleResult()).longValue());
        assertEquals(Boolean.TRUE, entityManager.createNativeQuery("""
                select has_table_privilege(current_user, 'repository.source_target_heads', 'SELECT')
                   and has_table_privilege(current_user, 'repository.source_target_heads', 'INSERT')
                   and has_column_privilege(current_user, 'repository.source_target_heads', 'head_sha', 'UPDATE')
                   and not has_column_privilege(current_user, 'repository.source_target_heads', 'tenant_id', 'UPDATE')
                   and not has_table_privilege(current_user, 'repository.source_target_heads', 'DELETE')
                   and to_regclass('repository.source_snapshots') is null
                """).getSingleResult());
    }

    @Test
    void staleWriterCannotOverwriteNewerObservationAndReturningToAnOldShaHasANewSourceRevision() {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        var before = read(target);
        assertEquals(0, before.getObservationVersion());

        var first = write(target, before, Observation.present(AUTHORITY, A));
        assertEquals(Outcome.ACCEPTED, first.getOutcome());
        assertEquals(1, first.getHead().getSourceRevision());
        assertEquals(Outcome.OBSERVATION_CONFLICT, write(target, before, Observation.present(AUTHORITY, B)).getOutcome());

        var firstRead = read(target);
        var same = write(target, firstRead, Observation.present(AUTHORITY, A));
        assertEquals(2, same.getHead().getObservationVersion());
        assertEquals(1, same.getHead().getSourceRevision());
        var later = write(target, read(target), Observation.present(AUTHORITY, B));
        assertEquals(2, later.getHead().getSourceRevision());
        assertEquals(Outcome.OBSERVATION_CONFLICT, write(target, firstRead, Observation.present(AUTHORITY, A)).getOutcome());

        var back = write(target, read(target), Observation.present(AUTHORITY, A));
        assertEquals(3, back.getHead().getSourceRevision());
        assertEquals(4, back.getHead().getObservationVersion());
        assertEquals(A, read(target).getObservedHead().getHeadSha());
    }

    @Test
    void concurrentFirstWritesHaveExactlyOneWinner() throws Exception {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        var before = read(target);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return write(target, before, Observation.present(AUTHORITY, A));
            });
            var second = workers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return write(target, before, Observation.present(AUTHORITY, B));
            });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            List<Outcome> outcomes = List.of(a.getOutcome(), b.getOutcome());
            assertTrue(outcomes.containsAll(List.of(Outcome.ACCEPTED, Outcome.OBSERVATION_CONFLICT)));
            String winner = a.getOutcome() == Outcome.ACCEPTED ? A : B;
            assertEquals(winner, read(target).getObservedHead().getHeadSha());
            assertEquals(1, read(target).getObservationVersion());
        }
    }

    @Test
    void absenceAndForkRepositoryChangesEachAdvanceTheSourceRevision() {
        SourceTargetKey target = fixture(TargetKind.PR);
        write(target, read(target), Observation.present(AUTHORITY, A));
        var moved = write(target, read(target), Observation.present(FORK, A));
        assertEquals(2, moved.getHead().getSourceRevision());
        assertEquals(FORK, moved.getHead().getSourceRepositoryId());

        var absent = write(target, read(target), Observation.absent());
        assertEquals(HeadState.ABSENT, absent.getHead().getHeadState());
        assertNull(absent.getHead().getSourceRepositoryId());
        assertNull(absent.getHead().getHeadSha());
        assertEquals(3, absent.getHead().getSourceRevision());
        var stillAbsent = write(target, read(target), Observation.absent());
        assertEquals(4, stillAbsent.getHead().getObservationVersion());
        assertEquals(3, stillAbsent.getHead().getSourceRevision());
    }

    @Test
    void invalidShaLeavesTheAcceptedHeadAndBothVersionsIntact() {
        SourceTargetKey target = fixture(TargetKind.PR);
        write(target, read(target), Observation.present(FORK, A));
        var before = read(target);
        assertThrows(DataIntegrityViolationException.class,
                () -> write(target, before, Observation.present(FORK, "0".repeat(40))));

        var after = read(target);
        assertEquals(1, after.getObservationVersion());
        assertEquals(1, after.getObservedHead().getSourceRevision());
        assertEquals(A, after.getObservedHead().getHeadSha());
    }

    @Test
    void rejectsWritesBasedOnAnOutdatedConfiguration() {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        write(target, read(target), Observation.present(AUTHORITY, A));
        var old = read(target);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            tenant(target.getTenantId());
            entityManager.createNativeQuery("""
                    update repository.repository_connections set default_branch = 'stable'
                    where repository_connection_id = :id
                    """).setParameter("id", target.getRepositoryConnectionId()).executeUpdate();
        });

        assertEquals(Outcome.CONFIG_CHANGED, write(target, old, Observation.present(AUTHORITY, B)).getOutcome());
        var current = read(target);
        assertNotEquals(old.getConnection().configRevision(), current.getConnection().configRevision());
        assertEquals(1, current.getObservationVersion());
        assertEquals(A, current.getObservedHead().getHeadSha());
    }

    @Test
    void isolatesObservationsByTenantAndWorkspace() {
        SourceTargetKey target = fixture(TargetKind.BRANCH);
        var initial = read(target);
        write(target, initial, Observation.present(AUTHORITY, A));
        var otherWorkspace = new SourceTargetKey(new SourceScope(target.getTenantId(), UUID.randomUUID()),
                target.getRepositoryConnectionId(), target.getTargetKind(), target.getTargetRef());
        assertTrue(find(otherWorkspace).isEmpty());
        assertEquals(Outcome.UNKNOWN_CONNECTION,
                write(otherWorkspace, initial, Observation.present(AUTHORITY, B)).getOutcome());

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            tenant(UUID.randomUUID());
            assertTrue(heads.findByTargetAndAuthorityRepositoryId(target, AUTHORITY).isEmpty());
            assertEquals(1, read(target).getObservationVersion());
            assertTrue(heads.findByTargetAndAuthorityRepositoryId(target, AUTHORITY).isEmpty());
        });
    }

    private Optional<JpaConnectionLookup.TargetContext> find(SourceTargetKey target) {
        return lookup.findTarget(new SourceScope(target.getTenantId(), target.getWorkspaceId()),
                target.getRepositoryConnectionId(), target.getTargetKind(), target.getTargetRef());
    }

    private JpaConnectionLookup.TargetContext read(SourceTargetKey target) {
        return find(target).orElseThrow();
    }

    private JpaSourceObservationStore.WriteResult write(SourceTargetKey target, JpaConnectionLookup.TargetContext context,
                                                        Observation observation) {
        return store.writeObservation(target, AUTHORITY, context.getConnection().configRevision(),
                context.getObservationVersion(), observation);
    }

    private SourceTargetKey fixture(TargetKind kind) {
        SourceScope scope = new SourceScope(UUID.randomUUID(), UUID.randomUUID());
        SourceTargetKey target = new TransactionTemplate(transactions).execute(tx -> {
            tenant(scope.tenantId());
            var github = new GithubConnectionEntity(scope.tenantId(), "42", "test-key.pem", "CONNECTED", null);
            entityManager.persist(github);
            var repository = new RepositoryConnectionEntity(scope.tenantId(), scope.workspaceId(),
                    github.getGithubConnectionId(), AUTHORITY, "main", "ACTIVE");
            entityManager.persist(repository);
            UUID repositoryId = repository.getRepositoryConnectionId();
            if (kind == TargetKind.BRANCH) {
                entityManager.persist(new TrackedBranchEntity(scope.tenantId(), repositoryId, REF));
            }
            return new SourceTargetKey(scope, repositoryId, kind, kind == TargetKind.BRANCH ? REF : "42");
        });
        testTenants.add(scope.tenantId());
        return target;
    }

    private void tenant(UUID id) {
        entityManager.createNativeQuery("select set_config('app.tenant_id', :tenant, true)")
                .setParameter("tenant", id.toString()).getSingleResult();
    }

    @AfterEach
    void cleanCommittedFixturesWithTheSchemaOwner() throws SQLException {
        if (testTenants.isEmpty()) {
            return;
        }
        // Vai trò ứng dụng được cấp quyền ghi nghiệp vụ theo V3, quyền dọn dữ liệu kiểm thử dùng schema owner.
        try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            for (UUID tenant : testTenants) {
                try (var scope = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                    scope.setString(1, tenant.toString());
                    scope.execute();
                }
                for (String table : List.of("source_target_heads", "tracked_branches",
                        "repository_connections", "github_connections")) {
                    try (var cleanup = connection.prepareStatement("delete from repository." + table + " where tenant_id = ?")) {
                        cleanup.setObject(1, tenant);
                        cleanup.executeUpdate();
                    }
                }
                connection.commit();
            }
        }
    }
}
