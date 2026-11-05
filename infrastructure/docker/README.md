# LivingDocs — PostgreSQL Local Setup

Hướng dẫn thiết lập PostgreSQL 17 và tạo database roles/schemas cho S01, S02, S04. Chỉ thực hiện phần tạo roles khi khởi tạo database lần đầu.

## 1. Yêu cầu

- Docker Desktop hoặc Docker Engine + Docker Compose.
- Clone repository LivingDocs.
- Database `livingdocs` được khai báo trong `compose.yaml`.

### macOS

Từ thư mục gốc repository:

```bash
cd infrastructure/docker
cp .env.example .env
```

### Windows PowerShell

```powershell
cd infrastructure/docker
Copy-Item .env.example .env
```

Mở `.env`, điền `POSTGRES_PASSWORD` cho PostgreSQL local. Nếu đã có database, giữ nguyên mật khẩu hiện tại.

## 2. Khởi động PostgreSQL

### macOS

```bash
docker compose config --quiet
docker compose up -d postgres
docker compose ps
```

### Windows PowerShell

```powershell
docker compose config --quiet
docker compose up -d postgres
docker compose ps
```

Mong đợi container `postgres` ở trạng thái `healthy`.

**Không chạy `docker compose down -v`** vì lệnh này xóa database volume.

## 3. Tạo database roles và schemas lần đầu

LivingDocs dùng hai tài khoản cho mỗi service:

| Service | App role | Flyway role | Schema |
|---|---|---|---|
| S01 | `ld_s01_app` | `ld_s01_migrator` | `identity` |
| S02 | `ld_s02_app` | `ld_s02_migrator` | `repository` |
| S04 | `ld_s04_app` | `ld_s04_migrator` | `document` |

- **App role:** truy cập bảng nghiệp vụ theo quyền được migration cấp.
- **Migrator role:** sở hữu schema và chạy Flyway migrations.
- Không dùng tài khoản `postgres` để chạy ứng dụng.

### Mở PostgreSQL terminal

**macOS:**

```bash
docker compose exec postgres psql -U postgres -d livingdocs
```

**Windows PowerShell:**

```powershell
docker compose exec postgres psql -U postgres -d livingdocs
```

### Tạo roles và schemas

Dán SQL sau vào terminal `psql`:

```sql
-- Tạo tài khoản cho 3 service
CREATE ROLE ld_s01_app LOGIN;
CREATE ROLE ld_s01_migrator LOGIN;

CREATE ROLE ld_s02_app LOGIN;
CREATE ROLE ld_s02_migrator LOGIN;

CREATE ROLE ld_s04_app LOGIN;
CREATE ROLE ld_s04_migrator LOGIN;

-- Tạo schema do migrator sở hữu
CREATE SCHEMA identity
    AUTHORIZATION ld_s01_migrator;

CREATE SCHEMA repository
    AUTHORIZATION ld_s02_migrator;

CREATE SCHEMA document
    AUTHORIZATION ld_s04_migrator;

-- Cho phép kết nối database
GRANT CONNECT ON DATABASE livingdocs TO
    ld_s01_app, ld_s01_migrator,
    ld_s02_app, ld_s02_migrator,
    ld_s04_app, ld_s04_migrator;

-- Cho phép app role truy cập schema tương ứng
GRANT USAGE ON SCHEMA identity TO ld_s01_app;
GRANT USAGE ON SCHEMA repository TO ld_s02_app;
GRANT USAGE ON SCHEMA document TO ld_s04_app;
```

**Chỉ chạy SQL này một lần cho database mới.** Nếu roles/schema đã tồn tại, bỏ qua; không xóa để tạo lại.

### Đặt mật khẩu cho từng tài khoản

Trong cùng terminal `psql`, chạy:

```sql
\password ld_s01_app
\password ld_s01_migrator

\password ld_s02_app
\password ld_s02_migrator

\password ld_s04_app
\password ld_s04_migrator
```

PostgreSQL sẽ yêu cầu nhập và xác nhận mật khẩu cho từng role.

Ghi nhớ mật khẩu để cấu hình `.env` của từng service. Không ghi mật khẩu thật vào README, Git hoặc lệnh SQL được commit.

Thoát `psql`:

```text
\q
```

## 4. Kiểm tra schemas và chủ sở hữu

### macOS

```bash
docker compose exec postgres psql -U postgres -d livingdocs -c "SELECT n.nspname AS schema, r.rolname AS owner FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner WHERE n.nspname IN ('identity','repository','document') ORDER BY n.nspname;"
```

### Windows PowerShell

```powershell
docker compose exec postgres psql -U postgres -d livingdocs -c "SELECT n.nspname AS schema, r.rolname AS owner FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner WHERE n.nspname IN ('identity','repository','document') ORDER BY n.nspname;"
```

Kết quả mong đợi:

| Schema | Owner |
|---|---|
| `identity` | `ld_s01_migrator` |
| `repository` | `ld_s02_migrator` |
| `document` | `ld_s04_migrator` |

## 5. Flyway migrations

Không chạy SQL migration thủ công.

Mỗi service kích hoạt `SPRING_PROFILES_ACTIVE=db` để tự chạy Flyway khi khởi động.

- S01: `V1`, `V2`
- S02: `V1`
- S04: `V1`

Flyway tự ghi lịch sử vào bảng `flyway_schema_history` của schema tương ứng. Không chỉnh sửa migration đã áp dụng.

Xem [hướng dẫn chạy backend](../../backend/README.md).

## 6. Lệnh quản lý Docker

Các lệnh giống nhau trên macOS và Windows PowerShell, chạy từ `infrastructure/docker`:

```bash
docker compose ps
docker compose logs --tail=100 postgres
docker compose stop postgres
docker compose up -d postgres
```

Dữ liệu PostgreSQL được giữ trong named volume `postgres_data`. Không xóa volume khi chỉ muốn dừng/khởi động lại database.
