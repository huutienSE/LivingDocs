package com.livingdocs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Quan sát đã lưu. Chỉ cập nhật qua thao tác có điều kiện phiên bản trong PostgreSQL. */
// bảng thuộc V3
@Entity
@Table(name = "source_target_heads", schema = "repository")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceTargetHeadEntity {
    public enum HeadState { PRESENT, ABSENT }

    @EmbeddedId
    private SourceTargetKey target;

    @Column(name = "authority_repository_id", nullable = false, length = 19)
    private String authorityRepositoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "head_state", nullable = false, length = 7)
    private HeadState headState;

    @Column(name = "source_repository_id", length = 19)
    private String sourceRepositoryId;

    @Column(name = "head_sha", length = 40)
    private String headSha;

    @Column(name = "observation_version", nullable = false)
    private long observationVersion;

    @Column(name = "source_revision", nullable = false)
    private long sourceRevision;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
}
