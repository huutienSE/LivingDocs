# LIV-136 — Tổng kết triển khai Register (API-001)

## Tổng quan

LIV-136 triển khai `POST /api/v1/auth/register` để tạo danh tính bằng email và mật khẩu trong PostgreSQL. Đây là đăng ký local credential: request thành công tạo một `Identity` có UUID ổn định, `status = ACTIVE` và mật khẩu đã mã hóa. Register không cấp JWT, tenant, workspace hoặc role. Login, OAuth và các quyền truy cập tiếp tục thuộc các task riêng. Contract API-001 hiện được đối chiếu với HTTP thực và Springdoc; các API S01 khác chưa vì thế trở thành implemented.

## Thành phần đã triển khai

| Thành phần | Trách nhiệm hiện tại |
| --- | --- |
| `AuthController` | Nhận JSON, dùng `@Valid`, gọi `RegistrationService` và trả HTTP 201. |
| `RegisterRequest`, `RegisterResponse`, `SuccessResponse<T>` | Ràng buộc input; chỉ đưa `identityId` vào `data` của success body. |
| `RegistrationService` / `RegistrationServiceImpl` | Interface cho Controller; implementation đặt transaction, trim email, mã hóa mật khẩu và lưu Identity. |
| `PasswordConfig`, `EmailNormalization` | Cấp `PasswordEncoder` BCrypt; bỏ khoảng trắng ASCII ở hai đầu email. |
| `Identity`, `IdentityRepository` | Mapping JPA và `saveAndFlush`; repository có truy vấn email chuẩn hóa cho các use case cần đọc. |
| Flyway V1/V2, PostgreSQL | Flyway quản lý schema; V1 có unique index `uq_identities_email` trên `LOWER(BTRIM(email))`. Hibernate chỉ validate schema. |
| `GlobalExceptionHandler`, tests | Chuyển lỗi sang Problem Details; unit/validation tests và integration tests dùng PostgreSQL Testcontainers. |

Mật khẩu bắt buộc, dài 12–64 theo `@Size` và tối đa 72 UTF-8 bytes; BCrypt nhận plaintext một lần rồi chỉ hash được lưu. `RegistrationServiceImpl` dùng `@Transactional` và `saveAndFlush` để bắt lỗi unique ngay trong method: chỉ SQLSTATE `23505` với constraint `uq_identities_email` được chuyển thành `IdentityAlreadyExistsException`. Lỗi database khác đi theo nhánh 500; transaction thất bại rollback. Email được trim khoảng trắng ASCII trước validation ở DTO và trước lưu ở service; so sánh không phân biệt hoa thường dựa vào unique index PostgreSQL.

## API contract

Thành công: **HTTP 201**, `application/json`, `code = IDENTITY_REGISTERED`, `message = Registration completed`, `data.identityId` là UUID. Không trả email, mật khẩu hoặc password hash. YAML tĩnh gọi schema này là `RegisterApiResponse`; Java dùng `SuccessResponse<RegisterResponse>`, còn Springdoc đặt tên component theo kiểu generic. Các cấu trúc JSON tương đương.

Lỗi **400** (`INVALID_REQUEST`), **409** (`IDENTITY_ALREADY_EXISTS`), **415** (`UNSUPPORTED_MEDIA_TYPE`) và **500** (`INTERNAL_ERROR`) dùng `application/problem+json` theo RFC 9457. Body có `type`, `title`, `status`, `detail`, `instance`, `code`, `correlationId`; `instance` và correlation ID được sinh theo request. Thông điệp 409 không đưa email vào response; 500 không lộ chi tiết exception.

## Bằng chứng kiểm thử

Lần chạy local ngày 14/11/2026: Maven `clean verify` với **Java 21** PASS **13 tests**, gồm **7 integration tests** trên PostgreSQL Testcontainers; Newman PASS **16 requests / 83 assertions**. OpenAPI 3.0.3 và gateway/OpenAPI validator PASS; YAML phục vụ ở port 8090 trùng file local, còn schema runtime port 8081 khớp cấu trúc API-001. HTTP 201/400/409/415 đã được quan sát; 500 được kiểm tra trong integration test bằng lỗi database có kiểm soát, không thử gây lỗi trên S01 đang chạy. Đây là bằng chứng **local**; GitHub Actions CI cho commit cuối **chưa xác nhận**. Xem [sequence và hướng dẫn kiểm thử](LIV-136-registration-sequence-and-testing.md).

## Giới hạn và follow-ups

Chưa chốt benchmark BCrypt/cost và password policy dài hạn; cần kiểm chứng thêm Unicode/collation của email, chống dò tài khoản và rate limiting. Tiếp tục theo dõi transaction boundary quanh BCrypt, regression cho tên unique constraint và method-validation proxy. Login, JWT/JWKS, refresh và OAuth chưa thuộc Register; không coi các nhánh đó đã triển khai.
