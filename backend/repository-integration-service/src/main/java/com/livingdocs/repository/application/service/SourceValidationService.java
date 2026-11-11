package com.livingdocs.repository.application.service;

import com.livingdocs.repository.domain.model.*;
import com.livingdocs.repository.domain.port.AuthoritativeHeadLookup;
import com.livingdocs.repository.domain.port.ConnectionLookup;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Điều phối xác minh; các mô hình và giao diện nghiệp vụ nằm trong domain. */
public final class SourceValidationService {
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private final ConnectionLookup connections;
    private final AuthoritativeHeadLookup heads;
    private final Clock clock;
    private final Duration validity;

    public SourceValidationService(ConnectionLookup connections, AuthoritativeHeadLookup heads,
                                   Clock clock, Duration validity) {
        this.connections = Objects.requireNonNull(connections);
        this.heads = Objects.requireNonNull(heads);
        this.clock = Objects.requireNonNull(clock);
        if (validity == null || validity.isNegative() || validity.isZero()) {
            throw new IllegalArgumentException("Thời hạn hiệu lực phải dương và được cấu hình rõ ràng");
        }
        this.validity = validity;
    }

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
            Optional<SourceConnection> found = connections.find(scope, request.repositoryConnectionId());
            if (found.isEmpty()) {
                return result(validationId, scope, request, ValidationReason.UNKNOWN_CONNECTION, null, checkedAt, expiresAt);
            }
            SourceConnection connection = found.get();
            if (!connection.scope().equals(scope) || !connection.repositoryConnectionId().equals(request.repositoryConnectionId())) {
                return result(validationId, scope, request, ValidationReason.UNKNOWN_CONNECTION, null, checkedAt, expiresAt);
            }
            if (!connection.active()) {
                return result(validationId, scope, request, ValidationReason.CONNECTION_INACTIVE, null, checkedAt, expiresAt);
            }
            if (!hasText(connection.configRevision())) {
                return result(validationId, scope, request, ValidationReason.SOURCE_UNAVAILABLE, null, checkedAt, expiresAt);
            }
            if (!request.expectedConfigRevision().equals(connection.configRevision())) {
                return result(validationId, scope, request, ValidationReason.CONFIG_CHANGED, null, checkedAt, expiresAt);
            }
            Optional<SourceHead> observed = heads.readCurrent(connection, request.targetKind(), request.targetRef());
            if (observed.isEmpty()) {
                return result(validationId, scope, request, ValidationReason.UNKNOWN_REF, null, checkedAt, expiresAt);
            }
            SourceHead head = observed.get();
            if (!scope.equals(head.scope()) || !request.repositoryConnectionId().equals(head.repositoryConnectionId())
                    || request.targetKind() != head.targetKind() || !request.targetRef().equals(head.targetRef())) {
                return result(validationId, scope, request, ValidationReason.SOURCE_BINDING_MISMATCH, null, checkedAt, expiresAt);
            }
            if (!request.expectedConfigRevision().equals(head.configRevision())) {
                return result(validationId, scope, request, ValidationReason.CONFIG_CHANGED, null, checkedAt, expiresAt);
            }
            if (head.sha() == null || !SHA.matcher(head.sha()).matches() || !hasText(head.sourceRevision())) {
                return result(validationId, scope, request, ValidationReason.INVALID_SOURCE, null, checkedAt, expiresAt);
            }
            if (!clock.instant().isBefore(expiresAt)) {
                return result(validationId, scope, request, ValidationReason.VALIDATION_EXPIRED, null, checkedAt, expiresAt);
            }
            ValidationReason reason = request.expectedHeadSha().equalsIgnoreCase(head.sha())
                    ? ValidationReason.CURRENT : ValidationReason.STALE_SHA;
            return result(validationId, scope, request, reason, head, checkedAt, expiresAt);
        } catch (RuntimeException ignored) {
            // Không chuyển lỗi cơ sở dữ liệu, thông tin xác thực hay lỗi GitHub ra bằng chứng.
            return result(validationId, scope, request, ValidationReason.SOURCE_UNAVAILABLE, null, checkedAt, expiresAt);
        }
    }

    private static boolean validRequest(SourceValidationRequest request) {
        if (request.repositoryConnectionId() == null || request.targetKind() == null
                || !hasText(request.expectedConfigRevision()) || request.expectedConfigRevision().length() > 255
                || request.expectedHeadSha() == null || !SHA.matcher(request.expectedHeadSha()).matches()
                || request.targetRef() == null || request.targetRef().length() > 512) {
            return false;
        }
        if (request.targetKind() == TargetKind.PR) {
            return request.targetRef().matches("[1-9][0-9]*");
        }
        String ref = request.targetRef();
        if (!ref.startsWith("refs/heads/") || ref.length() == "refs/heads/".length()
                || ref.contains("..") || ref.contains("@{") || ref.endsWith(".") || ref.endsWith("/")) {
            return false;
        }
        for (char c : ref.toCharArray()) {
            if (c <= 32 || c == 127 || "~^:?*[\\".indexOf(c) >= 0) {
                return false;
            }
        }
        for (String component : ref.split("/", -1)) {
            if (component.isEmpty() || component.startsWith(".") || component.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static SourceValidationResult result(UUID id, SourceScope scope, SourceValidationRequest request,
                                                  ValidationReason reason, SourceHead head,
                                                  Instant checkedAt, Instant expiresAt) {
        return new SourceValidationResult(id, scope.tenantId(), scope.workspaceId(), request.repositoryConnectionId(),
                request.targetKind(), request.targetRef(), request.expectedHeadSha(), request.expectedConfigRevision(),
                reason == ValidationReason.CURRENT ? ValidationStatus.PASS : ValidationStatus.FAIL, reason,
                head == null ? null : head.sha(), head == null ? null : head.sourceRevision(), checkedAt, expiresAt);
    }
}
