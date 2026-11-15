package com.livingdocs.repository;

import com.livingdocs.model.SourceConnection;
import com.livingdocs.model.SourceScope;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Tra cứu kết nối trong giao dịch riêng để không tái sử dụng bộ nhớ đệm của đơn vị khác. */
public class JpaConnectionLookup implements ConnectionLookup {
    private final EntityManager entityManager;
    private final RepositoryConnectionRepository repositories;
    private final GithubConnectionRepository githubConnections;
    private final ConfigurationRevisionLookup revisions;
    private final Set<String> githubReadyStatuses;

    public JpaConnectionLookup(EntityManager entityManager, RepositoryConnectionRepository repositories,
                               GithubConnectionRepository githubConnections, ConfigurationRevisionLookup revisions,
                               Set<String> githubReadyStatuses) {
        this.entityManager = Objects.requireNonNull(entityManager);
        this.repositories = Objects.requireNonNull(repositories);
        this.githubConnections = Objects.requireNonNull(githubConnections);
        this.revisions = Objects.requireNonNull(revisions);
        this.githubReadyStatuses = Set.copyOf(githubReadyStatuses);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Optional<SourceConnection> find(SourceScope scope, UUID repositoryConnectionId) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(repositoryConnectionId);
        entityManager.createNativeQuery("select set_config('app.tenant_id', :tenant, true)")
                .setParameter("tenant", scope.tenantId().toString()).getSingleResult();

        var found = repositories.findByTenantIdAndWorkspaceIdAndRepositoryConnectionId(
                scope.tenantId(), scope.workspaceId(), repositoryConnectionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var repository = found.get();
        var github = githubConnections.findByTenantIdAndGithubConnectionId(
                scope.tenantId(), repository.getGithubConnectionId());
        boolean active = "ACTIVE".equals(repository.getStatus()) && github.isPresent()
                && githubReadyStatuses.contains(github.get().getStatus());
        if (!active) {
            return Optional.of(new SourceConnection(scope, repositoryConnectionId, null, false));
        }
        String revision = revisions.findCurrent(scope, repositoryConnectionId)
                .filter(value -> !value.isBlank() && value.length() <= 255)
                .orElse(null);
        return Optional.of(new SourceConnection(scope, repositoryConnectionId, revision, true));
    }
}
