package com.livingdocs.repository;

import com.livingdocs.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm chứng ánh xạ trên PostgreSQL thật đã chạy migration, bằng vai trò ứng dụng. */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("db")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql:.*")
@Transactional
class RepositoryEntityPersistenceTests {
    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private Flyway flyway;

    @Autowired
    private GithubConnectionRepository githubConnections;

    @Autowired
    private RepositoryConnectionRepository repositoryConnections;

    @Autowired
    private TrackedBranchRepository trackedBranches;

    @Autowired
    private TriggerPolicyRevisionRepository triggerPolicies;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveries;

    @Autowired
    private SourceRevisionRepository sourceRevisions;

    @Autowired
    private SourceValidationReceiptRepository validationReceipts;

    @Autowired
    private ExportOperationRepository exportOperations;

    @Autowired
    private ExportProvenanceRepository exportProvenance;

    private void tenant(UUID tenantId) {
        entityManager.createNativeQuery("select set_config('app.tenant_id', :tenant, true)")
                .setParameter("tenant", tenantId.toString()).getSingleResult();
    }

    private GithubConnectionEntity github(UUID tenantId) {
        GithubConnectionEntity github = new GithubConnectionEntity(tenantId,
                "installation-" + UUID.randomUUID(), "test-secret-reference", "PENDING",
                Instant.parse("2026-11-06T00:00:00Z"));
        githubConnections.save(github);
        return github;
    }

    private RepositoryConnectionEntity repository(UUID tenantId, UUID githubId) {
        return repositoryConnections.save(new RepositoryConnectionEntity(tenantId, UUID.randomUUID(),
                githubId, "repo-" + UUID.randomUUID(), "main", "ACTIVE"));
    }

    private List<?> configRevisions(UUID tenantId, UUID repositoryId) {
        return entityManager.createNativeQuery("""
                select r.config_revision, g.config_revision
                from repository.repository_connections r
                join repository.github_connections g
                  on g.tenant_id = r.tenant_id and g.github_connection_id = r.github_connection_id
                where r.tenant_id = :tenant and r.repository_connection_id = :id
                """)
                .setParameter("tenant", tenantId).setParameter("id", repositoryId).getResultList();
    }

    private long[] configRevisionPair(UUID tenantId, UUID repositoryId) {
        Object[] row = (Object[]) configRevisions(tenantId, repositoryId).getFirst();
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    @Test
    void configRevisionChangesOnlyForRelevantRepositoryAndGithubUpdates() {
        UUID tenantId = UUID.randomUUID();
        tenant(tenantId);
        GithubConnectionEntity github = github(tenantId);
        RepositoryConnectionEntity repository = repository(tenantId, github.getGithubConnectionId());
        UUID repositoryId = repository.getRepositoryConnectionId();
        entityManager.flush();
        entityManager.clear();

        long[] original = configRevisionPair(tenantId, repositoryId);
        assertTrue(original[0] > 0);
        assertTrue(original[1] > 0);

        entityManager.createNativeQuery("""
                update repository.repository_connections
                set default_branch = default_branch, config_revision = config_revision + 1000
                where repository_connection_id = :id
                """).setParameter("id", repositoryId).executeUpdate();
        assertArrayEquals(original, configRevisionPair(tenantId, repositoryId));

        entityManager.createNativeQuery("""
                update repository.repository_connections set default_branch = 'release'
                where repository_connection_id = :id
                """).setParameter("id", repositoryId).executeUpdate();
        long[] changed = configRevisionPair(tenantId, repositoryId);
        assertTrue(changed[0] > original[0]);
        assertEquals(original[1], changed[1]);

        entityManager.createNativeQuery("""
                update repository.github_connections set credential_ref = credential_ref
                where github_connection_id = :id
                """).setParameter("id", github.getGithubConnectionId()).executeUpdate();
        assertArrayEquals(changed, configRevisionPair(tenantId, repositoryId));

        entityManager.createNativeQuery("""
                update repository.github_connections set credential_ref = 'rotated-test-reference'
                where github_connection_id = :id
                """).setParameter("id", github.getGithubConnectionId()).executeUpdate();
        long[] rotated = configRevisionPair(tenantId, repositoryId);
        assertEquals(changed[0], rotated[0]);
        assertTrue(rotated[1] > changed[1]);

        tenant(UUID.randomUUID());
        assertTrue(configRevisions(tenantId, repositoryId).isEmpty());
    }

    @Test
    void trackedBranchChangesInvalidateOnlyAffectedRepositoryRevisions() {
        UUID tenantId = UUID.randomUUID();
        tenant(tenantId);
        UUID githubId = github(tenantId).getGithubConnectionId();
        UUID first = repository(tenantId, githubId).getRepositoryConnectionId();
        UUID second = repository(tenantId, githubId).getRepositoryConnectionId();
        entityManager.flush();

        long[] beforeFirst = configRevisionPair(tenantId, first);
        long[] beforeSecond = configRevisionPair(tenantId, second);
        TrackedBranchEntity branch = trackedBranches.save(new TrackedBranchEntity(tenantId, first, "refs/heads/main"));
        entityManager.flush();
        UUID branchId = branch.getTrackedBranchId();
        long[] afterInsert = configRevisionPair(tenantId, first);
        assertTrue(afterInsert[0] > beforeFirst[0]);
        assertArrayEquals(beforeSecond, configRevisionPair(tenantId, second));

        entityManager.createNativeQuery("""
                update repository.tracked_branches set active = active where tracked_branch_id = :id
                """).setParameter("id", branchId).executeUpdate();
        assertArrayEquals(afterInsert, configRevisionPair(tenantId, first));

        branch.deactivate();
        entityManager.flush();
        long[] afterDeactivate = configRevisionPair(tenantId, first);
        assertTrue(afterDeactivate[0] > afterInsert[0]);

        entityManager.createNativeQuery("""
                update repository.tracked_branches set active = true where tracked_branch_id = :id
                """).setParameter("id", branchId).executeUpdate();
        long[] afterReactivate = configRevisionPair(tenantId, first);
        assertTrue(afterReactivate[0] > afterDeactivate[0]);

        entityManager.createNativeQuery("""
                update repository.tracked_branches set repository_connection_id = :repository
                where tracked_branch_id = :id
                """).setParameter("repository", second).setParameter("id", branchId).executeUpdate();
        long[] afterMoveFirst = configRevisionPair(tenantId, first);
        long[] afterMoveSecond = configRevisionPair(tenantId, second);
        assertTrue(afterMoveFirst[0] > afterReactivate[0]);
        assertTrue(afterMoveSecond[0] > beforeSecond[0]);

        entityManager.createNativeQuery("""
                delete from repository.tracked_branches where tracked_branch_id = :id
                """).setParameter("id", branchId).executeUpdate();
        long[] afterDelete = configRevisionPair(tenantId, second);
        assertTrue(afterDelete[0] > afterMoveSecond[0]);
        assertEquals(beforeSecond[1], afterDelete[1]);
        assertArrayEquals(afterMoveFirst, configRevisionPair(tenantId, first));
    }

    @Test
    void allNineEntitiesRoundTripAgainstMigratedSchema() {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        tenant(tenantId);

        GithubConnectionEntity github = github(tenantId);

        RepositoryConnectionEntity repository = new RepositoryConnectionEntity(tenantId, workspaceId,
                github.getGithubConnectionId(), "repo-" + UUID.randomUUID(), null, "ACTIVE");
        repositoryConnections.save(repository);

        TrackedBranchEntity branch = new TrackedBranchEntity(tenantId,
                repository.getRepositoryConnectionId(), "refs/heads/main");
        trackedBranches.save(branch);

        Map<String, Object> rules = Map.of("enabled", true, "events", List.of("push", "pull_request"),
                "options", Map.of("trackedOnly", true));
        TriggerPolicyRevisionEntity policy = new TriggerPolicyRevisionEntity(tenantId,
                PolicyScopeType.REPOSITORY, repository.getRepositoryConnectionId(), 1, rules);
        triggerPolicies.save(policy);

        WebhookDeliveryEntity delivery = new WebhookDeliveryEntity(tenantId, github.getGithubConnectionId(),
                UUID.randomUUID().toString(), "push", "TRACKED_BRANCH");
        webhookDeliveries.save(delivery);

        SourceRevisionEntity revision = new SourceRevisionEntity(tenantId,
                repository.getRepositoryConnectionId(), branch.getRefName(), "a".repeat(40));
        sourceRevisions.save(revision);

        // Thời hạn hiệu lực dùng cho dữ liệu kiểm thử.
        SourceValidationReceiptEntity receipt = new SourceValidationReceiptEntity(
                tenantId, repository.getRepositoryConnectionId(), branch.getRefName(),
                revision.getCommitSha(), "cfg-1", SourceValidationStatus.PASS,
                Instant.now().plusSeconds(3600));
        validationReceipts.save(receipt);

        ExportOperationEntity operation = new ExportOperationEntity(tenantId,
                repository.getRepositoryConnectionId(), UUID.randomUUID(), "refs/heads/document-export");
        exportOperations.save(operation);

        ExportProvenanceEntity provenance = new ExportProvenanceEntity(tenantId,
                operation.getExportOperationId(), operation.getTargetRef(), null, "b".repeat(40));
        exportProvenance.save(provenance);
        entityManager.flush();
        entityManager.clear();

        GithubConnectionEntity storedGithub = entityManager.find(GithubConnectionEntity.class, github.getGithubConnectionId());
        assertEquals("PENDING", storedGithub.getStatus());
        assertEquals(github.getConnectedAt(), storedGithub.getConnectedAt());
        assertEquals("test-secret-reference", storedGithub.getCredentialRef());
        assertNotNull(storedGithub.getCreatedAt());
        assertNotNull(storedGithub.getUpdatedAt());

        RepositoryConnectionEntity storedRepository = entityManager.find(RepositoryConnectionEntity.class, repository.getRepositoryConnectionId());
        assertEquals("ACTIVE", storedRepository.getStatus());
        assertEquals(workspaceId, storedRepository.getWorkspaceId());
        assertEquals(github.getGithubConnectionId(), storedRepository.getGithubConnectionId());
        assertNull(storedRepository.getDefaultBranch());
        assertNotNull(storedRepository.getCreatedAt());
        assertNotNull(storedRepository.getUpdatedAt());

        TrackedBranchEntity storedBranch = entityManager.find(TrackedBranchEntity.class, branch.getTrackedBranchId());
        assertEquals(Boolean.TRUE, storedBranch.getActive());
        assertEquals(branch.getRefName(), storedBranch.getRefName());
        assertNotNull(storedBranch.getConfiguredAt());

        TriggerPolicyRevisionEntity storedPolicy = entityManager.find(TriggerPolicyRevisionEntity.class, policy.getPolicyRevisionId());
        assertEquals(PolicyScopeType.REPOSITORY, storedPolicy.getScopeType());
        assertEquals(rules, storedPolicy.getRules());
        assertEquals(1, storedPolicy.getRevision());
        assertNotNull(storedPolicy.getCreatedAt());

        WebhookDeliveryEntity storedDelivery = entityManager.find(WebhookDeliveryEntity.class, delivery.getDeliveryId());
        assertEquals(delivery.getProviderDeliveryKey(), storedDelivery.getProviderDeliveryKey());
        assertEquals("TRACKED_BRANCH", storedDelivery.getClassification());
        assertNotNull(storedDelivery.getReceivedAt());

        SourceRevisionEntity storedRevision = entityManager.find(SourceRevisionEntity.class, revision.getSourceRevisionId());
        assertEquals(revision.getCommitSha(), storedRevision.getCommitSha());
        assertNotNull(storedRevision.getObservedAt());

        SourceValidationReceiptEntity storedReceipt = entityManager.find(SourceValidationReceiptEntity.class, receipt.getValidationId());
        assertEquals(SourceValidationStatus.PASS, storedReceipt.getValidationStatus());
        assertEquals(receipt.getValidatedHeadSha(), storedReceipt.getValidatedHeadSha());
        assertNotNull(storedReceipt.getCheckedAt());
        assertTrue(storedReceipt.getExpiresAt().isAfter(storedReceipt.getCheckedAt()));

        ExportOperationEntity storedOperation = entityManager.find(ExportOperationEntity.class, operation.getExportOperationId());
        assertEquals("REQUESTED", storedOperation.getStatus());
        assertEquals(operation.getPublishedVersionId(), storedOperation.getPublishedVersionId());
        assertNotNull(storedOperation.getRequestedAt());

        ExportProvenanceEntity storedProvenance = entityManager.find(ExportProvenanceEntity.class, provenance.getProvenanceId());
        assertEquals(operation.getExportOperationId(), storedProvenance.getExportOperationId());
        assertEquals(provenance.getExportCommitSha(), storedProvenance.getExportCommitSha());
        assertNull(storedProvenance.getPullRequestRef());
        assertNotNull(storedProvenance.getExportedAt());

        UUID wrongId = UUID.randomUUID();
        assertTrue(githubConnections.findByTenantIdAndGithubConnectionId(tenantId, github.getGithubConnectionId()).isPresent());
        assertTrue(githubConnections.findByTenantIdAndGithubConnectionId(wrongId, github.getGithubConnectionId()).isEmpty());
        assertTrue(githubConnections.findByTenantIdAndExternalInstallationRef(tenantId, github.getExternalInstallationRef()).isPresent());
        assertTrue(githubConnections.findByTenantIdAndExternalInstallationRef(wrongId, github.getExternalInstallationRef()).isEmpty());
        assertTrue(repositoryConnections.findByTenantIdAndWorkspaceIdAndRepositoryConnectionId(tenantId, workspaceId, repository.getRepositoryConnectionId()).isPresent());
        assertTrue(repositoryConnections.findByTenantIdAndWorkspaceIdAndRepositoryConnectionId(tenantId, wrongId, repository.getRepositoryConnectionId()).isEmpty());
        assertTrue(repositoryConnections.findByTenantIdAndWorkspaceIdAndExternalRepoId(tenantId, workspaceId, repository.getExternalRepoId()).isPresent());
        assertTrue(repositoryConnections.findByTenantIdAndWorkspaceIdAndExternalRepoId(wrongId, workspaceId, repository.getExternalRepoId()).isEmpty());
        assertTrue(trackedBranches.findByTenantIdAndRepositoryConnectionIdAndRefName(tenantId, repository.getRepositoryConnectionId(), branch.getRefName()).isPresent());
        assertTrue(trackedBranches.findByTenantIdAndRepositoryConnectionIdAndRefName(tenantId, wrongId, branch.getRefName()).isEmpty());
        assertTrue(trackedBranches.findByTenantIdAndRepositoryConnectionIdAndRefNameAndActiveTrue(tenantId, repository.getRepositoryConnectionId(), branch.getRefName()).isPresent());
        assertTrue(trackedBranches.findByTenantIdAndRepositoryConnectionIdAndRefNameAndActiveTrue(wrongId, repository.getRepositoryConnectionId(), branch.getRefName()).isEmpty());
        assertTrue(triggerPolicies.findFirstByTenantIdAndScopeTypeAndScopeRefOrderByRevisionDesc(tenantId, PolicyScopeType.REPOSITORY, repository.getRepositoryConnectionId()).isPresent());
        assertTrue(triggerPolicies.findFirstByTenantIdAndScopeTypeAndScopeRefOrderByRevisionDesc(wrongId, PolicyScopeType.REPOSITORY, repository.getRepositoryConnectionId()).isEmpty());
        assertTrue(webhookDeliveries.findByTenantIdAndGithubConnectionIdAndProviderDeliveryKey(tenantId, github.getGithubConnectionId(), delivery.getProviderDeliveryKey()).isPresent());
        assertTrue(webhookDeliveries.findByTenantIdAndGithubConnectionIdAndProviderDeliveryKey(tenantId, wrongId, delivery.getProviderDeliveryKey()).isEmpty());
        assertTrue(sourceRevisions.findByTenantIdAndRepositoryConnectionIdAndSourceRevisionId(tenantId, repository.getRepositoryConnectionId(), revision.getSourceRevisionId()).isPresent());
        assertTrue(sourceRevisions.findByTenantIdAndRepositoryConnectionIdAndSourceRevisionId(tenantId, wrongId, revision.getSourceRevisionId()).isEmpty());
        assertTrue(sourceRevisions.findByTenantIdAndRepositoryConnectionIdAndTargetRefAndCommitSha(tenantId, repository.getRepositoryConnectionId(), revision.getTargetRef(), revision.getCommitSha()).isPresent());
        assertTrue(sourceRevisions.findByTenantIdAndRepositoryConnectionIdAndTargetRefAndCommitSha(tenantId, repository.getRepositoryConnectionId(), revision.getTargetRef(), "c".repeat(40)).isEmpty());
        assertTrue(validationReceipts.findByTenantIdAndRepositoryConnectionIdAndValidationId(tenantId, repository.getRepositoryConnectionId(), receipt.getValidationId()).isPresent());
        assertTrue(validationReceipts.findByTenantIdAndRepositoryConnectionIdAndValidationId(tenantId, wrongId, receipt.getValidationId()).isEmpty());
        assertTrue(exportOperations.findByTenantIdAndRepositoryConnectionIdAndExportOperationId(tenantId, repository.getRepositoryConnectionId(), operation.getExportOperationId()).isPresent());
        assertTrue(exportOperations.findByTenantIdAndRepositoryConnectionIdAndExportOperationId(tenantId, wrongId, operation.getExportOperationId()).isEmpty());
        assertTrue(exportProvenance.findByTenantIdAndExportOperationId(tenantId, operation.getExportOperationId()).isPresent());
        assertTrue(exportProvenance.findByTenantIdAndExportOperationId(wrongId, operation.getExportOperationId()).isEmpty());
        storedBranch.deactivate();
        trackedBranches.save(storedBranch);
        entityManager.flush();
        assertTrue(trackedBranches.findByTenantIdAndRepositoryConnectionIdAndRefName(
                tenantId, repository.getRepositoryConnectionId(), branch.getRefName()).isPresent());
        assertTrue(trackedBranches.findByTenantIdAndRepositoryConnectionIdAndRefNameAndActiveTrue(
                tenantId, repository.getRepositoryConnectionId(), branch.getRefName()).isEmpty());

    }

    @Test
    void tenantScopeIsNotReplacedByEntityIdentifiers() {
        UUID tenantId = UUID.randomUUID();
        tenant(tenantId);
        GithubConnectionEntity github = github(tenantId);
        entityManager.flush();
        entityManager.clear();

        tenant(UUID.randomUUID());
        assertTrue(githubConnections.findByTenantIdAndGithubConnectionId(tenantId, github.getGithubConnectionId()).isEmpty());
        assertTrue(githubConnections.findById(github.getGithubConnectionId()).isEmpty());

        tenant(tenantId);
        assertNotNull(entityManager.find(GithubConnectionEntity.class, github.getGithubConnectionId()));
    }

    @Test
    void updatePreservesCreationTimeAndPersistsChangedValues() {
        UUID tenantId = UUID.randomUUID();
        tenant(tenantId);
        GithubConnectionEntity github = github(tenantId);
        entityManager.flush();
        Instant createdAt = github.getCreatedAt();
        Instant updatedAt = github.getUpdatedAt();

        github.markConnected();
        entityManager.flush();
        entityManager.clear();

        GithubConnectionEntity stored = entityManager.find(GithubConnectionEntity.class, github.getGithubConnectionId());
        assertEquals("CONNECTED", stored.getStatus());
        assertEquals(createdAt, stored.getCreatedAt());
        assertFalse(stored.getUpdatedAt().isBefore(updatedAt));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repositoryRequiresAnExistingTransaction() {
        assertThrows(IllegalTransactionStateException.class, () ->
                githubConnections.findByTenantIdAndGithubConnectionId(UUID.randomUUID(), UUID.randomUUID()));
        assertThrows(IllegalTransactionStateException.class, () ->
                githubConnections.save(new GithubConnectionEntity(UUID.randomUUID(),
                        "test-installation", "test-secret-reference", "PENDING", null)));
    }

    @Test
    void latestPolicyQueryRespectsRevisionAndScope() {
        UUID tenantId = UUID.randomUUID();
        UUID scopeRef = UUID.randomUUID();
        tenant(tenantId);
        for (int revision : new int[]{3, 1, 2}) {
            TriggerPolicyRevisionEntity policy = new TriggerPolicyRevisionEntity(tenantId,
                    PolicyScopeType.WORKSPACE, scopeRef, revision, Map.of("revision", revision));
            triggerPolicies.save(policy);
        }
        entityManager.flush();
        entityManager.clear();
        assertEquals(3, triggerPolicies.findFirstByTenantIdAndScopeTypeAndScopeRefOrderByRevisionDesc(
                tenantId, PolicyScopeType.WORKSPACE, scopeRef).orElseThrow().getRevision());
        assertTrue(triggerPolicies.findFirstByTenantIdAndScopeTypeAndScopeRefOrderByRevisionDesc(
                tenantId, PolicyScopeType.REPOSITORY, scopeRef).isEmpty());
        assertTrue(triggerPolicies.findFirstByTenantIdAndScopeTypeAndScopeRefOrderByRevisionDesc(
                tenantId, PolicyScopeType.WORKSPACE, UUID.randomUUID()).isEmpty());
        assertTrue(triggerPolicies.findFirstByTenantIdAndScopeTypeAndScopeRefOrderByRevisionDesc(
                UUID.randomUUID(), PolicyScopeType.WORKSPACE, scopeRef).isEmpty());
    }

    @Test
    void missingDatabaseTenantContextHidesRowsEvenWhenQueryTenantIsCorrect() {
        UUID tenantId = UUID.randomUUID();
        tenant(tenantId);
        GithubConnectionEntity github = github(tenantId);
        entityManager.flush();
        entityManager.clear();
        entityManager.createNativeQuery("select set_config('app.tenant_id', '', true)").getSingleResult();
        assertTrue(githubConnections.findByTenantIdAndGithubConnectionId(tenantId, github.getGithubConnectionId()).isEmpty());
    }

    @Test
    void platformPolicyUsesItsOwnLookupWithoutLeakingTenantPolicies() throws Exception {
        UUID policyId = UUID.randomUUID();
        // Chỉ vai trò migrator được tạo fixture PLATFORM; vai trò ứng dụng chỉ đọc.
        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
             var insert = connection.prepareStatement("""
                     insert into repository.trigger_policy_revisions
                         (policy_revision_id, scope_type, revision, rules)
                     select ?, 'PLATFORM', coalesce(max(revision), 0) + 1, '{"guardrail":true}'::jsonb
                     from repository.trigger_policy_revisions where scope_type = 'PLATFORM'
                     """)) {
            insert.setObject(1, policyId);
            insert.executeUpdate();
        }
        try {
            tenant(UUID.randomUUID());
            TriggerPolicyRevisionEntity policy = triggerPolicies
                    .findFirstByTenantIdIsNullAndScopeRefIsNullOrderByRevisionDesc().orElseThrow();
            assertEquals(policyId, policy.getPolicyRevisionId());
            assertEquals(PolicyScopeType.PLATFORM, policy.getScopeType());
            assertNull(policy.getTenantId());
            assertNull(policy.getScopeRef());
            assertEquals(Map.of("guardrail", true), policy.getRules());
        } finally {
            entityManager.clear();
            try (var connection = flyway.getConfiguration().getDataSource().getConnection();
                 var delete = connection.prepareStatement(
                         "delete from repository.trigger_policy_revisions where policy_revision_id = ?")) {
                delete.setObject(1, policyId);
                delete.executeUpdate();
            }
        }
    }
}
