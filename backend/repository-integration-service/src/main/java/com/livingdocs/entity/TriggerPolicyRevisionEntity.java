package com.livingdocs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/** Phiên bản chính sách kích hoạt; chính sách cấp nền tảng không có tenantId và scopeRef. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "trigger_policy_revisions", schema = "repository")
public class TriggerPolicyRevisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "policy_revision_id", nullable = false)
    private UUID policyRevisionId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private PolicyScopeType scopeType;

    @Column(name = "scope_ref")
    private UUID scopeRef;

    @Column(name = "revision", nullable = false)
    private Integer revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rules;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public TriggerPolicyRevisionEntity(UUID tenantId, PolicyScopeType scopeType, UUID scopeRef,
                                       Integer revision, Map<String, Object> rules) {
        this.tenantId = tenantId;
        this.scopeType = Objects.requireNonNull(scopeType, "scopeType");
        this.scopeRef = scopeRef;
        this.revision = Objects.requireNonNull(revision, "revision");
        this.rules = Objects.requireNonNull(rules, "rules");
    }
}
