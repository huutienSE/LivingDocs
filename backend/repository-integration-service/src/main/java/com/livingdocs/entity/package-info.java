/**
 * Ánh xạ 9 bảng nghiệp vụ thuộc schema repository trong migration V1 của S02.
 *
 * <p>Flyway quản lý cấu trúc bảng, khóa ngoại ghép tenant, các ràng buộc CHECK,
 * chỉ mục có điều kiện và chính sách cách ly dữ liệu theo hàng. Các liên kết được
 * giữ dưới dạng UUID; không truy vấn trực tiếp schema của S01 hoặc S04.
 *
 * <p>UUID được JPA sinh khi lưu mới. Thời điểm tạo/quan sát mặc định do cơ sở dữ
 * liệu sinh; updatedAt được cập nhật bằng thời gian cơ sở dữ liệu khi Hibernate
 * ghi thay đổi. Không ánh xạ revision thành khóa phiên bản tự tăng của Hibernate:
 * đây là số phiên bản nghiệp vụ của chính sách.
 *
 * <p>Trước khi truy xuất dữ liệu, tầng điều phối phải thiết lập app.tenant_id trong
 * cùng giao dịch với truy vấn, bằng phạm vi đã được xác thực. Các lớp này không
 * tự thực hiện xác thực hoặc thiết lập tenant. Không trả Entity trực tiếp qua API,
 * nhất là GithubConnectionEntity có chứa tham chiếu bí mật credentialRef.
 */
package com.livingdocs.entity;
