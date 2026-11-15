package com.livingdocs.repository;

import com.livingdocs.model.SourceConnection;
import com.livingdocs.model.SourceScope;
import com.livingdocs.model.TargetKind;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Tra cứu kết nối trong giao dịch riêng để không tái sử dụng bộ nhớ đệm của đơn vị khác. */
public class JpaConnectionLookup implements ConnectionLookup {
    private final EntityManager entityManager;
    private final RepositoryConnectionRepository repositories;
    private final GithubConnectionRepository githubConnections;
    private final TrackedBranchRepository trackedBranches;
    private final ConfigurationRevisionLookup revisions;
    private final Set<String> githubReadyStatuses;

    public JpaConnectionLookup(EntityManager entityManager, RepositoryConnectionRepository repositories,
                               GithubConnectionRepository githubConnections, TrackedBranchRepository trackedBranches,
                               ConfigurationRevisionLookup revisions,
                               Set<String> githubReadyStatuses) {
        this.entityManager = Objects.requireNonNull(entityManager);
        this.repositories = Objects.requireNonNull(repositories);
        this.githubConnections = Objects.requireNonNull(githubConnections);
        this.trackedBranches = Objects.requireNonNull(trackedBranches);
        this.revisions = Objects.requireNonNull(revisions);
        this.githubReadyStatuses = Set.copyOf(githubReadyStatuses);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Optional<SourceConnection> find(SourceScope scope, UUID repositoryConnectionId) {
        return read(scope, repositoryConnectionId, null, null).map(TargetContext::getConnection);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Optional<TargetContext> findTarget(SourceScope scope, UUID repositoryConnectionId, TargetKind kind, String ref) {
        return read(scope, repositoryConnectionId, Objects.requireNonNull(kind), Objects.requireNonNull(ref));
    }

    private Optional<TargetContext> read(SourceScope scope, UUID repositoryConnectionId, TargetKind kind, String ref) {
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
        String revision = active ? revisions.findCurrent(scope, repositoryConnectionId)
                .filter(value -> !value.isBlank() && value.length() <= 255)
                .orElse(null) : null;
        SourceConnection connection = new SourceConnection(scope, repositoryConnectionId, revision, active);
        if (kind == null || revision == null) {
            return Optional.of(new TargetContext(connection, false, null, null, null));
        }
        boolean allowed = kind != TargetKind.BRANCH || trackedBranches
                .findByTenantIdAndRepositoryConnectionIdAndRefNameAndActiveTrue(scope.tenantId(), repositoryConnectionId, ref)
                .isPresent();
        if (!allowed) {
            return Optional.of(new TargetContext(connection, false, null, null, null));
        }
        var githubConnection = github.orElseThrow();
        return Optional.of(new TargetContext(connection, true, repository.getExternalRepoId(),
                githubConnection.getExternalInstallationRef(), githubConnection.getCredentialRef()));
    }

    /** Dữ liệu nội bộ trả khỏi giao dịch đọc. Tham chiếu bí mật không xuất hiện trong toString(). */
    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class TargetContext {
        private final SourceConnection connection;
        private final boolean allowed;
        private final String externalRepoId;
        private final String installationRef;
        private final String credentialRef;
    }
}
