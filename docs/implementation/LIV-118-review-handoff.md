# LIV-118 — Bàn giao core persistence để review

Ngày: 05/11/2026 (UTC+7). Người soạn: Codex, tác giả implementation; không phải independent approval.

## Phạm vi giữ lại

DocumentVersion, ReviewDecision, DocumentAudit; PASS/RETURN có guard; transaction,
revision/concurrency, idempotency, Flyway V2, unit/PostgreSQL tests và persistence sau application restart.
Không mở rộng thành toàn bộ 65 API S04. Không triển khai thêm login, Gateway, UI, Manager Approval hoặc Publish.

## Ba điểm cần xác nhận trước tích hợp

| Điểm | Quyết định trong draft này | Owner cần xác nhận / tiêu chí đóng |
| --- | --- | --- |
| Identity và quyền S01 | Giữ guard service. Claim tenant_active/workspace_grants chỉ là adapter candidate; không công bố là S01 contract đã duyệt. Thiếu trust config tiếp tục fail closed. | Owner S01 cùng LIV-119/S04: cung cấp token/claim contract có version và xác nhận mapping bằng test token S01 thật. Chưa có xác nhận. |
| Endpoint đọc và DTO | API-120 đúng route inventory. GET API-106 hiện chỉ là review-state slice, không phải API-106 đầy đủ và không thay API-116. Không mở rộng GET chỉ để lấp inventory. | S04/LIV-119: thống nhất quyền DOCUMENT_SCOPE, DTO và endpoint cấp ETag; sửa hoặc bỏ GET slice trước khi coi API bàn giao hoàn chỉnh. Chưa có xác nhận. |
| API-115/116 thuộc task nào | Jira LIV-118 nêu state/persistence; comment và PR LIV-119 lại chờ API-115/116/120 từ LIV-118. Đây là khoảng trống phân công, không tự quyết là ngoài scope hoặc tự sinh thêm code. | Lead cùng assignee LIV-118 HAI NGUYEN XUAN và LIV-119 thinh vo: xác nhận giữ trong LIV-118 hay tách task, ghi acceptance và owner trước tích hợp. Chưa có xác nhận. |

Draft PR chỉ xin review code/persistence và ba điểm trên; chưa ready-to-merge.
Không dùng thiếu toàn bộ E2E của parent LIV-97 làm blocker mặc định cho subtask persistence.
Source evidence hiện chỉ kiểm tra tồn tại đúng version và SHA bound; không chứng minh current GitHub head từ S02.

## Nguồn đã đối chiếu

- [LIV-118](https://huutiense25.atlassian.net/browse/LIV-118): đọc live 05/11/2026; In Progress; reporter TIỀN HUỲNH PHẠM HỮU; updated 05/11/2026. Scope/AC là nguồn chính; không phải evidence code đã đạt.
- api_inventory_v0.4.json do người dùng cung cấp: 172 operations / 65 S04; SHA256 `CEF175093F70E86DFAF60E377BD79D92BB34AD9CC1F86BAC2DB4662B150EF6B6`. Không có metadata tác giả/approval trong JSON. API-106 DOCUMENT_SCOPE; API-120 STAFF,REVIEW_GUARD,ETAG. Inventory không định nghĩa claim S01 hoặc schema DTO chi tiết.
- [LIV-119](https://huutiense25.atlassian.net/browse/LIV-119), comment thinh vo ngày 05/11/2026 và [contract notes tại commit aab6027](https://github.com/huutienSE/LivingDocs/blob/aab60274504aa1c9e3d4c5b0f59a28a9854cc4da/docs/implementation/LIV-119-staff-review-contract-notes.md): nguồn trực tiếp về nhu cầu UI; tài liệu tự ghi candidate, chưa là quyết định thay đổi scope.
- Hai checklist review/Jira v1.1 người dùng cung cấp: khung review, không phải approval record cho LIV-118.

## Bằng chứng và giới hạn

Tests gồm 15 policy/ETag, 13 API/DB, 1 migration upgrade, 1 application restart,
1 context smoke, 1 missing-trust = 32. Restart đóng/mở Spring context, HTTP server và pool
với cùng PostgreSQL; không phải kill/restart JVM hoặc PostgreSQL.

Lần 21:19 ngày 05/11: 32 PASS. Lần review 22:15: 16 PASS/16 errors do Docker engine
không khả dụng; không diễn giải thành 16 lỗi nghiệp vụ. Lần bàn giao mới được ghi ở evidence riêng
gắn commit. GitHub CI và independent approval phải kiểm tra riêng.

Thay đổi application-db.yaml đã có trước implementation không thuộc bàn giao này và không đưa vào commit.
