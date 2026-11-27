package com.livingdocs.repository;

import com.livingdocs.client.GitHubInstallationTokenProvider;
import com.livingdocs.client.GitHubInstallationTokenProvider.RepositoryAccess;
import com.livingdocs.client.GitHubSourceClient;
import com.livingdocs.client.GitHubSourceClient.CommitObservation;
import com.livingdocs.entity.TrackedBranchEntity;
import com.livingdocs.exception.SourceApiException;
import com.livingdocs.exception.SourceApiException.UnavailableReason;
import com.livingdocs.model.*;
import com.livingdocs.service.GitHubSourceLookup;
import com.livingdocs.service.GitHubSourceLookup.Outcome;
import com.livingdocs.service.SourceValidationService;
import com.livingdocs.entity.GithubConnectionEntity;
import com.livingdocs.entity.RepositoryConnectionEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.Value;
import lombok.experimental.Accessors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Đọc phiên bản cấu hình do PostgreSQL cấp, với trạng thái GitHub dành cho kiểm thử. */
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "livingdocs.source.lookup.github-ready-statuses=READY_TEST"})
@ActiveProfiles("db")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql:.*")
class JpaConnectionLookupTests {
    private static final String TRACKED_REF = "refs/heads/Release/v1";
    @Autowired private ConnectionLookup lookup;
    @Autowired private GitHubSourceLookup sourceLookup;
    @Autowired private SourceValidationService validation;
    @Autowired private PlatformTransactionManager transactions;
    @PersistenceContext private EntityManager entityManager;
    @MockitoBean private GitHubInstallationTokenProvider tokens;
    @MockitoBean private GitHubSourceClient github;
    private final List<Fixture> fixtures = new ArrayList<>();

    @Value
    @Accessors(fluent = true)
    private static class Fixture {
        private final SourceScope scope;
        private final UUID githubId;
        private final UUID repositoryId;
    }

    private void tenant(UUID id) {
        entityManager.createNativeQuery("select set_config('app.tenant_id', :tenant, true)")
                .setParameter("tenant", id.toString()).getSingleResult();
    }

    private Fixture fixture(String repositoryStatus, String githubStatus) {
        SourceScope scope = new SourceScope(UUID.randomUUID(), UUID.randomUUID());
        Fixture fixture = new TransactionTemplate(transactions).execute(transaction -> {
            tenant(scope.tenantId());
            GithubConnectionEntity github = new GithubConnectionEntity(scope.tenantId(),
                    UUID.randomUUID().toString(), "test-private-secret-reference", githubStatus, null);
            entityManager.persist(github);
            RepositoryConnectionEntity repository = new RepositoryConnectionEntity(scope.tenantId(),
                    scope.workspaceId(), github.getGithubConnectionId(), "123456", null, repositoryStatus);
            entityManager.persist(repository);
            return new Fixture(scope, github.getGithubConnectionId(), repository.getRepositoryConnectionId());
        });
        fixtures.add(fixture);
        return fixture;
    }

    private SourceConnection connection(Fixture fixture) {
        return lookup.find(fixture.scope(), fixture.repositoryId()).orElseThrow();
    }

    @AfterEach
    void cleanFixtures() {
        for (Fixture fixture : fixtures) {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                tenant(fixture.scope().tenantId());
                entityManager.createNativeQuery("delete from repository.tracked_branches where tenant_id = :tenant and repository_connection_id = :id")
                        .setParameter("tenant", fixture.scope().tenantId()).setParameter("id", fixture.repositoryId()).executeUpdate();
                entityManager.createNativeQuery("delete from repository.repository_connections where tenant_id = :tenant and repository_connection_id = :id")
                        .setParameter("tenant", fixture.scope().tenantId()).setParameter("id", fixture.repositoryId()).executeUpdate();
                entityManager.createNativeQuery("delete from repository.github_connections where tenant_id = :tenant and github_connection_id = :id")
                        .setParameter("tenant", fixture.scope().tenantId()).setParameter("id", fixture.githubId()).executeUpdate();
            });
        }
    }

    @Test
    void scopedLookupStopsBeforeGithubOnInvalidUnknownInactiveChangedOrUntrackedTarget() {
        Fixture active = fixture("ACTIVE", "READY_TEST");
        Fixture inactive = fixture("ACTIVE", "REVOKED");
        track(active, TRACKED_REF, false);
        String revision = connection(active).configRevision();

        for (String ref : List.of("2147483648", "0", "042", "-1")) {
            assertEquals(Outcome.INVALID_REQUEST, sourceLookup.readCurrent(
                    active.scope(), active.repositoryId(), TargetKind.PR, ref, revision).getOutcome());
        }
        assertEquals(Outcome.UNKNOWN_CONNECTION, sourceLookup.readCurrent(
                new SourceScope(active.scope().tenantId(), UUID.randomUUID()), active.repositoryId(),
                TargetKind.BRANCH, TRACKED_REF, revision).getOutcome());
        assertEquals(Outcome.UNKNOWN_CONNECTION, sourceLookup.readCurrent(
                new SourceScope(UUID.randomUUID(), active.scope().workspaceId()), active.repositoryId(),
                TargetKind.BRANCH, TRACKED_REF, revision).getOutcome());
        assertEquals(Outcome.CONNECTION_INACTIVE, sourceLookup.readCurrent(inactive.scope(), inactive.repositoryId(),
                TargetKind.BRANCH, TRACKED_REF, revision).getOutcome());
        assertEquals(Outcome.CONFIG_CHANGED, sourceLookup.readCurrent(active.scope(), active.repositoryId(),
                TargetKind.BRANCH, TRACKED_REF, "old-config").getOutcome());
        assertEquals(Outcome.UNKNOWN_REF, sourceLookup.readCurrent(active.scope(), active.repositoryId(),
                TargetKind.BRANCH, TRACKED_REF, revision).getOutcome());
        verifyNoInteractions(tokens, github);
    }

    @Test
    void scopedLookupReadsAnExactTrackedBranchOutsideTheDatabaseTransaction() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        track(fixture, TRACKED_REF, true);
        String revision = connection(fixture).configRevision();
        RepositoryAccess access = authorize(fixture, revision);
        CommitObservation commit = mock(CommitObservation.class);
        when(github.readBranch(access, TRACKED_REF)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(commit);
        });

        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            tenant(fixture.scope().tenantId());
            var result = sourceLookup.readCurrent(fixture.scope(), fixture.repositoryId(), TargetKind.BRANCH,
                    TRACKED_REF, revision);
            assertEquals(Outcome.OBSERVED, result.getOutcome());
            assertSame(commit, result.getCommit());
            assertEquals(revision, result.getConnection().configRevision());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(fixture.scope().tenantId().toString(), entityManager
                    .createNativeQuery("select current_setting('app.tenant_id')").getSingleResult());
        });
        assertEquals(Outcome.UNKNOWN_REF, sourceLookup.readCurrent(fixture.scope(), fixture.repositoryId(),
                TargetKind.BRANCH, "refs/heads/release/v1", revision).getOutcome());
    }

    @Test
    void scopedLookupDistinguishesGithubAbsenceAndAnUnavailableSourceWithoutRequiringATrackedBranchForPr() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        String revision = connection(fixture).configRevision();
        RepositoryAccess access = authorize(fixture, revision);
        when(github.readPullRequest(access, Integer.MAX_VALUE)).thenReturn(Optional.empty())
                .thenThrow(SourceApiException.unavailable(UnavailableReason.GITHUB_SOURCE_UNVERIFIED));

        var absent = sourceLookup.readCurrent(fixture.scope(), fixture.repositoryId(), TargetKind.PR,
                Integer.toString(Integer.MAX_VALUE), revision);
        assertEquals(Outcome.REF_ABSENT, absent.getOutcome());
        assertNull(absent.getCommit());
        var unavailable = sourceLookup.readCurrent(fixture.scope(), fixture.repositoryId(), TargetKind.PR,
                Integer.toString(Integer.MAX_VALUE), revision);
        assertEquals(Outcome.SOURCE_UNAVAILABLE, unavailable.getOutcome());
        assertNull(unavailable.getCommit());
    }

    private void track(Fixture fixture, String ref, boolean active) {
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            tenant(fixture.scope().tenantId());
            var branch = new TrackedBranchEntity(fixture.scope().tenantId(), fixture.repositoryId(), ref);
            if (!active) {
                branch.deactivate();
            }
            entityManager.persist(branch);
        });
    }

    private RepositoryAccess authorize(Fixture fixture, String revision) {
        RepositoryAccess access = mock(RepositoryAccess.class);
        when(tokens.openRepository(eq(fixture.scope()), anyString(), eq("123456"),
                eq("test-private-secret-reference"), eq(revision))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return access;
        });
        return access;
    }

    @Test
    void readsActualRepositoryAndGithubWithoutReturningCredentials() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        SourceConnection connection = connection(fixture);
        assertEquals(fixture.scope(), connection.scope());
        assertEquals(fixture.repositoryId(), connection.repositoryConnectionId());
        assertTrue(connection.configRevision().matches("v1:[1-9][0-9]*:[1-9][0-9]*"));
        assertEquals(connection.configRevision(), connection(fixture).configRevision());
        assertTrue(connection.active());
        assertFalse(connection.toString().contains("test-private-secret-reference"));
    }

    @Test
    void hidesUnknownRepositoryAndWrongTenantOrWorkspace() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        assertTrue(lookup.find(fixture.scope(), UUID.randomUUID()).isEmpty());
        assertTrue(lookup.find(new SourceScope(UUID.randomUUID(), fixture.scope().workspaceId()), fixture.repositoryId()).isEmpty());
        assertTrue(lookup.find(new SourceScope(fixture.scope().tenantId(), UUID.randomUUID()), fixture.repositoryId()).isEmpty());
    }

    @Test
    void pendingRevokedAndUnknownStatusesReturnNoConfigurationRevision() {
        for (String[] statuses : new String[][]{{"DISCONNECTED", "READY_TEST"}, {"ACTIVE", "PENDING"},
                {"ACTIVE", "REVOKED"}, {"ACTIVE", "UNRECOGNIZED"}}) {
            Fixture fixture = fixture(statuses[0], statuses[1]);
            SourceConnection connection = connection(fixture);
            assertFalse(connection.active());
            assertNull(connection.configRevision());
        }
    }

    @Test
    void rejectsPreviousRevisionBeforeCallingGithubAfterRepositoryOrCredentialChanges() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        String original = connection(fixture).configRevision();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            tenant(fixture.scope().tenantId());
            assertEquals(1, entityManager.createNativeQuery("""
                    update repository.repository_connections set default_branch = 'release'
                    where repository_connection_id = :id
                    """).setParameter("id", fixture.repositoryId()).executeUpdate());
        });
        String afterRepositoryChange = connection(fixture).configRevision();
        assertNotEquals(original, afterRepositoryChange);

        assertEquals(ValidationReason.CONFIG_CHANGED, validation.validate(fixture.scope(),
                new SourceValidationRequest(fixture.repositoryId(), TargetKind.BRANCH,
                        "refs/heads/main", "a".repeat(40), original)).reason());

        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            tenant(fixture.scope().tenantId());
            assertEquals(1, entityManager.createNativeQuery("""
                    update repository.github_connections set credential_ref = 'rotated-test-reference'
                    where github_connection_id = :id
                    """).setParameter("id", fixture.githubId()).executeUpdate());
        });
        assertNotEquals(afterRepositoryChange, connection(fixture).configRevision());
        assertEquals(ValidationReason.CONFIG_CHANGED, validation.validate(fixture.scope(),
                new SourceValidationRequest(fixture.repositoryId(), TargetKind.BRANCH,
                        "refs/heads/main", "a".repeat(40), afterRepositoryChange)).reason());
        verifyNoInteractions(tokens, github);
    }

    @Test
    void isolatesLookupsAndRestoresTheCallersTenantTransaction() {
        Fixture first = fixture("ACTIVE", "READY_TEST");
        Fixture second = fixture("ACTIVE", "READY_TEST");
        String firstRevision = connection(first).configRevision();
        String secondRevision = connection(second).configRevision();
        assertNotEquals(firstRevision, secondRevision);
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            tenant(first.scope().tenantId());
            assertNotNull(entityManager.find(RepositoryConnectionEntity.class, first.repositoryId()));
            assertEquals(secondRevision, connection(second).configRevision());
            assertEquals(first.scope().tenantId().toString(), entityManager
                    .createNativeQuery("select current_setting('app.tenant_id')").getSingleResult());
            assertNull(entityManager.find(RepositoryConnectionEntity.class, second.repositoryId()));
        });
        assertEquals(firstRevision, connection(first).configRevision());
    }
}
