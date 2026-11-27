# LIV-151 Triển khai và evidence theo từng lát

Ngày bắt đầu: 27/11/2026, Asia/Saigon. Nhánh do Owner chuẩn bị: `feature/LIV-151-GitHub-authorization-scopes-permissions`; mốc review `46c18b7`. Owner giao triển khai và commit từng lát trong chat. Không tạo/cập nhật Jira, không push/merge/deploy. Giữ nguyên staged deletions của hai package-info, worktree deletions của hai tài liệu Gateway và thư mục secrets chưa được theo dõi.

## Slice 0 Contract và security freeze

- [x] Đọc kế hoạch Vertical Slice Owner cung cấp; bản Downloads và workspace có cùng SHA-256 `0C9954DDEAFFE76D5E1607CEB9B000D597E718DB75D5E588B820D8A4289037D2`.
- [x] Đối chiếu Master Decisions/Standards v3 và thiết kế v1 Owner duyệt ngày 24/11/2026; lưu bản contract được Git theo dõi tại `LIV-151-authorization-contract.json`.
- [x] Giữ GET `/api/v1/integrations/github/authorization`, operationId `api046_get`, user JWT audience `livingdocs-api`; backend gọi S01 API-038 bằng chính token đã verify.
- [x] Workspace role DEVELOPER hoặc TECHNICAL_LEAD và WORKSPACE_VIEW; không bắt Repository Grant trước connect, không tin client identity headers.
- [x] Requirements toàn App: metadata read, contents write, pull_requests write, checks write; source token LIV-122 vẫn chỉ contents/pull_requests read.
- [x] Phân biệt USER_AUTHORIZATION_REQUIRED, AUTHORIZATION_PENDING, INSTALLATION_SELECTION_REQUIRED, VERIFIED_SUFFICIENT, VERIFIED_INSUFFICIENT, INSTALLATION_SUSPENDED; không đổi thành READY/NOT_INSTALLED từ ví dụ trong kế hoạch.
- [x] GET không tạo context/connection/Grant hoặc mint/refresh token. Context và năm helper operations có mutations/contract riêng đã duyệt trong v1, được triển khai cùng Slice 2.
- [x] Slice 4A không áp dụng: repo target vẫn được API-047 kiểm lại; repo discovery helper không phải bằng chứng cho phép connect.
- [x] Seams: business Interface, HTTP S02, HTTP S01/GitHub adapters, PostgreSQL/RLS, Gateway, real browser/provider. Component mocks không là E2E PASS.
- [x] Xác nhận source S02 trên nhánh hiện tại có LIV-122, package `com.livingdocs`; S01 hiện có API-039 nhưng không đổi thiết kế read API-038 đã duyệt.

Contract artifact là approved design, không phải API runtime đã implemented. Canonical OpenAPI được cập nhật theo từng slice cùng HTTP/DTO. App ID/slug/domain/secret references thật còn thiếu: chặn GitHub live/browser acceptance, không chặn component tests bằng fixture đã ghi nhãn. Docker mặc định bị chặn quyền pipe; Maven offline chạy được khi cấp quyền cache lock. Không đọc raw secrets hoặc `.env` thật.

**Kiểm chứng:** Maven offline `validate` PASS (JDK 25, Java release 21); static JSON/OpenAPI lint ghi trong log Slice 0. Không có runtime thay đổi trong slice này. Commit: `docs(LIV-151): define API-046 authorization contract`.

## Slice 1 Xem required permissions

Phạm vi file được thực hiện: `backend/repository-integration-service/pom.xml` (build output riêng để giữ evidence cũ); các lớp mới `config/GitHubAuthorizationProperties`, `config/GitHubAuthorizationSecurityConfiguration`, `config/GitHubAuthorizationHttpConfiguration`, `controller/GitHubAuthorizationController`, `service/GitHubAuthorizationService`, `service/impl/GitHubAuthorizationServiceImpl`, `client/S01AccessContextClient`, `model/GitHubAuthorizationUser`, `model/GitHubAuthorizationRequirements`, `dto/request/GitHubAuthorizationQuery`, `dto/response/GitHubAuthorizationView`, `mapper/GitHubAuthorizationMapper`, `exception/GitHubAuthorizationException`, `exception/GitHubAuthorizationProblems`, `security/GitHubAuthorizationRequestFilter`; cập nhật `model/SuccessCode`, canonical OpenAPI S02/inventory, `.env.example`, HTTP tests, tài liệu evidence này. Các tên Java nằm dưới `src/main/java/com/livingdocs`; tests dưới `src/test/java/com/livingdocs`.

- [ ] HTTP → verify user JWT → query validation → S01 current Workspace policy → required manifest → typed SuccessResponse.
- [ ] Unit/API/contract/security positive và negative tests PASS.
- [ ] Thử dependency failure và chứng minh không giả granted hoặc trust từ header.
- [ ] Review diff/standards/spec, không đụng user changes hoặc source security trust.
- [ ] Commit `feat(LIV-151): expose required GitHub App permissions` sau verification.

## Các lát tiếp theo

| Slice | Hành vi | Trạng thái |
| --- | --- | --- |
| 2 | Owner-bound encrypted onboarding context, callback/discovery/cancel; không bind bằng setup installation_id | NOT STARTED |
| 3 | Đọc actual installation permissions từ GitHub sau user-scoped proof | NOT STARTED |
| 4 | So sánh levels/permissions, sufficient/insufficient/suspended | NOT STARTED |
| 4A | Repository-specific verification trong API-046 | N/A theo contract |
| 5 | Real integration, Gateway, browser/GitHub acceptance, no-mutation/no-secret | NOT STARTED; cần cấu hình thật |

Không báo Task Done dựa trên từng slice hoặc CI xanh; không chuyển slice nếu gate tương ứng còn FAIL/BLOCKED.
