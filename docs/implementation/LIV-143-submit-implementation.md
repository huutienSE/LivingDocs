# LIV-143 — API-113 Submit Draft

**Người lập:** Codex. **Cập nhật:** 27/11/2026, UTC+7.
**Nhánh:** `feature/LIV-143-s04-submit-draft-for-staff-review`.
**Phạm vi:** Submit/resubmit Draft, authority, persisted evidence/binding, ETag/idempotency và atomic state/receipt/audit.
Fresh authorization được người dùng yêu cầu triển khai trực tiếp ngày 27/11. Chưa deploy hoặc sửa DB ứng dụng.

## Kết quả triển khai

1. User JWT được S04 xác minh issuer/JWKS/audience/expiry. API-038 lấy authority thật từ S01,
   không lấy role/scope tự khai từ request. Client chung `S01AccessContextClient` xử lý HTTP/parser,
   giữ đầy đủ grants; adapter Submit giữ policy DEVELOPER + DOCUMENT_AUTHOR + WORKSPACE_VIEW và mã lỗi riêng.
2. Read transaction đặt tenant RLS và xác định version/scope. Đóng transaction trước khi xin Service JWT
   và gọi API-039 bằng User JWT + service assertion cho **DOCUMENT_AUTHOR**.
3. Sau ALLOW, write transaction khóa lại version/scope, đối chiếu action/tenant/workspace/repository,
   kiểm quyền local rồi xử lý replay hoặc kiểm revision/state/evidence/binding cho request mới.
4. Chuyển DRAFT → IN_REVIEW/STAFF_REVIEW. Version, submit receipt/snapshot và audit cùng commit/rollback.
   Audit bổ sung authorization decisionId/action/scope/authzRevision/evaluatedAt, không chứa token/secret.
5. Exact replay vẫn cần API-038 và fresh ALLOW mới; giữ body/ETag gốc, không ghi thêm receipt/audit.
6. Public success `SuccessResponse<T>`, RFC 9457 errors, ETag và input budget giữ nguyên.
   Contract **0.3.0** công bố fresh bắt buộc và hành vi repository-free mới.

Code helper/comment có sẵn trong `DocumentSubmitServiceImpl` được giữ và mở rộng, không thay bằng bản service cũ.
Refactor API-038 đã kiểm 91 tests và commit riêng `9d38de5`; hai nhánh liên quan đã nhận chung commit local.
Phần fresh Submit được bàn giao riêng trên nhánh hiện tại; trạng thái phân phối kiểm bằng Git refs, không tự đưa vào develop.

## Giới hạn runtime

| Giới hạn | Hành vi |
|---|---|
| Repository-free Submit | S01 yêu cầu repository ID cho DOCUMENT_AUTHOR; request mới/replay hiện **503 AUTHORIZATION_UNAVAILABLE**. Cần chốt contract trước khi mở positive path |
| Grant production | Positive integration dùng CONTROLLED SQL VERIFIED-grant fixture trong DB tạm; chưa chứng minh Request/Verify/Activate hoặc trusted S02 binding |
| Current source | Submit kiểm persisted SHA/provenance/evidence; không gọi live current-SHA API. Không tự thêm source validation vào scope Submit |
| Revocation | S01 evaluation và commit S04 không nguyên tử xuyên service; chưa bảo đảm instant revocation |
| Deployment/QA | Credential/registry/TLS cần provision thực tế; chưa deploy, remote CI, peer approval hoặc full flow nghiệm thu |
| Gateway | Anonymous security body còn giới hạn RFC 9457 toàn Gateway; Newman kiểm 401/challenge, không công bố global conformance |

Fresh DENY trả 403 FORBIDDEN_SCOPE; user authentication lỗi trả 401 UNAUTHENTICATED;
workload/assertion/config/upstream lỗi trả 503 AUTHORIZATION_UNAVAILABLE. Các nhánh này không mutate DB.
Scope hoặc revision thay đổi sau ALLOW bị chặn trước write. Source/evidence/state guards vẫn giữ.

## Cấu hình và DevOps

API-038 Submit tiếp tục dùng `S01_SUBMIT_ACCESS_CONTEXT_URI` và `S04_SUBMIT_ALLOW_INSECURE_HTTP`.
Shared fresh client của workload s04 dùng các cấu hình đã có:
`S01_REVIEW_SERVICE_TOKEN_URI`, `S01_REVIEW_AUTHORIZATION_URI`, `S04_REVIEW_WORKLOAD_SECRET_PATH`,
`S04_REVIEW_ALLOW_INSECURE_HTTP`. Namespace cũ được giữ tương thích; cùng issuer/credential và operation authorization:evaluate.
Không cache ALLOW, không fallback sang API-038, không giữ private key S01 ở S04.
Mặc định HTTPS; HTTP chỉ là ngoại lệ local tường minh. Không sửa migration V1–V4, Flyway history hoặc `.env` thật.

## Kiểm chứng

| Kiểm tra local | Kết quả |
|---|---|
| Maven S04 cuối | **99/99 PASS**, 0 failures/errors/skips; 2026-11-27T17:27:41+07:00 |
| Runner Gateway/S01/S04 | **36/36 checks PASS**; 2026-11-27T17:29:57+07:00 |
| Newman API-113 | **34 requests / 60 assertions PASS**, 5 phase, 0 failures/skips |
| Regression collection Review | **38 requests / 75 assertions PASS**, 32 runner checks PASS |
| Static contracts/config | **6 OpenAPI VALID**, 114 APIs/91 Gateway entries/19 groups; Compose valid, không deploy |

Lượt này không chạy lại full Gateway Maven suite; không đổi source Gateway hoặc suy ra toàn bộ Gateway checks PASS.
Artifacts nằm trong S04 `target/`; có thể bị clean/ghi đè, manifest giữ summary và hash.

[Collection](../../tests/postman/liv143-submit.postman_collection.json) và runner đã cập nhật fixture repository-bound,
kiểm positive Submit/Return/resubmit/replay, PENDING grant, repository-free 503, revoke/outage và credential workload sai.
Runner kiểm state/revision/receipt/audit và fresh decision trong DB. JWT, API-038, Service JWT và API-039 gọi S01 thật;
grant/Draft vẫn là controlled SQL fixtures. Raw credential/environment/report chỉ ở thư mục tạm và được dọn.

```powershell
# Working directory: repository root; cần JAR S01/S04/Gateway đã build
python -X utf8 scripts/test_api113_s01_integration.py --gateway-newman --newman-cli "$env:TEMP/livingdocs-newman/node_modules/newman/bin/newman.js"
```

Lượt đầu refactor gặp Docker engine chưa chạy, đã khởi động nền và chạy lại 91/91 PASS.
Lượt đầu fresh có một assertion cũ đếm một lần evaluate; đã sửa thành hai lần cho Submit + Staff Return.
Không tính các lượt lỗi là PASS. Evidence/hash/logs và kết quả lịch sử: [manifest](LIV-143-test-evidence.json).

## Master Decision và nguồn

Layered Service interface/Impl, JPA/Flyway/RLS, DTO/MapStruct, SuccessResponse/RFC 9457 và atomic persistence
được giữ. Fresh API-039 đã triển khai theo yêu cầu người dùng; LD-D17 được kiểm qua Maven + Newman + HTTP/DB thật.
Không suy ra full Golden Flow hoặc Story Done từ kết quả của slice này.

- [Jira LIV-143](https://huutiense25.atlassian.net/browse/LIV-143): nguồn scope đã đối chiếu ở phiên trước;
  không cập nhật hoặc xác nhận Jira status mới trong lượt này; tác giả description chưa xác minh độc lập.
- [S01 contract](../../contracts/openapi/s01-identity.yaml): author Git huutienSE, 24/11/2026;
  action DOCUMENT_AUTHOR được đối chiếu với implementation và HTTP thật, không đoán action DOCUMENT_SUBMIT.
- [Master Decision](../engineering/LivingDocs_Master_Decisions_Technical_Rules_Team_Agent_Handoff_2026-11-13.md)
  và [Standards v3](../engineering/LivingDocs_Backend_Engineering_Standards_v3.md): Owner snapshot 13/11/2026,
  nguồn quy tắc nội bộ; bản bàn giao không tự thay approved contract hoặc xác nhận runtime.
- Codex thực hiện và ghi evidence local 27/11; chưa có reviewer/QA độc lập hoặc remote CI cho diff mới.
