Vì vậy, tôi đề xuất chốt lại Flow 1 gồm hai giai đoạn nghiệp vụ bắt buộc:

# 1. Onboarding & Repository Access: Tenant/Workspace → Members/Roles → GitHub Connection → Verified Repository Access.

# 2. Document Governance: Developer Draft → Staff Review → Eligible Manager Approval → Current Source Validation → Publish.

Hai giai đoạn này phải được triển khai như một flow sản phẩm, thay vì tiếp tục coi giai đoạn đầu là chuyện phụ.

flowchart TD
    A["Tenant Owner: Workspace + Members + Roles
    S01 — API-029 / 034 / 036"] --> B["Developer / Technical Lead:
    Connect GitHub Repository
    S02 — API-047"]
    B --> C["S02: Verified Repository Connection
    API-048 / 049 + trusted binding"]
    C --> D["S01: Grant Repository Access
    Request → Verify → Activate
    for Developer / Staff / Manager"]
    D --> E["Developer:
    Create / Edit / Submit Draft
    S04 — API-100 / 105 / 107 / 113"]
    E --> F["Staff Review:
    PASS / RETURN
    S04 — API-120"]
    F --> G["Manager:
    Final Approve
    S04 — API-124"]
    G --> H["S01 API-039: Fresh Permission
    S02 API-070: Current SHA"]
    H --> I["S04 — API-127
    PUBLISHED + Audit"]


## Lưu ý: sơ đồ thể hiện thứ tự nghiệp vụ, không phải mọi lần gọi API theo thời gian. Fresh authorization và source guards được thực hiện tại những protected actions tương ứng, không chỉ ở bước ngay trước Publish.

Một điểm quan trọng để không tạo vòng lặp: Developer hoặc Technical Lead được phép kết nối repository dựa trên Workspace 

permission + GitHub access hợp lệ. Không thể bắt họ phải có Repository Grant VERIFIED cho chính connection chưa được tạo. 

Sau khi có connection, hệ thống mới thiết lập quyền repository cho những người tham gia workflow.
## 2. Danh sách API phải hoàn thiện cho Flow 1
Tôi chia thành 4 nhóm triển khai. P0 là bắt buộc để đi từ người dùng đến Published bằng dữ liệu thật; P1 là các chức năng 

bổ sung để hoàn thiện trải nghiệm và quản trị.

### A. S01 — Tenant, Workspace, Role và Repository Access

API / Operation	Role	Nghiệp vụ	Ưu tiên

API-029 — Create Workspace	Tenant Owner	Tạo Workspace chứa repositories và documents	P0

API-034 — Add Workspace Member	Tenant Owner	Đưa Developer, Staff, Manager, Technical Lead vào Workspace	P0

API-036 — Assign Workspace Roles	Tenant Owner	Gán quyền theo vai trò	P0

Create Repository Grant — mới	Actor quản trị được policy cho phép	Cấp access repository cho một Workspace Member	P0

Verify/Confirm Repository Grant — mới	Trusted S01/S02 integration	Xác minh rồi chuyển PENDING → VERIFIED	P0

Get Repository Grants — mới	Actor có quyền quản trị/đọc	Xem ai có quyền trên repository nào	P0

API-038 — Access Context	User	Xem effective roles, permissions và repository access	Đã có

API-039 — Fresh Authorization	S04	Đánh giá quyền hiện hành theo actor + action + resource/scope cho sensitive actions; phạm vi từng API ở mục 3	PR2, chưa có evaluator trong S01 đã kiểm tra

Service Token Issuance — mới	Workload S03/S04	Cấp Service JWT cho internal operations	PR2

API-028/033/037	Authorized user	Danh sách Workspace, Membership, Roles	P0 cho UI quản trị

Revoke Repository Grant — mới	Actor quản trị được policy cho phép	Thu hồi quyền repository, lưu audit/revision	P1 

trước nghiệm thu quản trị đầy đủ

Những operation Repository Grant mới chưa có API ID/path chính thức. Chúng phải được bổ sung vào OpenAPI, không gán nhầm thành API-038/039.

API-034/036 hiện cũng chưa có HTTP controller hoàn chỉnh; PR1 chủ yếu xây nền tảng và Operator CLI.

Quyết định nghiệp vụ còn phải khóa: ai có quyền cấp Grant cho thành viên khác? Tôi đề xuất Tenant Owner quản trị quyền cho các Workspace Members, không tự có quyền duyệt tài liệu; Technical Lead được quyền quản lý connection/trigger trong phạm vi được cấp. Đây là đề xuất, không phải chính sách đã approve.

### B. S02 — GitHub Repository Connection và Source Authority

API	Role / Caller	Nghiệp vụ	Ưu tiên

API-046	Developer / Technical Lead	Xem GitHub authorization scopes	P0

API-047	Developer / Technical Lead	Kết nối repository thật với Workspace	P0

API-048	Authorized user	Xem danh sách repository đã kết nối	P0

API-049	Authorized user	Xem trạng thái/ownership của connection	P0

Verify Repository Binding — mới	Trusted S01/S02	Xác nhận connection ID, tenant, workspace, status	P0

API-070	S04 workload	Kiểm current SHA trước các protected transitions	P0

API-069	S03 workload; những use case source binding phù hợp	Resolve source snapshot/SHA	Đang làm, quan trọng cho Flow 2

API-050/051	Authorized Developer / Technical Lead	Disconnect và reauthorize connection	P1

API-057/058	Technical Lead	Xem/chọn tracked branch	P1 cho Flow 1; P0 cho Flow 2

Phải tách hai chức năng kiểm chứng:

- Binding verification: repository connection có thực sự thuộc Tenant/Workspace đó và ACTIVE không?

- Source validation: source SHA gắn với tài liệu còn current không?

API-070 không tự động hoàn thành cả vòng đời Repository Grant.

### C. S04 — Developer Document APIs

API	Role	Nghiệp vụ	Ưu tiên

API-100	Developer	Tạo document trong Workspace	P0

API-105	Developer	Tạo Document Version dạng DRAFT	P0

API-107	Developer	Chỉnh sửa nội dung Draft	P0

API-113	Developer	Submit Draft sang Staff Review	P0

API-101/104/106	Authorized user	Đọc document, version history và version detail	P0 cho UI

API-108	Authorized user	Xem source evidence/diff	P0 cho source-bound review



Nhóm này cần một backend developer phụ trách riêng. Nếu không có, Frontend sẽ phải tiếp tục dùng fixture hoặc thao tác 
trực tiếp database để tạo Draft, không đúng một flow người dùng hoàn chỉnh.

### D. S04 — Staff, Manager và Publish

API	Role	Nghiệp vụ	Ưu tiên

API-115/116	Staff	Review Queue và Review Context	P0

API-120	Staff	PASS / RETURN	Đã tích hợp JWT thật + API-038; còn thiếu fresh evaluator API-039

API-121	Staff	Review History	P0

API-117/118	Staff	Đọc/thêm review comments	P1 cho full review workflow

API-119	Staff	Minor edit, ghi editor history	P1, cần test No Self Approval

API-122/123	Manager	Approval Queue và Approval Context	P0

API-124	Manager	Final Approve / Reject / Return	P0

API-125	Manager	Approval History	P0

API-127	Manager có quyền	Publish version APPROVED	P0

API-109/110/111	Authorized user	Change log, compare versions, attribution	P1

API-114	Authorized user	Revalidation khi source stale	P1 cho recovery đầy đủ



Trên API-120/124/127, S04 phải kiểm quyền thật qua S01, source binding/current SHA qua S02 khi áp dụng, và giữ các business 

guards riêng: Staff PASS, state, ETag, editor history và No Self Final Approval.

## 3. Phân loại API cần fresh authorization của S01

**Người cập nhật:** Codex, theo yêu cầu người dùng. **Ngày cập nhật:** 23/11/2026, UTC+7.
Phạm vi đối chiếu: các task S04 Flow 1 đang làm và dependency S01/S02 đã rà soát trong phiên ngày 23/11/2026; không phải xác nhận mọi API trong danh sách mục 2 đã có implementation.

Fresh authorization ở đây là S01 đánh giá quyền hiện hành cho một protected action cụ thể, theo actor và resource/scope; không phải refresh access token. API-038 cung cấp snapshot access context, không thay cho evaluator API-039. Service JWT/service assertion xác thực caller giữa service là cơ chế riêng.

### 3.1. Nhóm đã có căn cứ yêu cầu fresh

| API / Operation | Task liên quan | Yêu cầu | Căn cứ và giới hạn |
|---|---|---|---|
| API-120 — `POST /api/v1/document-versions/{versionId}/staff-decisions` | LIV-118, dependency LIV-138 | Fresh cho cả `PASS` và `RETURN_FOR_REVISION` trước mutation | Runbook LIV-138 yêu cầu action review/approve/publish hỏi S01 fresh. Cả hai action thay đổi workflow; API-120 hiện mới dùng JWT + API-038. |
| API-124 — `POST /api/v1/document-versions/{versionId}/manager-decisions` | LIV-120/121 | Fresh cho quyết định Manager: `FINAL_APPROVE`, `RETURN_STAFF`, `RETURN_DEV`, `REJECT` | Final Approval là sensitive action; LIV-121 yêu cầu S01 fresh authorization/scope và negative cases khi quyền bị thu hồi. Áp dụng cùng bước enforcement cho các quyết định Manager; business guards vẫn theo từng action. |
| API-127 — `POST /api/v1/document-versions/{versionId}/publish` | LIV-123, dependency LIV-138 | Fresh trước Publish | Publish được nêu trực tiếp trong baseline authorization và scope LIV-138. ALLOW không thay điều kiện APPROVED hay source revalidation. |

### 3.2. Nhóm đề xuất bổ sung bắt buộc, chưa phải chính sách đã chốt

| API / Operation | Task liên quan | Đề xuất | Lý do / điểm cần chốt |
|---|---|---|---|
| API-113 — `POST /api/v1/document-versions/{versionId}/submit` | LIV-143 | Bổ sung fresh trước Submit | Chuyển `DRAFT → IN_REVIEW / STAFF_REVIEW`, cần quyền Developer hiện hành đúng scope. Task đã yêu cầu authorization/scope, nhưng chưa quy định riêng đủ rõ phải dùng API-039. |
| API-119 — `PATCH /api/v1/document-versions/{versionId}/staff-edits` | LIV-149 | Bổ sung fresh trước minor edit | Sửa nội dung đang review, revision và editor history, ảnh hưởng No Self Final Approval. Task đã yêu cầu effective permission; việc bắt buộc API-039 là đề xuất cần chốt. |

Danh sách đề xuất sau khi chốt sẽ gồm **API-113, API-119, API-120, API-124, API-127**. Không ghi cả năm là yêu cầu đã được phê duyệt. Bổ sung enforcement tại consumer thuộc task API tương ứng; provider/evaluator và service trust thuộc LIV-138, không mở rộng toàn bộ vào LIV-118.

### 3.3. Nhóm có điều kiện hoặc không mặc định gọi API-039

| API / Operation | Phân loại | Yêu cầu còn phải giữ |
|---|---|---|
| API-118 — thêm review comment/request change, LIV-148 | Nếu chỉ lưu feedback: chưa đủ căn cứ bắt buộc API-039. Nếu có tác động workflow: phải chốt hành vi với PO và áp dụng fresh cho protected action tương ứng. | Staff đúng scope, actor từ authenticated identity, validation, atomic comment/audit. Không tự đồng nhất request change theo section với Return của API-120. |
| API-115/116/117/121 — review queue/context/comments/history, LIV-144/145/147/146 | Không mặc định gọi API-039 mỗi request. | JWT hợp lệ, kiểm quyền đọc và tenant/workspace/repository/resource scope tại backend; UI visibility không phải enforcement. |
| API-122/123 — approval queue/context; API-125 — approval history | Là các API GET đọc dữ liệu, không phải API ghi quyết định Manager; không mặc định bắt buộc API-039. | Kiểm quyền đọc Manager/resource scope. API ghi quyết định là API-124. Chưa xác nhận task riêng đang triển khai cho từng API đọc trong nhóm này. |
| API-038 — access context | Snapshot phục vụ context; không yêu cầu gọi vòng qua API-039. | Xác thực Identity và dựng effective grants theo contract S01. Không dùng context FE gửi lên làm nguồn quyền tin cậy. |
| API-070 — S02 source validation, LIV-122 | Không tự động cần thêm một lần user fresh chỉ vì là lời gọi nội bộ. | Xác thực service caller, audience/scope phù hợp và kiểm source binding/current SHA. S04 đã kiểm quyền user cho action tương ứng; S02 vẫn giữ guards riêng. |

Các API khác trong mục 2, gồm onboarding/quản trị quyền và Developer create/edit, chưa được phân loại đầy đủ ở lượt rà soát này. Thao tác đổi quyền thuộc nhóm nhạy cảm theo baseline, nhưng actor policy và cách enforcement cho từng operation còn phải đối chiếu riêng; không suy ra chúng được miễn kiểm quyền hiện hành.

### 3.4. Điều kiện triển khai và kiểm chứng

- S04 xác thực User JWT, resolve resource/scope và hỏi S01 theo **actor + action + resource/scope**; không chỉ hỏi user có role Staff/Manager chung hay không. Contract chi tiết PR2 trong runbook còn là dự kiến, cần chốt trước khi triển khai đồng loạt.
- Với action cần fresh, S01 trả DENY hoặc không thể đánh giá thì S04 chặn action; không fallback sang quyền cũ trong JWT/API-038. Lỗi không được tạo partial mutation.
- Theo hướng PR2 dự kiến: resolve resource dưới RLS trong read transaction, đóng transaction trước HTTP S01; sau ALLOW mở write transaction và kiểm lại state/scope/ETag. S04 tiếp tục giữ business guards, source checks, editor history, No Self Final Approval và atomic audit phù hợp từng action.
- Fresh ALLOW không bảo đảm nguyên tử giữa một lần thu hồi quyền ở S01 và commit ở S04. Không tuyên bố instant revocation xuyên service khi chưa có cơ chế và bằng chứng tương ứng.
- Bộ kiểm thử cần có: quyền hợp lệ; quyền bị thu hồi nhưng JWT chưa hết hạn; sai action/scope/tenant; S01 unavailable; local business guard thất bại sau ALLOW. Xác định hành vi replay và kiểm quyền theo contract, không dùng idempotency để bỏ qua authorization.

**Hiện trạng đã kiểm tra ngày 23/11/2026:** API-120 gọi API-038 mỗi request, kể cả exact replay, rồi kiểm scope tại S04; chưa gọi evaluator API-039. Client hiện lọc `STAFF` + `DOCUMENT_STAFF_REVIEW`, chưa phải client auth dùng nguyên trạng cho Developer/Manager/Publish. Source/runtime S01 đã kiểm tra trong phiên chưa có evaluator và cơ chế cấp/xác minh service assertion. Kết quả test JWT/API-038 không chứng minh fresh API-039 đã tích hợp. Xem [báo cáo tích hợp API-120](../authorization/LIV-118-api120-s01-integration.md).

Mục này là kết quả rà soát và đề xuất chính sách; chưa triển khai evaluator, chưa xác nhận integration/QA cho các API còn lại.

## 4. Nguồn đối chiếu và mức độ xác thực cho mục 3

| Nguồn / nơi đăng | Tác giả, phiên bản và thời gian | Giá trị bằng chứng / giới hạn |
|---|---|---|
| [Master Decision Log — LD-D10](https://huutiense25.atlassian.net/wiki/spaces/LD/pages/18841604) — Confluence LivingDocs | Version 1, người cập nhật TIỀN HUỲNH PHẠM HỮU, 13/11/2026 UTC+7 | Nguồn quyết định nội bộ sơ cấp: quyền nhạy cảm cần fresh validation. Trạng thái ĐÃ CHỐT trong tài liệu không xác nhận runtime đã triển khai. |
| [LD-API-001 — API Inventory](https://huutiense25.atlassian.net/wiki/spaces/LD/pages/11337744) — Confluence LivingDocs | Nội dung v0.4, Confluence version 2; người cập nhật TIỀN HUỲNH PHẠM HỮU, 24/10/2026 UTC+7 | Nguồn thiết kế sơ cấp cho API ID/path và baseline: fresh cho sensitive actions, không nằm trên mọi request. Đây là candidate design, không tự chứng minh ma trận từng API đã được approve hoặc triển khai. |
| [SRS — FR-AUT-002, SEC-002, FR-DOC-008, FR-REV-007/008](https://huutiense25.atlassian.net/wiki/spaces/LD/pages/3702801) — Confluence LivingDocs | Version 4; người cập nhật nhan nguyen, 23/10/2026 UTC+7 | Yêu cầu protected action có Effective Permission đúng scope; phân biệt submit, comment và minor edit. Không quy định riêng mọi mutation phải gọi API-039. Người cập nhật version không đồng nghĩa tác giả toàn bộ yêu cầu. |
| [LIV-138](https://huutiense25.atlassian.net/browse/LIV-138), [LIV-121](https://huutiense25.atlassian.net/browse/LIV-121), [LIV-123](https://huutiense25.atlassian.net/browse/LIV-123) — Jira LivingDocs | Đọc live 23/11/2026; issue lần lượt cập nhật 15/11, 09/11, 17/11/2026 UTC+7. Tác giả description chưa xác định. | Nguồn scope/điều kiện task sơ cấp cho authority, Manager fresh và Publish. Assignee không chứng minh tác giả hoặc người phê duyệt; trạng thái In Progress không phải evidence PASS. |
| [LIV-143](https://huutiense25.atlassian.net/browse/LIV-143), [LIV-148](https://huutiense25.atlassian.net/browse/LIV-148), [LIV-149](https://huutiense25.atlassian.net/browse/LIV-149) — Jira LivingDocs | Đọc live 23/11/2026; issue lần lượt cập nhật 22/11, 23/11, 23/11/2026 UTC+7. Tác giả description chưa xác định. | Căn cứ task Submit/comment/minor edit và các quyết định còn cần PO review. Chưa có quyết định riêng chốt API-039 cho API-113/119. |
| [Runbook LIV-138, phần API-038 và PR2](../authorization/LIV-138-s01-authority-and-testing.md#api-038-fe-và-pr-2s04) — repository | Commit `e0d7fc1`, author `huutienSE`, 22/11/2026 10:21:52 UTC+7; commit author không tự chứng minh tác giả mọi nội dung | Căn cứ trực tiếp action review/approve/publish phải hỏi S01 fresh. Contract PR2 được ghi là dự kiến; không coi là provider đã tồn tại. |
| [S04 authorization client](../../../backend/document-governance-service/src/main/java/com/livingdocs/document/client/S01ReviewAuthorizationClient.java), [báo cáo API-120](../authorization/LIV-118-api120-s01-integration.md) — repository | Source được Codex kiểm tra ngày 23/11/2026; báo cáo do Codex lập cùng ngày | Bằng chứng implementation hiện tại dùng API-038. Tài liệu này chưa bổ sung test evaluator thật hoặc xác nhận độc lập của reviewer/QA. |
