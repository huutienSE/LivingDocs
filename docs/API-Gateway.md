# API Gateway trong LivingDocs

Là **cổng tiếp nhận chung của các API bên ngoài**, tương tự quầy tiếp nhận của một tòa nhà. Client gửi yêu cầu đến Gateway; Gateway kiểm tra, chuyển đến đúng service rồi trả kết quả về client. Service vẫn quyết định nội dung nghiệp vụ.

```text
Client ──► Gateway :8081 ──┬──► S01: Danh tính, tenant, workspace
                          ├──► S02: GitHub, repository
                          ├──► S03: Analysis, drift
                          └──► S04: Document, review, approval
```

**Khác Eureka:** Eureka cung cấp địa chỉ service; Gateway trực tiếp nằm trên đường đi của request nghiệp vụ. LivingDocs hiện không dùng Eureka.

> Đối chiếu mã ngày 07/11/2026. Các ví dụ minh họa cách xử lý, không phải kết quả nghiệm thu E2E. Các cấu trúc dữ liệu dưới đây được rút gọn để dễ đọc.

# Kiến trúc cốt lõi

LivingDocs dùng **Spring Cloud Gateway Server WebFlux**, tích hợp Spring Security. Ba cơ chế chính:

1. **Security — Có được đi tiếp không?** Kiểm tra JWT và các quy tắc truy cập tại biên.
2. **Predicate — Request thuộc route nào?** Kiểm tra điều kiện như Path và HTTP Method.
3. **Filter — Cần xử lý gì?** Gắn thông tin vào request/response, chuyển tiếp HTTP, xử lý lỗi.

**Route** là quy tắc ghép điều kiện khớp request với địa chỉ service đích. **Downstream** là service mà Gateway gọi, ví dụ S04.

Gateway vừa là HTTP server đối với client, vừa là HTTP client đối với downstream. Có hai chặng mạng riêng:

```text
Client ↔ Gateway     Gateway ↔ Service
```

Vì vậy, Gateway vẫn có thể hoạt động trong khi S04 đã dừng. Khi đó nó nhận được request nhưng không chuyển tiếp thành công.

# Kiến trúc bên dưới và dữ liệu xử lý

## 1. Route — Quy tắc dùng chung cho nhiều request

Ví dụ rút gọn từ nhóm route S04 trong `application.yaml`:

```yaml
- id: s04-get-api-099-110
  uri: ${S04_SERVICE_URI:http://127.0.0.1:8084}
  predicates:
    - Path=/api/v1/documents/{documentId}
    - Method=GET
```

Spring chuyển cấu hình thành đối tượng Route có thể dùng khi xử lý:

```text
Route
 ├── id        : Nhãn nhận diện quy tắc
 ├── uri       : Địa chỉ service đích
 ├── predicate : Điều kiện khớp request
 ├── filters   : Các filter riêng của route, nếu có
 └── order     : Thứ tự ưu tiên
```

Gateway không cần đọc lại YAML từ đầu cho mỗi request. `099-110` chỉ là cách đặt tên; không tự mở một khoảng API.

## 2. ServerWebExchange — Hồ sơ của từng request

Đối tượng này chứa thông tin cho **một lần trao đổi HTTP** và được các bước xử lý truyền tiếp:

```text
ServerWebExchange
 ├── request    : Method, URL, headers, body đầu vào
 ├── response   : Status, headers, body chuẩn bị trả về
 └── attributes : Route đã chọn, URL downstream, dữ liệu nội bộ...
```

Trong code, `exchange.getRequest()` đọc request; `exchange.getResponse()` chuẩn bị response. Attributes dùng giữa các bước trong Gateway, không tự trở thành HTTP header gửi sang service.

## 3. Hai vòng filter

```text
WebFlux WebFilter chain
 ├── CorrelationIdWebFilter
 └── Spring Security: CORS → Authentication → Authorization
                            │
                       Chọn route
                            │
Gateway filter chain        ▼
 ├── GatewayDownstreamErrorFilter
 └── Các filter framework phục vụ HTTP forwarding
```

- **WebFilter:** xử lý đầu vào trước khi chọn route. Correlation filter thuộc nhóm này.
- **GlobalFilter:** áp dụng chung khi xử lý các route. Downstream error filter thuộc nhóm này.
- **GatewayFilter riêng:** áp dụng cho route khai báo nó; YAML hiện tại chưa có `filters:` riêng cho các route.

Hai filter tự viết cùng có `HIGHEST_PRECEDENCE` nhưng được xếp trong **hai chuỗi khác nhau**. “Global” không có nghĩa bắt mọi lỗi: request bị security trả `401` chưa đi vào Gateway filter chain.

# Chi tiết toàn bộ các luồng hoạt động

Dùng một request xuyên suốt: người dùng muốn xem tài liệu `doc-1`.

```http
GET /api/v1/documents/doc-1
Authorization: Bearer <access-token>
X-Correlation-ID: req-001
```

`doc-1` là ID minh họa, cũng được dùng trong test Gateway; service thật có thể yêu cầu định dạng khác.

```text
Client gửi request
        │
        ▼
 [1] Correlation ID ── Kiểm tra/tạo mã theo dõi
        │
 [2] CORS ─────────── Kiểm chính sách trình duyệt
        │             Preflight hợp lệ: trả lời ngay
 [3] Authentication ─ Xác minh JWT
        │             Token sai: 401
 [4] Authorization ── Kiểm quyền đi qua Gateway
        │             Đã xác thực, gọi path cấm: 403
 [5] Predicate ────── Khớp Path + Method, chọn S04
        │             Không có route/handler: 404
 [6] Forwarding ───── Gateway filters và HTTP client gọi S04
        │             Lỗi kết nối/timeout: nhánh 503/504
 [7] Service ──────── Kiểm quyền và xử lý nghiệp vụ
        │
 [8] Response ─────── Qua Gateway về client
```

**Thứ tự cần nhớ:** security đi trước route matching. URL không tồn tại vẫn có thể trả `401` nếu request chưa vượt qua security.

## Luồng 1. Gắn mã theo dõi — Correlation ID

**Mục đích:** đối chiếu cùng request giữa client, Gateway và service. **Class:** `CorrelationIdWebFilter`.

Quy trình:

1. Đọc `X-Correlation-ID` từ request.
2. Kiểm định dạng: 1–64 ký tự; bắt đầu bằng chữ/số; phần sau cho phép thêm `.`, `_`, `-`.
3. Có và hợp lệ → giữ lại. Thiếu hoặc sai → tạo UUID mới.
4. Gắn ID vào request đi tiếp và response trả về.
5. Gọi `chain.filter(...)` để bước sau xử lý.

```text
Client gửi req-001 ──► Gateway giữ req-001 ──► S04 nhận req-001
Client nhận req-001 ◄─ Gateway trả response
```

Header response được gắn **trước** khi chuyển bước. Vì vậy, security từ chối sau đó thì response `401` vẫn có ID để tra cứu. `request.mutate()` tạo phiên bản request đã điều chỉnh cho lần xử lý này, không sửa một request dùng chung.

**Giới hạn:** ID không xác thực danh tính, không chắc duy nhất nếu client dùng lại. Muốn tra cứu xuyên service, hệ thống log và downstream cũng phải sử dụng ID.

## Luồng 2. Trình duyệt xin phép gọi API — CORS

**Bài toán:** frontend `http://localhost:3000` gọi Gateway `http://localhost:8081`. Khác port nên khác origin. Origin gồm **scheme + host + port**.

**Class cấu hình:** `GatewayCorsConfig`, được Spring Security sử dụng qua `.cors(...)`.

Trước request có Bearer token, trình duyệt có thể gửi preflight:

```http
OPTIONS /api/v1/documents/doc-1
Origin: http://localhost:3000
Access-Control-Request-Method: GET
Access-Control-Request-Headers: authorization,x-correlation-id
```

Nghĩa là: “Trang web này có được gửi GET với những header này không?”

Quy trình:

1. Gateway tìm chính sách CORS; dự án đăng ký cho `/api/**`.
2. Kiểm tra Origin, method và các header dự kiến.
3. Preflight hợp lệ → trả header cho phép, kết thúc tại Gateway; không cần JWT, không gọi S04.
4. Trình duyệt gửi GET thật; GET vẫn phải qua JWT và các bước sau.
5. Origin không được phép → bị từ chối, test hiện có kỳ vọng `403`.

Việc xử lý preflight trước authentication theo cơ chế tích hợp [Spring Security CORS](https://docs.spring.io/spring-security/reference/reactive/integrations/cors.html).

| Cấu hình hiện tại | Giá trị |
|---|---|
| Origin | Biến `GATEWAY_ALLOWED_ORIGINS`, mặc định `http://localhost:3000` |
| Methods | GET, POST, PUT, PATCH, DELETE, OPTIONS |
| Allowed request headers | Authorization, Content-Type, Accept, X-Correlation-ID, If-Match, Idempotency-Key |
| Exposed response headers | X-Correlation-ID, ETag |
| Allow credentials / max-age | false / 3600 giây |

**Allowed** cho biết browser được gửi header nào; **exposed** cho biết JavaScript được đọc thêm response header nào. Max-age cache kết quả preflight, không cache dữ liệu API.

Preflight thành công chưa chứng minh endpoint tồn tại hoặc người dùng có quyền. CORS cho phép DELETE cũng chưa mở một route DELETE. `allowCredentials=false` chưa hỗ trợ CORS có credentials như cookie cross-origin; nó không phải filter xóa cookie.

CORS phục vụ chính sách của trình duyệt, không thay thế JWT cho mọi HTTP client.

## Luồng 3. Xác minh người gọi — JWT Authentication

**Bài toán:** client đưa token và nói “tôi là người dùng này”; Gateway phải xác minh lời khẳng định đó.

**Cấu hình:** `GatewaySecurityConfig` bật OAuth2 Resource Server. Spring Security và `ReactiveJwtDecoder` thực hiện xác minh, dựa trên:

```yaml
jwt:
  issuer-uri: ${JWT_ISSUER_URI}
  audiences:
    - ${JWT_AUDIENCE}
```

JWT có dạng `header.payload.signature`: thông tin token, các claim và chữ ký. Đọc được payload chưa chứng minh token đáng tin; phải kiểm chữ ký.

Quy trình chính:

1. Trích token từ `Authorization: Bearer ...`.
2. Decoder tìm thông tin issuer và JWKS (danh sách khóa công khai), rồi dùng khóa tin cậy để kiểm chữ ký.
3. Kiểm thời gian hiệu lực, issuer và audience theo cấu hình.
4. Hợp lệ → tạo `Authentication`, cung cấp danh tính qua ngữ cảnh security reactive.
5. Token bị xác định không hợp lệ → xác thực thất bại, thông thường trả `401`. API bảo vệ không có token cũng nhận `401`.

```text
sub : Token đại diện cho ai?        iss : Ai phát hành?
aud : Token dành cho đối tượng nào? exp : Hết hạn khi nào?
nbf : Chưa được dùng trước thời điểm nào?
```

Với khóa bất đối xứng, issuer giữ private key để ký; Gateway dùng public key để xác minh. Gateway không cần biết mật khẩu người dùng cho mỗi request. Cơ chế framework được mô tả trong [Reactive JWT Resource Server](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html).

**Giới hạn hiện tại:** chưa có logic Gateway tra DB/Redis để kiểm trạng thái tài khoản hoặc thu hồi token tức thời. README cũng chưa có bằng chứng JWT thật của S01 tích hợp E2E; test Gateway dùng decoder mock.

## Luồng 4. Cho phép đi qua cổng — Authorization

**Authentication:** người gọi là ai? **Authorization:** request này có được đi tiếp không?

`GatewaySecurityConfig` khai báo ba nhóm quy tắc:

| Nhóm request | Quy tắc |
|---|---|
| `/internal/v1`, `/actuator`, `/v3/api-docs`, `/swagger-ui` và các path con tương ứng | `denyAll()` — cấm |
| POST `/api/v1/auth/register`, POST `/api/v1/auth/login` | `permitAll()` — không bắt buộc xác thực |
| Phần còn lại | `authenticated()` — phải được xác thực |

```text
GET documents, không token   → 401
GET internal, token hợp lệ   → 403
POST login, không token      → Đi tiếp đến chọn route
```

Public chỉ áp dụng đúng hai POST endpoint, không áp dụng mọi API cùng nhóm route. Nếu client gửi Bearer token sai đến endpoint public, lớp authentication vẫn có thể từ chối. Request vào path cấm nhưng chưa xác thực có thể nhận `401` trước, không phải luôn `403`.

**Giới hạn trách nhiệm:** Gateway chưa có logic kiểm role Staff/Manager, membership hay quyền từng tài liệu.

```text
Gateway: Token hợp lệ, request được đi qua cổng.
S04:     Người này có quyền đọc doc-1 trong tenant/workspace này không?
```

Theo thiết kế, service vẫn phải xác minh JWT và kiểm quyền tài nguyên/trạng thái nghiệp vụ. Đây là yêu cầu đối với service, không phải bằng chứng mọi kiểm tra đã triển khai.

## Luồng 5. Chọn nơi xử lý — Predicate Matching

**Thành phần framework:** `RoutePredicateHandlerMapping`.

Quy trình:

1. Lấy các Route đã chuẩn bị từ cấu hình.
2. Kiểm tra predicate theo thứ tự route, chọn route đầu tiên phù hợp.
3. Lưu route vào attributes của exchange và chuyển đến `FilteringWebHandler`.
4. Không có route/handler phù hợp → `404`, sau khi request đã vượt security.

Đây là bước chọn route trước khi chạy filter chain trong [Gateway — How It Works](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/how-it-works.html).

Với ví dụ route đầu tài liệu:

```text
Path khớp /api/v1/documents/{documentId} VÀ Method bằng GET

GET    /api/v1/documents/doc-1          → Khớp
DELETE /api/v1/documents/doc-1          → Không khớp ví dụ này
GET    /api/v1/documents/doc-1/private  → Không khớp ví dụ này
```

Nhiều mẫu trong `Path=...,...` kết hợp bằng **HOẶC**; các predicate Path và Method trong cùng route kết hợp bằng **VÀ**. `{documentId}` chưa kiểm ID đúng định dạng nghiệp vụ, tài liệu tồn tại hoặc quyền sở hữu. Body/query cũng chưa được validate bởi hai predicate này.

Dự án dùng allowlist Path + Method cụ thể thay vì mở `/api/v1/**`. Hiện có 19 nhóm route; README ghi nhận 91 cặp Method + Path. Có route chưa có nghĩa đã có controller hoạt động.

## Luồng 6. Chạy filter và chuyển tiếp HTTP

**Thành phần framework:** `FilteringWebHandler`.

Quy trình:

1. Đọc route từ exchange.
2. Ghép GlobalFilter với filter riêng của route, nếu có; sắp xếp theo order, số nhỏ ưu tiên cao hơn.
3. Chạy chain; `GatewayDownstreamErrorFilter` bao quanh xử lý phía trong để nhận lỗi.
4. Các filter HTTP của framework tạo URL đích và gọi downstream.

Cách kết hợp và sắp xếp filter theo [Gateway — Global Filters](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html).

```text
RouteToRequestUrlFilter  → Chuẩn bị URL downstream
NettyRoutingFilter      → Gửi HTTP đến service
NettyWriteResponseFilter → Ghi body phản hồi về client
```

```text
Đầu vào: GET http://localhost:8081/api/v1/documents/doc-1?view=full
Đầu ra:  GET http://127.0.0.1:8084/api/v1/documents/doc-1?view=full
```

Không có `StripPrefix`, nên giữ `/api/v1/...`. Method, query, body và header ứng dụng liên quan được chuyển tiếp qua HTTP client; framework xử lý các header giao thức theo vai trò proxy.

Gateway không tự parse DTO nghiệp vụ rồi tạo lại JSON. Hiện không có filter tự viết để thay Bearer token bằng danh tính khác. `Authentication` không tự truyền xuyên mạng: service nhận thông tin qua HTTP và tự xác minh.

## Luồng 7. Chuyển response của service về client

Nếu service đã trả HTTP response, Gateway chuyển phản hồi đó về client, qua các xử lý header liên quan của Gateway/security/CORS.

```text
S04 trả 200 + JSON → Gateway chuyển response thành công
S04 trả 403       → Gateway chuyển response từ chối quyền
S04 trả 404       → Gateway chuyển response không tìm thấy
S04 trả 500       → Gateway chuyển response lỗi của service
```

Correlation ID đã được chuẩn bị từ đầu; CORS expose nó cho frontend khi áp dụng. Chưa có filter bọc lại mọi JSON hoặc chuẩn hóa mọi lỗi.

**Nguyên lý quan trọng:** service trả HTTP `500` vẫn là **nhận được response**. Nó khác với không kết nối được service và không tự kích hoạt `onErrorResume` chỉ vì status là 500.

## Luồng 8. Xử lý lỗi kết nối hoặc timeout downstream

**Class:** `GatewayDownstreamErrorFilter`.

```java
return chain.filter(exchange).onErrorResume(error -> {
    HttpStatus status = classify(error);
    if (status == null || exchange.getResponse().isCommitted()) {
        return Mono.error(error);
    }
    return writeError(exchange, status);
});
```

Quy trình:

1. Cho phần còn lại của chain xử lý.
2. Nếu pipeline phát sinh lỗi, `onErrorResume` nhận exception.
3. Duyệt exception và các `cause` bên trong để phân loại.
4. Nhận diện được và response chưa commit → tạo response lỗi.
5. Không nhận diện được hoặc đã commit → chuyển lỗi tiếp.

| Nguyên nhân được nhận diện | Status / code |
|---|---|
| ConnectException, UnknownHostException, NoRouteToHostException | 503 / `UPSTREAM_UNAVAILABLE` |
| ReadTimeoutException, ConnectTimeoutException, java.util.concurrent.TimeoutException | 504 / `UPSTREAM_TIMEOUT` |
| ResponseStatusException có status 504 | 504 / `UPSTREAM_TIMEOUT` |

`NoRouteToHostException` là lỗi đường mạng đến host, không phải lỗi khớp route API.

Filter tạo `Content-Type: application/problem+json`, ví dụ:

```json
{
  "type": "about:blank",
  "title": "Gateway Timeout",
  "status": 504,
  "detail": "Downstream service did not respond in time",
  "instance": "urn:uuid:05464120-8f17-4787-b55c-e814a504caa8",
  "code": "UPSTREAM_TIMEOUT",
  "correlationId": "req-001"
}
```

`code` để client phân biệt lỗi; `correlationId` để đối chiếu request; `instance` định danh lần lỗi. JSON được mã hóa UTF-8, bọc thành DataBuffer (vùng đệm dữ liệu) và ghi vào response.

**Vì sao kiểm `isCommitted()`?** Nếu status/headers đã gửi, Gateway không thể tùy ý đổi chúng. Đã gửi `200` và một phần body rồi mất kết nối thì không thể bảo đảm thay bằng một `504` hoàn chỉnh.

Filter này không chuẩn hóa `401` của security hoặc `404` do không khớp route: chúng xảy ra ngoài phạm vi xử lý downstream của nó.

# Cơ chế bất đồng bộ và thứ tự filter

WebFlux/Reactor cho phép phối hợp I/O non-blocking: khi chờ S04, luồng xử lý sự kiện (event loop) có thể xử lý việc khác thay vì giữ một thread đứng chờ riêng cho request.

- `chain.filter(exchange)`: trả pipeline xử lý phần sau; chưa có nghĩa service đã trả xong.
- `Mono<Void>`: tác vụ hoàn tất hoặc lỗi, không phát ra giá trị nghiệp vụ. HTTP response vẫn có thể có body.
- `.then(...)`: nối tác vụ chạy sau khi phần trước hoàn tất thành công.
- `.onErrorResume(...)`: chọn nhánh xử lý khi có lỗi.

```text
Filter A: trước → Filter B: trước → Gọi service
Filter A: sau   ← Filter B: sau   ← Nhận kết quả
```

Phần “sau” phải được nối đúng pipeline reactive. Chỉ viết code ở dòng sau `chain.filter()` không có nghĩa code đó chờ mạng xong. Tránh `.block()`, `Thread.sleep()` và I/O blocking trong filter. Non-blocking chưa phải kết luận hiệu năng đã benchmark.

# Timeout và các header cập nhật an toàn

## 1. Hai loại timeout

```yaml
httpclient:
  connect-timeout: ${GATEWAY_CONNECT_TIMEOUT_MS:2000}
  response-timeout: ${GATEWAY_RESPONSE_TIMEOUT:5s}
```

- **Connect timeout:** giới hạn chờ thiết lập kết nối.
- **Response timeout:** giới hạn chờ phản hồi theo cơ chế Gateway; không bảo đảm toàn bộ body luôn tải xong trong 5 giây.

Kết nối bị từ chối có thể thất bại ngay → 503; connect timeout được filter nhận diện → 504. Không cộng máy móc thành “mọi request tối đa 7 giây”. Đây là giá trị khởi đầu, chưa phải SLA được đo.

**Timeout không phải rollback:** service có thể đã ghi dữ liệu nhưng phản hồi chậm khiến client nhận 504. Gửi lại thao tác ghi cần chính sách idempotency; Gateway hiện chưa khai báo Retry filter.

## 2. ETag, If-Match và Idempotency-Key

| Header | Mục đích | Gateway hiện làm gì? |
|---|---|---|
| ETag | Nhận diện phiên bản dữ liệu | Cho frontend đọc header do service trả |
| If-Match | Yêu cầu thao tác trên phiên bản đã xem | Cho phép gửi và chuyển tiếp |
| Idempotency-Key | Giúp service nhận diện lần gửi lại cùng ý định | Cho phép gửi và chuyển tiếp |

Service phải so sánh revision và thực thi idempotency. Có tên header trong CORS chưa chứng minh các cơ chế đó đã hoạt động.

# Cấu hình vận hành cần nhớ

1. **Khởi động:** `GatewayApplication` gọi `SpringApplication.run(...)`; Spring đọc cấu hình, tạo bean, chuẩn bị route/HTTP client rồi mở listener. Các phương thức `@Bean` không xây lại toàn bộ cấu hình cho mỗi request.
2. **File môi trường:** `optional:file:./.env[.properties]` đọc `.env` theo định dạng properties tại working directory. `optional` không tự cung cấp issuer/audience còn thiếu.
3. **Cổng:** API mặc định 8081; management 9091, expose `health,info`. Chưa expose toàn bộ actuator/Prometheus.
4. **Địa chỉ service:** Gateway chạy trên host dùng `127.0.0.1` với port S01/S02/S03/S04 lần lượt 8082/8083/8085/8084. Trong Compose dùng DNS service với port 8080; localhost trong container là chính container đó.
5. **Management trong Docker:** YAML cố định `management.server.address: 127.0.0.1`, chưa sử dụng biến `GATEWAY_MANAGEMENT_ADDRESS` từ Compose. Không mặc định publish 9091 sẽ truy cập được. Tách port cũng không tự tạo ngoại lệ anonymous cho rule security chặn `/actuator/**`.
6. **Security hiện tại:** tắt HTTP Basic, form login và CSRF mặc định. Luồng cookie refresh/logout chưa mở theo baseline, cần thiết kế CSRF/session riêng. GitHub webhook cũng chưa public khi chưa hoàn tất cơ chế bảo vệ tương ứng.
7. **Kubernetes tương lai:** gọi DNS Kubernetes Service để nền tảng phân phối kết nối. Gateway vẫn cần quy tắc chọn service theo API. Hiện không dùng Eureka hoặc `lb://`; không proxy công khai S05 hay `/internal/v1/**`.

Health Gateway `UP` không chứng minh downstream, JWT issuer hoặc nghiệp vụ hoạt động. Cấu hình hiện tại cũng chưa cung cấp bằng chứng nghiệm thu HTTPS/TLS staging/production.

# Phân biệt lỗi và phạm vi kiểm chứng

| Kết quả | Nơi có thể tạo ra / điều cần kiểm tra |
|---|---|
| 401 | Security Gateway hoặc service: kiểm token/hợp đồng xác thực |
| 403 | CORS, authorization Gateway hoặc service: kiểm origin/path/quyền |
| 404 | Không khớp route hoặc downstream không có controller/resource |
| 412 | Service hỗ trợ concurrency guard: If-Match không còn khớp |
| 503 + code của error filter | Lỗi downstream không khả dụng được Gateway nhận diện |
| 504 + code của error filter | Timeout downstream được Gateway nhận diện |

Không chỉ nhìn status để kết luận lớp gây lỗi; kết hợp body, correlation ID và log.

**Test hiện có:** `GatewayFoundationTest` kiểm security/CORS/correlation; `GatewayRoutingIntegrationTest` kiểm forwarding đến stub S01/S02/S04; `GatewayDownstreamFailureIntegrationTest` kiểm 503/504. Decoder JWT được mock, nên chưa kiểm chữ ký/JWKS thật. File `GatewayCorsPreflightTest.java` hiện bị comment toàn bộ. Validator route/OpenAPI chỉ là kiểm tra tĩnh.

**Chưa có logic/cấu hình tại Gateway đã đọc:** rate limiting, circuit breaker, Retry filter, blacklist token qua Redis, kiểm quyền tài nguyên chi tiết, kiểm body theo OpenAPI, gom dữ liệu nhiều service và chuẩn hóa mọi response.

Route có sẵn không chứng minh controller đã có; test Gateway độc lập không chứng minh JWT thật hoặc nghiệp vụ E2E. Tài liệu này được đối chiếu bằng đọc mã/test, không báo cáo một lần chạy test mới.

# Ba nguyên lý cần nhớ

1. **Security quyết định được đi tiếp; predicate quyết định đi đến đâu.** Khớp URL chưa đủ để đọc tài liệu.
2. **WebFilter ở đầu vào; Gateway filters xử lý route.** Error filter downstream không bắt mọi lỗi của ứng dụng.
3. **Gateway quản lý chặng HTTP; service quyết định nghiệp vụ.** Token hợp lệ chưa chứng minh có quyền trên tài nguyên.

# Nguồn đối chiếu

- Vai trò và trạng thái baseline: [README Gateway](C:/Users/admin/Documents/LivingDocs/LivingDocs/infrastructure/api-gateway/README.md).
- Route/JWT/timeout: [application.yaml](C:/Users/admin/Documents/LivingDocs/LivingDocs/infrastructure/api-gateway/src/main/resources/application.yaml).
- Cơ chế truy cập: [GatewaySecurityConfig.java](C:/Users/admin/Documents/LivingDocs/LivingDocs/infrastructure/api-gateway/src/main/java/com/livingdocs/gateway/config/GatewaySecurityConfig.java); chính sách trình duyệt: [GatewayCorsConfig.java](C:/Users/admin/Documents/LivingDocs/LivingDocs/infrastructure/api-gateway/src/main/java/com/livingdocs/gateway/config/GatewayCorsConfig.java).
- Mã theo dõi: [CorrelationIdWebFilter.java](C:/Users/admin/Documents/LivingDocs/LivingDocs/infrastructure/api-gateway/src/main/java/com/livingdocs/gateway/filter/CorrelationIdWebFilter.java); lỗi downstream: [GatewayDownstreamErrorFilter.java](C:/Users/admin/Documents/LivingDocs/LivingDocs/infrastructure/api-gateway/src/main/java/com/livingdocs/gateway/filter/GatewayDownstreamErrorFilter.java).
- Giới hạn tích hợp: [LIV-133 contract alignment](C:/Users/admin/Documents/LivingDocs/LivingDocs/docs/implementation/LIV-133-contract-alignment.md).

Phần framework còn được đối chiếu với mã nguồn thư viện Spring trên máy; các liên kết tài liệu chính thức được đặt tại cơ chế tương ứng ở trên.
