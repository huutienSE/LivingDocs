package com.livingdocs.repository.infrastructure.persistence;

import com.livingdocs.repository.application.service.SourceValidationService;
import com.livingdocs.repository.domain.model.*;
import com.livingdocs.repository.domain.port.ConfigurationRevisionLookup;
import com.livingdocs.repository.domain.port.ConnectionLookup;
import com.livingdocs.repository.infrastructure.persistence.entity.GithubConnectionEntity;
import com.livingdocs.repository.infrastructure.persistence.entity.RepositoryConnectionEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** Cơ sở dữ liệu thật; trạng thái READY_TEST và phiên bản cấu hình chỉ là dữ liệu kiểm thử. */
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "livingdocs.source.lookup.github-ready-statuses=READY_TEST"})
@ActiveProfiles("db")
@Import(JpaConnectionLookupTests.FixtureConfiguration.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql:.*")
class JpaConnectionLookupTests {
    @Autowired private ConnectionLookup lookup;
    @Autowired private RevisionFixture revisions;
    @Autowired private PlatformTransactionManager transactions;
    @PersistenceContext private EntityManager entityManager;
    private final List<Fixture> fixtures = new ArrayList<>();

    record Fixture(SourceScope scope, UUID githubId, UUID repositoryId) {}
    record RevisionKey(SourceScope scope, UUID repositoryId) {}

    static class RevisionFixture implements ConfigurationRevisionLookup {
        final Map<RevisionKey, String> current = new HashMap<>();
        int calls;

        @Override
        public Optional<String> findCurrent(SourceScope scope, UUID repositoryId) {
            calls++;
            return Optional.ofNullable(current.get(new RevisionKey(scope, repositoryId)));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        RevisionFixture revisionFixture() { return new RevisionFixture(); }
    }

    private void tenant(UUID id) {
        entityManager.createNativeQuery("select set_config('app.tenant_id', :tenant, true)")
                .setParameter("tenant", id.toString()).getSingleResult();
    }

    private Fixture fixture(String repositoryStatus, String githubStatus) {
        SourceScope scope = new SourceScope(UUID.randomUUID(), UUID.randomUUID());
        Fixture fixture = new TransactionTemplate(transactions).execute(transaction -> {
            tenant(scope.tenantId());
            GithubConnectionEntity github = new GithubConnectionEntity();
            github.setTenantId(scope.tenantId());
            github.setExternalInstallationRef(UUID.randomUUID().toString());
            github.setCredentialRef("test-private-secret-reference");
            github.setStatus(githubStatus);
            entityManager.persist(github);
            RepositoryConnectionEntity repository = new RepositoryConnectionEntity();
            repository.setTenantId(scope.tenantId());
            repository.setWorkspaceId(scope.workspaceId());
            repository.setGithubConnectionId(github.getGithubConnectionId());
            repository.setExternalRepoId("123456");
            repository.setStatus(repositoryStatus);
            entityManager.persist(repository);
            return new Fixture(scope, github.getGithubConnectionId(), repository.getRepositoryConnectionId());
        });
        fixtures.add(fixture);
        return fixture;
    }

    private void revision(Fixture fixture, String revision) {
        revisions.current.put(new RevisionKey(fixture.scope(), fixture.repositoryId()), revision);
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
        revisions.current.clear();
        revisions.calls = 0;
    }

    @Test
    void readsActualRepositoryAndGithubWithoutReturningCredentials() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        revision(fixture, "cfg-7");
        SourceConnection connection = lookup.find(fixture.scope(), fixture.repositoryId()).orElseThrow();
        assertEquals(fixture.scope(), connection.scope());
        assertEquals(fixture.repositoryId(), connection.repositoryConnectionId());
        assertEquals("cfg-7", connection.configRevision());
        assertTrue(connection.active());
        assertFalse(connection.toString().contains("test-private-secret-reference"));
    }

    @Test
    void hidesUnknownRepositoryAndWrongTenantOrWorkspaceBeforeConfigurationLookup() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        assertTrue(lookup.find(fixture.scope(), UUID.randomUUID()).isEmpty());
        assertTrue(lookup.find(new SourceScope(UUID.randomUUID(), fixture.scope().workspaceId()), fixture.repositoryId()).isEmpty());
        assertTrue(lookup.find(new SourceScope(fixture.scope().tenantId(), UUID.randomUUID()), fixture.repositoryId()).isEmpty());
        assertEquals(0, revisions.calls);
    }

    @Test
    void pendingRevokedAndUnknownStatusesFailClosedBeforeConfigurationLookup() {
        for (String[] statuses : new String[][]{{"DISCONNECTED", "READY_TEST"}, {"ACTIVE", "PENDING"},
                {"ACTIVE", "REVOKED"}, {"ACTIVE", "UNRECOGNIZED"}}) {
            Fixture fixture = fixture(statuses[0], statuses[1]);
            SourceConnection connection = lookup.find(fixture.scope(), fixture.repositoryId()).orElseThrow();
            assertFalse(connection.active());
            assertNull(connection.configRevision());
        }
        assertEquals(0, revisions.calls);
    }

    @Test
    void missingOrInvalidRevisionBlocksValidationWithoutCallingGithub() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        SourceValidationService service = new SourceValidationService(lookup,
                (connection, kind, ref) -> fail("Không được gọi GitHub khi thiếu phiên bản cấu hình"),
                Clock.systemUTC(), Duration.ofSeconds(30));
        for (String value : new String[]{null, " ", "a".repeat(256)}) {
            revision(fixture, value);
            SourceValidationResult result = service.validate(fixture.scope(),
                    new SourceValidationRequest(fixture.repositoryId(), TargetKind.BRANCH, "refs/heads/main", "a".repeat(40), "cfg-7"));
            assertEquals(ValidationStatus.FAIL, result.validationStatus());
            assertEquals(ValidationReason.SOURCE_UNAVAILABLE, result.reason());
        }
    }

    @Test
    void currentRevisionComesFromProviderRatherThanTheValidationRequest() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        revision(fixture, "cfg-8");
        SourceValidationService service = new SourceValidationService(lookup,
                (connection, kind, ref) -> fail("Không được gọi GitHub khi cấu hình đã thay đổi"),
                Clock.systemUTC(), Duration.ofSeconds(30));
        SourceValidationResult result = service.validate(fixture.scope(),
                new SourceValidationRequest(fixture.repositoryId(), TargetKind.BRANCH, "refs/heads/main", "a".repeat(40), "cfg-7"));
        assertEquals(ValidationReason.CONFIG_CHANGED, result.reason());
    }

    @Test
    void validationCanUseARealDatabaseLookupAndAnExplicitTestHeadProvider() {
        Fixture fixture = fixture("ACTIVE", "READY_TEST");
        revision(fixture, "cfg-7");
        SourceValidationService service = new SourceValidationService(lookup,
                (connection, kind, ref) -> Optional.of(new SourceHead(connection.scope(),
                        connection.repositoryConnectionId(), kind, ref, "a".repeat(40), "cfg-7", "test-fence")),
                Clock.systemUTC(), Duration.ofSeconds(30));
        SourceValidationResult result = service.validate(fixture.scope(),
                new SourceValidationRequest(fixture.repositoryId(), TargetKind.BRANCH, "refs/heads/main", "a".repeat(40), "cfg-7"));
        assertEquals(ValidationStatus.PASS, result.validationStatus());
        assertEquals(ValidationReason.CURRENT, result.reason());
    }

    @Test
    void isolatesLookupsAndRestoresTheCallersTenantTransaction() {
        Fixture first = fixture("ACTIVE", "READY_TEST");
        Fixture second = fixture("ACTIVE", "READY_TEST");
        revision(first, "first-config");
        revision(second, "second-config");
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            tenant(first.scope().tenantId());
            assertNotNull(entityManager.find(RepositoryConnectionEntity.class, first.repositoryId()));
            assertEquals("second-config", lookup.find(second.scope(), second.repositoryId()).orElseThrow().configRevision());
            assertEquals(first.scope().tenantId().toString(), entityManager
                    .createNativeQuery("select current_setting('app.tenant_id')").getSingleResult());
            assertNull(entityManager.find(RepositoryConnectionEntity.class, second.repositoryId()));
        });
        assertEquals("first-config", lookup.find(first.scope(), first.repositoryId()).orElseThrow().configRevision());
    }
}
