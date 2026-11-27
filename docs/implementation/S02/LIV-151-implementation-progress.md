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

**Kiểm chứng:** Maven offline `validate` PASS (JDK 25, Java release 21); static JSON/OpenAPI lint ghi trong log Slice 0. Không có runtime thay đổi trong slice này. Commit: `c374e22` — `docs(LIV-151): define API-046 authorization contract`.

## Slice 1 Xem required permissions

Phạm vi file được thực hiện: `backend/repository-integration-service/pom.xml` (build output riêng để giữ evidence cũ); các lớp mới `config/GitHubAuthorizationProperties`, `config/GitHubAuthorizationSecurityConfiguration`, `config/GitHubAuthorizationHttpConfiguration`, `controller/GitHubAuthorizationController`, `service/GitHubAuthorizationService`, `service/impl/GitHubAuthorizationServiceImpl`, `client/S01AccessContextClient`, `model/GitHubAuthorizationUser`, `model/GitHubAuthorizationRequirements`, `dto/request/GitHubAuthorizationQuery`, `dto/response/GitHubAuthorizationView`, `mapper/GitHubAuthorizationMapper`, `exception/GitHubAuthorizationException`, `exception/GitHubAuthorizationProblems`, `security/GitHubAuthorizationRequestFilter`; cập nhật `model/SuccessCode`, canonical OpenAPI S02/inventory, `.env.example`, HTTP tests, tài liệu evidence này. Các tên Java nằm dưới `src/main/java/com/livingdocs`; tests dưới `src/test/java/com/livingdocs`.

Allowlist bổ sung theo các finding review: `client/GitHubAuthorizationIo`, `model/GitHubAuthorizationError`, `model/S01AccessSnapshot`, `security/GitHubAuthorizationBodyGuard`, `src/main/resources/application.yaml`, `scripts/validate_liv151_authorization.py`. Không sửa source workload decoder/provider hoặc dữ liệu cấu hình riêng của Owner.

- [x] HTTP → verify user JWT → query validation → S01 current Workspace policy → required manifest → typed SuccessResponse.
- [x] 52 HTTP/component cases PASS: Developer/TL, role/permission deny, unknown workspace, tenant null, expired/wrong claims, duplicate/unknown/malformed input, revoked membership, S01 failures and malformed projection.
- [x] S01 projection được kiểm tra đủ required fields/type, UUID, revision, identity và evaluatedAt; business policy nằm ở Service implementation.
- [x] Dependency timeout/overload fail closed; shared 16 slots không xếp hàng; deadline JWKS 1s gồm cả refresh-lock wait, S01 1.5s gồm body/parse. Tác vụ timeout giữ slot tới khi kết thúc; không có fallback quyền cũ.
- [x] HTTP/2 thật trên Tomcat: body không khai báo độ dài bị treo → 400 trong deadline; END_STREAM rỗng trễ → 200, giữ verified identity qua request-only security context. Không đưa request stream sang worker; dùng servlet readiness/async callbacks.
- [x] Runtime Springdoc + canonical YAML + response success/Problem HTTP fixture khớp schema; không có CandidateObject ở API-046. Inventory vẫn IN_PROGRESS vì onboarding/verification chưa triển khai.
- [x] Full S02 regression với PostgreSQL 18 test riêng: **194 tests, 0 failures, 0 errors, 2 skipped** (GitHub live). 192 tests thực thi PASS, gồm source HTTP/JPA/RLS/locking hiện hữu.
- [x] Review Standards và Spec riêng; xử lý toàn bộ findings: malformed projection, policy trong adapter, JWKS lock deadline và unknown-length body lifecycle. Rereview không còn finding.
- [x] GET không tạo context/connection/grant, không truy cập S02 persistence trong luồng này; source token/trust hiện hữu giữ nguyên. User JWT không trở thành source workload credential.
- [x] Diff/allowlist tách user changes và secrets; success/Problem fixtures không chứa token/private key/credential reference.
- [x] Commit riêng `feat(LIV-151): expose required GitHub App permissions` sau verification; xem SHA bằng Git log trên nhánh hiện tại.

### Luồng hoàn thành trong Slice 1

```mermaid
sequenceDiagram
    participant U as Caller
    participant H as S02 HTTP/Security
    participant J as Pinned S01 JWKS
    participant S as GitHubAuthorizationService
    participant I as S01 API-038
    U->>H: GET authorization?workspaceId + human JWT
    H->>J: Verify RS256, issuer, audience, UUID sub, time claims
    H->>H: Validate method, query, body, Accept
    H->>S: Verified actor and workspace query
    S->>I: Same verified Bearer + correlation, no redirects/cache
    I-->>S: Current validated access snapshot
    S->>S: Tenant + scoped DEVELOPER/TL + WORKSPACE_VIEW
    S->>S: Reviewed whole-App requirements profile
    S-->>H: Requirements only; context NONE
    H-->>U: 200 SuccessResponse, USER_AUTHORIZATION_REQUIRED, no-store
```

Context-bearing reads trả 503 fail closed trong lát này; không biến thành verified hoặc not-installed. Danh tính App public từ cấu hình server, mặc định để trống → 503; fixture App ID 151 chỉ dùng kiểm thử. Required profile `github-app-profile-v1`: metadata read, contents write, pull_requests write, checks write; events push/pull_request.

### Evidence và khả năng tái chạy

Ngày kiểm chứng cuối: **28/11/2026 00:09 Asia/Saigon**. Windows, JDK 25.0.1/Java release 21, Maven 3.10.0 offline, Spring Boot 4.1.1. Build riêng `target/liv151-build` để giữ evidence cũ. `spring.config.import` được override rỗng khi chạy full suite cô lập, không dùng `.env` của dự án cho test này.

| Check | Kết quả | Evidence dưới `backend/repository-integration-service/target/` |
| --- | --- | --- |
| RED ban đầu | Expected FAIL trước implementation | `liv151-slice1-red.log` |
| RED lỗi body/projection/JWKS | Tái hiện đúng finding trước khi sửa | `liv151-slice1-body-red.log`, `liv151-slice1-projection-red.log`, `liv151-slice1-jwks-red.log` |
| HTTP cuối, gồm Tomcat HTTP/2 | PASS 52/52 | `liv151-slice1-http-final.log` |
| Full S02 + PostgreSQL/RLS | PASS 192, skipped 2 live tests | `liv151-slice1-postgres-suite-final.log` |
| Static/runtime/HTTP schema | PASS | `liv151-build/runtime-openapi.json`, `http-success.json`, `http-problem.json` |
| Gateway inventory/static routes | PASS 119 catalogued APIs, 96 exact entries, 21 route groups | `scripts/validate_gateway_openapi.py` |
| Canonical S02 OpenAPI validator | PASS OpenAPI 3.0.3 | `openapi_spec_validator.validate` |
| Real S01-issued JWT/Gateway/GitHub/browser | NOT RUN | Không suy ra E2E từ upstream fixture |

Full-suite command: `mvn -o -f backend/repository-integration-service/pom.xml -Dspring.config.import= -Dlivingdocs.build.directory=target/liv151-build test`. Cần PostgreSQL test riêng và biến DB/FLYWAY trỏ vào đó. Lần chạy này dùng cluster `target/liv151-postgres-20261127`, loopback port 58451, database `livingdocs_liv151`, runtime/migrator NOSUPERUSER/NOBYPASSRLS, max_connections 100. Database test đã dừng sau suite. Không dùng database hiện tại của Owner.

Contract checks: `python scripts/validate_gateway_openapi.py`; `python scripts/validate_liv151_authorization.py --evidence-dir backend/repository-integration-service/target/liv151-build` (PyYAML/jsonschema); `openapi-spec-validator contracts/openapi/s02-repository.yaml`. Tool libraries có sẵn tại `target/python-tools` trong môi trường này; không thêm dependency runtime S02.

Các lượt full suite trước FAIL do cấu hình test: lần đầu chưa có DB fixture; sau đó nested test configuration bị application scan và PostgreSQL initdb mặc định chỉ có 20 connections. Đã cô lập fixture bằng `@Configuration` + `@TestComponent`, tăng connections riêng cho test và chạy lại toàn suite PASS. Không xóa hoặc che log FAIL cũ. Các cảnh báo Mockito/Lombok/JDK không làm suite FAIL.

## Các lát tiếp theo

| Slice | Hành vi | Trạng thái |
| --- | --- | --- |
| 2 | Owner-bound encrypted onboarding context, callback/discovery/cancel; không bind bằng setup installation_id | NOT STARTED |
| 3 | Đọc actual installation permissions từ GitHub sau user-scoped proof | NOT STARTED |
| 4 | So sánh levels/permissions, sufficient/insufficient/suspended | NOT STARTED |
| 4A | Repository-specific verification trong API-046 | N/A theo contract |
| 5 | Real integration, Gateway, browser/GitHub acceptance, no-mutation/no-secret | NOT STARTED; cần cấu hình thật |

Không báo Task Done dựa trên từng slice hoặc CI xanh; không chuyển slice nếu gate tương ứng còn FAIL/BLOCKED.
