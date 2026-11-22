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

Fresh clone LIV-138 PR 1: xem [runbook S01](../../docs/implementation/authorization/LIV-138-s01-authority-and-testing.md) để tạo JWT PEM/pepper ngoài Git, đăng ký Identity A/B/C, bootstrap một lần, tạo manifest CLI và chạy Postman/Newman trên database mới. File này chỉ giữ phần hạ tầng chung để không lặp lại toàn bộ fixture nghiệp vụ.

**Compose parse:** `s01-provisioner` dùng `${S01_OPERATOR_DB_LOGIN:?...}` và `${S01_OPERATOR_DB_PASSWORD:?...}`; `docker compose config/up` cũng cần hai biến này khi profile `operator` chưa bật. Trong giai đoạn tạo DB roles, đặt giá trị `config_only` tại shell để parse (không chạy provisioner), sau khi DBA enroll operator thì thay bằng login/password thật. Không lưu credential operator trong `.env` được commit.

> Tất cả `docker compose` bên dưới chạy **từ thư mục `infrastructure/docker`**. Dùng `docker compose ...` giống nhau trên macOS/Linux và Windows PowerShell.

## 2. Compose profiles — chạy đúng thành phần cần thiết

| Profile | Services được định nghĩa | Mục đích |
| --- | --- | --- |
| Mặc định (không profile) | `postgres` | Database local |
| `platform` | `kafka`, `redis`, `ai-service` | Nền tảng Kafka KRaft, Redis, S05 FastAPI |
| `services` | `identity-organization-service` (S01), `repository-integration-service` (S02), `source-intelligence-service` (S03), `document-governance-service` (S04) | Domain backend; S01/S02/S04 cần roles/Flyway |
| `gateway` | `api-gateway` | Gateway ingress; yêu cầu cấu hình S01 JWT issuer/audience thật |
| `operator` | `s01-provisioner` | CLI một lần, không có HTTP port; yêu cầu manifest và DB role riêng |

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

Cho LIV-138 PR 1, sau khi tạo S01 roles/schema (§3), chạy thêm `docker compose --profile platform up -d redis` trước S01. Chỉ bật `identity-organization-service` trong profile `services` để tránh kéo theo S02/S04 chưa cần cho Flow 1. Lần khởi động sau dùng `up -d` trên cùng named volume; không chạy `down -v` hoặc bootstrap lại.

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
| S01 | `127.0.0.1:8081` → `8081` |
| S02 | `127.0.0.1:8082` → `8082` |
| S03 | `127.0.0.1:8083` → `8083` |
| S04 | `127.0.0.1:8084` → `8084` |

S01 trong Compose cần Redis và thư mục ngoài Git chứa JWT private/public PEM cùng login rate pepper, mount chỉ đọc tại `/run/secrets`. Không dùng `localhost` giữa các container: Gateway gọi S03 bằng `http://source-intelligence-service:8083`, không phải loopback host.

### 2.5 Chạy API Gateway (chỉ khi có JWT S01 thật)

Cấu hình `JWT_ISSUER_URI`, `JWT_AUDIENCE` đúng hợp đồng S01 trong `.env` của **Compose** (khác với `.env` của Gateway khi chạy host). Bảo đảm S01–S04 đã sẵn sàng độc lập, rồi:

```bash
docker compose --profile gateway up -d --build api-gateway
docker compose --profile gateway ps
docker compose --profile gateway logs --tail=100 api-gateway
```

Gateway được publish trên host `127.0.0.1:8080`; port management `127.0.0.1:9091` được khai báo trong Compose. **Chưa bảo đảm health endpoint ở 9091 truy cập được từ host:** `application.yaml` cố định `management.server.address: 127.0.0.1`, trong khi Compose truyền `GATEWAY_MANAGEMENT_ADDRESS: 0.0.0.0` nhưng YAML chưa sử dụng biến này. Cần đồng bộ cấu hình và test runtime riêng trước khi coi 9091 qua Docker là PASS.

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

- S01: V1, V2; LIV-138 PR 1 bổ sung V3 (`scoped_authorization`) và V4 (`platform_authority`).
- S02: V1.
- S04: V1.

Migration trên feature branch của thành viên có thể bổ sung version mới. Không sửa migration đã áp dụng và không xóa volume để “sửa nhanh”. Xem [backend README](../../backend/README.md).

### S01 Platform bootstrap và operator provisioning sau V4

CLI chỉ dành cho operator, **không có endpoint HTTP cấp quyền**. Sau Flyway V4, DBA chạy `provisioner-role.sql` để tạo privilege group `ld_s01_provisioner` dạng `NOLOGIN` và bootstrap group `ld_s01_bootstrap`. Mỗi operator phải có DB login riêng, Identity ACTIVE và mapping chính xác. Không lưu password trong SQL, manifest, Git hoặc reports.

1. Requester B và Platform Admin A tự Register để có Identity thật. DBA cấp một named bootstrap DB login quyền `ld_s01_bootstrap` trong cửa sổ vận hành có phê duyệt, rồi chạy `bootstrap-platform-admin.sql` với `-v initial_identity=<A_UUID> -v reason='<approved change reference>'`. Function kiểm chỉ chạy một lần, ghi platform audit và assignment cùng transaction. DBA thu hồi membership bootstrap và disable login ngay sau đó. Không chạy bootstrap bằng role ứng dụng hoặc từ service HTTP.
2. DBA chạy `operator-enroll.sql` với `-v operator_login=ld_operator_<name> -v operator_identity_id=<A_UUID>` để tạo login cá nhân, ràng buộc với Identity và grant privilege group. Đặt password riêng bằng `\password ld_operator_<name>` trong `psql` hoặc secret store. Script validation/transaction từ chối Identity không ACTIVE hoặc mapping không khớp.
3. Đặt `S01_OPERATOR_DB_LOGIN`, `S01_OPERATOR_DB_PASSWORD` từ secret store và `S01_PROVISION_MANIFEST_DIR` là thư mục riêng ngoài repository. Chạy `docker compose --profile operator run --rm --no-deps s01-provisioner` khi PostgreSQL đã healthy. Trên PowerShell dùng `$env:S01_OPERATOR_DB_LOGIN`, `$env:S01_OPERATOR_DB_PASSWORD`, `$env:S01_PROVISION_MANIFEST_DIR`. Lệnh `run` đọc duy nhất `/run/provision/manifest.json`; mỗi operation cần `operationId` mới, chạy lặp cùng bytes trả `ALREADY_APPLIED`.

Lệnh psql macOS/Windows, thứ tự đặt password, manifest A/B/C và cách chạy từng CLI operation nằm trong [runbook fresh clone §C](../../docs/implementation/authorization/LIV-138-s01-authority-and-testing.md#c-identity-abc-bootstrap-và-cli). Khi thu hồi operator, DBA chạy `operator-disable.sql` với `operator_login` và `reason`. Bootstrap script chỉ chạy một lần; nếu mất toàn bộ Platform Admin, cần incident/change control, không tự chạy lại để bypass one-shot guard.

`CREATE_TENANT` dùng actor A từ DB login mapping và `identityId` trong manifest là requester B, có thể khác A. Trường `operatorIdentityId` legacy có thể bỏ; nếu được gửi phải khớp actor đã xác thực. `CREATE_TENANT` cần Platform permission; Tenant mutations cần Tenant Owner role riêng; `REQUEST_REPOSITORY_GRANT` chỉ cho TECHNICAL_LEAD active tự tạo PENDING cho chính membership. PENDING không có quyền Repository cho tới khi có verification từ S02 theo policy được duyệt. Mỗi operation idempotent theo `operationId` và SHA-256 nội dung, commit với audit/revision trong một transaction.

**Ranh giới:** login DB cá nhân vẫn là credential vận hành mạnh; giới hạn network access, quản lý trong secret store, rotation/revoke kịp thời và đối chiếu DB audit. Platform Admin không tự thành Tenant Owner hay Document reviewer. Nếu V3 đã có Flyway checksum ở môi trường dùng chung, giữ V3 bất biến; V4 là migration tiếp theo. PR 1 không mở `VERIFIED`/attestation từ S02 và không mở CLI `REVOKE_REPOSITORY_GRANT`; các bước này là BLOCKED, không dùng SQL trực tiếp để tạo bằng chứng PASS.
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
