# S04 — Staff Review và Submit Draft

Spring Boot 4.1.1 / Java 21 / PostgreSQL 17. Phạm vi: Staff PASS/RETURN,
DocumentVersion, ReviewDecision, DocumentAudit và Flyway persistence.

LIV-143 bổ sung `POST /api/v1/document-versions/{versionId}/submit` (API-113):
Developer có `DOCUMENT_AUTHOR` + `WORKSPACE_VIEW` và repository grant khi áp dụng,
submit/resubmit `DRAFT → IN_REVIEW / STAFF_REVIEW`. Body nhận `note` nullable tối đa
2000 ký tự; yêu cầu strong `If-Match` và `Idempotency-Key`. Evidence và source binding
lấy từ canonical S04 DB, không nhận actor/scope/target state từ client.
Submit đọc evidence bằng JPA/JPQL có tenant/version scope và provenance qua field
read-only của Entity; không còn native SQL trong `DocumentSubmitRepository`.
State, submit receipt và audit commit cùng transaction; exact replay kiểm quyền lại
qua API-038 và trả response/ETag gốc. Chi tiết, kiểm chứng và giới hạn:
[LIV-143 report](../../docs/implementation/LIV-143-submit-implementation.md).
Collection [LIV-143 Newman](../../tests/postman/liv143-submit.postman_collection.json)
kiểm Submit/replay/Return/resubmit/revoke/outage qua Gateway thật bằng runner local.
Lệnh chạy, cách tạo fixture DB tạm và các blocker Gateway/fresh nằm trong report.

Cấu hình thêm `S01_SUBMIT_ACCESS_CONTEXT_URI` và `S04_SUBMIT_ALLOW_INSECURE_HTTP`
(HTTP chỉ cho local). API-038 là snapshot, chưa thay cho evaluator API-039;
Submit chưa gọi current-SHA API-070. Không suy ra fresh/source guards chỉ cần trước Publish.

## API và transaction

- `POST /api/v1/document-versions/{versionId}/staff-decisions` (API-120).
- Body gồm `decision`, `feedback`, `evidenceRefs`; yêu cầu `If-Match` và `Idempotency-Key`.
- `PASS`: `IN_REVIEW / STAFF_REVIEW` → `IN_REVIEW / MANAGER_APPROVAL`.
- `RETURN_FOR_REVISION`: → `DRAFT`, review stage null, bắt buộc feedback không rỗng.
- PASS cần ít nhất một evidence ID đã lưu đúng tenant/version; tối đa 100, không trùng.
  Đây là giới hạn kỹ thuật của implementation.
- Không nhận target state, tenant, actor hoặc quyền từ body/header tự khai; field lạ bị từ chối.
- Thành công trả `SuccessResponse<DocumentVersionView>` và strong ETag. Contract:
  [s04-document-review.yaml](../../contracts/openapi/s04-document-review.yaml).

```json
{
  "decision": "RETURN_FOR_REVISION",
  "feedback": "Cần bổ sung giải thích thay đổi API.",
  "evidenceRefs": []
}
```

Response HTTP 200 có dạng:

```json
{
  "code": "STAFF_REVIEW_RECORDED",
  "message": "Staff review decision recorded",
  "data": {
    "documentId": "11111111-1111-1111-1111-111111111111",
    "versionId": "22222222-2222-2222-2222-222222222222",
    "state": "DRAFT",
    "reviewStage": null,
    "revision": 1,
    "sourceBinding": null
  }
}
```

Contract `0.2.0` thay response phẳng bằng `{code, message, data}`; consumer đọc các field version
qua `data`. HTTP status, strong ETag và lỗi RFC 9457 giữ nguyên. `model.SuccessCode` cung cấp
wire code/message; không có `success/status` trong envelope và không bọc Problem Details.

Spring Data JPA/Hibernate repositories dùng cùng transaction với `set_config('app.tenant_id', ..., true)` cho RLS.
Service khóa version bằng `FOR UPDATE`, scope rows bằng `FOR SHARE`, kiểm quyền, replay,
revision và state trước update có optimistic lock `@Version`. Một transaction ghi version + decision + audit;
lỗi ghi bất kỳ rollback toàn bộ. Content revisions, decisions và audit cũ được giữ nguyên.
Runtime role chỉ được đọc/thêm decisions và audit, không sửa/xóa chúng.

Ba entity nằm trong `entity`, tách khỏi immutable business models trong `model`.
Version chỉ map các cột cần cho review; những cột tạo version/content không thuộc write path này.
Decision/audit dùng `@Immutable`, UUID cấp trước và `Persistable.isNew()` để Spring Data gọi
`persist` cho row mới. MapStruct chuyển domain sang entity; JSONB giữ response snapshot/metadata
với data version như cũ. `ddl-auto: validate`, `open-in-view: false`; Flyway tiếp tục sở hữu DDL.
Native SQL chỉ còn tenant context, khóa scope và bulk evidence check; lý do nằm ngay tại query.

Idempotency được scope theo tenant/version/reviewer/operation. Exact retry với If-Match, decision,
feedback và danh sách evidence theo thứ tự ban đầu trả response/ETag gốc; nội dung khác với cùng key
trả 409. Request mới với revision cũ trả 412. V3 lưu response snapshot; keyed V2 decisions được
backfill từ audit. Snapshot mới lưu cả envelope để replay giữ nguyên code/message/data, kể cả khi
default message thay đổi ở lần deploy sau. Snapshot cũ chỉ có version view được bọc khi đọc;
không rewrite decision/audit hay migration. Không đủ audit để tái tạo response lịch sử trả
`409 REPLAY_SNAPSHOT_UNAVAILABLE`.

Các lỗi nghiệp vụ dùng `ReviewException` abstract và exception theo điều kiện cụ thể như
`DocumentVersionNotFoundException`, `ReviewRevisionConflictException`, `InvalidReviewTransitionException`,
`ReviewIdempotencyConflictException`, `ReturnFeedbackRequiredException`, `SourceChangedException` và
`SourceValidationUnavailableException`. Lỗi transport dùng `ReviewRequestException` với typed code.
`ReviewErrorCode` giữ wire code/HTTP status; advice và security filter dùng cùng registry.
Các lỗi transport trả 405 `METHOD_NOT_ALLOWED`, 406 `NOT_ACCEPTABLE`, 415 `UNSUPPORTED_MEDIA_TYPE`
với Problem Details. HTTP 405 giữ header `Allow`. Correlation ID theo Gateway nhận 1–64 ký tự
chữ/số/dấu chấm/gạch dưới/gạch ngang, bắt đầu bằng chữ hoặc số; ID không hợp lệ được thay bằng UUID.

Controller chuyển `ReviewRequest` thành `StaffDecisionCommand` và nhận `StaffReviewResult` từ service.
`StaffDecisionMapper` chuyển result sang response DTO; Controller dựng success envelope/ETag.
Business result giữ receipt của lần ghi đầu để replay không bị thay default message mới.
Service không nhận/trả public DTO hoặc success envelope.

`ReviewSnapshotCodec` là port nhận/trả business result và chuỗi snapshot opaque;
`JacksonReviewSnapshotCodec` ở mapper adapter xử lý DTO/JSON và tương thích snapshot cũ.
Repository chỉ đọc/ghi snapshot string, không chọn success code hoặc deserialize public response.
Encoding và lưu snapshot diễn ra trước commit cùng version/decision/audit; lỗi encoding rollback
toàn bộ và không tiêu thụ idempotency key. Không dùng callback sau commit hoặc transaction ở Controller.

## Quyền và xác minh source

JWT RS256 được kiểm signature, issuer, audience và expiration. Thiếu trust configuration thì
token bị từ chối. Cấu hình: `S01_ISSUER_URI`, `S01_JWK_SET_URI`, `S04_TOKEN_AUDIENCE`.
JWT thật của S01 xác thực Identity qua `sub`; S04 không lấy tenant/roles/grants từ JWT.
Mỗi request API-120, kể cả exact replay, chuyển nguyên User JWT qua Bearer đến API-038
`GET /api/v1/users/me/access-context`, trước khi mở write transaction ở S04. Không cache context.
Adapter kiểm Identity khớp `sub`, tenant UUID, revision, thời điểm evaluation và workspace grants.
Chỉ grant có đồng thời `STAFF` và `DOCUMENT_STAFF_REVIEW` mới được dùng tại workspace đó.
Tenant Owner/Manager không tự có quyền Staff; role ở workspace khác không được gộp quyền.
Repository-backed version cần repository ID trong grant của cùng workspace; version không gắn
repository chỉ cần Staff scope. S04 đối chiếu scope dưới RLS trong schema của mình, không đọc DB S01.

Cấu hình thêm: `S01_REVIEW_ACCESS_CONTEXT_URI` trỏ đúng API-038; mặc định yêu cầu HTTPS.
HTTP local cần bật tường minh `S04_REVIEW_ALLOW_INSECURE_HTTP=true` (xem `.env.example`/Compose).
Client không follow redirect, giới hạn response 256 KiB, connect timeout 2 giây và tổng deadline 3 giây.
Thiếu endpoint, network/timeout, upstream lỗi hoặc context hỏng/sai Identity trả
`503 AUTHORIZATION_UNAVAILABLE`, không ghi state/decision/audit và không fallback sang JWT claims.
S01 401/403 trả S04 401/403; tenantId null hoặc thiếu quyền trả 403. Version tenant khác trả 404 dưới RLS.
Replay vẫn phải có quyền tại lần lookup mới và S01 hoạt động.

API-038 là snapshot quyền, **không phải API-039 fresh evaluation**. Thu hồi trước lần lookup kế tiếp
được áp dụng với cùng JWT; không bảo đảm nguyên tử giữa thu hồi quyền và commit ở S04.
API-120 chưa tích hợp fresh evaluator API-039 theo yêu cầu của
[runbook S01](../../docs/implementation/authorization/LIV-138-s01-authority-and-testing.md).
Không có quyết định của người dùng cho phép API-038 thay bước fresh. Kiểm tra ngày
23/11/2026 chưa tìm thấy evaluator/service assertion trong source S01 hoặc runtime
OpenAPI hiện truy cập được; nhánh S01 fresh auth chưa có commit khác `develop`.
[Báo cáo triển khai](../../docs/implementation/LIV-118-implementation-report.md) ghi
bằng chứng dependency và các bước còn thiếu; chưa nghiệm thu đầy đủ auth API-120.

Request PASS mới cho document gắn repository yêu cầu `SourceReviewValidator` đáng tin cậy.
Kết quả phải khớp tenant/workspace/repository/version/revision/SHA và còn hiệu lực trước khi ghi.
Thiếu adapter, kết quả unknown/không hợp lệ/hết hạn trả `503 SOURCE_VALIDATION_UNAVAILABLE`;
SHA khác trả `409 SOURCE_CHANGED_REVALIDATION_REQUIRED`. Các ca này không ghi state/decision/audit.
RETURN và exact replay không yêu cầu source check mới.

Chưa có adapter S02 thật; positive source tests dùng provider chỉ được import trong test.
SourceBinding response chỉ có repositoryConnectionId/headSha đã lưu; targetKind, targetRef,
comparisonSha, configRevision và capturedAt hiện null. Không suy diễn source provenance từ SHA.

API đọc version/queue/context (API-106/115/116), UI, Final Approval, Publish và API S02 không được
triển khai trong slice này. Các giới hạn tích hợp S01/S02 và SourceBinding được mô tả ở trên.

## Kiểm thử tích hợp S01 thật

Từ repository root, build hai service rồi chạy runner:

```powershell
mvn -B -ntp -f backend/identity-organization-service/pom.xml package
mvn -B -ntp -f backend/document-governance-service/pom.xml clean verify
python -X utf8 scripts/test_api120_s01_integration.py
```

Cần Java 21, Docker và Python 3.12. Runner tạo PostgreSQL 17/Redis tạm, sinh khóa chỉ cho S01,
chạy hai JVM từ jar hiện tại, đăng ký/login qua HTTP thật và đọc quyền qua API-038 thật.
Fixtures authority/document được tạo bằng SQL **chỉ trong DB test riêng**; không kiểm chứng operator CLI.
Không đọc `.env`, không dùng DB Compose hiện có, không tạo VERIFIED repository grant để bỏ qua S02.
Runner xóa đúng container tạm và dừng hai JVM khi kết thúc, ghi kết quả không chứa credential vào
`target/api120-s01-integration.json` cùng service logs trong `target/` của S04.

Positive PASS/RETURN dùng document không gắn repository. Negative tests kiểm unprovisioned Identity,
cross-tenant/workspace, grant PENDING, revoked role/membership, retry và S01 unavailable.
Bộ Maven dùng API-038 contract stub/test issuer cho component tests; đây là bằng chứng riêng với
runner hai service thật. Không suy ra Gateway/S02/repository-backed Golden Flow đã PASS.

## Migration và chạy service

- V1 tạo schema; V2 thêm aggregate revision/idempotency và siết review stage; V3 thêm response snapshot.
- V4 thêm source provenance nullable và submit receipt append-only có tenant RLS.
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
chạy V1–V4 và dùng JWT ký bằng RSA test key. `S04_USE_CI_DATABASE=true` chỉ dùng với DB test
tách biệt do CI cung cấp (`DB_URL`, `DB_PASSWORD`, `FLYWAY_PASSWORD`, `S04_TEST_ADMIN_PASSWORD`).

Tests gồm PASS/RETURN/history, token/scope denials, revision/idempotency/concurrency,
evidence validation, audit rollback, migration, RLS và persistence sau application restart.
Restart test đóng/mở Spring context, HTTP server và pool trên cùng DB, không restart JVM/PostgreSQL.

Kết quả, source hashes, report/log references và CI gắn commit được lưu trong
[LIV-118-test-evidence.json](../../docs/implementation/LIV-118-test-evidence.json).
Kết quả tích hợp S01 và giới hạn nghiệm thu: [API-120/S01 evidence](../../docs/implementation/authorization/LIV-118-api120-s01-integration.md).
Surefire reports ở `target/surefire-reports`; build outputs không được commit.
Tests dùng authority giả lập không chứng minh tích hợp S01/S02/GitHub thật hoặc deployment TLS.
Nguồn yêu cầu và giới hạn tin cậy nằm trong bảng tham chiếu bên dưới.

### DTO, mapping và runtime OpenAPI

Request/response API-120 dùng Java class với private fields và Lombok, không dùng `@Data`.
Request giữ immutable input, chuẩn hóa evidence null/omitted thành danh sách rỗng.
Request dùng `@Size(max=100)`, `@UniqueEvidenceRefs` và container-element `@NotNull`;
header Idempotency-Key dùng `@NotNull`/`@Pattern`. Advice giữ lỗi evidence quá giới hạn/trùng
là 422 với typed code, lỗi cấu trúc request/header là 400. Business command giữ invariant cho
caller trực tiếp của service. Constructor DTO chỉ chuẩn hóa/copy input trước Bean Validation.
`mapper/StaffDecisionMapper` dùng MapStruct Spring component với
`unmappedTargetPolicy = ERROR`; mapper chỉ chuyển dữ liệu, không truy cập DB hoặc quyết định quyền.
Repository-free document trả `sourceBinding = null`; các thuộc tính source chưa lưu tiếp tục null.
Response class vẫn đọc được JSON snapshot V3 trước đây; legacy replay giữ version data/ETag và
thêm envelope mới. Snapshot được ghi từ rollout này giữ nguyên toàn bộ body khi exact replay.

Springdoc sinh runtime spec cho API-120 và API-113. Mặc định `S04_OPENAPI_ENABLED=false`; bật biến này
trong môi trường dev/test cần đối chiếu rồi khởi động lại S04. `GET /v3/api-docs` (JSON) và
`GET /v3/api-docs.yaml` yêu cầu `Authorization: Bearer <verified JWT>` theo trust config S01.
Không mở anonymous docs, Swagger UI S04 hoặc route docs qua Gateway. Docker Swagger tĩnh hiện tại
được giữ nguyên. Tests so sánh method/path/status/media types, header preconditions, schema fields,
required fields, types/formats/enums và evidence limits giữa YAML và runtime; đồng thời kiểm tra
resource OpenAPI không được đăng ký khi tắt và snapshot lịch sử vẫn replay được.
Customizer bổ sung giá trị JSON null vào nullable enum trong OpenAPI 3.0; parity test
đối chiếu cả nullability và toàn bộ enum, bao gồm null.

Dependency: Springdoc 3.2.0 như S01; MapStruct 1.6.3 cùng Lombok do Spring Boot quản lý và
`lombok-mapstruct-binding` 0.2.0 theo [MapStruct reference](https://mapstruct.org/documentation/stable/reference/html/#_lombok).
Xem [Springdoc WebMVC API module](https://springdoc.org/#spring-webmvc-support) về runtime spec.


## Package layout (LIV-118)

Cập nhật 14/11/2026 theo [Backend Engineering Standards v3](../../docs/engineering/LivingDocs_Backend_Engineering_Standards_v3.md)
và [Coding Standards Amendment §2](../../docs/implementation/LivingDocs_Backend_Coding_Standards_Amendment_2026-11-13.md#2-cấu-trúc-chuẩn).
Cây package S04 hiện tại thay cấu trúc SAD được chọn trước đây cho LIV-118:

```text
com/livingdocs/document/
├── DocumentGovernanceServiceApplication.java
├── controller/       # Staff decision endpoint và strong ETag
├── dto/
│   ├── request/
│   └── response/
├── service/          # DocumentReviewService interface và source guard
│   └── impl/         # DocumentReviewServiceImpl, transaction boundary
├── entity/           # JPA mappings: version, decision, audit
├── repository/       # JpaRepository và adapter khóa/RLS đặc biệt
├── model/            # Business models, enums và transition policy
├── mapper/           # MapStruct domain ↔ entity, domain → response DTO
├── validation/       # Custom constraint cho evidence IDs không trùng
├── exception/        # ReviewException, advice và Problem Details
├── security/         # JWT trust và actor/resource scopes
├── config/           # Correlation filter và OpenAPI
└── client/           # SourceReviewValidator integration port
```

Các package `api`, `application`, `domain`, `infrastructure` cũ đã được chuyển sang cây trên.
Spring application vẫn ở root `com.livingdocs.document` để scan các component.

## Nguồn yêu cầu và giới hạn tin cậy

| Nguồn | Tác giả / thời gian / nơi đăng | Giá trị và giới hạn |
| --- | --- | --- |
| [Master Decisions](../../docs/engineering/LivingDocs_Master_Decisions_Technical_Rules_Team_Agent_Handoff_2026-11-13.md) | Nguồn chính do Owner chọn; snapshot 13/11/2026; Git ghi tác giả huutienSE tại e32b1c3 ngày 14/11/2026; repository docs/engineering. | Baseline cho kiến trúc, JPA, DTO, typed errors và success wrapper. Tài liệu bàn giao chờ Owner review, không tự là ADR hoặc bằng chứng runtime; các gate đề xuất chưa thành quy trình team. |
| [LIV-118](https://huutiense25.atlassian.net/browse/LIV-118) | Reporter TIỀN HUỲNH PHẠM HỮU; cập nhật 08/11/2026; Jira LivingDocsPro; đọc trực tiếp 14/11/2026. | Nguồn chính cho scope DocumentVersion/ReviewDecision/DocumentAudit, guarded PASS/RETURN, unit+DB tests và state/audit sau restart. Không phải bằng chứng implementation PASS. |
| [PR #26](https://github.com/huutienSE/LivingDocs/pull/26) | Tác giả tài khoản nguyenxuanhai-arch; tạo 07/11/2026; GitHub huutienSE/LivingDocs; đọc trực tiếp 08/11/2026. | Code tại 69d0c03 và check runs là nguồn trực tiếp cho implementation/CI của commit đó; không bao phủ thay đổi local sau commit. |
| [LD-API-001](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/11337744) | Confluence LPKB; page version 2, nội dung v0.4; tác giả version TIỀN HUỲNH PHẠM HỮU, 24/10/2026; đọc trực tiếp 14/11/2026. | Thiết kế API-120, ETag/idempotency/Problem; tài liệu tự ghi candidate, chưa Accepted ADR/G2/runtime evidence. Không xác nhận claim S01 hay wire contract S02 đã chốt. |
| [REQ-05](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/3702785) | Confluence LPKB; page version 6; tác giả version HAI NGUYEN XUAN, 21/10/2026 (UTC+7), nội dung đề ngày 20/10/2026; đọc trực tiếp 14/11/2026. | Tham chiếu transition T-DOC-03/04B; nội dung tự ghi FINAL CANDIDATE, không dùng thay test code hoặc xác nhận nghiệm thu. |
| [LD-ARCH-005](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/10715150) | Confluence LPKB; page version 1, nội dung v1.4; tác giả version TIỀN HUỲNH PHẠM HỮU, 23/10/2026; đọc trực tiếp 14/11/2026. | Tham chiếu scope/RLS/service ownership; nguồn ghi reviewed candidate/pending ADR. |
| [Backend Engineering Standards v3](../../docs/engineering/LivingDocs_Backend_Engineering_Standards_v3.md) | Repository `docs/engineering/`; tài liệu đề ngày 13/11/2026; Git ghi tác giả huutienSE tại commit e32b1c3 ngày 14/11/2026. | Nguồn nội bộ cho yêu cầu Service interface/implementation; tự ghi Owner decision. Metadata Git không thay independent approval hoặc runtime evidence. |

Các mốc đọc nguồn trong bảng là lịch sử đối chiếu, không phải trạng thái remote mới nhất.
Tài liệu dự án cung cấp scope/contract; source/report/log chỉ xác nhận phiên bản và môi trường
được ghi rõ. Local PASS không thay peer approval hoặc nghiệm thu parent Story.
