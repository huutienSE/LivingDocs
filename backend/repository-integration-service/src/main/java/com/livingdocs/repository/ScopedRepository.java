package com.livingdocs.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Giao dịch bên gọi phải thiết lập app.tenant_id trước khi dùng repository.
 * Luồng nghiệp vụ đọc qua các truy vấn có phạm vi được khai báo trong từng repository.
 */
@NoRepositoryBean
@Transactional(propagation = Propagation.MANDATORY)
public interface ScopedRepository<T> extends JpaRepository<T, UUID> {
    @Override
    <S extends T> S save(S entity);
}
