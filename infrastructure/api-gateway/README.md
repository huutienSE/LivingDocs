# LivingDocs — API Gateway Foundation (LIV-115)

## 1. Mục đích và phạm vi

**Spring Cloud Gateway Server WebFlux** là điểm vào cho các API công khai được cho phép của LivingDocs, định tuyến đến:

- **S01 — Identity & Organization:** danh tính, đăng nhập, tenant và workspace.
- **S02 — Repository Integration:** kết nối GitHub và thông tin repository.
- **S04 — Document Governance:** tài liệu, phiên bản, review, phê duyệt và publish.

Gateway chịu trách nhiệm **định tuyến, xác thực JWT tại biên, CORS, correlation ID và xử lý lỗi kết nối downstream**. Các domain service vẫn phải **tự xác minh JWT theo hợp đồng và kiểm tra quyền theo tenant/workspace/repository, business guards**. Gateway không quyết định quyền phê duyệt hoặc trạng thái tài liệu.

**Nguồn hợp đồng:**

- [LD-API-001 — Full MVP API Inventory & Integration Contract Design](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/11337744) — API contract candidate v0.4.
- [Architecture Decision Records — ADR-01 Gateway](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/3702834).
- [Jira LIV-115](https://huutiense25.atlassian.net/browse/LIV-115).

Bảy route ban đầu trong `application.yaml` mới bao phủ **một phần** API inventory. Khi domain service bổ sung API, phải cập nhật danh sách **Path + Method** theo contract; không mở rộng tùy tiện thành `Path=/api/v1/**`. Không dùng `StripPrefix` vì public API và API downstream có cùng prefix `/api/v1/...`.

### Luồng request

```text
Client
  → Spring Cloud Gateway :8081
      → CorrelationIdWebFilter
      → Spring Security (CORS / JWT / authorization)
      → RoutePredicateHandlerMapping (Path + Method)
      → Gateway filters / HTTP forwarding
      → S01 :8082 | S02 :8083 | S04 :8084
      → Response về client
```

## 2. Service Discovery và Load Balancing

**Không dùng Eureka** và chưa cần Spring Cloud LoadBalancer (`lb://`).

- **Local:** Gateway gọi `http://127.0.0.1:8082`, `:8083`, `:8084` qua các biến `S01_SERVICE_URI`, `S02_SERVICE_URI`, `S04_SERVICE_URI`.
- **Kubernetes:** Gateway gọi tên DNS của **Kubernetes Service** tương ứng. Kubernetes Service phân phối kết nối đến các Pod đủ điều kiện nhận traffic. Gateway không tự chọn từng Pod.

Điều này là lựa chọn kiến trúc, **không đồng nghĩa đã kiểm chứng load balancing nhiều replica**.

## 3. Cấu hình môi trường

Tham khảo [`.env.example`](.env.example). **IntelliJ không tự đọc `.env.example`**: khai báo biến trong **Run → Edit Configurations → Environment variables**, hoặc sử dụng cơ chế nạp `.env` của môi trường chạy.

### Thiết lập môi trường local

1. Copy `.env.example` thành `.env` tại thư mục `infrastructure/api-gateway`.
2. Điều chỉnh port, URI của S01/S02/S04 và CORS origin nếu cần.
3. Đặt IntelliJ Working Directory là `infrastructure/api-gateway`.
4. Không cần nhập thủ công các biến đã có trong `.env` vào Run Configuration.
5. Khi S01 cung cấp JWT contract thật, điền `JWT_ISSUER_URI`
   và `JWT_AUDIENCE`.

Spring Boot nạp `.env` bằng:

`spring.config.import=optional:file:./.env[.properties]`

File `.env` local không được commit. CI và Kubernetes cung cấp
cấu hình bằng môi trường triển khai tương ứng.

Lưu ý: Gateway chưa có JWT issuer thật thì chưa thể xác nhận
authentication/runtime E2E. Không sử dụng issuer giả hoặc tắt
JWT validation để vượt qua bước này.


Các timeout trên là **giá trị cấu hình ban đầu**, chưa phải SLA đã được benchmark và phê duyệt. URI downstream chỉ chứa scheme/host/port, không thêm `/api/v1` vào cuối.

**JWT:** S01 phải cung cấp cơ chế phát token, khóa ký, JWKS/metadata, `iss`, `aud`, thời hạn token và chính sách xoay khóa thống nhất với Gateway. Không hardcode issuer giả trong runtime, không vô hiệu hóa JWT để vượt qua kiểm thử. Nếu S01 không cung cấp issuer discovery chuẩn, có thể cấu hình thêm `jwk-set-uri` **sau khi được chốt hợp đồng và cấu hình thuộc tính tương ứng**, vẫn duy trì kiểm tra issuer.

## 4. Bảo mật và phạm vi public

- Theo contract, `POST /api/v1/auth/register` và `POST /api/v1/auth/login` là public. Các API khác phải xác thực trừ ngoại lệ được review rõ ràng.
- Không route công khai `/internal/v1/**`, `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**` hoặc S05. Management port riêng cũng phải được bảo vệ ở tầng mạng, không chỉ dựa vào security rules của public Gateway.
- `POST /integrations/github/webhooks` **chỉ được mở** khi S02 đã xác minh GitHub HMAC trên **raw request body** và xử lý replay/duplicate.
- Refresh token/session sử dụng cookie `HttpOnly`, `Secure`, `SameSite` theo contract S01. Nếu frontend gửi cookie qua origin khác, CORS phải cho phép credentials với **origin allowlist cụ thể**, không dùng `*`. **CORS không thay thế CSRF**; chỉ mở refresh/logout khi cơ chế CSRF, session rotation và kiểm thử bảo mật đã hoàn thành.
- `X-Correlation-ID` được kiểm tra/tạo lại tại ingress, truyền xuống downstream và trả về cả trên response lỗi.
- Không log JWT, refresh cookie, mật khẩu, GitHub secret hoặc dữ liệu nhạy cảm.
- **Staging/demo và production:** phải tuân theo chính sách HTTPS/TLS trong LD-API-001, kể cả các chặng backend phù hợp. HTTP loopback local chỉ dùng cho phát triển, không phải bằng chứng nghiệm thu TLS.

## 5. Build và kiểm thử

Trong thư mục `infrastructure/api-gateway`:

**Windows PowerShell:**

```powershell
.\mvnw.cmd -B -ntp clean verify
```

**macOS/Linux:**

```bash
./mvnw -B -ntp clean verify
```

Bộ test cần bao gồm:

1. **Security Foundation:** thiếu/sai JWT → `401`; chặn đường dẫn nội bộ/Actuator; CORS; correlation ID.
2. **Routing Integration:** dùng **route production trong `application.yaml`**, xác minh đúng S01/S02/S04, Path + Method và forwarding header.
3. **Downstream Failure:** upstream từ chối kết nối → `503`; upstream không phản hồi đúng hạn → `504`; body `application/problem+json` có `code`, `instance` opaque và `correlationId`.

Trong các integration tests, HTTP stub và mock JWT decoder chỉ dùng **để kiểm thử độc lập Gateway**. Chúng **không phải** bằng chứng rằng S01 đã phát được JWT thật hoặc các business controller đã hoạt động.

**CI:** `.github/workflows/backend-ci.yaml` đã có job build/test cho API Gateway. Không tạo thêm workflow trùng chức năng; kiểm tra job trên PR.

## 6. Vận hành và tra lỗi

| Kết quả | Ý nghĩa cần kiểm tra |
| --- | --- |
| `401` | JWT thiếu, sai, hết hạn hoặc issuer/audience/khóa không hợp lệ |
| `403` | Gateway từ chối path nội bộ, hoặc domain service từ chối quyền nghiệp vụ |
| `404` | Không có route Path + Method phù hợp, hoặc downstream chưa triển khai controller |
| `503` | Gateway nhận diện lỗi downstream không khả dụng/kết nối thất bại |
| `504` | Gateway nhận diện timeout kết nối hoặc phản hồi downstream |

Kiểm tra Gateway health tại local (khi Gateway đã có đủ cấu hình để khởi động):

```bash
curl -i http://127.0.0.1:9091/actuator/health
```

`UP` của Gateway **không chứng minh** downstream API đã đáp ứng. Không retry tự động các POST nghiệp vụ nhạy cảm nếu chưa có chính sách idempotency rõ ràng.
## 7. Docker Compose — Platform và Domain Services (LIV-117)

LivingDocs sử dụng Docker Compose profiles để khởi động đúng nhóm service cần thiết.

| Profile | Thành phần |
|---|---|
| Mặc định | PostgreSQL |
| `platform` | Kafka KRaft, Redis, S05 FastAPI |
| `services` | S01, S02, S03, S04 |
| `gateway` | API Gateway (chỉ bật khi JWT issuer thật sẵn sàng) |

### Khởi động Platform

Từ thư mục `infrastructure/docker`.

**macOS:**

```bash
docker compose --profile platform configtest.md --quiet
docker compose --profile platform up -d --build --wait
docker compose --profile platform ps
```

**Windows PowerShell:**

```powershell
docker compose --profile platform config --quiet
docker compose --profile platform up -d --build --wait
docker compose --profile platform ps
```

Kiểm tra Kafka, Redis và S05:

**macOS:**

```bash
docker compose --profile platform exec -T redis redis-cli ping
docker compose --profile platform exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --list
curl -i http://127.0.0.1:8000/health
curl -i http://127.0.0.1:8000/metrics
```

**Windows PowerShell:**

```powershell
docker compose --profile platform exec -T redis redis-cli ping
docker compose --profile platform exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --list
curl.exe -i http://127.0.0.1:8000/health
curl.exe -i http://127.0.0.1:8000/metrics
```

Redis phải trả `PONG`. Kafka CLI hoàn thành thành công; danh sách topic rỗng vẫn hợp lệ.

### Khởi động Domain Services

Trước khi chạy, đảm bảo PostgreSQL đã có roles, schemas và mật khẩu đúng theo hướng dẫn ở các mục trước.

**macOS:**

```bash
docker compose --profile services up -d --build
docker compose --profile services ps
```

**Windows PowerShell:**

```powershell
docker compose --profile services up -d --build
docker compose --profile services ps
```

S01, S02, S03, S04 lần lượt có cổng local 8082, 8083, 8085, 8084.

### Lưu ý về môi trường

- Container gọi nhau bằng Docker service DNS, không dùng `localhost` để gọi container khác.
- PostgreSQL data được lưu trong volume `postgres_data`.
- Kafka local dùng KRaft một node, không có tính sẵn sàng cao.
- Redis baseline không bật persistence; không lưu dữ liệu nghiệp vụ quan trọng tại đây.
- Gateway chưa được nghiệm thu với JWT thật; không sử dụng JWT placeholder để đánh dấu E2E PASS.
- Không chạy `docker compose down -v` trên môi trường có dữ liệu cần giữ.
- Không đưa mật khẩu thật vào Git.

### CI và phạm vi nghiệm thu

Workflow `platform-compose-ci.yaml` kiểm tra nền tảng Platform bằng Docker Compose.

CI PASS của Platform chứng minh container health và smoke, không thay thế các bài kiểm thử nghiệp vụ xuyên Gateway và domain services.