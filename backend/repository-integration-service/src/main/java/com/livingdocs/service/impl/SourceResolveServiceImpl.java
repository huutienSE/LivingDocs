package com.livingdocs.service.impl;

import com.livingdocs.entity.SourceTargetHeadEntity;
import com.livingdocs.entity.SourceTargetHeadEntity.HeadState;
import com.livingdocs.entity.SourceTargetKey;
import com.livingdocs.exception.SourceUnavailableException;
import com.livingdocs.model.SourceResolveRequest;
import com.livingdocs.model.SourceResolveResult;
import com.livingdocs.model.SourceScope;
import com.livingdocs.model.TargetKind;
import com.livingdocs.model.ValidationReason;
import com.livingdocs.repository.JpaSourceObservationStore;
import com.livingdocs.repository.JpaSourceObservationStore.Observation;
import com.livingdocs.service.GitHubSourceLookup;
import com.livingdocs.service.SourceResolveService;
import jakarta.persistence.PersistenceException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Tra SHA từ GitHub và chỉ trả kết quả sau khi PostgreSQL chấp nhận quan sát. */
@Service
@RequiredArgsConstructor
public class SourceResolveServiceImpl implements SourceResolveService {
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final String ZERO_SHA = "0".repeat(40);
    private final GitHubSourceLookup github;
    private final JpaSourceObservationStore observations;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SourceResolveResult resolve(SourceScope scope, SourceResolveRequest request) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(request);
        if (request.repositoryConnectionId() == null || request.targetKind() == null
                || !request.targetKind().isValidRef(request.targetRef())
                || (request.explicitSha() != null && !validSha(request.explicitSha()))) {
            return result(scope, request, ValidationReason.INVALID_REQUEST, null, null);
        }
        try {
            // API-069 tra cấu hình hiện hành từ S02 cùng lúc đọc phiên bản quan sát.
            var read = github.readCurrent(scope, request.repositoryConnectionId(), request.targetKind(), request.targetRef(), null);
            boolean absent = read.getOutcome() == GitHubSourceLookup.Outcome.REF_ABSENT;
            if (!absent && read.getOutcome() != GitHubSourceLookup.Outcome.OBSERVED) {
                return result(scope, request, readFailure(read.getOutcome()), null, null);
            }
            var connection = read.getConnection();
            if (connection == null || !scope.equals(connection.scope())
                    || !request.repositoryConnectionId().equals(connection.repositoryConnectionId())) {
                return result(scope, request, ValidationReason.SOURCE_BINDING_MISMATCH, null, null);
            }
            if (!connection.active()) {
                return result(scope, request, ValidationReason.CONNECTION_INACTIVE, null, null);
            }
            String configRevision = connection.configRevision();
            if (configRevision == null || configRevision.isBlank() || configRevision.length() > 255) {
                return result(scope, request, ValidationReason.SOURCE_UNAVAILABLE, null, null);
            }
            String authorityId = read.getAuthorityRepositoryId();
            var commit = read.getCommit();
            if (!absent) {
                if (commit == null || !validSha(commit.getSha()) || commit.getSourceRepositoryId() <= 0) {
                    return result(scope, request, ValidationReason.INVALID_SOURCE, null, null);
                }
                if (!Long.toString(commit.getQueriedRepositoryId()).equals(authorityId)
                        || (request.targetKind() == TargetKind.BRANCH
                        && commit.getQueriedRepositoryId() != commit.getSourceRepositoryId())) {
                    return result(scope, request, ValidationReason.SOURCE_BINDING_MISMATCH, null, null);
                }
            }
            if (authorityId == null || authorityId.isBlank()) {
                return result(scope, request, ValidationReason.INVALID_SOURCE, null, null);
            }
            var target = new SourceTargetKey(scope, request.repositoryConnectionId(), request.targetKind(), request.targetRef());
            Observation observation = absent ? Observation.absent() : Observation.present(
                    Long.toString(commit.getSourceRepositoryId()), commit.getSha().toLowerCase(Locale.ROOT));
            var written = observations.writeObservation(target, authorityId, configRevision,
                    read.getObservationVersion(), observation);
            if (written.getOutcome() != JpaSourceObservationStore.Outcome.ACCEPTED) {
                return result(scope, request, writeFailure(written.getOutcome()), null, null);
            }
            var head = written.getHead();
            if (head.getHeadState() == HeadState.ABSENT) {
                return result(scope, request, ValidationReason.UNKNOWN_REF, null, null);
            }
            ValidationReason reason = request.explicitSha() == null || request.explicitSha().equalsIgnoreCase(head.getHeadSha())
                    ? ValidationReason.CURRENT : ValidationReason.STALE_SHA;
            return result(scope, request, reason, head, configRevision);
        } catch (SourceUnavailableException | DataAccessException | PersistenceException | TransactionException failure) {
            return result(scope, request, ValidationReason.SOURCE_UNAVAILABLE, null, null);
        }
    }

    private static boolean validSha(String sha) {
        return sha != null && SHA.matcher(sha).matches() && !ZERO_SHA.equals(sha);
    }

    private static ValidationReason readFailure(GitHubSourceLookup.Outcome outcome) {
        return switch (outcome) {
            case INVALID_REQUEST -> ValidationReason.INVALID_REQUEST;
            case UNKNOWN_CONNECTION -> ValidationReason.UNKNOWN_CONNECTION;
            case CONNECTION_INACTIVE -> ValidationReason.CONNECTION_INACTIVE;
            case CONFIG_CHANGED -> ValidationReason.CONFIG_CHANGED;
            case UNKNOWN_REF -> ValidationReason.UNKNOWN_REF;
            case SOURCE_UNAVAILABLE -> ValidationReason.SOURCE_UNAVAILABLE;
            case OBSERVED, REF_ABSENT -> throw new IllegalArgumentException("Quan sát đã xác minh cần được ghi.");
        };
    }

    private static ValidationReason writeFailure(JpaSourceObservationStore.Outcome outcome) {
        return switch (outcome) {
            case UNKNOWN_CONNECTION -> ValidationReason.UNKNOWN_CONNECTION;
            case CONNECTION_INACTIVE -> ValidationReason.CONNECTION_INACTIVE;
            case CONFIG_CHANGED -> ValidationReason.CONFIG_CHANGED;
            case UNKNOWN_REF -> ValidationReason.UNKNOWN_REF;
            case OBSERVATION_CONFLICT -> ValidationReason.SOURCE_OBSERVATION_CONFLICT;
            case ACCEPTED -> throw new IllegalArgumentException("Quan sát đã được ghi.");
        };
    }

    private static SourceResolveResult result(SourceScope scope, SourceResolveRequest request, ValidationReason reason,
                                               SourceTargetHeadEntity head, String configRevision) {
        return new SourceResolveResult(scope, request.repositoryConnectionId(), request.targetKind(), request.targetRef(),
                reason, head == null ? null : head.getHeadSha(), configRevision,
                head == null ? null : Long.toString(head.getSourceRevision()));
    }
}
