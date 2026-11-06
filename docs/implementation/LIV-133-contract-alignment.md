# LIV-133 — Đối chiếu API Staff Review giữa FE, Gateway và S04

**Ngày kiểm tra:** 06/11/2026  
**Nguồn:** PR #15 (LIV-119), PR #20 (LIV-118), Gateway trong `develop`, LD-API-001 v0.4.  
**Mục đích:** ghi nhận khoảng trống tích hợp, không phê duyệt thay owner hoặc tuyên bố E2E PASS.

## 1. Bảng đối chiếu

| API | FE (PR #15) | Gateway (`develop`) | S04 (PR #20) | Kết luận |
| --- | --- | --- | --- | --- |
| API-115 `GET /api/v1/review-queue` | Có client, UI và DTO candidate | Chưa có route | Chưa có read controller | **BLOCKER** — owner S04 cần chốt API và triển khai/ủy quyền riêng |
| API-116 `GET /api/v1/document-versions/{versionId}/review-context` | Có client, UI cần header `ETag` | Chưa có route | Chưa có read controller | **BLOCKER** — cần strong ETag và DTO được phê duyệt |
| API-120 `POST /api/v1/document-versions/{versionId}/staff-decisions` | Có client, `If-Match` và `Idempotency-Key` | Có route POST đến S04 | Có controller, DTO và OpenAPI candidate | **CÓ THỂ ĐỐI CHIẾU** — cần tích hợp JWT thật và dữ liệu đọc phù hợp |

## 2. API-120: phần đã khớp

- Request `decision = PASS | RETURN_FOR_REVISION`, `feedback` và `evidenceRefs`; FE giữ nguyên `Idempotency-Key` khi retry cùng intent.
- S04 trả HTTP 200, `ETag`, JSON có `documentId`, `versionId`, `state`, `reviewStage`, `revision`, `sourceBinding`. `sourceBinding` có thể null; các field target chưa lưu cũng có thể null. Không còn mismatch *thiếu `documentId`/`sourceBinding`* như inventory sơ bộ.
- Strong ETag theo S04: `"<version UUID>:revision-<nonnegative integer>"`. FE lấy nguyên giá trị từ header và gửi lại qua `If-Match`.
- Exact replay cùng Idempotency-Key/If-Match/payload trả lại body và ETag gốc; stale revision với key mới trả 412.

## 3. Các điểm cần owner chốt trước khi coi là tích hợp thành công

### M01/M02 — API-115 và API-116 (P0)

**S04 owner:** xác nhận contract GET queue/context; phân quyền staff theo tenant/workspace/repository, phân trang, kiểu response, hidden/404 behavior, version state; `review-context` phải trả strong `ETag` tương thích API-120. Không tạo GET đọc trực tiếp DB S04 từ Gateway/frontend.

**Gateway owner:** chỉ thêm hai route GET allowlisted sau khi hợp đồng/path đã được duyệt; không mở `Path=/api/v1/**`.

**Frontend owner:** cập nhật DTO theo contract đã chốt; bảo đảm UI ẩn quyết định nếu ETag không có hoặc version không còn `IN_REVIEW/STAFF_REVIEW`.

### M03 — Mã lỗi 403 (P1)

FE hiện nhận diện `FORBIDDEN_SCOPE`, `PERMISSION_REVOKED` và fallback HTTP 403; S04 định nghĩa `REVIEW_FORBIDDEN`. Vì FE có fallback status nên không khẳng định đang crash, nhưng cần thêm `REVIEW_FORBIDDEN` vào mapping/hợp đồng để không phân kỳ.

### M04 — DTO và ETag (P0)

S04 response API-120 hiện đã có `documentId` và `sourceBinding`. FE cho `revision: string | number` trong khi S04 trả `long` dạng JSON number. Chuẩn type là integer nonnegative theo S04; `ReviewContextView` đọc vẫn chỉ là candidate. Chốt cách render `sourceBinding` null, strong ETag và replay behavior.

### M07 — Evidence ID (P0)

FE `ReviewDetailPage` lấy `context.evidence.map(e => e.ref)` để gửi `evidenceRefs`; type phía FE chỉ là `string`, ví dụ trong tài liệu candidate là `ev-1`. S04 yêu cầu các ID dạng UUID, tối đa 100, không trùng; evidence phải tồn tại đúng tenant/version; PASS cần ít nhất một evidence. API-116 cần trả ID UUID thật. Không dùng label/URL/source ref làm evidence ID.

### M08 — Feedback length (P1)

FE giới hạn 2000 ký tự, S04 chưa chốt cùng giới hạn trong OpenAPI. Owner S04 xác nhận giới hạn và mã lỗi khi vượt; backend phải tự kiểm tra nếu đây là rule được duyệt (không dựa vào UI).

### M05 — JWT và E2E (P0)

S04 sử dụng JWT RS256 xác minh chữ ký/issuer/audience; claim mapping là candidate. S01 issuer/JWKS/claims thật và Gateway tích hợp S01 chưa có bằng chứng E2E, không bypass bằng token giả.

## 4. Phân công/điều kiện hoàn thành

| Người chịu trách nhiệm | Việc cần xác nhận | Bằng chứng đóng |
| --- | --- | --- |
| Hải (S04, LIV-118) | API-115/116 owner, schema queue/context/ETag, UUID evidence, server feedback limit | Contract reviewed + controller/tests ở PR riêng hoặc task được gán rõ |
| Thịnh (FE, LIV-119) | Error code REVIEW_FORBIDDEN, evidence IDs thật, DTO nullable/number | FE tests + alignment với S04 |
| Project Leader/Gateway | Route GET allowlist, S01 JWT dependency | Test route đúng Path/Method và 401/403 khi contract sẵn sàng |
| Tiến (S02, LIV-122) | Authoritative SHA lookup wire contract | S02/S04 xác nhận method/path + response/error |

**Quy tắc merge:** không ép PR persistence LIV-118 phải triển khai mọi GET endpoint trong cùng scope; có thể merge slice độc lập khi review PASS, nhưng parent story LIV-97/E2E vẫn blocker cho tới khi read APIs và JWT thật tích hợp.

## 5. Bản mở rộng route và OpenAPI trong LIV-133 (cập nhật sau đối chiếu)

- Gateway được chuẩn bị **91 cặp Method + Path tường minh** cho S01/S02/S03/S04; không dùng wildcard tổng quát.
- API-115/116 có route GET đến S04 **nhưng chưa có controller read**. Việc route có sẵn không thay thế triển khai của Hải; trước khi E2E cần queue/context thật với ETag và scope.
- API-120 vẫn là implementation/contract authoritative từ PR LIV-118, không sao chép vào spec S04 của LIV-133 để tránh hai nguồn cạnh tranh.
- CORS allow `If-Match`, `Idempotency-Key`, expose `ETag`; vẫn `allowCredentials=false`. Cookie refresh/logout chưa route, chờ S01 CSRF/session decision.
- Các API S02/S03 và Manager/Publish là **CANDIDATE/PLANNED**, không coi Gateway route thành bằng chứng business API. Mọi protected transition phải fail closed nếu chưa có authorization/source check.
- S03 được thêm `S03_SERVICE_URI` vào Docker Compose gateway; không proxy `/internal/v1/**` hay S05 qua Gateway.

### Điều kiện để nâng trạng thái contract lên accepted

1. Hải xác nhận API-115/116 DTO, query budgets, strong ETag, evidence UUID và phân quyền; Thịnh xác nhận DTO/error mapping tương ứng.
2. Tiến xác nhận S02 authoritative SHA receipt/query/error; không tự public source validation.
3. S01 hoàn tất issuer/JWKS/claims, refresh rotation và CSRF; chỉ khi đó đánh giá mở thêm cookie auth route.
4. Bảo và owner S04 xác nhận API-124 Manager (không self-approve) và API-127 Publish (source revalidation); có integration test và audit.
5. OpenAPI schema validator + Gateway route regression + các CI hiện có PASS và reviewer độc lập phê duyệt.

**Chưa có chứng cứ:** kiểm thử Java trên máy người dùng, GitHub CI của bản patch mới, S01 JWT end-to-end, controller API-115/116, và yêu cầu bảo mật staging TLS.
