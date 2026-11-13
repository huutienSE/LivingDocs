# S3-US02-T02 — Manager decision: UI, S01 fresh authorization, security tests

Story: LIV-98 (Final Approval) · Sub-task tương ứng LIV-121 · Gốc: LIV-84 (RBAC), LIV-87 (Staff Review/Final Approval/Continuity)
Phụ thuộc chặn: **S3-US02-T01 API contract** · Contract nguồn: **LD-API-001 v0.4.1** · Quyết định áp dụng: LD-DEC-025/027/028/029

## 1. Phạm vi và kết quả

| Hạng mục (Scope) | Kết quả | File chính |
|---|---|---|
| Manager queue + action (UI) | Hàng chờ API-122, chi tiết API-123, quyết định API-124 (4 quyết định, REJECT có xác nhận) | `frontend/src/features/approval/*` |
| S01 fresh authorization/scope | API-039 `POST /internal/v1/authorization/evaluate` + migration V3 | `backend/identity-organization-service/.../authz/*`, `V3__create_workspace_rbac_tables.sql` |
| Revoked role + cross-tenant tests | Unit, PostgreSQL integration, Vitest, Postman/Newman | `src/test/.../authz/*`, `*.test.ts(x)`, `tests/e2e/manager-decision/*` |

AC: *403/guard errors, no owner implicit approval, no self approval.*

## 2. Quy tắc nghiệp vụ được cài và nơi kiểm tra

| Quy tắc | Nguồn | Nơi thực thi | Test |
|---|---|---|---|
| Chỉ role **Manager đang hiệu lực trong đúng Workspace** mới duyệt | LD-DEC-027 §4.2 | S01 `AuthorizationPolicy` + `JdbcAuthorizationFactsRepository` | Policy/JDBC/Postman 06 |
| **Tenant Owner không có quyền duyệt ngầm** | LD-DEC-025 §3.9/3.15, LD-DEC-029 §5 | S01: facts **không có** dữ liệu Owner nên không thể cấp quyền | JDBC "tenantOwner…", Postman 03, UI |
| **Không tự duyệt** (tác giả/người sửa, đa vai trò) | LD-DEC-027 §4.6, LD-DEC-029 §5 | **S04** (lịch sử tác giả thuộc S04) — S01 không biết | Postman 04, UI (message) |
| Role thu hồi ⇒ `PERMISSION_REVOKED` dù JWT còn hạn | LD-API-001 §2, §4.2 | S01 đọc DB tại thời điểm gọi, **không cache** | JDBC "revoked…", Postman 07 |
| Cross-tenant ⇒ `FORBIDDEN_SCOPE`, không lộ gì | LD-DEC-028 §8, LD-API-001 §2 | PostgreSQL RLS (`app.tenant_id`) + lọc `tenant_id` tường minh | JDBC "crossTenant…", Postman 05 |
| Không chứng minh được quyền ⇒ **fail-closed** (503) | LD-API-001 §2 | S01 ném `AuthorizationUnavailableException` → 503; S04 phải chặn | Unit/JDBC; **S04 chưa kiểm** |
| Mọi ca bị từ chối **không đổi state** | LD-API-001 §4.2 | S04 | Postman readback (ETag + state) |

⚠️ **S01 chỉ trả lời "actor có quyền Manager trong workspace không".** Self-approval, Staff PASS, source guard, state, ETag thuộc S04 (LIV-120). T02 không thay thế phần đó.

## 3. API-039 — hình dạng đã cài

Request (`AuthorizationEvaluationRequest`, LD-API-001 §4): `actorIdentityId`, `requestedAction`, `resourceScope{tenantId, workspaceId}`, `resourceRef`, `callerService`, `correlationId`.
Result: `allowed`, `checkedAt`, `decisionId`, `effectiveRoles?`, `reasonCode?`, `membershipRevision?`, `expiresAt?`.

Từ chối là **quyết định** (HTTP 200, `allowed=false`); S04 chuyển thành 403 `FORBIDDEN_SCOPE`/`PERMISSION_REVOKED`. Lỗi: 401 `INVALID_SERVICE_IDENTITY`, 422 `INVALID_REQUEST`, 400 `MALFORMED_REQUEST`, 503 `DEPENDENCY_UNAVAILABLE`.

### Field/giá trị SUY RA (chưa có trong LD-API-001) — cần S04/T01 xác nhận

| Mục | Giá trị đã chọn | Ghi chú |
|---|---|---|
| `requestedAction` | `DOCUMENT_VERSION_FINAL_APPROVE`, `DOCUMENT_VERSION_PUBLISH` | Tài liệu không chốt enum |
| `resourceScope` | `{tenantId, workspaceId}` | Tài liệu không chốt cấu trúc |
| `reasonCode` | `FORBIDDEN_SCOPE`, `PERMISSION_REVOKED`, `UNSUPPORTED_ACTION` | Hai mã đầu trùng danh mục lỗi |
| Action không hỗ trợ | HTTP 200 `allowed=false, UNSUPPORTED_ACTION` | Có thể đổi thành 422 |
| `expiresAt` | `checkedAt + 30s` | Thời gian S04 còn được dựa vào quyết định |
| `membershipRevision` | `workspace_memberships.revision` | Cột mới trong V3 |
| ApprovalQueueView / ApprovalContextView | Xem `frontend/.../types.ts` (`staffReview`, `createdBy`, `editedBy[]`, `evidence[]`, `reviewRound`) | Giống cách LIV-119 đánh dấu "candidate"; cần khớp LIV-120 |
| Giới hạn độ dài `reason` | 2000 ký tự (UI) | S04 cần xác nhận |
| `sourceEvidenceRef` | UI gửi evidence loại `SOURCE*`, nếu không có thì item đầu tiên | Bỏ nếu không có evidence |

## 4. Câu hỏi mở / rủi ro — cần quyết định trước khi chốt Story

1. **Chưa có cơ chế xác thực service-to-service (SEC-DES-01).** `DenyAllServiceIdentityVerifier` mặc định từ chối mọi caller (401). **API-039 chưa dùng được từ S04 cho đến khi** có mTLS/service-JWT và một `ServiceIdentityVerifier` thật. Cố ý không bịa cơ chế tạm vì sẽ cho phép bất kỳ caller mạng nào "duyệt" tài liệu.
2. **Không có atomic xuyên S01/S04** (LD-API-001 §4.2 tự thừa nhận): role có thể bị thu hồi ngay sau khi S01 trả `allowed`. Đề xuất: S04 gọi API-039 **ngay trong** transition và tôn trọng `expiresAt`. Chưa có contract freshness/revocation chính thức ⇒ T01 **chưa thực sự đóng** ở điểm này.
3. **API-039 phải không bao giờ được route qua Gateway công khai.** Cần kiểm tra route (Gateway deny `/internal/**`).
4. **Trùng phạm vi với LIV-84:** migration V3 tạo `workspaces`, `workspace_memberships`, `workspace_role_assignments`, `tenant_owner_grants` (theo LD-DATA-002). Nếu LIV-84 cũng tạo các bảng này thì **gộp thành một migration và đổi số version** (Flyway chặn trùng). V3 cũng thêm `UNIQUE (tenant_id, membership_id)` vào `tenant_memberships` để FK ghép ép "cùng tenant".
5. **Frontend dựa trên LIV-119** (chưa merge vào `develop` khi soạn). PR của T02 phải rebase sau khi LIV-119 merge; 3 file dùng chung bị sửa (`routes.tsx`, `App.tsx`, `styles.css`) — xem `frontend-shared-files.patch`.
6. **Ngữ nghĩa thu hồi:** "đã từng giữ Manager, nay không còn" ⇒ `PERMISSION_REVOKED`; "chưa từng" ⇒ `FORBIDDEN_SCOPE`. Điều này cho chính người đó biết trạng thái của mình, không lộ thông tin về tenant khác (cross-tenant luôn là `FORBIDDEN_SCOPE`). Xác nhận đây là hành vi mong muốn.
7. **Điều hướng theo Effective Permission (LD-DEC-027 §4.4):** UI hiện liệt kê cả "Staff Review" và "Manager Approval" cho mọi user đã đăng nhập; server quyết định. Ẩn menu theo quyền cần API-038 (`access-context`) — chưa có schema.
8. **Kết nối DB của S01:** repository dùng JDBC thuần trên `DataSource`; giả định `spring-boot-starter-flyway` kéo theo DataSource. Runtime role phải là `ld_s01_app` (không phải owner, nếu không RLS bị bỏ qua) và cần `GRANT USAGE ON SCHEMA identity`.

## 5. Ma trận kiểm chứng (đã chạy gì, ở đâu, kết quả)

| Phần | Cách chạy | Kết quả | Giới hạn |
|---|---|---|---|
| Frontend | `npm test`, `npx tsc --noEmit`, `npm run build` trong `frontend/` | **69 test đạt** (30 của LIV-119 không đổi + 39 mới), tsc sạch, build OK | Chưa chạy trong trình duyệt/E2E |
| Frontend — đột biến | Cố ý phá code 10 lần | 10/10 bị test bắt | — |
| S01 logic + JDBC | JDK 21 + JUnit Jupiter 5.10 + **PostgreSQL 16.15 thật**, migration V1,V2 (từ `develop`) + V3 | **49 test đạt** (2 file unit + 1 file tích hợp DB) | **Chưa build bằng Maven/Spring Boot 4.1.1** — môi trường không truy cập được Maven Central |
| S01 — đột biến | 11 lỗi cố ý | 10 bị bắt (2 lỗ hổng test phát hiện và đã vá); 1 đột biến tương đương (bộ lọc tenant dư thừa ở truy vấn workspace — bị truy vấn khác chặn cùng ca) | — |
| Lớp Spring của S01 (controller, advice, `@Component`) | Biên dịch với stub annotation | Biên dịch sạch với `-Xlint:all` | **Chỉ chứng minh mã tự nhất quán**, không chứng minh khớp API Spring Boot 4.1.1; **chưa có test HTTP** |
| Postman | Newman 6.2.3 + mock tạm | Mock đúng: 56 request/162 assertion, 0 lỗi; 6 lỗi cố ý đều bị bắt | **Chưa chạy trên stack thật** |

Test DB của S01 chạy khi có biến môi trường (xem §6), nếu không chúng được **skip** (không fail) — hãy đảm bảo CI đặt biến để chúng thật sự chạy.

## 6. Cách chạy

**Frontend** (trong `frontend/`): `npm ci && npm test && npm run build`.

**S01** (Maven của dự án): `./mvnw test`. Để chạy test PostgreSQL cần DB đã áp dụng Flyway V1..V3 và:

```
LD_TEST_DB_URL=jdbc:postgresql://localhost:5432/<db>
LD_TEST_DB_APP_USER=ld_s01_app   LD_TEST_DB_APP_PASS=...   # role runtime, chịu RLS
LD_TEST_DB_OWNER_USER=...        LD_TEST_DB_OWNER_PASS=... # owner migration, chỉ để seed dữ liệu test
```

Test tự dọn dữ liệu nó tạo ra. Dùng DB dùng một lần, không dùng DB có dữ liệu thật.

**API** (stack thật): xem `tests/e2e/manager-decision/README.md`.

## 7. Việc còn lại để Story DONE (DoD chung)

- [ ] S04 (LIV-120) gọi API-039 trong transition FINAL_APPROVE/Publish; OpenAPI cập nhật; xác nhận §3 và §4.
- [ ] Cơ chế service identity (SEC-DES-01) + gỡ `DenyAllServiceIdentityVerifier`.
- [ ] Gateway: route `/api/v1/approval-queue`, `/approval-context`, `/manager-decisions` và **deny** `/internal/**`.
- [ ] Build S01 bằng Maven + CI xanh; thêm Testcontainers/service container PostgreSQL để test DB không bị skip.
- [ ] Chạy Newman trên stack thật, đính evidence; test thủ công S01-down (503, không mutate).
- [ ] PR được reviewer khác duyệt (Cường); gộp V3 với LIV-84.
