package com.livingdocs.repository.infrastructure.persistence;

import com.livingdocs.repository.infrastructure.persistence.entity.*;
import com.livingdocs.repository.infrastructure.persistence.repository.*;
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
        GithubConnectionEntity github = new GithubConnectionEntity();
        github.setTenantId(tenantId);
        github.setExternalInstallationRef("installation-" + UUID.randomUUID());
        github.setCredentialRef("test-secret-reference");
        github.setConnectedAt(Instant.parse("2026-11-06T00:00:00Z"));
        githubConnections.save(github);
        return github;
    }

    @Test
    void allNineEntitiesRoundTripAgainstV1Schema() {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        tenant(tenantId);

        GithubConnectionEntity github = github(tenantId);

        RepositoryConnectionEntity repository = new RepositoryConnectionEntity();
        repository.setTenantId(tenantId);
        repository.setWorkspaceId(workspaceId);
        repository.setGithubConnectionId(github.getGithubConnectionId());
        repository.setExternalRepoId("repo-" + UUID.randomUUID());
        repositoryConnections.save(repository);

        TrackedBranchEntity branch = new TrackedBranchEntity();
        branch.setTenantId(tenantId);
        branch.setRepositoryConnectionId(repository.getRepositoryConnectionId());
        branch.setRefName("refs/heads/main");
        trackedBranches.save(branch);

        TriggerPolicyRevisionEntity policy = new TriggerPolicyRevisionEntity();
        policy.setTenantId(tenantId);
        policy.setScopeType(PolicyScopeType.REPOSITORY);
        policy.setScopeRef(repository.getRepositoryConnectionId());
        policy.setRevision(1);
        Map<String, Object> rules = Map.of("enabled", true, "events", List.of("push", "pull_request"),
                "options", Map.of("trackedOnly", true));
        policy.setRules(rules);
        triggerPolicies.save(policy);

        WebhookDeliveryEntity delivery = new WebhookDeliveryEntity();
        delivery.setTenantId(tenantId);
        delivery.setGithubConnectionId(github.getGithubConnectionId());
        delivery.setProviderDeliveryKey(UUID.randomUUID().toString());
        delivery.setEventType("push");
        delivery.setClassification("TRACKED_BRANCH");
        webhookDeliveries.save(delivery);

        SourceRevisionEntity revision = new SourceRevisionEntity();
        revision.setTenantId(tenantId);
        revision.setRepositoryConnectionId(repository.getRepositoryConnectionId());
        revision.setTargetRef(branch.getRefName());
        revision.setCommitSha("a".repeat(40));
        sourceRevisions.save(revision);

        SourceValidationReceiptEntity receipt = new SourceValidationReceiptEntity();
        receipt.setTenantId(tenantId);
        receipt.setRepositoryConnectionId(repository.getRepositoryConnectionId());
        receipt.setTargetRef(branch.getRefName());
        receipt.setValidatedHeadSha(revision.getCommitSha());
        receipt.setConfigRevision("cfg-1");
        receipt.setValidationStatus(SourceValidationStatus.PASS);
        // Giá trị fixture, không quy định thời hạn hiệu lực của ứng dụng.
        receipt.setExpiresAt(Instant.now().plusSeconds(3600));
        validationReceipts.save(receipt);

        ExportOperationEntity operation = new ExportOperationEntity();
        operation.setTenantId(tenantId);
        operation.setRepositoryConnectionId(repository.getRepositoryConnectionId());
        operation.setPublishedVersionId(UUID.randomUUID());
        operation.setTargetRef("refs/heads/document-export");
        exportOperations.save(operation);

        ExportProvenanceEntity provenance = new ExportProvenanceEntity();
        provenance.setTenantId(tenantId);
        provenance.setExportOperationId(operation.getExportOperationId());
        provenance.setBranchRef(operation.getTargetRef());
        provenance.setExportCommitSha("b".repeat(40));
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
        storedBranch.setActive(false);
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

        github.setStatus("CONNECTED");
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
    }

    @Test
    void latestPolicyQueryRespectsRevisionAndScope() {
        UUID tenantId = UUID.randomUUID();
        UUID scopeRef = UUID.randomUUID();
        tenant(tenantId);
        for (int revision : new int[]{3, 1, 2}) {
            TriggerPolicyRevisionEntity policy = new TriggerPolicyRevisionEntity();
            policy.setTenantId(tenantId);
            policy.setScopeType(PolicyScopeType.WORKSPACE);
            policy.setScopeRef(scopeRef);
            policy.setRevision(revision);
            policy.setRules(Map.of("revision", revision));
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
