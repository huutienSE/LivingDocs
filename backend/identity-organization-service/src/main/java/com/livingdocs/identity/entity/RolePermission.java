package com.livingdocs.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "identity", name = "role_permissions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RolePermission {
    @EmbeddedId // composite primary key
    private Key id;

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Column(name = "role_code")
        private String roleCode;
        @Column(name = "permission_code") // TENANT_ADMIN, WORKSPACE_VIEW, DOCUMENT_READ,..
        private String permissionCode;

        @Override public boolean equals(Object other) {
            return other instanceof Key key // kiem tra other có phải Key không, đúng thì key sẽ dùng tiếp key để đi thực hiện tiếp
                    && Objects.equals(roleCode, key.roleCode) // ss obj hiện tại với key (other được gán trước đó)
                    && Objects.equals(permissionCode, key.permissionCode);
        }
        @Override public int hashCode() { return Objects.hash(roleCode, permissionCode); }
    }
}
