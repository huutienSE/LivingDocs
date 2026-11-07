# LIV-118 — S04 Staff Review

Triển khai local review state/persistence trong Spring Boot 4.1.1 / Java 21 / PostgreSQL 17.
Endpoint và conventions tham chiếu LD-API-001; đây là slice để review, chưa phải toàn bộ LIV-97.

## API và dữ liệu

- API đọc version/queue/context không được cung cấp trong slice persistence này; không nhận API-106/115/116 đã hoàn thành.
- `POST /api/v1/document-versions/{versionId}/staff-decisions`: body `decision`, `feedback`, `evidenceRefs`; bắt buộc `If-Match` và `Idempotency-Key`.
- `PASS`: `IN_REVIEW / STAFF_REVIEW` → `IN_REVIEW / MANAGER_APPROVAL`.
- `RETURN_FOR_REVISION`: → `DRAFT`, xóa review stage, bắt buộc feedback không rỗng; giữ content revisions, decisions và evidence cũ.
- Không nhận trạng thái đích hoặc tenant/actor/quyền từ body/header tự khai. Field lạ bị từ chối.
- Contract: [`s04-document-review.yaml`](../../contracts/openapi/s04-document-review.yaml).

```json
{
  "decision": "RETURN_FOR_REVISION",
  "feedback": "Cần bổ sung giải thích thay đổi API.",
  "evidenceRefs": []
}
```

JDBC repositories map model vào schema V1 hiện có. `@Transactional` dùng cùng connection với
`set_config('app.tenant_id', ..., true)` để giữ RLS context trong transaction. Service khóa version
bằng `FOR UPDATE` và scope rows bằng `FOR SHARE`, kiểm quyền, replay key, revision và state trước
khi cập nhật có điều kiện `WHERE revision = expected`. Đây là row locking kết hợp revision guard,
không phải JPA `@Version`. Một transaction ghi version + decision + audit; lỗi bất kỳ rollback toàn bộ.

V2 thêm revision và kết quả/idempotency vào decision hiện có, không sửa V1 hoặc rewrite lịch sử.
Nếu dữ liệu V1 đã có `IN_REVIEW` với stage null, V2 sẽ từ chối migration; cần người sở hữu dữ liệu
xác định stage đúng và sửa dữ liệu đó trước triển khai. Không tự đoán stage để vượt constraint.
`round_no` tăng theo số decision của version dưới row lock. Decision/audit vẫn append-only với runtime role.
Audit lưu actor, tenant/workspace/repository, source SHA, state/stage trước và sau, revision,
decision ID, evidence IDs, correlation ID và timestamp. Exact retry trả lại response/ETag gốc;
client cần GET lại nếu muốn trạng thái mới nhất sau những mutation khác.

## Ranh giới tích hợp S01

Checkout này chưa có login/token issuer của S01. Adapter hiện dùng JWT RS256 với signature,
issuer, audience, expiration được kiểm chứng. Nếu thiếu trust configuration, token bị từ chối (401).
Không có chế độ tự tin `X-User-Role` hoặc bỏ xác thực để demo.

Các biến cấu hình: `S01_ISSUER_URI`, `S01_JWK_SET_URI`, `S04_TOKEN_AUDIENCE`.
Mapping claim dưới đây là **đề xuất tích hợp của implementation**, cần S01/LIV-119 đối chiếu trước merge;
không được coi là contract claim đã được dự án phê duyệt:

```json
{
  "sub": "<identity UUID>",
  "iss": "<configured issuer>",
  "aud": ["<configured S04 audience>"],
  "exp": 0,
  "tenant_id": "<tenant UUID>",
  "tenant_active": true,
  "workspace_grants": [{
    "workspace_id": "<workspace UUID>",
    "active": true,
    "roles": ["STAFF"],
    "repository_ids": ["<authorized repository UUID>"],
    "document_ids": ["<authorized repository-free document UUID>"]
  }]
}
```

`exp: 0` chỉ minh họa field, không phải token dùng được. Repository-backed document yêu cầu
repository grant; document không gắn repository yêu cầu explicit document grant. Tenant Owner/Manager
không tự có quyền Staff. Một role ở workspace khác không mở quyền cho workspace này. Service đối chiếu
scope với các bảng S04, không query schema S01. JWT phản ánh snapshot quyền khi phát token;
chưa chứng minh tức thời revoke quyền hoặc kết nối issuer S01 thật.

PASS yêu cầu evidence IDs đã lưu đúng tenant/version và source SHA cho tài liệu gắn repository.
`NEEDS_REVALIDATION` bị chặn bởi state guard. Chưa gọi S02 kiểm current GitHub head; các kiểm tra này
không chứng minh external source freshness. Source revalidation, submit Draft, full detail/queue UI,
Manager Approval, Publish và Gateway integration nằm ngoài slice này.

## Chạy và kiểm chứng

```powershell
cd backend/document-governance-service
.\mvnw.cmd -B -ntp clean verify
```

Cần Java 21 và Docker. Mặc định test tự tạo PostgreSQL 17 riêng, bootstrap migrator/runtime roles,
chạy V1+V2, dùng HTTP server thật và JWT ký bằng RSA test key. Không dùng DB ứng dụng đang chạy ở 5432.
`S04_USE_CI_DATABASE=true` chỉ dành cho DB thử nghiệm tách biệt trong CI; dùng các biến `DB_URL`,
`DB_PASSWORD`, `FLYWAY_PASSWORD`, `S04_TEST_ADMIN_PASSWORD` do workflow cung cấp. Không bật cờ này trên DB thật.

Tests kiểm tra PASS/RETURN/history, typed denials, token signature/issuer/audience/expiry, tenant/workspace/
repository/role/membership, replay, revision conflict, evidence scope, audit failure rollback, concurrency,
PostgreSQL constraints/RLS/append-only và đóng/mở lại application HTTP server với cùng DB. Restart test
chứng minh persistence qua Spring application restart, không mô phỏng crash/kill JVM hoặc restart PostgreSQL.
Kết quả thực tế nằm ở `target/surefire-reports`; không suy ra GitHub CI hoặc peer approval từ local tests.

Kết quả local đã thực hiện ngày **05/11/2026, 21:19 (UTC+7)**: `mvnw.cmd -B -ntp clean verify`
thành công, **32 tests / 0 failures / 0 errors / 0 skipped**, Java 21.0.9, PostgreSQL 17.11.
Bao gồm 15 policy/ETag cases, 13 API/DB cases, 1 migration upgrade, 1 application restart,
1 context smoke và 1 missing-trust check. Log: `liv118-verify.log`; XML: `target/surefire-reports/TEST-*.xml`.
Đã kiểm tra OpenAPI bằng `python -m openapi_spec_validator ../../contracts/openapi/s04-document-review.yaml`
(kết quả `OK`) và parse workflow YAML. Các log/build outputs là local, không đưa vào Git.

Để chạy service với DB đã bootstrap, kích hoạt profile `db`; truyền `DB_URL`, `DB_SCHEMA=document`,
`DB_USERNAME=ld_s04_app`, `DB_PASSWORD`, `FLYWAY_USERNAME=ld_s04_migrator`, `FLYWAY_PASSWORD` và cấu hình S01.
Không dùng superuser làm runtime. Không đưa secret vào repository. Trust/TLS thực tế phải được môi trường
triển khai cấu hình; tests loopback không chứng minh deployment TLS.

## Nguồn, người viết và trạng thái bằng chứng

Ghi chú implementation: Codex, ngày 05/11/2026, lưu trong repository LivingDocs.
Nguồn đã đọc trực tiếp ngày 05/11/2026:

| Nguồn | Tác giả / thời gian / nơi đăng | Giá trị và giới hạn |
| --- | --- | --- |
| [LIV-118](https://huutiense25.atlassian.net/browse/LIV-118) | Reporter TIỀN HUỲNH PHẠM HỮU; cập nhật 05/11/2026; Jira LivingDocsPro | Nguồn nội bộ trực tiếp cho phạm vi state/decision/audit, unit+DB tests và restart; chưa là bằng chứng task Done. |
| [LD-API-001](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/11337744) | Metadata author TIỀN HUỲNH PHẠM HỮU; version 2 ngày 24/10/2026; Confluence LPKB | Nguồn thiết kế nội bộ cho API-106/120, ETag, idempotency, Problem; chính tài liệu ghi candidate, chưa Accepted ADR/G2/runtime evidence. |
| [REQ-05](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/3702785) | Cùng author metadata; version 6 ngày 20/10/2026; Confluence LPKB | Nguồn yêu cầu state transition T-DOC-03/04B; không thay thế test implementation. |
| [LD-ARCH-005](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/10715150) | Cùng author metadata; version 1 ngày 23/10/2026; Confluence LPKB | Hướng scope/RLS/service ownership; tài liệu ghi reviewed candidate/pending ADR. |
| [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) | Spring project; tài liệu chính thức, truy cập 05/11/2026 | Nguồn kỹ thuật sơ cấp cho JWT verification; không xác thực claim contract riêng của LivingDocs. |

Độ tin cậy được đánh giá theo quyền sở hữu tài liệu dự án và trạng thái công bố trên nguồn; không suy diễn
chuyên môn cá nhân từ tên tác giả. Subtask cần code/test/evidence được review; không mặc định yêu cầu
toàn bộ S01/S02 E2E của parent để đóng subtask persistence. Flow end-to-end vẫn cần tích hợp thực tế.
Các điểm contract/phân công chưa chốt nằm trong docs/implementation/LIV-118-review-handoff.md.

Sau review: bỏ GET API-106 thu gọn để tránh lệch contract. Restart test đọc state qua repository của application mới và replay POST qua HTTP. DB runtime bắt buộc truyền credentials, không fallback superuser. Evidence mới mô tả working tree, không gắn commit đã thu hồi.

Kiểm chứng sau sửa: 05/11/2026 22:38:32 UTC+7, verify BUILD SUCCESS; 32 tests, 0 failures/errors/skipped. OpenAPI validator OK. Log liv118-fixed-verify.log; evidence và SHA256 working tree tại docs/implementation/LIV-118-test-evidence.json.

## Review fixes — 06/11/2026

API-120 trả DocumentVersionView gồm documentId, versionId, state, reviewStage, revision, sourceBinding.
SourceBinding trả repositoryConnectionId/headSha đã lưu. targetKind, targetRef, comparisonSha,
configRevision, capturedAt hiện null do schema chưa lưu; không được coi là source-binding đầy đủ đã xác minh.
Evidence refs tối đa 100 và không trùng; đây là budget kỹ thuật ban đầu, không phải requirement nghiệp vụ
hoặc ngưỡng benchmark được phê duyệt. Request lỗi trả 422 trước DB/lock; kiểm evidence theo một query IN.
V3 lưu response snapshot cùng decision và khôi phục snapshot V2 từ audit bất biến; không sửa V1/V2.
Không có audit đủ dữ liệu cho replay lịch sử thì trả 409 REPLAY_SNAPSHOT_UNAVAILABLE.
Tests sau sửa: 34/34, BUILD SUCCESS lúc 17:46:54 UTC+7 ngày 06/11; log liv118-review-fixes-final.log.
Evidence local và CI của base commit được ghi riêng trong docs/implementation/LIV-118-test-evidence.json.
