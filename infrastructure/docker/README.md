# LivingDocs — Docker Compose & PostgreSQL Local Setup

Hướng dẫn chạy **PostgreSQL 17**, platform services, domain services và API Gateway theo đúng profiles trong `compose.yaml`. Phần PostgreSQL roles/Flyway bên dưới phục vụ S01/S02/S04 và chỉ cần tạo khi khởi tạo database mới.

> **Trạng thái Sprint 3:** `compose.yaml` có định nghĩa `api-gateway`, nhưng container Gateway **không tự xuất hiện** khi chỉ chạy platform hay services. Gateway thuộc profile riêng `gateway` và chỉ nên bật runtime khi hợp đồng JWT issuer/audience S01 thật đã sẵn sàng. LIV-133 không yêu cầu chạy Gateway container để validate OpenAPI/route.

## 1. Chuẩn bị môi trường

Yêu cầu Docker Desktop hoặc Docker Engine + Docker Compose; clone repository LivingDocs.

Từ root repository:

**macOS/Linux:**

```bash
cd infrastructure/docker
cp .env.example .env
```

**Windows PowerShell:**

```powershell
cd infrastructure/docker
Copy-Item .env.example .env
```

Nếu `.env` đã tồn tại, **không ghi đè**. Chỉnh `POSTGRES_PASSWORD` và mật khẩu S01/S02/S04 theo các database roles thực tế; giữ mật khẩu hiện hành nếu đã có database/volume. Không commit `.env`.

> Tất cả `docker compose` bên dưới chạy **từ thư mục `infrastructure/docker`**. Dùng `docker compose ...` giống nhau trên macOS/Linux và Windows PowerShell.

## 2. Compose profiles — chạy đúng thành phần cần thiết

| Profile | Services được định nghĩa | Mục đích |
| --- | --- | --- |
| Mặc định (không profile) | `postgres` | Database local |
| `platform` | `kafka`, `redis`, `ai-service` | Nền tảng Kafka KRaft, Redis, S05 FastAPI |
| `services` | `identity-organization-service` (S01), `repository-integration-service` (S02), `source-intelligence-service` (S03), `document-governance-service` (S04) | Domain backend; S01/S02/S04 cần roles/Flyway |
| `gateway` | `api-gateway` | Gateway ingress; yêu cầu cấu hình S01 JWT issuer/audience thật |

Các profile có thể kết hợp; `postgres` không có profile và thường được Compose khởi động cùng tập services. Chạy `--profile services` **không tự kích hoạt** `gateway`. `api-gateway` cũng không khai báo `depends_on` tới S01–S04: khởi động Gateway không có nghĩa downstream đã chạy.

### 2.1 Kiểm tra cú pháp cấu hình (không khởi động container)

```bash
docker compose config --quiet
docker compose --profile platform config --quiet
docker compose --profile services --profile gateway config --quiet
```

Lệnh `config --quiet` chỉ kiểm tra model Compose và biến môi trường; **không** xác minh sức khỏe dịch vụ, Dockerfile build, JWT, Controller hoặc đường truyền Gateway.

### 2.2 Chỉ khởi động PostgreSQL

```bash
docker compose up -d postgres
docker compose ps
```

Mong đợi `postgres` healthy. PostgreSQL host port mặc định `127.0.0.1:5432`, có thể chỉnh qua `POSTGRES_HOST_PORT`.

### 2.3 Chạy Platform (không bao gồm Gateway)
Từ thư mục `infrastructure/docker`.

```bash
docker compose --profile platform up -d --build --wait
docker compose --profile platform ps
```

Kiểm tra cơ bản:

```bash
docker compose --profile platform exec -T redis redis-cli ping
docker compose --profile platform exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --list
```

**macOS/Linux:**

```bash
curl -i http://127.0.0.1:8000/health
curl -i http://127.0.0.1:8000/metrics
```

**Windows PowerShell:**

```powershell
curl.exe -i http://127.0.0.1:8000/health
curl.exe -i http://127.0.0.1:8000/metrics
```

Redis trả `PONG`; Kafka CLI trả về thành công (danh sách topic rỗng vẫn hợp lệ). S05 health/metrics chỉ kiểm tra baseline platform, không chứng minh AI business flow.


### 2.4 Chạy S01–S04 (không bao gồm Gateway)

**Trước tiên hoàn thành tạo PostgreSQL roles/schemas ở §3 và cấu hình mật khẩu trong `.env`.**

```bash
docker compose --profile services up -d --build
docker compose --profile services ps
docker compose --profile services logs --tail=100
```

| Service | Host port → container port |
| --- | --- |
| S01 | `127.0.0.1:8082` → `8080` |
| S02 | `127.0.0.1:8083` → `8080` |
| S03 | `127.0.0.1:8085` → `8080` |
| S04 | `127.0.0.1:8084` → `8080` |

Không dùng `localhost` giữa các container: ví dụ Gateway gọi S03 bằng `http://source-intelligence-service:8080`, không phải `127.0.0.1:8085`.

### 2.5 Chạy API Gateway (chỉ khi có JWT S01 thật)

Cấu hình `JWT_ISSUER_URI`, `JWT_AUDIENCE` đúng hợp đồng S01 trong `.env` của **Compose** (khác với `.env` của Gateway khi chạy host). Bảo đảm S01–S04 đã sẵn sàng độc lập, rồi:

```bash
docker compose --profile gateway up -d --build api-gateway
docker compose --profile gateway ps
docker compose --profile gateway logs --tail=100 api-gateway
```

Gateway được publish trên host `127.0.0.1:8081`; port management `127.0.0.1:9091` được khai báo trong Compose. **Chưa bảo đảm health endpoint ở 9091 truy cập được từ host:** tại baseline remote, `application.yaml` cố định `management.server.address: 127.0.0.1`, trong khi Compose truyền `GATEWAY_MANAGEMENT_ADDRESS: 0.0.0.0` nhưng YAML chưa sử dụng biến này. Cần đồng bộ cấu hình và test runtime riêng trước khi coi 9091 qua Docker là PASS; không giả lập issuer để vượt qua điều kiện khởi động.

**Nếu bạn chỉ cần hoàn thành LIV-133**, không chạy bước này: dùng Python validators, Gateway Maven build và `docker compose config --quiet` là đủ để kiểm chứng baseline tĩnh. Các bài E2E/JWT thuộc giai đoạn service integration.

## 3. Tạo database roles và schemas lần đầu

Mỗi service database dùng một **app role** và một **Flyway migrator role** riêng:

| Service | App role | Flyway role | Schema |
| --- | --- | --- | --- |
| S01 | `ld_s01_app` | `ld_s01_migrator` | `identity` |
| S02 | `ld_s02_app` | `ld_s02_migrator` | `repository` |
| S04 | `ld_s04_app` | `ld_s04_migrator` | `document` |

- App role truy cập bảng nghiệp vụ theo quyền migration cấp.
- Migrator role sở hữu schema/chạy Flyway.
- Không dùng `postgres` làm app database user.

Mở PostgreSQL terminal:

```bash
docker compose exec postgres psql -U postgres -d livingdocs
```

Dán SQL sau **chỉ cho database mới** (không chạy lại nếu roles/schema đã có):

```sql
CREATE ROLE ld_s01_app LOGIN;
CREATE ROLE ld_s01_migrator LOGIN;
CREATE ROLE ld_s02_app LOGIN;
CREATE ROLE ld_s02_migrator LOGIN;
CREATE ROLE ld_s04_app LOGIN;
CREATE ROLE ld_s04_migrator LOGIN;

CREATE SCHEMA identity AUTHORIZATION ld_s01_migrator;
CREATE SCHEMA repository AUTHORIZATION ld_s02_migrator;
CREATE SCHEMA document AUTHORIZATION ld_s04_migrator;

GRANT CONNECT ON DATABASE livingdocs TO
    ld_s01_app, ld_s01_migrator,
    ld_s02_app, ld_s02_migrator,
    ld_s04_app, ld_s04_migrator;

GRANT USAGE ON SCHEMA identity TO ld_s01_app;
GRANT USAGE ON SCHEMA repository TO ld_s02_app;
GRANT USAGE ON SCHEMA document TO ld_s04_app;
```

Đặt mật khẩu cho từng role ngay trong `psql`:

```text
\password ld_s01_app
\password ld_s01_migrator
\password ld_s02_app
\password ld_s02_migrator
\password ld_s04_app
\password ld_s04_migrator
```

Thoát bằng `\q`. Không ghi password thật vào Git, README hoặc file `.env.example`.

### Xác nhận schema owner

```bash
docker compose exec postgres psql -U postgres -d livingdocs -c "SELECT n.nspname AS schema, r.rolname AS owner FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner WHERE n.nspname IN ('identity','repository','document') ORDER BY n.nspname;"
```

Kết quả mong đợi: `identity` → `ld_s01_migrator`, `repository` → `ld_s02_migrator`, `document` → `ld_s04_migrator`.

## 4. Flyway migrations

Không chạy SQL migration thủ công. S01/S02/S04 sử dụng `SPRING_PROFILES_ACTIVE=db` (đã khai báo trong `compose.yaml`) để chạy Flyway. Baseline `develop` tại thời điểm kiểm tra:

- S01: V1, V2.
- S02: V1.
- S04: V1.

Migration trên feature branch của thành viên có thể bổ sung version mới. Không sửa migration đã áp dụng và không xóa volume để “sửa nhanh”. Xem [backend README](../../backend/README.md).

## 5. Lệnh vận hành / troubleshooting

```bash
docker compose ps
docker compose --profile gateway ps
docker compose --profile services logs --tail=100
docker compose --profile gateway logs --tail=100 api-gateway
docker compose logs --tail=100 postgres
docker compose stop postgres
```

| Triệu chứng | Kiểm tra đầu tiên |
| --- | --- |
| Không thấy Gateway container | Bạn có bật `--profile gateway` chưa? Compose mặc định không khởi động Gateway. |
| Gateway không start | `JWT_ISSUER_URI`/`JWT_AUDIENCE`, log container và contract thật của S01. |
| Gateway `503` | Domain services chưa chạy/không reachable; kiểm Docker service DNS. |
| Lỗi database auth/Flyway | Roles, schema owner, password `.env`, schema migrations. |
| Host không gọi được management `9091` | `management.server.address` hardcode loopback trong container; xem §2.5. |
| Platform up nhưng không có S01–S04 | Phải bật profile `services`; `platform` chỉ chứa Kafka/Redis/S05. |

`postgres_data` và `kafka_data` là named volumes. **Không chạy `docker compose down -v` nếu có dữ liệu cần giữ.** Redis hiện tắt persistence; Kafka KRaft local một node, không có HA.

## 6. Liên kết kiểm chứng LIV-133

- [Gateway README](../api-gateway/README.md): cấu hình, routes, JWT và Maven build.
- [OpenAPI README](../../contracts/openapi/README.md): inventory/CI/Python validator.
- [LIV-133 alignment notes](../../docs/implementation/LIV-133-contract-alignment.md): phân biệt route candidate và implementation thực tế.

`platform-compose-ci.yaml` kiểm tra Platform, không phải JWT E2E. `openapi-contract-ci.yaml` kiểm tra OpenAPI và Gateway route inventory theo phiên bản workflow trên PR. Route chuẩn không đồng nghĩa tất cả API business chạy được.
