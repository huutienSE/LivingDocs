
# LivingDocs — PostgreSQL Local Environment

## Yêu cầu

- Docker Desktop hoặc Docker Engine
- Docker Compose
- PostgreSQL 17 chạy trong container

## Cấu hình

Trong thư mục `infrastructure/docker`:

1. Copy `.env.example` thành `.env`.
2. Điền mật khẩu PostgreSQL local.
3. Không commit `.env`.

Nếu PostgreSQL đã có volume dữ liệu, phải sử dụng
mật khẩu PostgreSQL hiện tại. Đổi mật khẩu trong `.env`
không tự thay đổi password đã lưu trong database.

## Khởi động

Chạy từ thư mục `infrastructure/docker`:

```powershell
docker compose config
docker compose up -d postgres
docker compose ps
```

## Dừng

```powershell
docker compose stop postgres
```

Không sử dụng `docker compose down -v` để dừng thông thường,
vì lệnh đó xóa named volumes và có nguy cơ mất dữ liệu local.

## Database và schema

Database: `livingdocs`

Các schema do từng domain service sở hữu:

- `identity` — S01
- `repository` — S02
- `document` — S04

Mỗi service sử dụng hai tài khoản riêng:

- Application role — truy cập dữ liệu theo quyền được cấp.
- Flyway migrator role — thực thi database migrations.

Database roles và schema phải được khởi tạo bởi bước
database bootstrap đã thống nhất; tạo `.env` không tự tạo role.

## Chạy backend

S01/S02/S04 chạy độc lập trong IntelliJ ở local.

Mỗi module có:

- `.env.example`: mẫu cấu hình đưa lên Git.
- `.env`: cấu hình local chứa mật khẩu.
- `application.yaml`: nạp `.env`.
- `application-db.yaml`: datasource và Flyway.

Spring profile `db` kích hoạt cấu hình PostgreSQL/Flyway.
```
