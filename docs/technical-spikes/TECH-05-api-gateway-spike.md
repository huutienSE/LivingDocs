# TECH-05 — API Gateway Technical Spike Result

> **Project:** LivingDocs — AI-Assisted Self-Updating Document System for Engineering Teams  
> **Jira:** [LIV-57 — TECH-05 — Perform API Gateway Technical Spike](https://huutiense25.atlassian.net/browse/LIV-57)  
> **Ngày thử nghiệm:** 21/10/2026 (giờ Việt Nam, UTC+07:00)  
> **Trạng thái tài liệu:** `EVIDENCE RECORDED — PENDING GIT COMMIT / PR REVIEW`  
> **Ứng viên được nhóm lựa chọn để thử nghiệm:** Spring Cloud Gateway **Server WebFlux**  
> **Phạm vi quyết định:** Technical Spike / PoC candidate; **không phải** quyết định kiến trúc production đã phê duyệt tại G2.

## 1. Mục tiêu và phạm vi

### 1.1. Mục tiêu

TECH-05 nhằm **kiểm chứng thực tế** khả năng sử dụng một API Gateway trong môi trường kỹ thuật hiện có của LivingDocs, trước khi mở rộng thành PoC nhiều downstream services. Theo Jira LIV-57, spike cần làm rõ:

- Khả năng khởi động Gateway trên máy phát triển.
- Cách định nghĩa route và cấu hình forwarding.
- Khả năng chuyển tiếp HTTP request đến Spring Boot Backend hiện có.
- Khả năng quan sát Gateway health và các route đã được nạp.
- Hành vi khi downstream không khả dụng.
- Mức độ phù hợp về công nghệ/cấu hình, các giới hạn và hướng triển khai tiếp theo.

### 1.2. Ngoài phạm vi

TECH-05 **không** triển khai đầy đủ nhiều demo microservices; không tự chốt kiến trúc service boundaries, tenant isolation, authentication/authorization, Kafka, Redis, Docker, Kubernetes hoặc production deployment. Những nội dung đó thuộc các task kỹ thuật tiếp theo và/hoặc thiết kế, ADR tại G2.

## 2. Phương án kỹ thuật và môi trường

### 2.1. Công nghệ được lựa chọn

| Thành phần | Lựa chọn / thông tin hiện có | Mức độ xác nhận |
| --- | --- | --- |
| Java | 21; log kiểm thử Spring Boot trước đó hiển thị `21.0.12.1` | Có bằng chứng từ backend test log |
| Spring Boot Backend Baseline | Spring Boot `4.1.1`, ứng dụng ở `backend/spring-boot-baseline/` | Đối chiếu `pom.xml` trên repository |
| Gateway candidate | Spring Cloud Gateway **Server WebFlux** | Định hướng nhóm đã chốt cho TECH-05 |
| Spring Cloud BOM | `2025.1.3` | Phiên bản theo cấu hình triển khai TECH-05; cần lưu `pom.xml` trong PR |
| Gateway runtime | Reactive / Reactor Netty | Đặc tính framework; chưa đo throughput hay latency |
| Hệ điều hành chạy thử | macOS | Theo terminal local của người thực hiện |
| Backend | `http://127.0.0.1:8080` | Đã thử bằng HTTP thực tế |
| Gateway | `http://127.0.0.1:8081` | Đã thử bằng HTTP thực tế |

> **Lưu ý về bằng chứng:** Log `curl` đã chứng minh Gateway đang chạy và chuyển tiếp request thành công. Báo cáo chưa được cung cấp đầu ra riêng của `mvn clean verify`, `java -version` tại Gateway, hoặc toàn bộ log startup; những phần đó không được tự đánh dấu là đã kiểm chứng.

### 2.2. Lý do chọn WebFlux làm ứng viên

- API Gateway có vai trò chủ yếu là tiếp nhận và chuyển tiếp network requests; mô hình reactive/non-blocking là ứng viên đáng kiểm chứng cho trách nhiệm này.
- Gateway chạy độc lập với Backend, nên không buộc Spring Boot MVC Backend hiện tại phải chuyển sang reactive.
- Dùng Java 21 và hệ sinh thái Spring của nhóm, thuận lợi cho việc duy trì và mở rộng PoC.
- Ưu tiên tái sử dụng module Gateway sau TECH-05, tránh viết một demo chỉ để bỏ đi.

Đây là **lý do lựa chọn ứng viên để kiểm thử**, không phải khẳng định WebFlux có hiệu năng tốt hơn Web MVC trong workload LivingDocs. TECH-05 chưa thực hiện benchmark đối chứng.

## 3. Kiến trúc thử nghiệm và cấu hình route

```text
HTTP Client / curl
       |
       | GET http://127.0.0.1:8081/backend/api/ping
       v
Spring Cloud Gateway Server WebFlux (:8081)
  Route ID: spring-boot-baseline
  Predicate: Path=/backend/**
  Filter: StripPrefix=1
  Target: http://127.0.0.1:8080
       |
       | GET /api/ping
       v
Spring Boot Baseline (:8080)
       |
       v
HTTP 200 — {"status":"ok"}
```

Route thực tế đã hiển thị qua `/actuator/gateway/routes`, xác nhận các thông số:

| Trường | Giá trị quan sát |
| --- | --- |
| `route_id` | `spring-boot-baseline` |
| Path predicate | `/backend/**` |
| Route filter | `StripPrefix parts = 1` |
| Downstream URI | `http://127.0.0.1:8080` |
| Route order | `0` |

**Cách cấu hình** tương ứng của Spring Cloud Gateway Server WebFlux 5.x trong `application.yaml`:

```yaml
server:
  address: 127.0.0.1
  port: ${GATEWAY_PORT:8081}

spring:
  application:
    name: livingdocs-api-gateway
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: spring-boot-baseline
              uri: ${GATEWAY_BACKEND_URI:http://127.0.0.1:8080}
              predicates:
                - Path=/backend/**
              filters:
                - StripPrefix=1

management:
  endpoint:
    gateway:
      access: read-only
  endpoints:
    web:
      exposure:
        include: health,info,gateway
```

> Đây là **mẫu cấu hình cho TECH-05** khớp với route quan sát. Khi tạo PR cần đối chiếu với nội dung `application.yaml` thực tế trên branch; log HTTP không tự chứng minh toàn bộ từng dòng YAML đã được commit.

## 4. Kịch bản thử nghiệm và kết quả thực tế

Các request được người triển khai thực hiện trên môi trường local. Thời điểm ghi trong HTTP `Date` là GMT/UTC; ngày thực hiện theo UTC+07:00 là **21/10/2026**.

| Test ID | Test case | Expected result | Actual result | Đánh giá |
| --- | --- | --- | --- | --- |
| `SPK-01` | `GET http://127.0.0.1:8080/api/ping` trực tiếp | Backend phản hồi HTTP 200, `{"status":"ok"}` | **HTTP 200**, body `{"status":"ok"}` | **PASS** |
| `SPK-02` | `GET http://127.0.0.1:8081/backend/api/ping` qua Gateway | Forward thành công, response khớp backend | **HTTP 200**, body `{"status":"ok"}` | **PASS** |
| `SPK-03` | `GET http://127.0.0.1:8081/actuator/health` | Gateway đang hoạt động | **HTTP 200**, `status=UP`; có `liveness` và `readiness` groups | **PASS** |
| `SPK-04` | `GET http://127.0.0.1:8081/actuator/gateway/routes` | Hiển thị route đã cấu hình | **HTTP 200**, tìm thấy `spring-boot-baseline`, `/backend/**`, `StripPrefix=1`, URI `:8080` | **PASS** |
| `SPK-05` | **Chủ động dừng backend**, sau đó gọi lại route qua Gateway | Quan sát được lỗi downstream để đánh giá failure behavior | **HTTP 500 Internal Server Error**, JSON lỗi có `path`, `status`, `requestId` | **OBSERVED — cần cải thiện/đánh giá** |
| `SPK-06` | Khởi động lại backend rồi gọi lại route | Route phục hồi khi downstream sẵn sàng | Chưa có log cung cấp | **NOT VERIFIED** |
| `SPK-07` | `./mvnw clean verify` riêng cho Gateway | Build/tests thành công | Chưa có output cung cấp | **NOT VERIFIED** |

### 4.1. Evidence E-01 — Backend trực tiếp

Request:

```bash
curl -i http://127.0.0.1:8080/api/ping
```

Response quan sát:

```http
HTTP/1.1 200
Content-Type: application/json
Content-Length: 15
Date: Tue, 20 Oct 2026 17:42:17 GMT

{"status":"ok"}
```

**Kết luận:** Spring Boot Backend Baseline hoạt động và có endpoint để Gateway sử dụng.

### 4.2. Evidence E-02 — HTTP forwarding qua Gateway

Request:

```bash
curl -i http://127.0.0.1:8081/backend/api/ping
```

Response quan sát:

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 15
Date: Tue, 20 Oct 2026 17:42:27 GMT

{"status":"ok"}
```

**Kết luận:** Gateway chuyển tiếp request đến downstream và bảo toàn response body trong kịch bản đã thử. Đây là bằng chứng trực tiếp cho Acceptance Criterion “At least one route is technically validated”.

### 4.3. Evidence E-03 — Gateway health visibility

Request:

```bash
curl -i http://127.0.0.1:8081/actuator/health
```

Response quan sát:

```http
HTTP/1.1 200 OK
Content-Type: application/vnd.spring-boot.actuator.v3+json

{"groups":["liveness","readiness"],"status":"UP"}
```

**Kết luận:** Health endpoint của Gateway hoạt động. `UP` phản ánh trạng thái health mà Gateway báo tại thời điểm kiểm tra, **không chứng minh mọi downstream đều sẵn sàng**.

### 4.4. Evidence E-04 — Route visibility

Request:

```bash
curl -i http://127.0.0.1:8081/actuator/gateway/routes
```

Response trích xuất từ log:

```json
[
  {
    "route_id": "spring-boot-baseline",
    "filters": ["[[StripPrefix parts = 1], order = 1]"],
    "uri": "http://127.0.0.1:8080",
    "order": 0
  }
]
```

> Trích xuất chỉ giữ các trường cần cho đánh giá route; response gốc còn trường `predicate` với biểu diễn nội bộ của Spring và thông tin `Paths: [/backend/**]`.

**Kết luận:** Gateway đã nạp được route được khai báo; Path predicate và `StripPrefix` đúng với kịch bản.

### 4.5. Evidence E-05 — Downstream unavailable

**Điều kiện thử nghiệm được người triển khai xác nhận:** Sau khi các request trên hoạt động, **chủ động dừng Spring Boot Backend** đang lắng nghe cổng `8080`, giữ Gateway tại cổng `8081` hoạt động rồi gọi lại route.

Request:

```bash
curl -i http://127.0.0.1:8081/backend/api/ping
```

Response quan sát:

```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json
Content-Length: 137

{"timestamp":"2026-10-20T17:43:37.714Z","path":"/backend/api/ping","status":500,"error":"Internal Server Error","requestId":"ccac37c1-4"}
```

**Diễn giải kết quả:**

- Gateway **vẫn có thể nhận request** trong khi downstream đã dừng.
- Route **không thể trả kết quả nghiệp vụ thành công** khi downstream không khả dụng.
- HTTP **500 đã được quan sát**, nhưng **không phải tiêu chuẩn response mong muốn cho production**. Trong thiết kế sau này cần xác định chính sách chuyển đổi lỗi kết nối sang response phù hợp (ví dụ 502/503 tùy nguyên nhân), không lộ thông tin nội bộ.
- Chưa có Gateway exception stack trace tại thời điểm này, nên **không khẳng định đã xác minh root cause ở mức exception**. Sự kiện backend bị dừng là điều kiện thử nghiệm đã được xác nhận.

**Đánh giá:** Negative-path behavior **đã được ghi nhận**; còn yêu cầu hoàn thiện error handling của PoC/hệ thống chính thức cần được xem xét ở task tiếp theo.

## 5. Kết quả đánh giá tính khả thi

| Tiêu chí | Kết luận | Evidence |
| --- | --- | --- |
| Startup và local integration | **Khả thi trong kịch bản đã thử** | Gateway trả HTTP và exposes Actuator; E-02/E-03 |
| Route definition / Path matching | **Đã kiểm chứng** | E-04 |
| Request forwarding | **Đã kiểm chứng** | E-01/E-02 |
| Health visibility | **Đã kiểm chứng ở Gateway level** | E-03 |
| Downstream failure behavior | **Đã quan sát; cần hoàn thiện policy** | E-05 |
| Build reproducibility | **Chưa xác minh độc lập qua log được cung cấp** | Cần lưu `mvn clean verify` / startup logs |
| Security, NFR performance và availability | **Ngoài phạm vi xác nhận của spike này** | Cần ARCH/G2, INT/QA và performance tests |

### 5.1. Các giới hạn đã xác định

1. Chỉ thử một route đến Spring Boot baseline, chưa thử nhiều downstream độc lập.
2. Chưa đo throughput, độ trễ p95, 20 concurrent PR processing hoặc khả năng đạt service availability 99,5% theo SRS.
3. Chưa kiểm chứng timeout, retry, circuit breaker, rate limiting hoặc cấu hình error mapping phù hợp khi downstream không sẵn sàng.
4. Chưa thiết kế authentication boundary, tenant/workspace/repository authorization, secure propagation của identity hoặc cách loại bỏ client-supplied identity headers.
5. Chưa tích hợp GitHub event handling, AI processing, Kafka, Redis, Docker hoặc Kubernetes; TECH-05 không tự đưa các công nghệ này vào phạm vi bắt buộc.
6. Việc expose `/actuator/gateway/routes` chỉ dùng cho thử nghiệm nội bộ; cần hạn chế quyền truy cập trong môi trường triển khai thật.
7. Chưa có benchmark đối chứng với Spring Cloud Gateway Server Web MVC; không suy luận ưu thế hiệu năng từ kết quả routing đơn giản.
8. Phần mềm phụ thuộc vào việc chọn đúng phiên bản Spring Boot/Spring Cloud và đúng namespace cấu hình cho **Gateway Server WebFlux**.

## 6. Recommendation và quyết định ở mức spike

**Recommendation:** Tiếp tục sử dụng **Spring Cloud Gateway Server WebFlux** làm **ứng viên triển khai Gateway PoC** cho TECH-06 và các task liên quan, trên cơ sở:

- Gateway đã hoạt động thực tế với downstream Spring Boot đang có.
- Path routing, forwarding và health visibility đều có evidence PASS.
- Vấn đề HTTP 500 khi backend dừng là hạn chế được ghi nhận để tiếp tục thiết kế/test; không làm mất giá trị của kết quả chứng minh khả năng định tuyến cơ bản.
- Dự kiến có thể tái sử dụng module cấu hình Gateway cho PoC tiếp theo, giảm chi phí làm lại chỉ để demo.

**Không kết luận** Gateway WebFlux đã đạt các chỉ tiêu performance, availability hoặc security của SRS. Quyết định Gateway production, service boundaries, deployment topology, authentication boundary và các ADR liên quan phải được xét ở **G2**.

## 7. Follow-up / Next implementation step

| Ưu tiên | Hành động | Task / Giai đoạn |
| --- | --- | --- |
| Ngay sau báo cáo | Đối chiếu `pom.xml`, `application.yaml` đang chạy; bổ sung `README.md`, build logs và bằng chứng HTTP; commit và mở PR liên kết Jira LIV-57 | TECH-05 |
| Tiếp theo | Mở rộng Gateway PoC và chạy thử với nhiều downstream service độc lập | TECH-06 (LIV-60) |
| Tiếp theo | Tạo Demo Microservice A/B | TECH-07 (LIV-53), TECH-08 (LIV-58) |
| Tiếp theo | Kiểm chứng đa route, unavailable/invalid route, health behavior | TECH-09 (LIV-55), TECH-10 (LIV-56) |
| Tiếp theo | Tài liệu vận hành PoC và kiểm chứng độc lập bởi thành viên khác | TECH-12 (LIV-62), TECH-13 (LIV-64) |
| Architecture | Quyết định trách nhiệm bảo mật, identity/tenant propagation, policy về lỗi, NFR, topology và adoption cuối cùng | G2 / ADR |

## 8. Đối chiếu Acceptance Criteria Jira LIV-57

| Acceptance Criterion | Trạng thái hiện tại | Diễn giải |
| --- | --- | --- |
| Gateway approach has actually been run, not only researched | **PASS** | HTTP request qua Gateway và Actuator thành công |
| At least one route is technically validated | **PASS** | `:8081/backend/api/ping` → `:8080/api/ping`, HTTP 200 |
| Configuration approach is documented | **DOCUMENTED — PENDING COMMIT** | Mẫu `application.yaml`, predicate/filter/URI tại mục 3 |
| Relevant limitations are documented | **DOCUMENTED — PENDING COMMIT** | Mục 4.5 và 5.1 |
| Recommendation is recorded | **DOCUMENTED — PENDING COMMIT** | Mục 6 |
| Next implementation step is clear | **DOCUMENTED — PENDING COMMIT** | Mục 7 |

**Tổng kết TECH-05:** Phần **thực nghiệm routing cơ bản đã đạt**; báo cáo đã ghi nhận evidence được người triển khai cung cấp. **Chưa đổi Jira LIV-57 sang Done**: cần đưa mã nguồn/configuration và báo cáo này lên GitHub, bổ sung evidence startup/build nếu cần và hoàn tất quy trình PR/review của nhóm.

## 9. Source và traceability

- **Jira task:** [LIV-57 — TECH-05 — Perform API Gateway Technical Spike](https://huutiense25.atlassian.net/browse/LIV-57).
- **Repository chính:** [huutienSE/LivingDocs](https://github.com/huutienSE/LivingDocs).
- **Yêu cầu hệ thống:** [REQ-07 — LivingDocs SRS v1.0 Official Candidate](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/3702801) — hiện chưa phải G1 approved baseline; đặc biệt `CON-008` không tự bắt buộc một topology hoặc công nghệ Gateway chính thức.
- **Nguồn evidence thử nghiệm:** Các response `curl -i` được người triển khai cung cấp trực tiếp trong cuộc trao đổi, kèm xác nhận đã chủ động dừng Backend trước negative-path test. **Không phải CI evidence hoặc peer-reviewed test report**.

---

**Document end — TECH-05 / LIV-57.**
