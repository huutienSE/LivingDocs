# Báo cáo triển khai và giới hạn API-120

**Hạng mục:** LIV-118 — S04 Staff Decision.
**Người lập:** Codex. **Ngày báo cáo:** 23/11/2026, UTC+7.
**Nơi lưu:** repository LivingDocs, nhánh `feature/LIV-118-s04-review-state-persistence`.

API-120 đã có nghiệp vụ PASS/RETURN và tích hợp xác thực, phân quyền S01 thật bằng
User JWT cùng API-038 Access Context. Kiểm thử local đã xác nhận Staff được cấp quyền
có thể thao tác trên document không gắn repository; sai scope, quyền bị thu hồi hoặc
S01 không hoạt động đều bị từ chối. Staff PASS trên repository thật chưa đủ điều kiện
nghiệm thu vì còn phụ thuộc quyền repository đã xác minh và tích hợp source validation S02.

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
API-039, Manager Approval và Publish không được triển khai thêm trong phạm vi này.

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
| **Chưa có adapter production S02 trong S04** | Repository-backed PASS chưa được nghiệm thu. Sau khi đủ quyền repository, nếu thiếu kết quả source validation đáng tin cậy thì source guard trả `503 SOURCE_VALIDATION_UNAVAILABLE`; guard vẫn được giữ. |
| **Chưa chứng minh quy trình cấp Repository Grant đã xác minh** | Runner chỉ kiểm grant `PENDING` bị loại khỏi quyền hiệu lực; không tự tạo grant `VERIFIED`. Role Staff trong workspace không đủ để review document gắn repository. |
| **API-038 là snapshot quyền** | Revoke trước lần lookup tiếp theo được áp dụng với cùng JWT. Chưa có bảo đảm nguyên tử nếu quyền bị thu hồi giữa lookup và commit S04. Không coi API-038 là API-039 fresh evaluation. |
| **Positive integration dùng document không gắn repository** | PASS/RETURN đã chạy với S01 thật, nhưng không thể suy ra S02/GitHub hoặc repository-backed Golden Flow đã PASS. |
| **Dữ liệu authority là fixture trong DB test riêng** | Chứng minh S01 đọc quyền thật từ PostgreSQL và S04 enforce context thật; không chứng minh quy trình operator CLI hoặc dữ liệu tài khoản đang dùng ở môi trường triển khai. |
| **Chưa redeploy Compose hiện có** | Code và cấu hình mới đã có ở local; chưa chứng minh tương thích DB ứng dụng hiện có. Báo cáo baseline ghi nhận chênh lệch migration S04 V4 so với V1–V3 trên nhánh LIV-118; cần kiểm tra lại trước khi chạy trên DB đó. |
| **Chưa kiểm chứng các luồng và môi trường còn lại** | Chưa có evidence cho Gateway HTTP/Newman của API-120 mới, Manager/Publish, deployment TLS hoặc restart JVM/PostgreSQL. Restart component test chỉ đóng/mở Spring context trên cùng DB. |
| **Chưa có CI và nghiệm thu độc lập cho bản tích hợp** | Kết quả trong báo cáo là kiểm chứng local; trạng thái bàn giao Git theo commit của nhánh. Chưa có bằng chứng CI cho diff mới, reviewer/QA độc lập hoặc cơ sở chuyển Jira Done. |

## Điều kiện để nghiệm thu Staff Review trên repository thật

1. Thống nhất contract source validation S02, cơ chế xác thực service-to-service và triển khai adapter S04 tương ứng.
2. Có repository connection hợp lệ và quy trình cấp/xác minh Repository Grant cho Staff đúng tenant/workspace.
3. Xác nhận schema DB đích tương thích, triển khai bản mới và chạy cả happy path lẫn deny/failure cases qua Gateway trên repository thật.

## Nguồn và độ tin cậy

| Nguồn | Tác giả, thời gian, nơi lưu | Cách sử dụng và mức tin cậy |
| --- | --- | --- |
| [Manifest kiểm chứng LIV-118](LIV-118-test-evidence.json) | Codex tổng hợp từ lượt chạy local ngày 23/11/2026; lưu trong repo. | Nguồn trực tiếp cho test totals, timestamps và SHA256. Đã đối chiếu artifact khi lập báo cáo; chưa phải QA độc lập hoặc remote CI. |
| [Chi tiết tích hợp API-120 và S01](authorization/LIV-118-api120-s01-integration.md) | Codex, 23/11/2026; lưu trong repo. | Ghi hành vi, lệnh tái lập, môi trường và giới hạn của các lượt chạy đã thực hiện. |
| [Runbook S01 LIV-138](authorization/LIV-138-s01-authority-and-testing.md) | Commit `e0d7fc1` của `huutienSE`, 22/11/2026 10:21:52 UTC+7; repository LivingDocs. | Nguồn dự án về API-038/authority; contract đã được đối chiếu với source và HTTP thật trong phiên triển khai. Git commit author không tự xác nhận tác giả mọi đoạn nội dung. |
| Xác nhận phạm vi API-120 trong phiên triển khai | Người dùng xác nhận ngày 23/11/2026; được ghi nhận trong tài liệu tích hợp và manifest của repo. | Áp dụng API-038 cho Staff Decision; không suy ra quyết định bỏ API-039 cho Manager/Publish hoặc bỏ source guard S02. |

Artifact trong `target/` có thể bị ghi đè hoặc xóa bởi clean build. Kết luận kiểm chứng
áp dụng cho đúng nguồn và artifact đã ghi trong manifest, không thay thế nghiệm thu môi trường đích.
