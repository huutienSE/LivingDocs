# LivingDocs — Backend & Flyway Setup

Hướng dẫn chạy S01/S02/S04 bằng Spring Boot 4, Java 21, PostgreSQL 17 và Flyway.

## 1. Chuẩn bị

Trước tiên hoàn thành [PostgreSQL Local Setup](../infrastructure/docker/README.md), bao gồm tạo database roles và schemas.

Mỗi service sử dụng `.env` riêng. Không nhập lại các biến này trong IntelliJ Run Configuration.

### macOS

Chạy từ thư mục gốc repository:

```bash
cp backend/identity-organization-service/.env.example backend/identity-organization-service/.env

cp backend/repository-integration-service/.env.example backend/repository-integration-service/.env

cp backend/document-governance-service/.env.example backend/document-governance-service/.env
```

### Windows PowerShell

```powershell
Copy-Item backend/identity-organization-service/.env.example backend/identity-organization-service/.env

Copy-Item backend/repository-integration-service/.env.example backend/repository-integration-service/.env

Copy-Item backend/document-governance-service/.env.example backend/document-governance-service/.env
```

Nếu `.env` đã tồn tại, không ghi đè.

## 2. Cấu hình `.env`

Mở `.env` của từng service và điền mật khẩu database đã tạo.

| Biến | S01 | S02 | S04 |
|---|---|---|---|
| `SERVER_PORT` | 8082 | 8083 | 8084 |
| `DB_SCHEMA` | identity | repository | document |
| `DB_USERNAME` | ld_s01_app | ld_s02_app | ld_s04_app |
| `FLYWAY_USERNAME` | ld_s01_migrator | ld_s02_migrator | ld_s04_migrator |

Các biến chung:

```properties
SPRING_PROFILES_ACTIVE=db
DB_URL=jdbc:postgresql://127.0.0.1:5432/livingdocs
```

- `DB_PASSWORD`: mật khẩu của app role tương ứng.
- `FLYWAY_PASSWORD`: mật khẩu của migrator role tương ứng.

Không commit `.env` chứa mật khẩu thật.

## 3. Chạy services

### IntelliJ IDEA

Giữ các Run Configuration Spring Boot hiện có.

Thiết lập Working Directory:

| Service | Working directory |
|---|---|
| S01 | `$PROJECT_DIR$/backend/identity-organization-service` |
| S02 | `$PROJECT_DIR$/backend/repository-integration-service` |
| S04 | `$PROJECT_DIR$/backend/document-governance-service` |

- JDK: Java 21.
- Active profiles: để trống.
- Environment variables: bỏ các biến đã chuyển vào `.env`.

Spring Boot đọc `.env` qua `spring.config.import` trong `application.yaml`. Profile `db` kích hoạt `application-db.yaml`.

### Chạy bằng Terminal (thay thế IntelliJ)

Mở terminal riêng cho mỗi service.

**macOS — ví dụ S01:**

```bash
cd backend/identity-organization-service
./mvnw spring-boot:run
```

**Windows PowerShell — ví dụ S01:**

```powershell
cd backend/identity-organization-service
.\mvnw.cmd spring-boot:run
```

Chạy tương tự cho S02 và S04 trong thư mục module tương ứng.

**Thứ tự chạy:** PostgreSQL → S01/S02/S04.

## 4. Kiểm tra service health

### macOS

```bash
curl -i http://127.0.0.1:8082/actuator/health
curl -i http://127.0.0.1:8083/actuator/health
curl -i http://127.0.0.1:8084/actuator/health
```

### Windows PowerShell

```powershell
curl.exe -i http://127.0.0.1:8082/actuator/health
curl.exe -i http://127.0.0.1:8083/actuator/health
curl.exe -i http://127.0.0.1:8084/actuator/health
```

Mong đợi cả ba trả HTTP 200 và `{"status":"UP"}`.

Trong startup log cần có profile `db` và port đúng với `.env`.

## 5. Xác minh Flyway migrations

Chạy các lệnh từ thư mục `infrastructure/docker`.

### macOS

```bash
docker compose exec postgres psql -U postgres -d livingdocs -c "SELECT version, description, success FROM identity.flyway_schema_history ORDER BY installed_rank;"

docker compose exec postgres psql -U postgres -d livingdocs -c "SELECT version, description, success FROM repository.flyway_schema_history ORDER BY installed_rank;"

docker compose exec postgres psql -U postgres -d livingdocs -c "SELECT version, description, success FROM document.flyway_schema_history ORDER BY installed_rank;"
```

### Windows PowerShell

```powershell
docker compose exec postgres psql -U postgres -d livingdocs -c "SELECT version, description, success FROM identity.flyway_schema_history ORDER BY installed_rank;"

docker compose exec postgres psql -U postgres -d livingdocs -c "SELECT version, description, success FROM repository.flyway_schema_history ORDER BY installed_rank;"

docker compose exec postgres psql -U postgres -d livingdocs -c "SELECT version, description, success FROM document.flyway_schema_history ORDER BY installed_rank;"
```

Kết quả mong đợi:

| Service | Số migrations | Trạng thái |
|---|---:|---|
| S01 | 2 | `success = true` |
| S02 | 1 | `success = true` |
| S04 | 1 | `success = true` |

Các migrations hiện tại còn tạo Row-Level Security (RLS) cho các bảng tenant-scoped. Không tắt RLS để khắc phục lỗi ứng dụng.

## 6. Build và test

Chạy trong thư mục của từng service.

**macOS:**

```bash
./mvnw -B -ntp clean verify
```

**Windows PowerShell:**

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Mong đợi `BUILD SUCCESS`. CI sẽ thực hiện các bài kiểm tra database/Flyway tương ứng.

## 7. Lỗi thường gặp

| Hiện tượng | Kiểm tra |
|---|---|
| `No active profile set` | Working Directory và `.env` |
| Tomcat chạy port `0` | `SERVER_PORT` chưa được nạp |
| `password authentication failed` | Mật khẩu app/migrator role |
| `permission denied for schema` | Schema owner và `GRANT USAGE` |
| Flyway migration failed | Logs Flyway, không xóa schema để thử lại |
| Actuator `DOWN` | Kết nối PostgreSQL và health contributor |

**Lưu ý:** S03 không sử dụng Flyway trong cấu hình foundation hiện tại. API Gateway cũng không kết nối PostgreSQL; JWT runtime phụ thuộc hợp đồng authentication của S01.
