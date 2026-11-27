# LIV-118 — API-120 Staff Decision

**Người lập:** Codex theo yêu cầu người dùng. **Cập nhật:** 26/11/2026, UTC+7.
**Nơi lưu:** LivingDocs, nhánh `feature/LIV-118-s04-review-state-persistence`.
**Trạng thái kiểm chứng:** đã kiểm thử local; chưa xác minh remote CI hoặc deployment; không thay DB ứng dụng.

## Kết quả trong phạm vi LIV-118

API-120 `POST /api/v1/document-versions/{versionId}/staff-decisions` đã có persistence và guards
cho Staff PASS/RETURN. S04 xác minh User JWT, lấy context API-038, xin Service JWT từ S01
và gọi fresh authorization API-039 cho từng request, kể cả exact replay.

Read transaction xác định resource/scope dưới RLS được đóng trước HTTP S01. Sau ALLOW,
write transaction khóa lại resource, kiểm scope/revision/state/evidence/idempotency,
rồi lưu version, decision, response snapshot và audit nguyên tử.
Audit ghi actor và authorization decisionId/action/scope/authzRevision/evaluatedAt, không lưu token/secret.

| Hạng mục | Kết quả |
|---|---|
| PASS | Sang Manager Approval khi đủ quyền/evidence/source validation; positive component test dùng source provider test |
| RETURN_FOR_REVISION | Về DRAFT, bắt buộc feedback; đã kiểm mutation thật với S01/S04 |
| RLS và scope | Kiểm tenant/workspace/repository, khóa và kiểm lại scope sau ALLOW |
| ETag/idempotency | Xung đột bị từ chối; exact replay giữ body/ETag, không thêm decision/audit |
| Atomic persistence | Version/decision/snapshot/audit cùng transaction; có rollback tests |
| Fresh/source guards | PASS/RETURN/replay cần evaluate mới; lỗi fail closed; ALLOW không thay bằng chứng source |

## Giới hạn và tác động runtime

| Giới hạn | Hành vi và phần còn thiếu |
|---|---|
| Repository-free | S01 yêu cầu repository ID cho DOCUMENT_*; PASS/RETURN/replay hiện **503 AUTHORIZATION_UNAVAILABLE**. Cần chốt contract trước khi mở positive path |
| Grant production | Action cần grant effective. Test dùng **CONTROLLED SQL VERIFIED-grant fixture** trong DB tạm; chưa chứng minh onboarding/verification production |
| Thiếu S02 adapter | PASS repository-bound sau fresh ALLOW trả **503 SOURCE_VALIDATION_UNAVAILABLE** nếu thiếu validator; RETURN/replay không gọi source check mới |
| Source binding Review | Model/request chưa nối đủ target/ref/config provenance và trust contract tới S02; legacy fields có thể null. Không suy diễn SHA hoặc tự backfill |
| Revocation | Evaluation tại S01 và commit S04 là hai thời điểm; chưa có atomic revocation guarantee |
| Deployment/nghiệm thu | Cần workload s04, allowlist, caller secret ngoài Git và HTTPS. Chưa có deployment, remote CI/peer approval/QA hoặc full Gateway/UI/S02/GitHub flow |

Contract API-120 **0.3.0** giữ success envelope/ETag/RFC 9457 hiện tại;
client cần biết giới hạn repository-free và source adapter trước khi sử dụng.

## Đối chiếu Master Decision

| Quyết định | Bằng chứng trong LIV-118 |
|---|---|
| LD-D02/03/06 | Layered, Service interface/Impl, DTO class/DI, JPA persistence và MapStruct mapping |
| LD-D04/05 | Flyway, `ddl-auto=validate`, transaction-local tenant context; isolation/rollback/locking tests |
| LD-D10 | S01 JWT/JWKS/API-038/Service JWT/API-039 thật trong runner; local guards kiểm lại trước write |
| LD-D11/12 | Typed success, RFC 9457/stable code/correlation, static/runtime contract và HTTP tests |
| LD-D17 | Collection riêng API-120; runner kiểm state/revision/decision/audit sau mutation/deny/replay |

Các mục trên có implementation và kiểm chứng trong phạm vi công bố. Positive PASS với S02 thật,
grant production và full flow chưa đạt; không suy ra toàn Master Decision hoặc Story đã Done.

## Collection Newman và cách chạy

[Collection LIV-118](../../tests/postman/liv118-review.postman_collection.json) có 5 phase:
Staff review, workload unavailable, revoked Staff, revoked tenant và S01 unavailable.
Bao phủ RETURN/replay, ETag/idempotency, feedback/evidence, JWT/scope, repository-free,
PASS thiếu adapter và authority failures. Không skip case để báo PASS.

Runner `scripts/test_api120_s01_integration.py` dựng hai JVM S01/S04, PostgreSQL/Redis tạm,
sinh workload credential/registry và environment Newman, đổi trạng thái giữa các phase,
kiểm DB và dọn tài nguyên. Không đọc `.env` hoặc dùng DB ứng dụng.
Raw environment/report có credential chỉ ở thư mục tạm; manifest giữ summary/hash đã loại credential.

```powershell
# Working directory: repository root; cần JAR S01/S04, Java 21, Docker, Python 3.12 và Node
npm.cmd install --prefix "$env:TEMP/livingdocs-newman" newman@6.2.3 --no-audit --no-fund
python -X utf8 scripts/test_api120_s01_integration.py --newman --newman-cli "$env:TEMP/livingdocs-newman/node_modules/newman/bin/newman.js"
```

Phải chạy qua runner hoặc chuẩn bị đúng credential/authority/outage cho từng phase;
không chạy tất cả folder trên môi trường giữ nguyên rồi coi là kết quả hợp lệ.
Collection đi trực tiếp S01/S04, không cần deploy thành service. PASS với evidence hợp lệ hiện kiểm
**503 khi thiếu S02**; đó là bằng chứng source guard, không phải positive business PASS.

## Kiểm chứng

- Regression API-120 và security/persistence dùng chung: **68/68 tests PASS** trong lượt Maven verify S04,
  26/11/2026 21:09:37 UTC+7; danh sách suite được phân loại trong manifest.
- Collection Newman riêng: **38 requests / 75 assertions PASS**, 5 phase, 0 failures/skips.
- Runner S01/S04 thật: **32/32 kiểm tra PASS**; kết thúc **26/11/2026 21:34:45 UTC+7**.
  DB readback xác nhận RETURN chỉ tạo một decision/audit; replay và các nhánh deny không ghi thêm.
- Static: 6 OpenAPI hợp lệ, inventory không lộ internal API; Compose chỉ validate, chưa deploy.
- [Manifest LIV-118](LIV-118-test-evidence.json) giữ hash source/JAR/runner/collection và logs.
  Kết quả cũ được giữ như lịch sử, không thay kết quả collection hiện tại.

## Nguồn và độ tin cậy

- [LIV-118](https://huutiense25.atlassian.net/browse/LIV-118), Jira LivingDocs: scope/AC đối chiếu 26/11;
  tác giả description chưa xác định độc lập. Jira status không tự chứng minh implementation PASS.
- [S01 OpenAPI](../../contracts/openapi/s01-identity.yaml) và [API-120 OpenAPI](../../contracts/openapi/s04-document-review.yaml),
  repository LivingDocs: contract đối chiếu với code/runtime; YAML đơn lẻ không là evidence E2E.
  Git ghi S01 author huutienSE, 24/11/2026 (`b4af4fa`); API-120 baseline author nguyenxuanhai-arch,
  23/11/2026 (`72a8311`), phần consumer/contract hiện tại được Codex cập nhật local 26/11.
- [Master Decision](../engineering/LivingDocs_Master_Decisions_Technical_Rules_Team_Agent_Handoff_2026-11-13.md)
  và [Standards v3](../engineering/LivingDocs_Backend_Engineering_Standards_v3.md): nguồn nội bộ của Owner,
  snapshot 13/11/2026; bản bàn giao không tự thay approved contract hoặc xác nhận runtime.
- Codex kiểm tra/chạy/report local ngày 26/11. Controlled fixtures ghi rõ;
  chưa có reviewer/QA độc lập hoặc remote CI xác nhận thay đổi này.
