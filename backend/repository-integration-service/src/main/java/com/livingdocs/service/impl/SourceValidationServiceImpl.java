package com.livingdocs.service.impl;

import com.livingdocs.config.SourceValidationProperties;
import com.livingdocs.entity.SourceTargetHeadEntity;
import com.livingdocs.entity.SourceTargetHeadEntity.HeadState;
import com.livingdocs.entity.SourceTargetKey;
import com.livingdocs.exception.SourceUnavailableException;
import com.livingdocs.model.SourceConnection;
import com.livingdocs.model.SourceScope;
import com.livingdocs.model.SourceValidationRequest;
import com.livingdocs.model.SourceValidationResult;
import com.livingdocs.model.TargetKind;
import com.livingdocs.model.ValidationReason;
import com.livingdocs.model.ValidationStatus;
import com.livingdocs.repository.JpaSourceObservationStore;
import com.livingdocs.repository.JpaSourceObservationStore.Observation;
import com.livingdocs.service.GitHubSourceLookup;
import com.livingdocs.service.SourceValidationService;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Xác minh bằng quan sát GitHub mới đã được PostgreSQL chấp nhận. */
@Service
@EnableConfigurationProperties(SourceValidationProperties.class)
public class SourceValidationServiceImpl implements SourceValidationService {
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final String ZERO_SHA = "0".repeat(40);
    private final GitHubSourceLookup github;
    private final JpaSourceObservationStore observations;
    private final Clock clock;
    private final Duration validity;

    @Autowired
    public SourceValidationServiceImpl(GitHubSourceLookup github, JpaSourceObservationStore observations,
                                       SourceValidationProperties properties) {
        this(github, observations, Clock.systemUTC(), properties.getValidity());
    }

    public SourceValidationServiceImpl(GitHubSourceLookup github, JpaSourceObservationStore observations,
                                       Clock clock, Duration validity) {
        this.github = Objects.requireNonNull(github);
        this.observations = Objects.requireNonNull(observations);
        this.clock = Objects.requireNonNull(clock);
        if (validity == null || validity.isNegative() || validity.isZero()) {
            throw new IllegalArgumentException("Thời hạn hiệu lực phải dương và được cấu hình rõ ràng");
        }
        this.validity = validity;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SourceValidationResult validate(SourceScope scope, SourceValidationRequest request) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(request);
        UUID validationId = UUID.randomUUID();
        Instant checkedAt = clock.instant();
        Instant expiresAt = checkedAt.plus(validity);
        if (!validRequest(request)) {
            return result(validationId, scope, request, ValidationReason.INVALID_REQUEST, null, checkedAt, expiresAt);
        }
        try {
            var read = github.readCurrent(scope, request.repositoryConnectionId(), request.targetKind(),
                    request.targetRef(), request.expectedConfigRevision());
            boolean absent = read.getOutcome() == GitHubSourceLookup.Outcome.REF_ABSENT;
            if (!absent && read.getOutcome() != GitHubSourceLookup.Outcome.OBSERVED) {
                return result(validationId, scope, request, readFailure(read.getOutcome()), null, checkedAt, expiresAt);
            }
            SourceConnection connection = read.getConnection();
            if (connection == null || !scope.equals(connection.scope())
                    || !request.repositoryConnectionId().equals(connection.repositoryConnectionId())) {
                return result(validationId, scope, request, ValidationReason.SOURCE_BINDING_MISMATCH, null, checkedAt, expiresAt);
            }
            if (!request.expectedConfigRevision().equals(connection.configRevision())) {
                return result(validationId, scope, request, ValidationReason.CONFIG_CHANGED, null, checkedAt, expiresAt);
            }
            String authorityId = read.getAuthorityRepositoryId();
            var commit = read.getCommit();
            if (!absent) {
                if (commit == null || !validSha(commit.getSha()) || commit.getSourceRepositoryId() <= 0) {
                    return result(validationId, scope, request, ValidationReason.INVALID_SOURCE, null, checkedAt, expiresAt);
                }
                if (!Long.toString(commit.getQueriedRepositoryId()).equals(authorityId)
                        || (request.targetKind() == TargetKind.BRANCH
                        && commit.getQueriedRepositoryId() != commit.getSourceRepositoryId())) {
                    return result(validationId, scope, request, ValidationReason.SOURCE_BINDING_MISMATCH, null, checkedAt, expiresAt);
                }
            }
            if (authorityId == null || authorityId.isBlank()) {
                return result(validationId, scope, request, ValidationReason.INVALID_SOURCE, null, checkedAt, expiresAt);
            }
            var target = new SourceTargetKey(scope, request.repositoryConnectionId(), request.targetKind(), request.targetRef());
            Observation observation = absent ? Observation.absent()
                    : Observation.present(Long.toString(commit.getSourceRepositoryId()), commit.getSha());
            var written = observations.writeObservation(target, authorityId, request.expectedConfigRevision(),
                    read.getObservationVersion(), observation);
            if (written.getOutcome() != JpaSourceObservationStore.Outcome.ACCEPTED) {
                return result(validationId, scope, request, writeFailure(written.getOutcome()), null, checkedAt, expiresAt);
            }
            SourceTargetHeadEntity head = written.getHead();
            if (head.getHeadState() == HeadState.ABSENT) {
                return result(validationId, scope, request, ValidationReason.UNKNOWN_REF, null, checkedAt, expiresAt);
            }
            if (!clock.instant().isBefore(expiresAt)) {
                return result(validationId, scope, request, ValidationReason.VALIDATION_EXPIRED, null, checkedAt, expiresAt);
            }
            ValidationReason reason = request.expectedHeadSha().equalsIgnoreCase(head.getHeadSha())
                    ? ValidationReason.CURRENT : ValidationReason.STALE_SHA;
            return result(validationId, scope, request, reason, head, checkedAt, expiresAt);
        } catch (SourceUnavailableException | DataAccessException | PersistenceException | TransactionException ignored) {
            // Không chuyển lỗi cơ sở dữ liệu, thông tin xác thực hay lỗi GitHub ra bằng chứng.
            return result(validationId, scope, request, ValidationReason.SOURCE_UNAVAILABLE, null, checkedAt, expiresAt);
        }
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

    private static boolean validRequest(SourceValidationRequest request) {
        return request.repositoryConnectionId() != null && request.targetKind() != null
                && request.targetKind().isValidRef(request.targetRef())
                && hasText(request.expectedConfigRevision()) && request.expectedConfigRevision().length() <= 255
                && validSha(request.expectedHeadSha());
    }

    private static boolean validSha(String sha) {
        return sha != null && SHA.matcher(sha).matches() && !ZERO_SHA.equals(sha);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static SourceValidationResult result(UUID id, SourceScope scope, SourceValidationRequest request,
                                                  ValidationReason reason, SourceTargetHeadEntity head,
                                                  Instant checkedAt, Instant expiresAt) {
        return new SourceValidationResult(id, scope.tenantId(), scope.workspaceId(), request.repositoryConnectionId(),
                request.targetKind(), request.targetRef(), request.expectedHeadSha(), request.expectedConfigRevision(),
                reason == ValidationReason.CURRENT ? ValidationStatus.PASS : ValidationStatus.FAIL, reason,
                head == null ? null : head.getHeadSha(), head == null ? null : Long.toString(head.getSourceRevision()),
                checkedAt, expiresAt);
    }
}
