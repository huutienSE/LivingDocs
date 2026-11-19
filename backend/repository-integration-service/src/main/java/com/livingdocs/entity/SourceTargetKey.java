package com.livingdocs.entity;

import com.livingdocs.model.SourceScope;
import com.livingdocs.model.TargetKind;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Khóa đầy đủ của đích nguồn trong phạm vi một kết nối. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceTargetKey implements Serializable {
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_connection_id", nullable = false)
    private UUID repositoryConnectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, length = 16)
    private TargetKind targetKind;

    @Column(name = "target_ref", nullable = false, length = 512)
    private String targetRef;

    public SourceTargetKey(SourceScope scope, UUID repositoryConnectionId, TargetKind targetKind, String targetRef) {
        this.tenantId = scope.tenantId();
        this.workspaceId = scope.workspaceId();
        this.repositoryConnectionId = Objects.requireNonNull(repositoryConnectionId);
        this.targetKind = Objects.requireNonNull(targetKind);
        if (!targetKind.isValidRef(targetRef)) {
            throw new IllegalArgumentException("Tham chiếu đích nguồn không hợp lệ.");
        }
        this.targetRef = targetRef;
    }
}
