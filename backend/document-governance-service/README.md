# LIV-118 — S04 Staff Review

Spring Boot 4.1.1 / Java 21 / PostgreSQL 17. Phạm vi: Staff PASS/RETURN,
DocumentVersion, ReviewDecision, DocumentAudit và Flyway persistence.

## API và transaction

- `POST /api/v1/document-versions/{versionId}/staff-decisions` (API-120).
- Body gồm `decision`, `feedback`, `evidenceRefs`; yêu cầu `If-Match` và `Idempotency-Key`.
- `PASS`: `IN_REVIEW / STAFF_REVIEW` → `IN_REVIEW / MANAGER_APPROVAL`.
- `RETURN_FOR_REVISION`: → `DRAFT`, review stage null, bắt buộc feedback không rỗng.
- PASS cần ít nhất một evidence ID đã lưu đúng tenant/version; tối đa 100, không trùng.
  Đây là giới hạn kỹ thuật của implementation.
- Không nhận target state, tenant, actor hoặc quyền từ body/header tự khai; field lạ bị từ chối.
- Thành công trả `DocumentVersionView` và strong ETag. Contract:
  [s04-document-review.yaml](../../contracts/openapi/s04-document-review.yaml).

```json
{
  "decision": "RETURN_FOR_REVISION",
  "feedback": "Cần bổ sung giải thích thay đổi API.",
  "evidenceRefs": []
}
```

JDBC repositories dùng cùng transaction với `set_config('app.tenant_id', ..., true)` cho RLS.
Service khóa version bằng `FOR UPDATE`, scope rows bằng `FOR SHARE`, kiểm quyền, replay,
revision và state trước conditional update. Một transaction ghi version + decision + audit;
lỗi ghi bất kỳ rollback toàn bộ. Content revisions, decisions và audit cũ được giữ nguyên.
Runtime role chỉ được đọc/thêm decisions và audit, không sửa/xóa chúng.

Idempotency được scope theo tenant/version/reviewer/operation. Exact retry với If-Match, decision,
feedback và danh sách evidence theo thứ tự ban đầu trả response/ETag gốc; nội dung khác với cùng key
trả 409. Request mới với revision cũ trả 412. V3 lưu response snapshot; keyed V2 decisions được
backfill từ audit. Không đủ audit để tái tạo response lịch sử trả `409 REPLAY_SNAPSHOT_UNAVAILABLE`.

## Quyền và xác minh source

JWT RS256 được kiểm signature, issuer, audience và expiration. Thiếu trust configuration thì
token bị từ chối. Cấu hình: `S01_ISSUER_URI`, `S01_JWK_SET_URI`, `S04_TOKEN_AUDIENCE`.
Mapping claims dưới đây là implementation candidate, chưa kiểm chứng bằng token S01 thật:

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
    "repository_ids": ["<authorized repository UUID>"]
  }]
}
```

`exp: 0` chỉ minh họa field, không phải token dùng được. Actor cần active tenant/workspace và
Staff role trong workspace sở hữu document. Document gắn repository cần thêm repository grant;
document không gắn repository dùng Staff scope của workspace. Tenant Owner/Manager không tự có
quyền Staff. S04 đối chiếu resource scope trong schema của mình, không đọc schema S01.
JWT là snapshot quyền; chưa có bằng chứng kiểm quyền thu hồi tức thời qua S01.

Request PASS mới cho document gắn repository yêu cầu `SourceReviewValidator` đáng tin cậy.
Kết quả phải khớp tenant/workspace/repository/version/revision/SHA và còn hiệu lực trước khi ghi.
Thiếu adapter, kết quả unknown/không hợp lệ/hết hạn trả `503 SOURCE_VALIDATION_UNAVAILABLE`;
SHA khác trả `409 SOURCE_CHANGED_REVALIDATION_REQUIRED`. Các ca này không ghi state/decision/audit.
RETURN và exact replay không yêu cầu source check mới.

Chưa có adapter S02 thật; positive source tests dùng provider chỉ được import trong test.
SourceBinding response chỉ có repositoryConnectionId/headSha đã lưu; targetKind, targetRef,
comparisonSha, configRevision và capturedAt hiện null. Không suy diễn source provenance từ SHA.

API đọc version/queue/context (API-106/115/116), UI, Final Approval, Publish và API S02 không được
triển khai trong slice này. Điểm tích hợp còn lại được ghi ở
[handoff](../../docs/implementation/LIV-118-review-handoff.md).

## Migration và chạy service

- V1 tạo schema; V2 thêm aggregate revision/idempotency và siết review stage; V3 thêm response snapshot.
- Khi nâng cấp từ V1, kiểm tra `IN_REVIEW` có review_stage null. V2 từ chối dữ liệu đó;
  stage phải được xác định từ dữ liệu nghiệp vụ trước migration, không tự gán mặc định.
- Test PostgreSQL kiểm chứng V2 lỗi được rollback cả schema/history; sau khi sửa fixture hợp lệ,
  V2 → V3 thành công và giữ lịch sử. Điều này không xác nhận dữ liệu DB triển khai đã sạch.

Chạy với profile `db`; cấu hình `DB_URL`, `DB_SCHEMA=document`, `DB_USERNAME=ld_s04_app`,
`DB_PASSWORD`, `FLYWAY_USERNAME=ld_s04_migrator`, `FLYWAY_PASSWORD` và trust configuration S01.
Service đọc `.env` ở working directory. `SERVER_PORT` mặc định 0 (cổng động).
Runtime và migrator dùng role riêng; môi trường triển khai chịu trách nhiệm TLS.

## Kiểm chứng và bằng chứng

```powershell
cd backend/document-governance-service
mvn -B -ntp clean verify
```

Cần Java 21, Maven và Docker. Mặc định Testcontainers tạo PostgreSQL 17 riêng, bootstrap roles,
chạy V1/V2/V3 và dùng JWT ký bằng RSA test key. `S04_USE_CI_DATABASE=true` chỉ dùng với DB test
tách biệt do CI cung cấp (`DB_URL`, `DB_PASSWORD`, `FLYWAY_PASSWORD`, `S04_TEST_ADMIN_PASSWORD`).

Tests gồm PASS/RETURN/history, token/scope denials, revision/idempotency/concurrency,
evidence validation, audit rollback, migration, RLS và persistence sau application restart.
Restart test đóng/mở Spring context, HTTP server và pool trên cùng DB, không restart JVM/PostgreSQL.

Kết quả, source hashes, report/log references và CI gắn commit được lưu trong
[LIV-118-test-evidence.json](../../docs/implementation/LIV-118-test-evidence.json).
Surefire reports ở `target/surefire-reports`; build outputs không được commit.
Tests dùng authority giả lập không chứng minh tích hợp S01/S02/GitHub thật hoặc deployment TLS.
Nguồn yêu cầu, tác giả, thời gian và giới hạn tin cậy được ghi trong handoff.


## Package layout (LIV-118)

LIV-118 follows the proposed layout in LD-SAD-001 v1.0, section 3.4.2,
as selected for this slice; this is not a backend-wide team mandate.

- `api/controller`: HTTP entry point, errors, correlation and ETags.
- `api/request`, `api/response`: HTTP contract types.
- `application/service`: review orchestration and source-validation port/guard.
- `domain/model`, `domain/policy`: business models and transition rules.
- `infrastructure/persistence`: JDBC repositories.
- `security`: actor resolution and security configuration.

JDBC classes remain concrete persistence implementations; no unused repository
interfaces or feature-level `review` package are introduced.
