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
@Table(schema = "identity", name = "platform_role_assignments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformRoleAssignment {
    @Id @Column(name = "assignment_id") private UUID assignmentId;
    @Column(name = "identity_id") private UUID identityId;
    @Column(name = "role_code") private String roleCode;
    @Column(name = "status") private String status;
    @Column(name = "assigned_at") private Instant assignedAt;
    @Column(name = "ended_at") private Instant endedAt;
    @Version @Column(name = "row_version") private Long rowVersion;
}
