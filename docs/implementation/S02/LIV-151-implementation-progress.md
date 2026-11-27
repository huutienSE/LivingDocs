# LIV-151 Triển khai và evidence theo từng lát

Ngày bắt đầu: 27/11/2026, Asia/Saigon. Nhánh do Owner chuẩn bị: `feature/LIV-151-GitHub-authorization-scopes-permissions`; mốc review `46c18b7`. Owner giao triển khai và commit từng lát trong chat. Không tạo/cập nhật Jira, không push/merge/deploy. Giữ nguyên staged deletions của hai package-info, worktree deletions của hai tài liệu Gateway và thư mục secrets chưa được theo dõi.

**Điều chỉnh quy trình theo Owner ngày 28/11/2026:** hoàn thiện code toàn bộ nghiệp vụ LIV-151 rồi mới thực thi checklist kiểm thử tổng; không chạy test theo từng lát nữa. Commit production code/tài liệu theo lát; các thay đổi `src/test`, test runners/validators và test evidence được commit riêng. Quyết định mới này thay gate chạy tests ở mỗi lát trong kế hoạch cũ. Các kết quả chạy trước quyết định vẫn được giữ đúng thời điểm; không suy thành PASS cho code sửa sau đó.

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

### Tái sử dụng App thật của LIV-122 — Owner xác nhận 28/11/2026

App thật **đã tồn tại và đã kiểm thử SHA**; không coi môi trường là chưa có App và không yêu cầu tạo App mới. Đã đối chiếu [evidence LIV-122](LIV-122-Kiem-thu-GitHub-that.md): kho `TienTuiTeen1405/livingdocs-s02-test`, repository ID `1463763176`, installation ID `183597993`, SHA PASS `351ee46328288729148635e218491be161767f77`. Các artifact local `target/liv122-https/evidence-final.json`, `target/liv122-test2/evidence.json` và `pr1-evidence.json` còn giữ evidence SHA nhánh/PR. Đây là bằng chứng chạy thật lịch sử, không phải lượt chạy lại trong LIV-151.

LIV-151 tái sử dụng App/key references hiện hữu. Cần kiểm lại **cấu hình hiện hành** của chính App này cho user authorization/callback/PKCE, declared permissions/events và secret references mới của transient context; không suy rằng bằng chứng installation token đọc SHA đã bao phủ browser consent. Không tự thay App settings hoặc mở rộng source installation token. Ưu tiên đọc lại metadata bằng runtime giữ kín key, chỉ lưu evidence đã lọc. Các tests GitHub live bị skipped trong suite Slice 1 không phủ nhận evidence LIV-122 trước đó.

Các lượt full suite trước FAIL do cấu hình test: lần đầu chưa có DB fixture; sau đó nested test configuration bị application scan và PostgreSQL initdb mặc định chỉ có 20 connections. Đã cô lập fixture bằng `@Configuration` + `@TestComponent`, tăng connections riêng cho test và chạy lại toàn suite PASS. Không xóa hoặc che log FAIL cũ. Các cảnh báo Mockito/Lombok/JDK không làm suite FAIL.

## Các lát tiếp theo

### Slice 2 — Implementation checkpoint

- [x] Transient context riêng, UUID/tenant/workspace/actor/App/config binding; cap 3 admission dùng advisory lock + count/insert trong cùng transaction.
- [x] AES-256-GCM state và credential key rings riêng; nonce cookie host-only Secure/HttpOnly/SameSite=Lax; PKCE S256; không lưu LivingDocs Bearer hoặc refresh token.
- [x] Callback protocol riêng không phát LivingDocs JWT/session; claim CAS trước GitHub, exchange và immutable GitHub user lookup ngoài transaction, completion CAS chống replay/cancel/expiry/config change.
- [x] Owner-scoped pending/authorized read, cancel/purge, user installation/repository discovery một trang 100; không dò installation bằng App JWT trong helper.
- [x] FORCE RLS + explicit actor/workspace filters; maintenance SECURITY DEFINER chỉ purge expired rows theo DB clock, fixed search_path, runtime không có BYPASSRLS/unscoped SELECT.
- [x] Deadline tổng 8s không reset khi async dispatch, 16 network slots và bounded request workers; database acquisition/commit được kiểm deadline. Request stream chỉ do servlet callbacks đọc.
- [x] Xử lý finding review: effective credential TTL = min(context, provider token); 429 có Retry-After từ expiry gần nhất trong transaction admission; pagination thiếu bằng chứng trang tiếp theo trả unavailable.
- [x] Năm helper operations dùng đúng operationIds đã duyệt làm catalog keys, không tự cấp numeric API ID; Gateway helpers chưa bật trong lát này.
- [x] Contract resource dùng chung cho runtime Springdoc và canonical S02; concrete schemas/headers cho 201/204/303 và lỗi, callback security exception chỉ đúng path.
- [x] Production compile PASS (`liv151-slice2-compile.log`, 28/11/2026); không chạy test trong lần compile này.
- [ ] Checklist kiểm thử tổng sau khi code tất cả nghiệp vụ hoàn thành — **QUEUED theo yêu cầu Owner**.

Evidence đã có **trước thay đổi quy trình**: 13 context/persistence tests PASS; HTTP create/pending/cancel PASS; callback303 + discovery/cancel PASS; pool exhaustion với deadline8s PASS. Các lượt RED cho TTL/Retry-After/pagination/Springdoc đã phát hiện lỗi và code đã sửa; không ghi GREEN cho các bản sửa chưa chạy lại. Tests mới bổ sung và sửa tests hiện hữu được giữ cho commit test riêng ở cuối task.

| Slice | Hành vi | Trạng thái |
| --- | --- | --- |
| 2 | Owner-bound encrypted onboarding context, callback/discovery/cancel; không bind bằng setup installation_id | CODE COMPLETE; final test checklist QUEUED |
| 3 | Đọc actual installation permissions từ GitHub sau user-scoped proof | CODE COMPLETE; final test checklist QUEUED |
| 4 | So sánh levels/permissions, sufficient/insufficient/suspended | CODE COMPLETE; final test checklist QUEUED |
| 4A | Repository-specific verification trong API-046 | N/A theo contract |
| 5 | Real integration, Gateway, browser/GitHub acceptance, no-mutation/no-secret | NOT STARTED; cần cấu hình thật |

Không báo Task Done dựa trên từng slice hoặc CI xanh; không chuyển slice nếu gate tương ứng còn FAIL/BLOCKED.

### Slice 3 — Đọc bằng chứng quyền từ GitHub

- [x] Tái sử dụng App signer của LIV-122; giữ token nguồn chỉ `contents:read`, `pull_requests:read` và đúng một repository.
- [x] Đọc `GET /app` có chữ ký, đối chiếu App/client identity và profile 4 quyền + 2 events đã duyệt; cache metadata tối đa 30 giây.
- [x] Tái sử dụng key directory hiện hữu, cho phép chọn tên file key qua reference; không cần tạo App mới. Pin App ID/slug/name là kiểm tra bổ sung nếu cấu hình.
- [x] Fresh S01/owner/workspace/context → trang `/user/installations` → chọn ID đúng trang → `GET /app/installations/{id}` bằng App JWT → đối chiếu App/account/installation.
- [x] Thiếu selection trả 409 trước App probe; không suy diễn provider 404 thành chưa cài. Không cache quyền installation hoặc cấp quyền người dùng.
- [x] Kiểm lại context, quyền workspace, hạn credential sau provider; không có token/key trong kết quả inspection.
- [x] Production compile PASS: `target/liv151-slice3-compile.log`; chưa chạy test theo yêu cầu kiểm thử một đợt cuối.
- [ ] Kiểm thử cuối task: provider faults, identity mismatch, proof ordering, source token regression và App thật.

Slice 3 cung cấp inspection cho orchestration; Slice 4 nối evidence này vào sáu trạng thái public API-046 theo contract đã có.

### Slice 4 — Kết quả đáp ứng quyền

- [x] API-046 có selected installation chạy xuyên suốt S01 → user proof → App inspection → permission policy → MapStruct → HTTP DTO.
- [x] So sánh theo category/name và thứ tự `read < write < admin`; quyền dư được công bố theo registry category, không thay thế quyền bắt buộc còn thiếu.
- [x] Đủ quyền → VERIFIED_SUFFICIENT; thiếu quyền → VERIFIED_INSUFFICIENT với mức quyền bắt buộc; suspended luôn ưu tiên INSTALLATION_SUSPENDED.
- [x] Verified branch có installation, granted/missing, checkedAt và `repositoryAccessVerified=false`; unverified branch chỉ có status, không có dữ liệu quyền giả.
- [x] Không tạo connection/grant, không kiểm repository cụ thể trong API-046; API-047 phải xác minh lại tại lúc connect.
- [x] Production compile PASS: `target/liv151-slice4-compile.log`. Tests được chạy chung sau khi code Slice 5 hoàn tất.
- [ ] Kiểm thử cuối task: sáu trạng thái, mapping/schema, higher/lower levels, extra/unknown permissions và provider errors.
