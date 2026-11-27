# LIV-144 — API-115 Staff Review Queue

Người triển khai và tổng hợp: **Codex**, theo yêu cầu trực tiếp của người dùng.
Ngày kiểm chứng: **27/11/2026, Asia/Bangkok (UTC+7)**.
Branch: `feature/LIV-144-s04-staff-review-queue`; nền trước triển khai: `f8457b1`.
Report phản ánh triển khai trên branch và kết quả kiểm chứng local; không phải xác nhận đã deploy hoặc Jira Done.

## Chuẩn đối chiếu trong repository

Theo chỉ định trực tiếp của người dùng ngày **27/11/2026**, dùng
[Master Decision trong repo](../engineering/LivingDocs_Master_Decisions_Technical_Rules_Team_Agent_Handoff_2026-11-13.md)
làm chuẩn engineering khi triển khai và review tại repository này, gồm
`SuccessResponse<T>`, Service interface/implementation, JPA/Flyway, RLS và test evidence.
Jira/Confluence tiếp tục cung cấp scope, business requirements và service ownership.
Bản Master trên Confluence còn nội dung cũ về wrapper; điểm này không thay đổi chuẩn
repo mà người dùng đã chọn. Các mục đề xuất/chưa chốt trong tài liệu vẫn giữ trạng thái đó.

## Kết quả và phạm vi

Đã triển khai `GET /api/v1/review-queue` trên S04, đọc PostgreSQL thật, trả
`SuccessResponse<ReviewQueueView>` với code `REVIEW_QUEUE_LOADED` và
`data={items,page,size,totalElements}`. Queue chỉ chứa `IN_REVIEW / STAFF_REVIEW`.
API không sửa state, revision, submission receipt, review decision hoặc audit.

Phạm vi theo [LIV-144](https://huutiense25.atlassian.net/browse/LIV-144):

- Candidate đã Submit xuất hiện trong queue; PASS/Return thành công làm candidate
  rời queue, resubmit làm candidate xuất hiện lại.
- Filter kết hợp AND theo workspace, repository connection, template, severity
  và khoảng ngày Submit inclusive. Template filter dùng `templateId`.
- Sort theo `REPOSITORY`, `TEMPLATE`, `DRIFT_SEVERITY`, `SUBMISSION_DATE`; hai chiều
  ASC/DESC. Repository/template sort theo UUID; severity LOW < MEDIUM < HIGH <
  CRITICAL; NULL luôn cuối, tie-break `versionId ASC`.
- Mặc định page 0, submission date DESC. Size mặc định 20, max size 100 và query
  timeout 2000ms là cấu hình server có thể đổi qua `S04_REVIEW_QUEUE_*`; OpenAPI
  không đóng một hard cap theo môi trường. Offset vượt khả năng JPA bị từ chối.
  Với Compose hiện tại, các biến queue mới chưa được forward vào container;
  khả năng override bằng môi trường Compose còn cần sửa như ghi ở phần giới hạn.
- Query sai kiểu, enum/ngày không hợp lệ, biên ngày ngược, tham số lạ, lặp hoặc
  rỗng bị 400; queue/page rỗng trả 200 với total đúng scope.
- Mở đúng route S04 và Springdoc; Gateway đã có GET allowlist nên không thêm route.
  OpenAPI và inventory chỉ đánh dấu `IN_PROGRESS`, còn owner/FE review.

## Auth và transaction

```text
User JWT → Gateway/S04 verify → S01 API-038 thật
  → Staff read policy → transaction read-only + tenant RLS
  → scoped JPA query/count → mapper → success envelope
```

JWT được kiểm chữ ký, issuer, audience, expiry và Identity subject theo cấu hình
S01 hiện có. `ReviewQueueAccessResolver` tái sử dụng `S01AccessContextClient`;
không đọc role/grant do client tự gửi hoặc tin grant trong token tự dựng.

Theo quyết định người dùng trong phiên 27/11: triển khai **Staff trước**. Trong
cùng workspace grant cần `STAFF + DOCUMENT_READ + WORKSPACE_VIEW`; candidate gắn
repository cần repository nằm trong chính grant đó. Repository-free candidate
được giới hạn bởi workspace được cấp. Không trộn repository của grant A với
workspace B. Filter workspace/repository ngoài quyền trả 403.

Không yêu cầu `DOCUMENT_STAFF_REVIEW` cho việc đọc và không gọi fresh API-039,
Service JWT hoặc S02 source validation trong API-115. Các protected mutation
vẫn giữ nguyên guard hiện có. Quyền đọc của vai trò khác **pending**, chưa mở.

API-038 hoàn tất trước transaction DB. Service interface/implementation dùng
transaction read-only `REPEATABLE_READ`, tenant context và statement timeout
transaction-local trên cùng Hibernate connection. Content và count cùng snapshot;
mọi scope/filter áp trong SQL trước pagination/count. JPA/MapStruct mapping trả DTO,
không trả Entity. View `security_invoker=true` giữ RLS ở các bảng nguồn; app chỉ có
SELECT trên view. Timeout trả lỗi đã lọc, transaction rollback và lần đọc sau hoạt động.

Success có `Cache-Control: no-store`, correlation header. Lỗi RFC 9457 có stable code
và correlationId: 400 invalid query; 401 JWT; 403 thiếu quyền/scope; 405/406 lỗi HTTP;
500 persistence/query timeout; 503 authority unavailable/malformed. Không lộ SQL,
upstream body hoặc credential.

## Persistence và thay đổi liên quan

Migration mới `V5__staff_review_queue.sql` giữ nguyên V1–V4:

- Thêm `drift_severity` nullable với constraint LOW/MEDIUM/HIGH/CRITICAL. NULL là
  chưa biết, không phải LOW hoặc không có drift; API đọc không tự tính severity.
  Đây là lựa chọn read model cho queue trong thay đổi hiện tại, chưa phải thiết kế
  snapshot/mapping S03–S04 đã được contract owner phê duyệt.
- View nối các bảng **trong schema S04**, không join schema service khác.
  Submission time/actor lấy từ receipt mới nhất theo resulting revision; dữ liệu
  legacy không có receipt giữ NULL, không thay bằng version creation time.
- Thêm index phục vụ candidate queue và receipt mới nhất. Không thêm bảng RLS mới:
  vẫn 13 bảng RLS, tổng 5 migration. CI hiện có được cập nhật đúng count này.

Các file production chính nằm trong `backend/document-governance-service/`:

| Vai trò | File |
| --- | --- |
| HTTP/query DTO | `controller/ReviewQueueController.java`, `dto/request/ReviewQueueQuery.java` |
| Auth read adapter | `security/ReviewQueueAccessResolver.java` |
| Use case | `service/ReviewQueueService.java`, `service/impl/ReviewQueueServiceImpl.java` |
| JPA read model | `entity/ReviewQueueEntry.java`, `repository/ReviewQueueJpaRepository.java` |
| Mapping/response | `mapper/ReviewQueueMapper.java`, `dto/response/ReviewQueueItem.java`, `ReviewQueueView.java` |
| Config/errors | `config/ReviewQueueProperties.java`, `exception/InvalidReviewQueueQueryException.java`, `ReviewQueueExceptionHandler.java` |

Các đường dẫn Java trong bảng tính từ `src/main/java/com/livingdocs/document/`.
File hiện có chỉ được sửa để thêm success code, route/security, Springdoc, config,
README/env example và regression assertions cho endpoint/migration mới. Helper
Newman hiện có nhận collection path tùy chọn để runner queue tái sử dụng; default
collection của runner cũ được giữ. Không thêm dependency hoặc CI workflow mới.

## Kiểm chứng

| Kiểm tra | Kết quả local | Bằng chứng |
| --- | --- | --- |
| S04 `mvn -B -ntp verify` — lượt review mới nhất | PASS 115 tests, 0 failure/error/skip; build JAR thành công lúc 19:00:01 | `target/liv144-recheck.log` |
| API-115 sau bổ sung failure/recovery tests | PASS 13 tests, 0 failure/error/skip lúc 18:33:41 | `target/liv144-queue-final.log`, Surefire XML |
| Canonical OpenAPI | PASS 6 YAML schemas | `openapi-spec-validator 0.7.2` |
| Gateway contract/routes | PASS 114 catalogued APIs, 91 method/path, 19 route groups; không public internal | `scripts/validate_gateway_openapi.py`; static validation |
| Gateway/S01/S04 + Newman | PASS 10 runner checks; 23 requests / 79 assertions, 0 failure/skip; 18:33:40–18:36:08 UTC+7 | `target/api115-s01-integration.json` |
| Whitespace diff | PASS | `git diff --check`; UTF-8/no trailing spaces cho Java mới |

Lượt Maven toàn bộ mới nhất đã chạy đủ **115 test**, gồm 13 test của queue suite;
không cộng các lượt chạy trùng thành số test độc lập. Lượt queue suite riêng ở
18:33:41 là evidence bổ sung trước lượt regression toàn bộ.
Component tests dùng JWT ký bằng test key và API-038/039/source validator test adapters,
được ghi nhãn riêng; PostgreSQL và RLS là thật. Test thêm query timeout bằng table lock,
HTTP errors, grants theo từng cặp scope, count/pagination, các bộ lọc/sort, metadata
nullable, tenant connection reuse, runtime Springdoc so với canonical schema và
Submit → queue → PASS/Return.

Collection riêng: [liv144-review-queue.postman_collection.json](../../tests/postman/liv144-review-queue.postman_collection.json).
Runner: [test_api115_s01_integration.py](../../scripts/test_api115_s01_integration.py).
Chạy từ repository root sau khi build JARs S01/S04/Gateway:

```powershell
python -X utf8 scripts/test_api115_s01_integration.py --newman-cli "$env:TEMP/livingdocs-newman/node_modules/newman/bin/newman.js"
```

Runner tạo DB/Redis và Java processes cô lập, SQL fixtures được ghi nhãn; dùng S01
register/login/JWT/API-038 thật, fresh authorization thật cho Submit/Return/resubmit,
và Gateway thật. Không đọc `.env` hoặc dùng DB đang phát triển. Credential/raw Newman
chỉ ở temp, JSON evidence đã lọc nằm trong S04 target, có SHA256 JAR/runner/collection.
Chứng cứ local không thay remote CI, FE acceptance hoặc production grant lifecycle.

Newman theo phase: scoped queue **15 request / 54 assertion**; Return/resubmit
**4 / 12**; revoked repository **2 / 7**; revoked Staff **1 / 3**; S01 unavailable
**1 / 3**. Các GET được so sánh tổng revision/audit/submission/decision trước và sau
để xác nhận không ghi state. JWT thiếu bị Gateway trả 401; JWT bị sửa bị S04 trả 401.
Đổi workload secret thành giá trị sai không làm GET queue thất bại, xác nhận read
không phụ thuộc fresh/workload authorization.

SHA256 của S04 JAR đã chạy:
`87e79b6ef97576804e45c51b97daa0b06ae513e8bfc8753d04d11cbfa3161a00`.
Collection:
`f8ea8e7eee2f2e3b921ea0fadca5995c5c020eaed4aad936e45501b008dfadce`.
Đã đối chiếu lại cả 5 hash JAR/runner/collection trong JSON với file trên đĩa sau run.

## Giới hạn và phần chờ

1. Quyền đọc bổ sung Developer/Manager/Technical Lead chờ Owner/PO, theo quyết định
   Staff trước của người dùng; chưa tuyên bố giải quyết mâu thuẫn RBAC toàn hệ thống.
2. Severity filter/sort đã chạy với giá trị lưu DB; **chưa có đường dữ liệu severity
   S03 → S04 được chốt và tích hợp**. Giới hạn này gồm cả capability service cung cấp
   lẫn contract/adapter tiếp nhận của S04, chi tiết ở bảng dưới. Dữ liệu chưa biết
   giữ NULL; SQL fixture không chứng minh tích hợp S03.
3. Chưa nghiệm thu FE với DTO/query mới, chưa có owner phê duyệt contract hoặc remote
   CI cho thay đổi local; inventory giữ `IN_PROGRESS`.
4. PASS thành công được chứng minh bằng component test có source validator test
   adapter. Newman kiểm chứng Return/resubmit với S01/Gateway/S04 thật, không coi
   đây là bằng chứng tích hợp S02 hoặc toàn Flow 1. API đọc không phụ thuộc S02.
5. Default sort/filter/budgets đã được mô tả cụ thể để owner/FE review; chưa có
   performance/load acceptance. Gateway anonymous JWT error body là hành vi sẵn có;
   integration kiểm 401 tại Gateway, không nhận đó là bằng chứng đủ RFC 9457 của Gateway.
6. **Phần cấu hình S04 còn cần sửa:** `infrastructure/docker/compose.yaml` chưa
   forward các biến `S04_REVIEW_QUEUE_DEFAULT_PAGE_SIZE`, `S04_REVIEW_QUEUE_MAX_PAGE_SIZE`,
   `S04_REVIEW_QUEUE_QUERY_TIMEOUT_MS` và URI override riêng của queue. Đã kiểm tra bằng
   `docker compose config` với env file tạm rỗng và các giá trị test: các key không có
   trong environment của container S04. Default và API-038 fallback vẫn hoạt động.
   Đây là việc cấu hình trên nhánh hiện tại, không phải dependency service khác.

### Severity: ranh giới service và điều kiện tích hợp

“Producer severity” trong trao đổi trước chỉ phần cung cấp dữ liệu drift; tài liệu
không bắt buộc một Kafka producer riêng cho API-115.

| Phần việc | Trách nhiệm / hiện trạng | Điều còn thiếu để kiểm chứng |
| --- | --- | --- |
| Phát hiện, phân loại và xác định severity của DriftIssue | S03 sở hữu theo LD-ARCH-003; module S03 trên nhánh đang kiểm tra mới có application bootstrap, chưa thấy implementation DriftIssue/severity | Capability S03 và evidence source-linked thật; chưa xác minh code trên các nhánh riêng của service khác |
| Chuyển dữ liệu sang candidate/read model | S03 cung cấp kết quả, S04 kiểm tra và ghi schema của mình qua contract đã thống nhất; S03 không ghi trực tiếp DB S04 | Payload/API hoặc event, provenance và consumer/adapter S04; API-160 materialize candidate hiện còn INTERNAL_CANDIDATE với CandidateObject |
| Mapping severity cho một candidate | Cột nullable hiện có phục vụ filter/sort S04; chưa coi đó là mapping đã chốt | Liên kết DriftIssue/analysis với candidate và source SHA; quy tắc khi có nhiều DriftIssue; snapshot theo thời điểm hay cập nhật hiện hành. Không tự mặc định lấy mức cao nhất |
| Đọc/filter/sort queue | Thuộc API-115, đã triển khai JPA/query và kiểm thử với dữ liệu DB | Chỉ tuyên bố phần đọc đã kiểm chứng; severity end-to-end cần đường dữ liệu thật ở các hàng trên |

SRS FR-DRI-004 quy định các mức LOW/MEDIUM/HIGH/CRITICAL, FR-REV-002 yêu cầu
filter/sort theo severity; các yêu cầu này không tự quyết định nơi lưu snapshot hoặc
thuật toán tổng hợp. Drift Severity cũng khác AI Confidence trong chính sách tạo Draft.
API-115 không tự mở rộng sang triển khai engine phân tích S03 hoặc API tạo candidate.

## Nguồn, tác giả và mức xác thực

| Nguồn | Tác giả / thời gian / nơi đăng | Mức tin cậy và giới hạn |
| --- | --- | --- |
| [LIV-144](https://huutiense25.atlassian.net/browse/LIV-144) | Jira của dự án; assignee HAI NGUYEN XUAN; updated 23/11/2026 14:48 UTC+7; đọc lại 27/11 | Nguồn quản lý scope/AC hiện hành, In Progress; trạng thái không chứng minh runtime hoặc owner đã duyệt DTO. |
| [REQ-07 SRS](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/3702801) | Confluence; v4, editor nhan nguyen, 23/10/2026 15:48 UTC; FR-REV-001/002 | Nguồn requirements nội bộ phù hợp cho queue/scope/filter. Metadata phiên bản đã đọc qua Atlassian; không có chứng thư phê duyệt độc lập trong lượt này. |
| [LD-API-001](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/11337744) | Confluence; v2, editor TIỀN HUỲNH PHẠM HỮU, 24/10/2026 14:57 UTC; catalog v0.4 | Nguồn ID/path/STAFF_SCOPE; candidate inventory không phải contract field-level đã nghiệm thu. |
| [REQ-02 RBAC](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/3670017) | Confluence; v6, editor TIỀN HUỲNH PHẠM HỮU, 19/10/2026 18:05 UTC | Nguồn phân quyền nội bộ, còn khác catalog về vai trò đọc; không tự suy ra policy mới. |
| [LD-ARCH-003 — Service Responsibilities](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/10190849) | Confluence; v2, editor nhan nguyen, 23/10/2026 15:51 UTC; đọc lại 27/11 | Nguồn ownership S03 drift/classification/severity và S04 canonical version; không cho cross-schema write. Chưa chốt mapping severity cho queue. |
| [LD-ARCH-006 — AI Runtime Boundary](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/10682549) | Confluence; v3, editor nhan nguyen, 24/11/2026 14:54 UTC; đọc lại 27/11 | Nguồn ranh giới deterministic/AI và confidence; không đồng nhất confidence với drift severity. |
| [Master Decision](../engineering/LivingDocs_Master_Decisions_Technical_Rules_Team_Agent_Handoff_2026-11-13.md) | Snapshot 13/11/2026, chủ sở hữu quyết định Project Owner; người dùng chỉ định dùng bản trong repo ngày 27/11 | Chuẩn engineering được chọn cho repo: LD-D02–06, D10–12, D17 và layered/transaction rules. Mục đề xuất/chưa chốt vẫn giữ nguyên; không tự cấp quyền Git/Jira/deploy. |
| [Gitflow](../gitflow.md), [Flow 1](flow1/README.md) | Repository local; Flow 1 do Codex tổng hợp từ nguồn người dùng ngày 22/11 | Quy trình và mapping task; file Flow 1 gốc/screenshot có giới hạn xác thực đã ghi trong README, không là runtime evidence. |
| Quyết định người dùng | Hội thoại trực tiếp 27/11/2026: triển khai Staff trước, vai trò khác pending; dùng Master Decision trong repo; ghi giới hạn service khác vào report | Căn cứ phạm vi và chuẩn áp dụng trong phiên; không tự suy ra quyền commit/push, sửa Jira hoặc merge develop. |

Tên editor và mốc phiên bản trên Confluence được xác minh qua tài khoản Atlassian
đang kết nối; không có căn cứ để đánh giá uy tín cá nhân vượt vai trò nguồn nội bộ.
Runtime evidence do Codex thực hiện local, chưa được QA độc lập hoặc Owner nghiệm thu.
