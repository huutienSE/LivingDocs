package com.livingdocs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Phiên bản mã nguồn đã quan sát; không tự chứng minh mã nguồn vẫn hiện hành. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "source_revisions", schema = "repository",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_source_revisions_tenant_id", columnNames = {"tenant_id", "source_revision_id"}),
            @UniqueConstraint(name = "uq_source_revision_identity", columnNames = {"tenant_id", "repository_connection_id", "target_ref", "commit_sha"})
        })
public class SourceRevisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "source_revision_id", nullable = false)
    private UUID sourceRevisionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "repository_connection_id", nullable = false)
    private UUID repositoryConnectionId;

    @Column(name = "target_ref", nullable = false, length = 512)
    private String targetRef;

    @Column(name = "commit_sha", nullable = false, length = 128)
    private String commitSha;

    @Generated(event = EventType.INSERT)
    @Column(name = "observed_at", nullable = false, insertable = false, updatable = false)
    private Instant observedAt;

    public SourceRevisionEntity(UUID tenantId, UUID repositoryConnectionId, String targetRef, String commitSha) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.repositoryConnectionId = Objects.requireNonNull(repositoryConnectionId, "repositoryConnectionId");
        this.targetRef = Objects.requireNonNull(targetRef, "targetRef");
        this.commitSha = Objects.requireNonNull(commitSha, "commitSha");
    }
}
