# LivingDocs — API Gateway (LIV-115 foundation, LIV-133 contract alignment)

> **Trạng thái:** Tài liệu vận hành cho baseline Sprint 3. Gateway route/OpenAPI **không chứng minh** downstream controller đã triển khai hoặc JWT S01 đã tích hợp E2E. Các API `CANDIDATE`/`PLANNED` phải giữ đúng trạng thái cho đến khi service owner xác nhận.

## 1. Vai trò và nguồn chuẩn

LivingDocs dùng **Spring Cloud Gateway Server WebFlux** làm ingress cho các endpoint `/api/v1` được allowlist. Gateway xử lý routing, JWT authentication tại biên, CORS, correlation ID và lỗi downstream. Authorization theo tenant/workspace/repository, review/publish state và business guards **vẫn thuộc domain service**.

| Service | Vai trò | Local URI (Gateway chạy trên host) | Docker Compose DNS (Gateway chạy trong container) |
| --- | --- | --- | --- |
| S01 — Identity & Organization | Identity, tenant, workspace | `http://127.0.0.1:8082` | `http://identity-organization-service:8080` |
| S02 — Repository Integration | GitHub, repositories | `http://127.0.0.1:8083` | `http://repository-integration-service:8080` |
| S03 — Source Intelligence | Analysis, drift, evidence | `http://127.0.0.1:8085` | `http://source-intelligence-service:8080` |
| S04 — Document Governance | Documents, reviews, approval | `http://127.0.0.1:8084` | `http://document-governance-service:8080` |

**S05** và các `/internal/v1/**` không được route qua public Gateway.

Nguồn chuẩn và checklist:
- [LD-API-001 v0.4 — API Inventory & Integration Contract](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/11337744)
- [LIV-115 — Gateway foundation](https://huutiense25.atlassian.net/browse/LIV-115)
- [LIV-133 — OpenAPI/Gateway alignment](https://huutiense25.atlassian.net/browse/LIV-133)
- [OpenAPI README](../../contracts/openapi/README.md)
- [Sprint 3 route inventory](../../contracts/openapi/sprint3-inventory.md)
- [Integration gap notes](../../docs/implementation/LIV-133-contract-alignment.md)

## 2. Routing: đọc và sửa đúng cách

`src/main/resources/application.yaml` định nghĩa **19 route groups chứa 91 cặp Method + Path tường minh** cho S01–S04; inventory có 112 API IDs, trong đó nhiều API chưa được triển khai. Các con số này là mốc của baseline LIV-133, **không phải 91 controller đã hoạt động**.

Ví dụ:

```yaml
- id: s04-get-api-111-125
  uri: ${S04_SERVICE_URI:http://127.0.0.1:8084}
  predicates:
    - Path=/api/v1/review-queue,/api/v1/document-versions/{versionId}/review-context
    - Method=GET
```

Ví dụ trên **rút gọn danh sách Path để minh họa**, không phải đoạn cấu hình để sao chép thay thế. `id` chỉ là nhãn route, không tự bật toàn bộ API trong khoảng số; **chỉ các Path được liệt kê và Method tương ứng mới match**. Gateway forward nguyên `/api/v1/...` (không `StripPrefix`). Không nhóm nhiều Method vào một route chung nhiều Path vì có thể tạo cross-product ngoài ý muốn; không dùng `Path=/api/v1/**`.

Luồng: Client → Gateway `:8081` → Path + Method allowlist → downstream S01/S02/S03/S04 → response. JWT được xác minh tại Gateway; domain service vẫn kiểm tra quyền ở mức tài nguyên.

Khi thêm/chỉnh route: cập nhật `contracts/openapi/sprint3-route-inventory.json`, service OpenAPI YAML và `application.yaml` nhất quán; chạy validator; yêu cầu service owner xác nhận DTO/security. **Không bật endpoint `BLOCKED_SECURITY` hoặc `/internal/v1/**` chỉ để thông qua CI.**

## Service Discovery và Load Balancing

**Không dùng Eureka** và chưa cần Spring Cloud LoadBalancer (`lb://`).

- **Local:** Gateway gọi `http://127.0.0.1:8082`, `:8083`, `:8084` qua các biến `S01_SERVICE_URI`, `S02_SERVICE_URI`, `S04_SERVICE_URI`.
- **Kubernetes:** Gateway gọi tên DNS của **Kubernetes Service** tương ứng. Kubernetes Service phân phối kết nối đến các Pod đủ điều kiện nhận traffic. Gateway không tự chọn từng Pod.

Điều này là lựa chọn kiến trúc, **không đồng nghĩa đã kiểm chứng load balancing nhiều replica**.

## 3. Chuẩn bị môi trường

**Build/validation chỉ cần JDK 21, Maven Wrapper và Python 3; không cần Gateway container, S04, PostgreSQL hoặc JWT thật.** Runtime HTTP của Gateway cần issuer/audience thật từ S01.

Tại root repository, copy file mẫu:

**macOS/Linux:**

```bash
cp infrastructure/api-gateway/.env.example infrastructure/api-gateway/.env
```

**Windows PowerShell:**

```powershell
Copy-Item infrastructure/api-gateway/.env.example infrastructure/api-gateway/.env
```

Chỉnh `.env` theo môi trường; không commit credentials. Khi chạy bằng IDE, đặt **Working Directory = `infrastructure/api-gateway`** để Spring Boot đọc:

```properties
spring.config.import=optional:file:./.env[.properties]
```

Nếu chạy Gateway trên host, `S01_SERVICE_URI` đến `S04_SERVICE_URI` dùng `127.0.0.1` và các port tương ứng ở bảng §1. Nếu Gateway nằm trong Docker Compose, dùng service DNS do `compose.yaml` đã khai báo; **không dùng `localhost` để gọi container khác**. Không thêm `/api/v1` vào cuối URI downstream.

Các biến chính:
- `GATEWAY_PORT` (mặc định `8081`), `GATEWAY_BIND_ADDRESS` (mặc định `127.0.0.1`).
- `GATEWAY_MANAGEMENT_PORT` (mặc định `9091`). `application.yaml` trên baseline remote đang cố định `management.server.address: 127.0.0.1`; lưu ý khác biệt khi chạy trong container, xem §6.
- `GATEWAY_ALLOWED_ORIGINS` (mặc định `http://localhost:3000`).
- `GATEWAY_CONNECT_TIMEOUT_MS` (mặc định `2000`), `GATEWAY_RESPONSE_TIMEOUT` (mặc định `5s`); chưa phải SLA.
- `JWT_ISSUER_URI`, `JWT_AUDIENCE`: **cần S01 phát hành và công bố contract thật trước khi coi Gateway chạy JWT E2E**. Không dùng issuer giả hoặc tắt JWT authentication.

## 4. Kiểm thử LIV-133 (không cần khởi động container)

**Từ root repo**, chạy validator route + contract:

```bash
python3 -m pip install openapi-spec-validator==0.7.2 PyYAML==6.0.3
python3 scripts/validate_gateway_openapi.py
```

Để validate tất cả OpenAPI specs theo cách CI sử dụng:

```bash
python3 - <<'PY'
from pathlib import Path
import yaml
from openapi_spec_validator import validate

for p in sorted(Path('contracts/openapi').glob('*.yaml')):
    with p.open(encoding='utf-8') as f:
        validate(yaml.safe_load(f))
    print('PASS:', p)
PY
```

**Windows PowerShell:** dùng `py -3.12` thay cho `python3`, xem ví dụ trong [OpenAPI README](../../contracts/openapi/README.md).

**Từ `infrastructure/api-gateway`:**

```bash
./mvnw -B -ntp clean verify
```

**Windows PowerShell:**

```powershell
.\mvnw.cmd -B -ntp clean verify
```

`clean verify` kiểm tra Maven/module và chạy các test có trong checkout. Nó không chứng minh route hiện có đã được downstream implement. CORS preflight regression test là hạng mục riêng; chỉ đánh dấu PASS khi test đã chạy thành công, không bỏ qua thất bại bằng cách skip toàn bộ test.

Kết quả validator baseline kỳ vọng: `112 catalogued APIs`, `91 exact Gateway method+path entries`, `19 route groups`. **Đó là kiểm tra tĩnh, không phải E2E.**

## 5. CORS, bảo mật và HTTP errors

Baseline LIV-133 giữ `allowCredentials(false)` và origin allowlist. Header request cần cho Staff/Manager Decision: `Authorization`, `Content-Type`, `Accept`, `X-Correlation-ID`, `If-Match`, `Idempotency-Key`. Exposed response headers gồm `X-Correlation-ID`, `ETag`. **Đặc biệt chỉ được gọi `cors.setAllowedHeaders(...)` một lần** để tránh bị ghi đè cấu hình.

Cookie refresh/logout, GitHub OAuth callback và GitHub webhook **chưa được public** khi chưa có CSRF/session rotation, HMAC raw-body/replay guard và security review. Không tự bật `allowCredentials(true)`.

| HTTP | Cần kiểm tra |
| --- | --- |
| `401` | JWT thiếu/sai/hết hạn; issuer/audience/JWKS không hợp lệ |
| `403` | Forbidden route hoặc backend từ chối quyền |
| `404` | Gateway không match Method + Path **hoặc** downstream chưa có controller |
| `503` | Downstream không kết nối/không khả dụng theo error mapping hiện tại |
| `504` | Downstream timeout theo error mapping hiện tại |
| `412` | `If-Match` không còn khớp tại backend hỗ trợ concurrency guard |

Gateway phải giữ `X-Correlation-ID`, `application/problem+json` theo contract. Không log JWT, cookie hoặc secret. Không coi `/actuator/health` là bằng chứng domain APIs/authorization chạy được.

## 6. Chạy runtime khi S01 JWT đã sẵn sàng

**Local host:** mở terminal tại `infrastructure/api-gateway` sau khi cấu hình issuer/audience hợp lệ:

```bash
./mvnw spring-boot:run
```

**Windows PowerShell:**

```powershell
.\mvnw.cmd spring-boot:run
```

Gateway dùng cổng `8081`; management riêng `9091`. Kiểm tra khi app thực sự start:

```bash
curl -i http://127.0.0.1:9091/actuator/health
```

**Docker Compose:** tham khảo [Docker README](../docker/README.md). Profile `gateway` là tùy chọn; không tự khởi động bởi `platform` hay `services`. Gateway Compose đang **không** có `depends_on` đến các domain service, nên cần bảo đảm downstream tồn tại độc lập.

**Lưu ý cấu hình đang cần đối chiếu trước khi dùng 9091 qua host Docker:** `compose.yaml` truyền `GATEWAY_MANAGEMENT_ADDRESS=0.0.0.0`, nhưng `application.yaml` remote đang đặt `management.server.address: 127.0.0.1` cố định. Vì vậy không được giả định `curl localhost:9091` từ host sẽ hoạt động với container cho đến khi cấu hình được đồng bộ và test. Đây là lưu ý runtime, **không phải blocker của static contract validation LIV-133**.

Các URL local dùng HTTP loopback để phát triển. Staging/demo/production cần chính sách TLS/HTTPS được review; chưa có bằng chứng này trong LIV-133.

## 7. CI và bàn giao

- `.github/workflows/openapi-contract-ci.yaml`: validate OpenAPI và chạy `scripts/validate_gateway_openapi.py` trên PR phù hợp (xác nhận CI sau khi push bản workflow mới).
- `.github/workflows/backend-ci.yaml`: Gateway Maven build/test.
- `.github/workflows/platform-compose-ci.yaml`: platform smoke; **không** chứng minh Gateway JWT E2E.

Lưu ý các thay đổi CORS/CI đang được chỉnh tại local có thể chưa xuất hiện trên GitHub. Chỉ lấy kết quả workflow sau khi push và PR chạy làm evidence chính thức.

**Definition of Done của LIV-133:** OpenAPI/inventory/route alignment + security allowlist/CORS baseline + CI + hướng dẫn/bàn giao được review. Không cần chờ service owner triển khai tất cả API candidate hoặc S01 JWT production-ready.
