# LIV-119 · S3-US01-T02 — Staff Review UI: contract notes for S04 (LIV-118) & Gateway (LIV-115)

Mục đích: ghi rõ những gì UI Staff Review **đang giả định** về API, để chốt cùng owner S04 (LIV-118), Gateway (LIV-115) và S01 trước khi tích hợp. Nguồn: LD-API-001 v0.4 (§2, §2.0, §3.4, §4.1, §4.7), REQ-05 v1.1, LD-SPR3-GUIDE-001 §6.

> Các field đánh dấu **(candidate)** chưa được LD-API-001 cố định. Nếu S04 chọn tên khác, chỉ cần sửa `frontend/src/features/review/types.ts` và collection Postman.

## 1. Endpoint dùng (qua Gateway, Bearer JWT)

| ID | Method / Path | Dùng ở |
|---|---|---|
| API-115 | `GET /api/v1/review-queue?page&size&sort` | Hàng chờ |
| API-116 | `GET /api/v1/document-versions/{versionId}/review-context` | Trang chi tiết; **phải trả strong `ETag`** |
| API-120 | `POST /api/v1/document-versions/{versionId}/staff-decisions` | PASS / RETURN |
| API-002/004/003 | `POST /api/v1/auth/login` · `/refresh` · `/logout` | Phiên đăng nhập |

## 2. Shape giả định

**ReviewQueueView (candidate)**
```json
{ "items": [ { "versionId": "…", "documentId": "…", "documentTitle": "…", "versionNo": 2,
               "state": "IN_REVIEW", "reviewStage": "STAFF_REVIEW",
               "sourceSha": "…", "submittedAt": "2026-11-04T10:00:00Z", "submittedBy": "…" } ],
  "page": 0, "size": 20, "totalElements": 1 }
```
Queue chỉ chứa version `IN_REVIEW / STAFF_REVIEW` thuộc scope của actor (tenant/workspace từ token, server tự xác định).

**ReviewContextView (candidate)** — header `ETag: "<revision>"`
```json
{ "version": { "documentId": "…", "versionId": "…", "state": "IN_REVIEW", "reviewStage": "STAFF_REVIEW",
               "revision": 3, "sourceBinding": { "headSha": "…" } },
  "documentTitle": "…", "versionNo": 2, "content": "…", "contentFormat": "MARKDOWN",
  "submittedBy": "…", "submittedAt": "…", "reviewRound": 1,
  "evidence": [ { "ref": "ev-1", "label": "Diff abc1234", "kind": "SOURCE_DIFF" } ] }
```
`version` = `DocumentVersionView` (LD-API-001 §4.1).

**StaffDecisionRequest** (LD-API-001 §4.7) — headers `If-Match`, `Idempotency-Key`
```json
{ "decision": "PASS" | "RETURN_FOR_REVISION", "feedback": "…", "evidenceRefs": ["ev-1"] }
```
- UI gửi `feedback` đã trim; bỏ field khi rỗng. RETURN bắt buộc feedback khác rỗng (khớp `ck_review_decisions_reason`). UI giới hạn 2000 ký tự (**candidate** — S04 cần chốt max length, server vẫn là nơi quyết định).
- Response 200: `DocumentVersionView` + `ETag` mới. PASS → `IN_REVIEW/MANAGER_APPROVAL`; RETURN → `DRAFT`, `reviewStage = null`.

## 3. Hành vi lỗi UI đang xử lý

| HTTP / code | UI làm gì |
|---|---|
| 401 | Refresh 1 lần (single-flight), gửi lại **cùng** `Idempotency-Key` + `If-Match`; refresh fail → về Login |
| 403 `FORBIDDEN_SCOPE` / 404 | Thông báo trung tính (không phân biệt "không tồn tại" và "tenant khác"), ẩn nút, đọc lại state |
| 409 `STATE_TRANSITION_FORBIDDEN` | Đọc lại; nếu không còn `STAFF_REVIEW` thì ẩn nút |
| 409 `IDEMPOTENCY_CONFLICT` | Báo lỗi; lần sau sinh key mới |
| 412 `REVISION_CONFLICT` | Đọc lại để lấy ETag mới; lần gửi sau dùng key mới |
| 428 | Đọc lại (thiếu ETag) |
| 400 / 422 | Hiện `detail` |
| Network error | Không coi là kết quả; bấm lại dùng **cùng** `Idempotency-Key` |

Lỗi theo `application/problem+json` (RFC 9457) có `status`, `code`, `correlationId`.

## 4. Cần owner xác nhận

1. **S04 / LIV-118**: tên field ở §2; max length feedback; cross-tenant trả 403 hay 404 (UI chấp nhận cả hai, Postman chấp nhận cả hai); replay cùng `Idempotency-Key` + cùng payload trả lại kết quả cũ (200) chứ không phải 409 trạng thái.
2. **Gateway / LIV-115**: route `/api/v1/review-queue` và `/api/v1/document-versions/**` → S04, `/api/v1/auth/**` → S01; validate JWT; không forward `X-User-Role`/`X-Tenant-Id` do client gửi; CORS/credentials cho refresh cookie. Gateway hiện bind `127.0.0.1:8081` (dev proxy của Vite trỏ vào đây).
3. **S01**: login trả `{accessToken, tokenType, expiresIn}` + refresh cookie HttpOnly; cơ chế CSRF cho `/auth/refresh` (UI đang gửi `X-XSRF-TOKEN` nếu có cookie `XSRF-TOKEN` — theo quy ước Spring Security, chờ Security Configuration Decision).
4. **Seed data** cho test: xem `tests/e2e/review-publish/README.md`.
