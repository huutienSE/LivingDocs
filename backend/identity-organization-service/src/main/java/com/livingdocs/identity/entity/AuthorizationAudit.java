package com.livingdocs.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "identity", name = "authorization_audit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthorizationAudit {

    @Id
    @Column(name = "audit_id", nullable = false)
    private UUID auditId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "authz_revision")
    private Long authzRevision;

    @Column(name = "actor_identity_id")
    private UUID actorIdentityId;

    @Column(name = "action")
    private String action;

    @Column(name = "target_table")
    private String targetTable;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "occurred_at")
    private Instant occurredAt;
}
