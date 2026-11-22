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
@Table(schema = "identity", name = "role_catalog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoleCatalog {

    // role_code: TECHNICAL_LEAD, MANAGER, DEVELOPER,  TENANT_OWNER, STAFF
    @Id
    @Column(name = "role_code", nullable = false)
    private String roleCode;

    // scope_type thể hiện là ap[s dụng ở phạm vi TENANT HAY WORKSPACE,...
    @Column(name = "scope_type")
    private String scopeType;
}
