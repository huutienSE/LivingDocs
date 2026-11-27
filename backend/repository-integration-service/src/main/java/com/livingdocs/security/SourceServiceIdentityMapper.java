package com.livingdocs.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** Chỉ ánh xạ JWT đã xác minh theo hợp đồng issuer được review. Mặc định chưa có cấp quyền. */
public interface SourceServiceIdentityMapper {
    SourceServiceAuthentication authenticate(Jwt verifiedJwt);
}
