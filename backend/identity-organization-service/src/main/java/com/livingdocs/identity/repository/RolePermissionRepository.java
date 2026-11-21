package com.livingdocs.identity.repository;

import com.livingdocs.identity.entity.RolePermission;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Key> {
    // trong danh sách roles của 1 người đó thì tương ứng với role trong roles đó họ tương những quyền hạn nào
    // sẽ trả vè biết được 1 ngừoi có nhiều roles và cũng sẽ biết tương ứng có quyền gì, nên trả về danh sách
    @Query("select p from RolePermission p where p.id.roleCode in :roles")
    List<RolePermission> findForRoles(@Param("roles") Collection<String> roles);
}
