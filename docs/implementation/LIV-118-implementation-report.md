# Báo cáo triển khai và giới hạn API-120

**Hạng mục:** LIV-118 — S04 Staff Decision.
**Người lập:** Codex. **Ngày báo cáo:** 23/11/2026, UTC+7.
**Nơi lưu:** repository LivingDocs, nhánh `feature/LIV-118-s04-review-state-persistence`.

**Trạng thái auth: chưa hoàn thành fresh authorization theo runbook LIV-138.** API-120
hiện có xác thực User JWT và lookup quyền qua API-038; chưa gọi evaluator API-039 trước
mutation. Người dùng yêu cầu tích hợp auth thật và không xác nhận dùng API-038 thay
fresh authorization. Các nội dung trước đây gán quyết định đó cho người dùng là sai.

Kiểm thử local đã xác nhận Staff được cấp quyền có thể PASS/RETURN document không gắn
repository; sai scope, quyền bị thu hồi trước lookup hoặc S01 không hoạt động đều bị
từ chối. Kết quả này chứng minh implementation JWT + API-038 hiện có, chưa chứng minh
đã hoàn tất auth theo thiết kế. Staff PASS trên repository thật còn phụ thuộc quyền
repository đã xác minh và tích hợp source validation S02.

## Phần đã triển khai

Endpoint: `POST /api/v1/document-versions/{versionId}/staff-decisions`.

| Hạng mục | Hành vi đã có |
| --- | --- |
| Staff PASS | Kiểm tra version ở `IN_REVIEW / STAFF_REVIEW`, evidence hợp lệ và các guard; chuyển review stage sang `MANAGER_APPROVAL`. |
| Return for Revision | Yêu cầu feedback không rỗng; đưa version về `DRAFT`, xóa review stage. |
| Persistence và audit | Version, review decision, response snapshot và audit được ghi trong cùng transaction; lỗi ghi làm rollback toàn bộ. Audit ghi đúng Identity thực hiện. |
| Revision và retry | Bắt buộc `If-Match` và `Idempotency-Key`; kiểm revision, xử lý ghi cạnh tranh và trả response/ETag gốc cho exact replay. |
| Xác thực S01 | S04 kiểm signature JWT qua JWKS S01, issuer, audience, expiration và Identity UUID trong `sub`. |
| Phân quyền S01 | Mỗi request, kể cả retry, gửi User JWT đến API-038 trước write transaction. Tenant/roles/grants lấy từ context S01; không lấy từ scoped JWT claims hoặc header tự khai. |
| Scope và RLS | Cần `STAFF` cùng `DOCUMENT_STAFF_REVIEW` trong grant của workspace sở hữu version. Document gắn repository cần thêm repository ID trong cùng grant. Scope được đối chiếu với resource S04 dưới PostgreSQL RLS. |
| Xử lý lỗi authority | Không cache quyền hoặc fallback khi S01 lỗi. Thiếu membership/quyền trả 403; khác tenant trả 404 dưới RLS. S01 401/403 được ánh xạ tương ứng; timeout, lỗi upstream hoặc context hỏng trả `503 AUTHORIZATION_UNAVAILABLE`, không ghi DB. |
| Cấu hình và contract | Đã bổ sung cấu hình issuer/JWKS/audience/API-038 cho S04 trong Compose và `.env.example`; đồng bộ OpenAPI cùng runtime Swagger. HTTP chỉ được bật qua ngoại lệ local tường minh. |

Tích hợp API-038 là phần bổ sung trong lượt triển khai này; các nghiệp vụ review,
persistence, ETag, idempotency và source guard được giữ lại và chạy regression.
Fresh authorization API-039 là phần còn thiếu để hoàn thiện auth của API-120 theo
runbook; Manager Approval và Publish là các endpoint khác.

## Kết quả kiểm tra khả năng tích hợp fresh authorization

Kiểm tra lại ngày **23/11/2026** trên source đã fetch, nhánh phụ thuộc, Jira và OpenAPI
S01 local. **Chưa thể tích hợp và kiểm chứng API-039 thật với phiên bản S01 hiện truy cập được.**

| Dependency | Bằng chứng hiện tại | Tác động tới API-120 |
| --- | --- | --- |
| API-039 evaluator và contract | Source S01 chỉ có `AuthController`, `AccessContextController`, `JwksController`; OpenAPI S01 chưa có operation/request/response fresh evaluation. Runtime `http://127.0.0.1:8081/v3/api-docs` chỉ công bố register, login, API-038 và JWKS. | Chưa có endpoint/DTO thực tế để S04 gửi action/scope và nhận quyết định fresh từ S01. |
| Service JWT/assertion và caller trust | `JwtTokenIssuer.issue(UUID)` hiện phát User JWT. Source chưa có issuer/validator cho service assertion; runbook mục PR2 mới ghi contract dự kiến với `X-LD-Service-Assertion`, caller/actor, method/path, `jti` và TTL. | Chưa có cơ chế được triển khai để S04 lấy assertion và S01 xác minh caller theo contract PR2. Không tự dùng User JWT như service credential. |
| Nhánh S01 fresh auth | `origin/feature/LIV-138-s01-service-jwt-fresh-authz` và `origin/develop` cùng SHA `1e76bad12ceb96c79c4820dcbf310a74b0d93fd3`; không có commit hoặc file khác biệt. Tìm PR theo đúng head branch không có kết quả. | Tên nhánh đã tồn tại nhưng chưa có implementation mới để tích hợp. |
| Bàn giao dependency | Jira LIV-138 vẫn `In Progress`, không có comment bổ sung contract; các worktree local `flow1-develop` và `liv118` không có thay đổi S01/OpenAPI chưa commit. | Chưa tìm thấy bản bàn giao khác trong các nguồn đã kiểm tra. Kết luận không bao phủ source chưa push hoặc môi trường không truy cập được. |

Khi S01 bàn giao evaluator và service assertion, S04 cần resolve resource dưới RLS
trong read transaction, kết thúc transaction trước HTTP fresh evaluation, rồi mở write
transaction kiểm lại resource scope/state/ETag trước mutation. Phải áp dụng cho cả
PASS/RETURN và kiểm quyền trước replay theo contract; DENY/UNAVAILABLE không được ghi DB.
Một response ALLOW không tự bảo đảm thu hồi quyền nguyên tử với commit xuyên hai DB.

## Kết quả kiểm chứng

| Bằng chứng | Kết quả | Phạm vi chứng minh |
| --- | --- | --- |
| S04 `mvn -B -ntp clean verify` | **59 tests PASS**, 0 failures/errors/skipped; kết thúc 23/11/2026 lúc **14:41:06 UTC+7**. | Nghiệp vụ, persistence PostgreSQL, RLS, rollback, concurrency, replay, restart Spring context, HTTP auth và runtime Swagger parity. Authority/issuer trong component tests là test provider. |
| Runner S01 và S04 thật | **22/22 assertions PASS**; kết thúc 23/11/2026 lúc **14:45:42 UTC+7**. | Hai JVM thật, PostgreSQL 17 và Redis riêng; register/login lấy JWT S01 thật, S04 đọc JWKS/API-038 qua HTTP thật, PASS/RETURN trên document không gắn repository. |
| OpenAPI tĩnh | **PASS**. | Contract S04 hợp lệ bằng openapi-spec-validator 0.7.2 và PyYAML 6.0.3. |
| Gateway inventory và Compose | **PASS kiểm tra tĩnh**: 112 APIs, 91 exact method/path entries, 19 route groups; Compose config hợp lệ. | Không chứng minh request API-120 đã đi qua Gateway hoặc bản Compose hiện có đã được redeploy. |

Runner hai service đã kiểm chứng Identity chưa provision, sai tenant/workspace, grant
repository `PENDING`, bearer không hợp lệ, revoke Staff/tenant membership với cùng JWT,
retry sau revoke và S01 down. Các request bị từ chối không ghi thêm state/decision/audit;
exact replay chỉ có một decision và một audit, với actor đúng Identity S01.

Manifest lưu SHA256 của nguồn, suite reports, log, jar và runner đã chạy.
Khi lập báo cáo, mã nguồn triển khai và các artifact kiểm thử được đối chiếu lại
với hash đã ghi. Lượt lập báo cáo không chạy lại test.

## Phần còn giới hạn

| Giới hạn hoặc phụ thuộc | Tác động thực tế |
| --- | --- |
| **Thiếu fresh authorization trước mutation** | JWT + API-038 mỗi request chưa đáp ứng bước S01 quyết định fresh action/scope theo runbook. API-120 chưa thể được nghiệm thu đầy đủ auth; thiếu endpoint/contract API-039 và service assertion như bảng kiểm tra ở trên. |
| **Chưa có adapter production S02 trong S04** | Repository-backed PASS chưa được nghiệm thu. Sau khi đủ quyền repository, nếu thiếu kết quả source validation đáng tin cậy thì source guard trả `503 SOURCE_VALIDATION_UNAVAILABLE`; guard vẫn được giữ. |
| **Chưa chứng minh quy trình cấp Repository Grant đã xác minh** | Runner chỉ kiểm grant `PENDING` bị loại khỏi quyền hiệu lực; không tự tạo grant `VERIFIED`. Role Staff trong workspace không đủ để review document gắn repository. |
| **API-038 là snapshot quyền** | Revoke trước lần lookup tiếp theo được áp dụng với cùng JWT. Chưa có bảo đảm nguyên tử nếu quyền bị thu hồi giữa lookup và commit S04. Không coi API-038 là API-039 fresh evaluation. |
| **Positive integration dùng document không gắn repository** | PASS/RETURN đã chạy với S01 thật, nhưng không thể suy ra S02/GitHub hoặc repository-backed Golden Flow đã PASS. |
| **Dữ liệu authority là fixture trong DB test riêng** | Chứng minh S01 đọc quyền thật từ PostgreSQL và S04 enforce context thật; không chứng minh quy trình operator CLI hoặc dữ liệu tài khoản đang dùng ở môi trường triển khai. |
| **Chưa redeploy Compose hiện có** | Code và cấu hình mới đã có ở local; chưa chứng minh tương thích DB ứng dụng hiện có. Báo cáo baseline ghi nhận chênh lệch migration S04 V4 so với V1–V3 trên nhánh LIV-118; cần kiểm tra lại trước khi chạy trên DB đó. |
| **Chưa kiểm chứng các luồng và môi trường còn lại** | Chưa có evidence cho Gateway HTTP/Newman của API-120 mới, Manager/Publish, deployment TLS hoặc restart JVM/PostgreSQL. Restart component test chỉ đóng/mở Spring context trên cùng DB. |
| **Chưa có CI và nghiệm thu độc lập cho bản tích hợp** | Kết quả trong báo cáo là kiểm chứng local; trạng thái bàn giao Git theo commit của nhánh. Chưa có bằng chứng CI cho diff mới, reviewer/QA độc lập hoặc cơ sở chuyển Jira Done. |

## Điều kiện để nghiệm thu Staff Review trên repository thật

1. S01 bàn giao API-039 và service assertion với contract/test evidence; tích hợp vào API-120 và kiểm ALLOW/DENY/UNAVAILABLE, revoke, caller sai, assertion hết hạn/replay, scope thay đổi và DB không mutate khi bị từ chối.
2. Thống nhất contract source validation S02, cơ chế xác thực service-to-service và triển khai adapter S04 tương ứng.
3. Có repository connection hợp lệ và quy trình cấp/xác minh Repository Grant cho Staff đúng tenant/workspace.
4. Xác nhận schema DB đích tương thích, triển khai bản mới và chạy cả happy path lẫn deny/failure cases qua Gateway trên repository thật.

## Nguồn và độ tin cậy

| Nguồn | Tác giả, thời gian, nơi lưu | Cách sử dụng và mức tin cậy |
| --- | --- | --- |
| [Manifest kiểm chứng LIV-118](LIV-118-test-evidence.json) | Codex tổng hợp từ lượt chạy local ngày 23/11/2026; lưu trong repo. | Nguồn trực tiếp cho test totals, timestamps và SHA256. Đã đối chiếu artifact khi lập báo cáo; chưa phải QA độc lập hoặc remote CI. |
| [Chi tiết tích hợp API-120 và S01](authorization/LIV-118-api120-s01-integration.md) | Codex, 23/11/2026; lưu trong repo. | Ghi hành vi, lệnh tái lập, môi trường và giới hạn của các lượt chạy đã thực hiện. |
| [Runbook S01 LIV-138](authorization/LIV-138-s01-authority-and-testing.md) | Commit `e0d7fc1` của `huutienSE`, 22/11/2026 10:21:52 UTC+7; repository LivingDocs. | Nguồn dự án về API-038/authority; contract đã được đối chiếu với source và HTTP thật trong phiên triển khai. Git commit author không tự xác nhận tác giả mọi đoạn nội dung. |
| Yêu cầu tích hợp auth trong phiên triển khai | Người dùng yêu cầu “tích hợp thật auth”, dẫn runbook LIV-138 và đính chính ngày 23/11/2026. | Không có xác nhận cho phép API-038 thay fresh authorization. Đây là căn cứ sửa kết luận auth chưa hoàn thành. |
| Nhánh S01 và runtime OpenAPI | GitHub `huutienSE/LivingDocs`, nhánh `feature/LIV-138-s01-service-jwt-fresh-authz`; runtime S01 local cổng 8081, đọc 23/11/2026. | Git/source và runtime spec là bằng chứng trực tiếp cho phiên bản đã kiểm tra; runtime spec không chứng minh mọi môi trường đều cùng phiên bản. |
| [Jira LIV-138](https://huutiense25.atlassian.net/browse/LIV-138) | LivingDocs Jira; assignee TIỀN HUỲNH PHẠM HỮU; issue cập nhật 15/11/2026; đọc live 23/11/2026. Tác giả description chưa xác định. | Nguồn quản lý dependency; trạng thái `In Progress` và không có comment không tự chứng minh code ở nơi khác chưa tồn tại. Kết luận readiness dựa thêm source và runtime ở bảng trên. |

Artifact trong `target/` có thể bị ghi đè hoặc xóa bởi clean build. Kết luận kiểm chứng
áp dụng cho đúng nguồn và artifact đã ghi trong manifest, không thay thế nghiệm thu môi trường đích.
