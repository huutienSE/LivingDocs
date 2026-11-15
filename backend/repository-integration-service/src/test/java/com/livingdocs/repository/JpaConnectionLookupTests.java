package com.livingdocs.repository;

import com.livingdocs.model.*;
import com.livingdocs.service.SourceValidationService;
import com.livingdocs.entity.GithubConnectionEntity;
import com.livingdocs.entity.RepositoryConnectionEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.Value;
import lombok.experimental.Accessors;

import static org.junit.jupiter.api.Assertions.*;

/** Đọc phiên bản cấu hình do PostgreSQL cấp, với trạng thái GitHub dành cho kiểm thử. */
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "livingdocs.source.lookup.github-ready-statuses=READY_TEST"})
@ActiveProfiles("db")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql:.*")
class JpaConnectionLookupTests {
    @Autowired private ConnectionLookup lookup;
    @Autowired private PlatformTransactionManager transactions;
    @PersistenceContext private EntityManager entityManager;
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
                entityManager.createNativeQuery("delete from repository.repository_connections where tenant_id = :tenant and repository_connection_id = :id")
                        .setParameter("tenant", fixture.scope().tenantId()).setParameter("id", fixture.repositoryId()).executeUpdate();
                entityManager.createNativeQuery("delete from repository.github_connections where tenant_id = :tenant and github_connection_id = :id")
                        .setParameter("tenant", fixture.scope().tenantId()).setParameter("id", fixture.githubId()).executeUpdate();
            });
        }
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

        SourceValidationService service = new SourceValidationService(lookup,
                (connection, kind, ref) -> fail("Không được gọi GitHub khi cấu hình đã thay đổi"),
                Clock.systemUTC(), Duration.ofSeconds(30));
        assertEquals(ValidationReason.CONFIG_CHANGED, service.validate(fixture.scope(),
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
        assertEquals(ValidationReason.CONFIG_CHANGED, service.validate(fixture.scope(),
                new SourceValidationRequest(fixture.repositoryId(), TargetKind.BRANCH,
                        "refs/heads/main", "a".repeat(40), afterRepositoryChange)).reason());
    }

    @Test
    void validationCanUseARealDatabaseLookupAndAnExplicitTestHeadProvider() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        String revision = connection(fixture).configRevision();
        SourceValidationService service = new SourceValidationService(lookup,
                (connection, kind, ref) -> Optional.of(new SourceHead(connection.scope(),
                        connection.repositoryConnectionId(), kind, ref, "a".repeat(40), connection.configRevision(), "test-fence")),
                Clock.systemUTC(), Duration.ofSeconds(30));
        SourceValidationResult result = service.validate(fixture.scope(),
                new SourceValidationRequest(fixture.repositoryId(), TargetKind.BRANCH, "refs/heads/main", "a".repeat(40), revision));
        assertEquals(ValidationStatus.PASS, result.validationStatus());
        assertEquals(ValidationReason.CURRENT, result.reason());
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
