package com.livingdocs.repository.infrastructure.persistence.repository;

import java.util.UUID;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chỉ cung cấp lưu dữ liệu; từng repository khai báo truy vấn đọc có phạm vi rõ ràng.
 * Giao dịch bên gọi phải thiết lập app.tenant_id trước khi dùng repository.
 */
@NoRepositoryBean
@Transactional(propagation = Propagation.MANDATORY)
public interface ScopedRepository<T> extends Repository<T, UUID> {
    <S extends T> S save(S entity);
}
