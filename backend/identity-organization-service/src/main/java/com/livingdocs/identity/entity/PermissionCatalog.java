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
@Table(schema = "identity", name = "permission_catalog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PermissionCatalog {

    @Id
    @Column(name = "permission_code", nullable = false)
    private String permissionCode; // TENANT_ADMIN, WORKSPACE_VIEW, DOCUMENT_READ,..
}
