package com.livingdocs.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "identity", name = "operator_principals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperatorPrincipal {
    @Id @Column(name = "db_login_name") private String dbLoginName;
    @Column(name = "identity_id") private UUID identityId;
    @Column(name = "status") private String status;
    @Column(name = "enrolled_at") private Instant enrolledAt;
    @Column(name = "revoked_at") private Instant revokedAt;
}
