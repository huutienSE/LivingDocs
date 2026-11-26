# LIV-143 — API-113 Submit Draft

**Người tổng hợp:** Codex. **Cập nhật:** 23/11/2026, UTC+7. Chờ reviewer/Owner nghiệm thu.

## Phạm vi và trạng thái nhánh

Workspace chính đang ở `feature/LIV-143-s04-submit-draft-for-staff-review`. Nhánh đã fast-forward từ `24fdc3b` lên `c87b068`, gồm hai commit LIV-118: `72a8311` (JWT S01 thật + API-038) và `c87b068` (đính chính giới hạn fresh). Đây là đồng bộ local; chưa commit/push phần API-113.

Phần Submit được khôi phục từ stash `3562f39f2a042ad9bc2a3055aa638477406c773c`, hợp nhất cấu hình review/submit và tiếp tục sửa/test. Hai stash vẫn được giữ lại. Bốn file local `.gitignore`, `.codegraph/.gitignore`, `flow1/README.md`, `flow1/xay_dung_cac_api_de_hoan_thanh_flow1.md` giữ nguyên SHA256, không thuộc phần implementation API-113 cần stage.

Chỉ bổ sung `POST /api/v1/document-versions/{versionId}/submit`. Không triển khai Developer create/edit APIs, Manager, Publish, UI, S01 evaluator hoặc S02 provider trong task này. API-120 dùng implementation đã đồng bộ; test cũ cập nhật danh sách endpoint và số migration/RLS.

## Hành vi đã triển khai

1. Xác minh JWT qua issuer/JWKS/audience/expiry S01. Body nhận `note` nullable, tối đa 2000 ký tự; từ chối trường lạ. Strong `If-Match` của version và `Idempotency-Key` bắt buộc.
2. Gọi API-038 bằng nguyên User JWT trước transaction ghi S04. Kiểm Content-Type, identity, tenant, revision, evaluatedAt và grants; không follow redirect, giới hạn body khi nhận và deadline 3 giây. Thiếu cấu hình, response sai hoặc S01 unavailable đều fail closed.
3. Đặt tenant RLS transaction-local, khóa version cùng scope. Yêu cầu `DEVELOPER`, `DOCUMENT_AUTHOR`, `WORKSPACE_VIEW` trong cùng workspace và repository grant khi document gắn repository. Không ghép quyền giữa các workspace, không tin role header/JWT claims tự khai.
4. Exact replay theo tenant/version/actor/key kiểm quyền lại trước khi trả receipt gốc. Cùng key khác note hoặc If-Match gốc trả 409. Request mới cần revision hiện hành, state `DRAFT`, stage null.
5. Evidence đọc từ canonical S04 DB. Repository-bound Draft cần source SHA và provenance hợp lệ đã lưu; thiếu evidence/binding, SHA sai hoặc capturedAt tương lai bị chặn. Chưa kiểm current SHA qua S02/API-070.
6. Chuyển `IN_REVIEW / STAFF_REVIEW`, revision tăng 1. Version, submit receipt và audit commit/rollback cùng transaction; không tạo ReviewDecision giả. Return rồi resubmit cùng version bắt đầu lại ở Staff Review, giữ decision/content/editor history.
7. Trả `SuccessResponse<DocumentVersionView>` và ETag; MapStruct chỉ mapping. Receipt giữ outcome code/message gốc khi replay. Static OpenAPI và runtime Springdoc có schema cụ thể; inventory vẫn ghi `IN_PROGRESS` vì còn bước nghiệm thu.

### Phần hoàn thiện sau đối chiếu Master Decision

- **LD-D03:** đọc evidence bằng `DocumentEvidenceEntity` + `DocumentEvidenceJpaRepository` (JPQL có tenant/version scope, loại reference rỗng); đọc provenance qua field read-only của `DocumentVersionEntity`. `DocumentSubmitRepository` không còn native SQL hoặc xử lý raw `Object[]`. Native SQL RLS/locking của nền LIV-118 giữ nguyên vì có mục đích riêng.
- **LD-D17:** bổ sung [collection API-113](../../tests/postman/liv143-submit.postman_collection.json), chạy qua Gateway/S01/S04 thật trong runner DB tạm. Các phase kiểm Submit/replay/negative cases, Return/resubmit, revoke và S01 outage; không skip để báo PASS. Return dùng API-120 đã có, không triển khai thêm hành vi cho API đó.

Runner lưu environment chứa credential/JWT và raw Newman report trong thư mục tạm, dọn sau khi chạy. Manifest chỉ giữ tên phase, số request/assertion, trạng thái và SHA256; không upload raw response, environment hoặc access token.

## Kết quả rà soát bổ sung

| Vấn đề | Thay đổi |
|---|---|
| Advice Submit gom mọi DataAccessException thành 500 | Optimistic lock → 412 `REVISION_CONFLICT`; pessimistic lock → 409 `CONCURRENT_REVIEW`; giữ 500 cho lỗi persistence khác. |
| S01 trả 401 sau khi JWT đã qua kiểm tra local nhưng Submit thiếu Bearer challenge | Thêm `WWW-Authenticate: Bearer`; đồng bộ runtime/static OpenAPI cho 401. |
| Schema lỗi API-120 không liệt kê hai mã mà Submit trả về | Tách `SubmitProblem`, gồm `FORBIDDEN_SCOPE` và `SUBMIT_EVIDENCE_REQUIRED`; thêm kiểm chứng enum mã lỗi. Không đổi schema API-120. |
| Báo cáo mất dấu tiếng Việt do PowerShell pipeline | Ghi lại trực tiếp bằng UTF-8 và kiểm nội dung tiếng Việt, không chỉ kiểm ký tự replacement. |

Test lỗi khóa DB dùng exception injection qua repository spy sau transaction proxy để kiểm HTTP mapping và rollback; không tuyên bố đã mô phỏng deadlock thật. Test concurrency thực tế vẫn dùng nhiều HTTP request và PostgreSQL thật tạm.

## Fresh authorization và source validation còn giới hạn

API-038 là snapshot quyền, không phải evaluator API-039. Thu hồi trước lookup kế tiếp có hiệu lực với cùng JWT; không bảo đảm nguyên tử giữa thu hồi tại S01 và commit tại S04.

Ma trận Flow 1 phân biệt yêu cầu fresh đã có căn cứ cho API-120/124/127 với đề xuất bổ sung API-113/119. Bắt buộc API-039 cho Submit chưa được chốt riêng; provider/service assertion PR2 cũng chưa có trong S01 đã kiểm tra. Không tuyên bố auth API-113 đã hoàn thành phần fresh evaluator.

Không có căn cứ để ghi người dùng đã chốt API-039/API-070 chỉ cần trước Publish. Sơ đồ nghiệp vụ không thay policy ở từng protected action. Source guard API-120 giữ nguyên; API-113 hiện chỉ kiểm persisted evidence/binding. Nếu chốt fresh/current-source cho Submit, cần bổ sung contract, consumer và kiểm thử trước nghiệm thu yêu cầu đó.

### Dependency, blocker và giới hạn nghiệm thu

| Hạng mục / chủ sở hữu | Trạng thái | Ảnh hưởng và việc còn lại |
|---|---|---|
| Gateway security error body — Owner Gateway | **BLOCKED cho tiêu chí LD-D11 qua Gateway** | Gateway hiện trả 401 + Bearer challenge nhưng body rỗng. Collection kiểm việc chặn anonymous, không coi đó là RFC 9457 PASS. Cần sửa security entry-point/denied handler tại task Gateway; không sửa Gateway chung trong LIV-143. S04 trực tiếp đã có Problem Details. |
| Evaluator API-039/service assertion — S01, LIV-138 | **BLOCKED cho tích hợp evaluator** | Provider/contract triển khai chưa có trong S01 đã kiểm tra. API-113 vẫn dùng API-038, không được ghi là đã tích hợp fresh. API-120 được dùng để Return trong bài thử cũng chưa được chứng minh đáp ứng fresh API-039. |
| Bắt buộc API-039 cho Submit — PO/owner auth contract | **NEEDS DECISION** | Đây vẫn là đề xuất riêng cho API-113; không tự biến thành policy đã duyệt hoặc bỏ qua một evaluator đã được yêu cầu. Sau khi chốt phải tích hợp consumer và kiểm thử deny/unavailable. |
| Verified repository grant lifecycle — S01/S02 | **NOT VERIFIED trong flow repository-bound thật** | Positive Submit qua Gateway dùng document không gắn repository; case PENDING grant bị chặn. Positive repository-bound ở component tests dùng provider kiểm thử, chưa chứng minh vòng Request → Verify → Activate thật. Không bổ sung live current-SHA API-070 vào Submit ngoài scope đã xác định. |
| GitHub Actions / reviewer / QA | **NOT RUN / PENDING** | Chưa có remote CI hoặc QA độc lập cho diff này. Kết quả hiện có là local; không thay review/QA hoặc nghiệm thu toàn Flow 1. |

Các dependency trên không được đổi thành fake provider hoặc sửa rộng S01/S02/Gateway để làm test xanh. API-113 giữ phạm vi Submit/resubmit, persisted evidence/binding, scope, ETag/idempotency và atomic state/audit.

## Cấu hình và migration

```text
S01_SUBMIT_ACCESS_CONTEXT_URI=http://identity-organization-service:8081/api/v1/users/me/access-context
S04_SUBMIT_ALLOW_INSECURE_HTTP=true
```

HTTP là ngoại lệ local Compose; deployment dùng HTTPS và flag false. Giữ issuer/audience dạng biến môi trường, cấu hình API-120 đã đồng bộ; không sửa `.env` chứa secret.

V4 bổ sung source provenance nullable và `document.submit_requests`: unique idempotency/revision, FK tenant/version, RLS và runtime SELECT/INSERT. Không backfill provenance suy đoán, không sửa V1–V3 hoặc Flyway history. CI cập nhật từ 3 lên 4 migrations, từ 12 lên 13 bảng RLS.

Lượt tiếp tục ngày 23/11 chỉ dùng DB tạm, chưa migrate/redeploy DB chính. Ghi chép lịch sử trong stash ngày 22/11 cho biết DB chính từng lên V4 và có backup:

```text
C:/Users/TP/AppData/Local/LivingDocs/backups/livingdocs-before-liv143-v4-20261122-222914.dump
SHA256 9C14CC751B05490EB444BA59E0E1FCC477AF3DA1C755D562EDEB9BC832851269
```

Thông tin backup/deployment cũ chưa được kiểm tra lại ở lượt này; không xác nhận trạng thái DB/container hiện tại từ ghi chép đó. Không sửa V4 đã từng áp dụng hoặc tự repair/xóa history khi quay về branch cũ.

## Kiểm chứng ngày 23/11/2026

| Kiểm chứng phiên bản sau rà soát | Kết quả |
|---|---|
| S04 Maven verify | **82 tests PASS**, 0 failures/errors/skipped; kết thúc 23/11/2026 20:42:55 UTC+7. Gồm JPQL evidence scope/reference và toàn bộ regression API-120/113. |
| Gateway Maven verify | **26 tests PASS**, 0 failures/errors/skipped; kết thúc 20:44:22 UTC+7. Build JAR hiện có để dùng trong bài thử, không sửa source Gateway. |
| Gateway/S01/S04 + Newman | **27/27 harness checks PASS**; **22 requests / 40 assertions Newman PASS**, 0 failures/skips, 4 phase bắt buộc; kết thúc 23/11/2026 20:48:59 UTC+7. Gồm Submit → Return → resubmit qua Gateway, DB readback history/audit, revoke và outage. Không tính global Gateway LD-D11 hoặc API-039 là PASS. |
| Static OpenAPI | **6 schemas VALID**, openapi-spec-validator 0.7.2 và PyYAML 6.0.3. |
| Gateway route validator | **PASS**: 112 catalogued APIs, 91 exact method/path entries, 19 route groups; chỉ static. |
| Bảo toàn 4 file local | **SHA256 khớp cả 4 file**. |

Lượt trước có 81 tests PASS và 20/20 assertions S01/S04 PASS. Bảng trên là phiên bản sau JPA refactor và bổ sung Newman/Gateway. Compose config đã kiểm hợp lệ ở lượt trước; lượt này không đổi Compose và không redeploy DB chính.

Manifest và SHA256 JAR/runner/source/collection: [LIV-143-test-evidence.json](LIV-143-test-evidence.json). Logs: `%TEMP%/liv143-jpa-verify.log`, `%TEMP%/liv143-gateway-build.log`, `%TEMP%/liv143-gateway-newman.log`; Surefire: `backend/document-governance-service/target/surefire-reports`.

```powershell
# Working directory: backend/document-governance-service
.\mvnw.cmd -B -ntp verify

# Working directory: repo root; cần JAR S01, S04 và Gateway đã build
npm.cmd install --prefix "$env:TEMP\livingdocs-newman" newman@6.2.3 --no-audit --no-fund
python -X utf8 scripts/test_api113_s01_integration.py --gateway-newman --newman-cli "$env:TEMP\livingdocs-newman\node_modules\newman\bin\newman.js"
python -X utf8 scripts/validate_gateway_openapi.py
```

Component tests dùng PostgreSQL 17 thật tạm, JWT/JWKS và API-038 provider kiểm thử. Bao phủ Submit/replay/conflict/concurrency, scope/repository grant, revoked permission khi replay, invalid authority, evidence/provenance, audit rollback, runtime contract, Submit → Staff Return → resubmit cùng version và restart application context/server/pool trên cùng DB. Đây không phải restart JVM/PostgreSQL.

Runner dùng Gateway/S01/S04 thật, Redis và PostgreSQL tạm; register/login qua HTTP, JWT Identity-only và API-038 đọc authority DB thật. Newman đăng nhập qua Gateway và kiểm Submit/Return/resubmit qua cùng ingress. Authority/Draft tạo bằng SQL fixture trong DB tạm; không phải kiểm chứng UI/Operator provisioning hoặc repository grant verification thật. Không đọc `.env`, không dùng DB chính.

Đã bổ sung bài thử business HTTP API-113 qua Gateway; chưa có CI GitHub remote, QA độc lập, S02 integration, UI E2E hoặc positive repository-bound flow với grant được verification thật. Lỗi Gateway chung và evaluator còn thiếu được liệt kê riêng ở bảng dependency, không được tính PASS theo kết quả Newman.

## Nguồn và độ tin cậy

| Nguồn / nơi đăng | Tác giả / thời gian / xác thực | Giá trị và giới hạn |
|---|---|---|
| [LIV-143](https://huutiense25.atlassian.net/browse/LIV-143), Jira LivingDocs | Issue cập nhật 22/11/2026; đọc lại live 23/11/2026. Tác giả description chưa xác định; assignee không đồng nghĩa tác giả. | Nguồn scope/AC sơ cấp: Submit/resubmit, scope, evidence/binding, ETag/idempotency, atomic state/audit. In Progress không xác nhận test PASS. |
| [LD-API-001](https://huutiense25.atlassian.net/wiki/spaces/LD/pages/11337744), Confluence LivingDocs | Nội dung v0.4 / page v2; người cập nhật TIỀN HUỲNH PHẠM HỮU, 24/10/2026 UTC+7; đã đối chiếu trong phiên 23/11. | Thiết kế API ID/path và baseline sensitive authorization; candidate design, không phải Accepted ADR hoặc runtime evidence. |
| [SRS](https://huutiense25.atlassian.net/wiki/spaces/LD/pages/3702801), Confluence LivingDocs | Page v4; người cập nhật nhan nguyen, 23/10/2026 UTC+7; đã đối chiếu trong phiên 23/11. | FR-DOC-008, FR-AUT-002/SEC-002: controlled Staff Review, Effective Permission đúng scope. Không tự chốt mọi mutation phải gọi API-039. |
| [Master Decisions](../engineering/LivingDocs_Master_Decisions_Technical_Rules_Team_Agent_Handoff_2026-11-13.md), [Standards v3](../engineering/LivingDocs_Backend_Engineering_Standards_v3.md), repository | Owner decision trong tài liệu ngày 13/11/2026; tác giả từng dòng chưa xác định độc lập. | Quy tắc nội bộ: JWT/domain guards, fresh cho quyền nhạy cảm, Service interface, JPA/Flyway, DTO/MapStruct, SuccessResponse. |
| [Runbook LIV-138](authorization/LIV-138-s01-authority-and-testing.md), [ma trận Flow 1](flow1/xay_dung_cac_api_de_hoan_thanh_flow1.md#3-phân-loại-api-cần-fresh-authorization-của-s01), repository | Runbook commit `e0d7fc1`, author `huutienSE`, 22/11/2026; ma trận do Codex cập nhật 23/11/2026 theo yêu cầu người dùng. | Phân biệt snapshot, fresh evaluation, service identity. Ma trận có phần đề xuất, không coi là policy đã approve. |
| Source, Surefire và runner manifest | Codex kiểm tra/chạy ngày 23/11/2026 | Bằng chứng local gắn JAR/runner SHA256; chưa có xác nhận reviewer/QA độc lập hoặc CI. |
