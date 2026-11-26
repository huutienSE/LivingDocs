# LIV-118 — API-120 tích hợp S01 JWT, API-038 và API-039

**Người lập:** Codex. **Cập nhật:** 26/11/2026, UTC+7.
**Baseline:** worktree LIV-118 trên `ef0f9ec`, đã nhận S01 PR #34 từ develop.
**Phạm vi:** consumer fresh authorization cho Staff PASS/RETURN; không triển khai upstream S01/S02 hoặc Grant lifecycle.

**Kết quả hiện tại:** xem [report cuối LIV-118](../LIV-118-implementation-report.md)
cho kết quả Maven và collection Newman riêng API-120. Số 68/26 bên dưới là snapshot lịch sử.

## Luồng hiện tại

```mermaid
sequenceDiagram
  participant U as Client
  participant S4 as S04
  participant S1 as S01
  participant D as PostgreSQL S04
  U->>S4: API-120 + User JWT + If-Match + Idempotency-Key
  S4->>S4: Verify User JWT signature/issuer/audience/exp
  S4->>S1: API-038 + User JWT
  S1-->>S4: Identity + tenant/workspace roles/permissions/repository access
  S4->>D: Read transaction: tenant RLS + resource/scope
  D-->>S4: Scope snapshot; close transaction
  S4->>S1: Service token issuance + Basic s04 credential + actor JWT hash/action/scope
  S1-->>S4: S01-signed Service JWT (60 seconds)
  S4->>S1: API-039 + User JWT + X-LD-Service-Assertion
  S1-->>S4: ALLOW / DENY / error
  S4->>D: After ALLOW: write transaction, lock/recheck scope/state/revision
  S4->>S4: Replay or guarded PASS/RETURN; PASS keeps source guard
  S4->>D: Atomic version + decision + snapshot + audit
  S4-->>U: Original success/ETag or RFC 9457 Problem
```

API-038 context is not fresh ALLOW. Both PASS and RETURN, including an exact retry, require a new API-039 decision.
Workload authentication and user authorization are separate. S04 passes the unmodified User JWT to S01 and hashes
its raw bytes for actorTokenSha256. The Service JWT is minted by S01 for exactly
`authorization:evaluate`, `livingdocs-s01-internal`, POST `/internal/v1/authorization/evaluate`,
`DOCUMENT_STAFF_REVIEW` and the resolved tenant/workspace/repository scope.

No S01 HTTP call runs inside a S04 database transaction. The write transaction rechecks the authorization scope,
then locks/version guards and idempotency rules apply. A concurrent workspace/repository move or stale revision
cannot reuse an ALLOW for a different scope. Audit metadata stores the decisionId, action, scope,
authzRevision and evaluatedAt; credentials/tokens are never stored in audit or returned in Problem responses.

## Cấu hình caller

| Biến S04 | Ý nghĩa |
|---|---|
| S01_ISSUER_URI / S01_JWK_SET_URI / S04_TOKEN_AUDIENCE | Xác minh User JWT |
| S01_REVIEW_ACCESS_CONTEXT_URI | API-038 |
| S01_REVIEW_SERVICE_TOKEN_URI | POST /internal/v1/auth/service-tokens |
| S01_REVIEW_AUTHORIZATION_URI | POST /internal/v1/authorization/evaluate |
| S04_REVIEW_WORKLOAD_SECRET_PATH | File secret riêng của workload s04, ngoài Git; S01 registry giữ BCrypt hash tương ứng |
| S04_REVIEW_ALLOW_INSECURE_HTTP | false mặc định; true chỉ cho local HTTP exception |

Hai internal URI phải cùng trusted origin. Không follow redirect. Mỗi response bị giới hạn 256 KiB;
connect timeout 2 giây, deadline toàn response 3 giây. Không cache Service JWT hoặc ALLOW.
Decision phải echo đúng action/scope, UUID/revision hợp lệ và evaluatedAt thuộc lần gọi hiện tại với
tolerance clock skew 5 giây. Thiếu config/secret hoặc không đọc được file phải fail closed.

Compose mount thư mục riêng `S04_WORKLOAD_SECRETS_DIR` vào `/run/secrets/s04`, chỉ cần `s04.secret`.
Không mount thư mục private key S01 vào S04. Operator phải đăng ký workload s04 với đúng tenant allowlist,
audience và operation; application không tự tạo hoặc nới registry.

## Error và replay

- API-039 HTTP 200 DENY → S04 403 REVIEW_FORBIDDEN.
- User JWT bị S01 từ chối với UNAUTHENTICATED → 401.
- Service credential/assertion lỗi, transport denied, upstream unavailable, body sai/sai scope,
  decision cũ hoặc timeout → 503 AUTHORIZATION_UNAVAILABLE.
- Sau ALLOW, local state/evidence/revision guards vẫn có thể từ chối. Scope/revision thay đổi → 412.
- Replay không bỏ qua API-038/API-039, không tạo decision/audit mới và không gọi source check mới.
- Source stale/unavailable vẫn theo guard hiện hữu; fresh ALLOW không thay bằng chứng source.

## Giới hạn bắt buộc công bố

1. S01 PR2 yêu cầu repository ID cho mọi DOCUMENT_* và grant effective. Document không gắn repository
   hiện trả 503 AUTHORIZATION_UNAVAILABLE cho PASS/RETURN/replay. Đây là giới hạn contract, không phải fallback
   hay quyết định tự mở quyền cho manual documents.
2. Grant VERIFIED trong runner được tạo bằng SQL trong PostgreSQL tạm và ghi nhãn CONTROLLED FIXTURE.
   Nó không chứng minh named Operator, S02 binding hoặc LIV-162 production lifecycle.
3. S04 chưa có production SourceReviewValidator; repository-backed PASS trả 503 SOURCE_VALIDATION_UNAVAILABLE.
   V4 của LIV-143 đã lưu target kind/ref/config revision/capturedAt cho Submit; Review chưa nối model/request/trust
   tới S02 và legacy fields có thể null. Không giả SHA/receipt hoặc tự backfill để PASS.
4. Không bảo đảm atomic revocation giữa S01 ALLOW và commit S04; không có distributed transaction.
5. Chưa deploy trên DB hiện có; Gateway/Newman được kiểm riêng trong report cuối LIV-118,
   vẫn chưa có S02/GitHub end-to-end.

## Kiểm chứng và chạy lại

- Maven S04: **68/68 tests PASS**, 0 failures/errors/skipped; kết thúc 2026-11-26T20:46:51+07:00.
- Runner cuối: **26/26 assertions PASS**, kết thúc 2026-11-26T20:49:52+07:00; hash khớp JAR và script bàn giao.
- Static: 6 OpenAPI hợp lệ, 114 API inventory/91 Gateway entries/19 route groups, Compose config valid.
- Một lượt runner đã gặp readiness race của PostgreSQL init server; đã đổi sang kiểm TCP readiness và chạy lại PASS. Không tính lượt lỗi là PASS.

```powershell
mvn -B -ntp -f backend/document-governance-service/pom.xml clean verify
mvn -B -ntp -f backend/identity-organization-service/pom.xml package
python -X utf8 scripts/test_api120_s01_integration.py
```

Maven dùng PostgreSQL 17 thật với S01 contract stubs, JWT test keys và source provider test.
Các case mới kiểm service token binding, scope/action mismatch, DENY, credential/upstream lỗi, response budget,
body deadline, replay sau fresh DENY, metadata audit, HTTP ngoài transaction và scope/revision race.

Runner dùng hai JVM, PostgreSQL 17 và Redis tạm; key ký chỉ cấp cho S01, workload secret/hash sinh riêng.
Đăng ký/login, JWKS/API-038, service token issuance và API-039 đều là HTTP tới code S01 thật.
Positive mutation là RETURN trên repository-bound version với controlled VERIFIED-grant fixture;
PASS thiếu S02 phải chặn. Không đọc .env hoặc dùng database ứng dụng; dừng đúng JVM/container tạm sau khi chạy.

Report/hash tại `target/api120-s01-integration.json`, service logs tại `target/`;
[manifest](../LIV-118-test-evidence.json) ghi source/test/artifact fingerprints.
Kết quả 59/22 ngày 23/11 là lịch sử slice API-038 và đã bị thay thế cho việc đánh giá implementation hiện tại.

## Nguồn và độ tin cậy

[S01 PR2 runbook](LIV-138-pr2-service-jwt-fresh-authorization.md), commit 412bdee,
Git author huutienSE, 24/11/2026; [S01 OpenAPI](../../../contracts/openapi/s01-identity.yaml) và
source controller/service là nguồn contract sơ cấp đã đối chiếu. [LIV-118](https://huutiense25.atlassian.net/browse/LIV-118)
là nguồn task scope, [LIV-162](https://huutiense25.atlassian.net/browse/LIV-162) ghi Grant lifecycle còn riêng.
Code và execution artifacts ngày 26/11 là bằng chứng local do Codex thực hiện, chưa có independent reviewer/QA
hoặc remote CI cho diff này. Không dùng report để khẳng định Golden Flow đã nghiệm thu.
