package com.livingdocs.repository;

import com.livingdocs.entity.SourceTargetHeadEntity;
import com.livingdocs.entity.SourceTargetKey;
import com.livingdocs.exception.SourceUnavailableException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Giao dịch B sau khi S02 đã đọc GitHub. */
@Repository
@RequiredArgsConstructor
public class JpaSourceObservationStore {
    private static final String CONNECTION_KEY = """
            tenant_id = :tenant and workspace_id = :workspace and repository_connection_id = :connection
            """;
    private static final String TARGET_KEY = CONNECTION_KEY + " and target_kind = :kind and target_ref = :ref";
    private static final String FIND_GITHUB_ID = """
            select github_connection_id from repository.repository_connections where
            """ + CONNECTION_KEY;
    private static final String INSERT_HEAD = """
            insert into repository.source_target_heads
                (tenant_id, workspace_id, repository_connection_id, target_kind, target_ref, authority_repository_id,
                 head_state, source_repository_id, head_sha, observation_version, source_revision)
            values (:tenant, :workspace, :connection, :kind, :ref, :authority, :state, :source, :sha, 1, 1)
            on conflict on constraint pk_source_target_heads do nothing
            """;
    private static final String UPDATE_HEAD = """
            update repository.source_target_heads set
                source_revision = source_revision + case
                    when head_state is distinct from :state or source_repository_id is distinct from :source
                        or head_sha is distinct from :sha then 1 else 0 end,
                observation_version = observation_version + 1,
                head_state = :state, source_repository_id = :source, head_sha = :sha,
                observed_at = statement_timestamp()
            where authority_repository_id = :authority and observation_version = :expected and
            """ + TARGET_KEY;

    private final EntityManager entityManager;
    private final JpaConnectionLookup connections;
    private final SourceTargetHeadRepository heads;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public WriteResult writeObservation(SourceTargetKey target, String authorityId, String expectedConfigRevision,
                                        long expectedVersion, Observation observation) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Phiên bản quan sát phải từ 0 trở lên.");
        }
        Objects.requireNonNull(observation);
        Outcome access = lockAndCheckTarget(target, authorityId, expectedConfigRevision);
        if (access != Outcome.ACCEPTED) {
            return rejected(access);
        }

        Query mutation = bindTarget(entityManager.createNativeQuery(expectedVersion == 0 ? INSERT_HEAD : UPDATE_HEAD), target)
                .setParameter("authority", authorityId)
                .setParameter("state", observation.state.name())
                .setParameter("source", observation.sourceRepositoryId)
                .setParameter("sha", observation.sha);
        if (expectedVersion != 0) {
            mutation.setParameter("expected", expectedVersion);
        }
        if (mutation.executeUpdate() != 1) {
            return rejected(Outcome.OBSERVATION_CONFLICT);
        }

        SourceTargetHeadEntity head = heads.findByTargetAndAuthorityRepositoryId(target, authorityId)
                .orElseThrow(JpaSourceObservationStore::unavailable);
        return new WriteResult(Outcome.ACCEPTED, head);
    }

    private Outcome lockAndCheckTarget(SourceTargetKey target, String authorityId, String expectedConfigRevision) {
        Objects.requireNonNull(authorityId);
        Objects.requireNonNull(expectedConfigRevision);
        connections.bindTenant(target.getTenantId());
        List<?> initial = bindConnection(entityManager.createNativeQuery(FIND_GITHUB_ID), target).getResultList();
        if (initial.isEmpty()) {
            return Outcome.UNKNOWN_CONNECTION;
        }
        UUID githubId = (UUID) initial.getFirst();

        // Giữ thứ tự GitHub → kết nối kho. Đọc lại liên kết sau khi khóa hàng kho.
        boolean githubLocked = !entityManager.createNativeQuery("""
                select github_connection_id from repository.github_connections
                where tenant_id = :tenant and github_connection_id = :github for no key update
                """).setParameter("tenant", target.getTenantId()).setParameter("github", githubId).getResultList().isEmpty();
        List<?> locked = bindConnection(entityManager.createNativeQuery(FIND_GITHUB_ID + " for no key update"), target)
                .getResultList();
        if (locked.isEmpty()) {
            return Outcome.UNKNOWN_CONNECTION;
        }
        if (!githubLocked || !githubId.equals(locked.getFirst())) {
            return Outcome.CONFIG_CHANGED;
        }

        var current = connections.findForWrite(target).orElseThrow(JpaSourceObservationStore::unavailable);
        if (!current.getConnection().active()) {
            return Outcome.CONNECTION_INACTIVE;
        }
        String revision = current.getConnection().configRevision();
        if (revision == null) {
            throw unavailable();
        }
        if (!expectedConfigRevision.equals(revision)) {
            return Outcome.CONFIG_CHANGED;
        }
        if (!current.isAllowed()) {
            return Outcome.UNKNOWN_REF;
        }
        return authorityId.equals(current.getExternalRepoId()) ? Outcome.ACCEPTED : Outcome.CONFIG_CHANGED;
    }

    private static Query bindConnection(Query query, SourceTargetKey target) {
        return query.setParameter("tenant", target.getTenantId()).setParameter("workspace", target.getWorkspaceId())
                .setParameter("connection", target.getRepositoryConnectionId());
    }

    private static Query bindTarget(Query query, SourceTargetKey target) {
        return bindConnection(query, target).setParameter("kind", target.getTargetKind().name())
                .setParameter("ref", target.getTargetRef());
    }

    private static WriteResult rejected(Outcome outcome) {
        return new WriteResult(outcome, null);
    }

    private static SourceUnavailableException unavailable() {
        return new SourceUnavailableException("Không xác minh được dữ liệu đầu nguồn đã lưu.");
    }

    public enum Outcome {
        ACCEPTED, UNKNOWN_CONNECTION, CONNECTION_INACTIVE, CONFIG_CHANGED, UNKNOWN_REF, OBSERVATION_CONFLICT
    }

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class WriteResult {
        private final Outcome outcome;
        private final SourceTargetHeadEntity head;
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Observation {
        private final SourceTargetHeadEntity.HeadState state;
        private final String sourceRepositoryId;
        private final String sha;
        public static Observation present(String sourceRepositoryId, String sha) {
            return new Observation(SourceTargetHeadEntity.HeadState.PRESENT, Objects.requireNonNull(sourceRepositoryId),
                    Objects.requireNonNull(sha));
        }

        public static Observation absent() {
            return new Observation(SourceTargetHeadEntity.HeadState.ABSENT, null, null);
        }
    }
}
