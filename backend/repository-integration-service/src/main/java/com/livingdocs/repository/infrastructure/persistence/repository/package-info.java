/**
 * Truy xuất 9 bảng S02 qua Spring Data JPA. Các truy vấn đọc yêu cầu tenantId và
 * phạm vi liên quan; không cung cấp findAll hoặc findById không có phạm vi.
 *
 * <p>Mỗi lời gọi phải nằm trong giao dịch của tầng điều phối. Bên gọi thiết lập
 * app.tenant_id bằng set_config với is_local=true trên cùng kết nối/giao dịch,
 * sử dụng phạm vi đã được xác thực. Không đổi tenant trong cùng persistence
 * context vì dữ liệu đã đọc có thể còn trong bộ nhớ đệm của EntityManager.
 *
 * <p>Lưu dữ liệu vẫn chịu chính sách RLS và khóa ngoại ghép tenant của V1.
 * Tham số tenantId trong truy vấn không thay thế xác thực hoặc RLS. Quyền ghi
 * chính sách PLATFORM bị chặn tại cơ sở dữ liệu bằng vai trò ld_s02_app.
 *
 * <p>Tra được source revision hoặc receipt không chứng minh GitHub còn hiện hành;
 * logic xác minh phải kiểm tra SHA/ref/config/freshness tại thời điểm sử dụng.
 */
package com.livingdocs.repository.infrastructure.persistence.repository;
