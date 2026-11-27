# LIV-145 — API-116 Staff Review Context

Người triển khai và tổng hợp: **Codex**, theo yêu cầu trực tiếp của người dùng.
Ngày kiểm chứng: **27/11/2026, Asia/Bangkok (UTC+7)**.
Branch: `feature/LIV-145-s04-staff-review-context`; nền trước triển khai: `1c00257`.
Nhánh đã nhận nền dùng chung bằng fast-forward. Report đi cùng implementation,
contract và test API-116 trong commit của task này.
Report phản ánh code và kiểm chứng local, không xác nhận deploy, remote CI hoặc Jira Done.

**Kết quả cuối:** Đã sửa local hai lỗi context ETag và conditional GET wildcard.
Người dùng đã được giải thích code và đồng ý mở rộng bộ đọc tag dùng chung nhận cả
tag cũ/mới; scope gồm phần tương thích API-113/120, không đổi luật quyền/state/mutation.
Full `mvn verify` PASS **136 tests**; integration PASS **13 runner checks,
23 requests / 67 assertions** trên Gateway/S01/S04 thật và DB/service cô lập.
Chi tiết quyết định tại [Sửa ETag và wildcard](#etag-fix); evidence cuối ở mục
Kiểm chứng local của chính report này. Master chỉ dùng để đối chiếu, giữ nguyên nội dung.

Trước sửa, 7 regression cases FAIL đúng bug lúc 23:36:17 UTC+7: 6 biến thể
metadata/content đổi vẫn nhận 304 với tag cũ, wildcard trả 200. Bảy ca này đã PASS
trong lượt cuối. Log tái hiện: `target/liv145-etag-bug-reproduction.log`.
Bản thử ban đầu từng rollback vì ảnh hưởng parser API-113/120. Sau khi được giải
thích code, người dùng đã chấp thuận sửa bộ đọc chung nhận cả tag cũ/mới. Các con số
trong report là evidence của implementation cuối, không lấy kết quả candidate đã bỏ.

**Rà soát trước commit/push:** Đối chiếu lại Master với auth/scope, service/JPA/mapper,
RLS/transaction, V6, response/ETag và config/CI. Chưa phát hiện lệch mới trong phạm vi
API-116 và phần dùng chung được chốt. Đã kiểm lại 7 hash integration, 159 class trong
JAR khớp output hiện tại, không có source S04 mới hơn JAR đã test; XML/log xác nhận
136 tests PASS. Lượt rà soát này dùng lại evidence cuối, không tuyên bố chạy lại full suite.
Master giữ nguyên như HEAD và không có trong diff commit. `.gitignore`, `.codegraph/`,
Flow 1 và output `target/` cũng nằm ngoài commit.

## Chuẩn áp dụng và phạm vi

Theo chỉ định người dùng, dùng [Master Decision trong repo](../engineering/LivingDocs_Master_Decisions_Technical_Rules_Team_Agent_Handoff_2026-11-13.md)
làm chuẩn engineering: Layered Architecture, Service interface/implementation,
JPA/Hibernate/Flyway, DTO class/private fields/Lombok, MapStruct strict mapping,
`SuccessResponse<T>`, RFC 9457, RLS và test evidence. Jira/Confluence là nguồn scope,
requirements và service ownership; các mục candidate/chưa chốt không tự thành approved contract.

Theo [LIV-145](https://huutiense25.atlassian.net/browse/LIV-145), đã triển khai:

- `GET /api/v1/document-versions/{versionId}/review-context` từ canonical S04 DB.
- Success envelope `REVIEW_CONTEXT_LOADED`, `data` là `ReviewContextView` có
  DocumentVersionView, document title/version number, content snapshot/ref/revision,
  selected template, comparison baseline, submitted actor/time, persisted review round
  và evidence UUID đúng tenant/version. Ticket enrichment không phải dependency.
- Strong context ETag `"{versionId}:revision-{revision}:context-{sha256}"`, hash toàn
  JSON success response. Context đổi làm tag đổi; API-113/120 nhận nguyên tag mới và
  tag revision-only cũ. Mutation dùng revision cũ bị 412; GET không mutate state.
- Standalone `If-None-Match: *` trả 304 sau auth/resource checks; anonymous, missing
  resource hoặc revoked scope vẫn trả lỗi. Exact/weak/list conditional comparison có regression.
- Shared Staff read authority/policy với queue; không dùng quyền mutation để gate GET.
- Migration V6 cho snapshot nội dung thật đã được người dùng chọn; giữ nguyên V1–V5.
- OpenAPI/Swagger/schema cụ thể, unit + PostgreSQL/API/negative/contract tests,
  collection Newman và runner riêng. Inventory giữ `IN_PROGRESS` chờ owner/FE/CI review.

Không triển khai API ghi nội dung, Review History riêng, tính diff tự động, parser
artifact/source, source currentness validation hoặc ghi quyết định trong GET.

<a id="etag-fix"></a>

## Sửa ETag và wildcard

| Lỗi trước sửa | Hành vi sau sửa | Bằng chứng |
| --- | --- | --- |
| Template/metadata đổi làm context đổi nhưng tag chỉ theo version revision, tag cũ vẫn nhận 304 | SHA-256 toàn JSON success response làm representation tag đổi; tag cũ nhận 200/body mới, tag mới nhận 304 khi context không đổi | Sáu biến thể PostgreSQL/API regression và Newman đổi template name |
| Standalone `If-None-Match: *` nhận 200 dù scoped context tồn tại | Trả 304 sau JWT/API-038/permission/resource checks; anonymous/missing/revoked scope vẫn trả lỗi | Component test auth/scope và Newman authorized/revoked wildcard |

Context tag mới: `"{versionId}:revision-{revision}:context-{sha256}"`. Hash gồm cả
success envelope và được kiểm với body HTTP thực. Parser `VersionEtags.require`
nhận nguyên tag này và tag revision-only cũ để giữ tương thích API-113/120. Mutation
vẫn kiểm version revision, không xác minh context digest hoặc thêm luật từ chối Review
khi template/baseline đổi độc lập. Consumer coi tag là opaque string; parser/regex ở
consumer chỉ nhận định dạng cũ cần cập nhật. Response tag của mutation giữ format cũ.

Đã đối chiếu Master LD-D02–06 (layer/service/JPA/Flyway/RLS/DTO), LD-D10–12
(auth/response/correlation) và LD-D17 (test evidence) với source. Các dependency
service khác và phần Owner/FE/CI chưa xác minh vẫn ghi ở mục Giới hạn bên dưới.

## Sequence, auth và transaction

```text
Client Bearer JWT
  → Gateway verify và forward Authorization
  → S04 verify JWT độc lập
  → StaffReadAccessResolver → S01 API-038
  → StaffReadPolicy → service read-only transaction + tenant RLS
  → kiểm đúng resource scope trước khi tải content/template/baseline/evidence
  → JPA reads + MapStruct → SuccessResponse + representation ETag
```

Trong cùng workspace grant cần `STAFF + DOCUMENT_READ + WORKSPACE_VIEW`.
Repository-bound version cần repository trong chính workspace grant đó;
repository-free version vẫn phải thuộc workspace được cấp. Vai trò đọc khác giữ
pending, theo phạm vi Staff trước đã được người dùng chọn. Không gọi fresh API-039,
Service JWT issuance hoặc S02 validation cho API-116.

`X-Correlation-ID` đã validate ở ingress được truyền tiếp từ S04 sang API-038
theo Master Decision §5.1; component tests kiểm tra đúng header ở authority adapter.
API-038 thực hiện trước DB transaction. Service dùng `REPEATABLE_READ`, read-only,
`set_config('app.tenant_id', ..., true)` và statement timeout transaction-local trên
cùng Hibernate connection; mọi dữ liệu context và revision quan sát cùng snapshot.
Version khác tenant hoặc không đúng workspace/repository trả 404, cùng lỗi với absent
version; thiếu Staff read permission trả 403. Selected template phải cùng tenant và
scope TENANT hoặc đúng workspace, không lộ metadata template của workspace khác.

Success có `Cache-Control: no-store`, `ETag`, `X-Correlation-ID`.

`VersionEtags.require` nhận format revision-only và format context có SHA-256.
Conditional GET so toàn bộ tag; mutation chỉ lấy versionId/revision, không xác minh
context digest. Vì vậy metadata đổi nhưng version revision giữ nguyên không tự
làm Submit/Review trả 412; không bổ sung luật từ chối Review dựa trên template/baseline
thay đổi. Writer sửa nội dung version vẫn phải bump revision atomically.
`If-None-Match` có thể trả 304 không body sau khi kiểm quyền; không bypass revocation.
Lỗi RFC 9457: 400 UUID/query không hợp lệ, 401 JWT, 403 quyền đọc, 404 resource scope,
405/406 HTTP transport, 500 DB/query timeout, 503 S01 unavailable/malformed. Lỗi đã
lọc không đưa SQL, upstream body hoặc credential vào response. Sau timeout transaction
rollback; lần đọc tiếp theo hoạt động, không giữ tenant context từ request trước.

## Dữ liệu và quy tắc đọc

| Dữ liệu | Nguồn / cách xử lý |
| --- | --- |
| Version và ETag | Document Version hiện tại trong S04; giữ source target/ref/SHA/config/capturedAt đã lưu, không chứng nhận SHA vẫn current |
| Nội dung | Content revision có `revision_no` lớn nhất của đúng tenant/version; trả `content_text`, format, ref và revision number |
| Selected template | Template version được pin bởi `template_version_id`; trả template identity/name/type/version, structure ref, formatting rules và publishedAt thực |
| Baseline | Cùng document/tenant, versionNo nhỏ hơn candidate, state APPROVED hoặc PUBLISHED; chọn versionNo lớn nhất rồi đọc content revision mới nhất của baseline đó |
| Evidence | Chỉ `document_evidence_refs` đúng tenant/version, thứ tự UUID ổn định; `ref` là `document_evidence_id`, kèm kind/sourceRef/artifactRef/attachedAt |
| Submission | Receipt Submit có resulting revision lớn nhất; không dùng version creation time làm submission time |
| Review round | Round lớn nhất đã persist; 0 nếu chưa có decision, không giả lập một round đã hoàn thành |

Không có baseline/template/submission thì trả NULL tương ứng. Không tự dùng một
version mới hơn candidate làm baseline hoặc chọn version của document khác.
Baseline rule là lựa chọn cụ thể của implementation để owner/FE review cùng DTO;
chưa coi đó là approved selection policy rộng hơn requirements.

### Snapshot nội dung được người dùng phê duyệt trong phiên

Schema trước đó chỉ có `content_ref`, không có artifact-read contract hoặc body text.
Người dùng chọn **bổ sung snapshot nội dung trong S04**, thay vì dùng ref làm text.
V6 thêm `content_text` nullable và `content_format` MARKDOWN/TEXT nullable, constraint
yêu cầu body/format cùng có hoặc cùng NULL. Không backfill hoặc sửa migration cũ.
Content revisions giữ append-only privilege của runtime role. Tổng 6 migration,
vẫn 13 bảng RLS; không thêm bảng cross-service hoặc native CRUD persistence.

Legacy reference-only trả `content=null`, `contentFormat=null` và `contentRef` thật.
Writer tạo/sửa nội dung phải append content revision và tăng Document Version revision
trong cùng transaction để ETag còn bảo vệ nội dung được review. Endpoint GET này không
tạo đường ghi nội dung mới; các writer/authoring API phải tích hợp snapshot sau đó.

API chỉ trả opaque source/artifact/structure refs đã lưu; không fetch URL, đọc file
hoặc suy đoán content/diff/template rules. Có hai snapshot body trong test để chứng
minh đọc revision mới nhất; đó là controlled DB fixture, không phải authoring E2E.

## Thay đổi code/config và contract

- Controller → Service interface/Impl → Spring Data JpaRepository → PostgreSQL;
  DTO riêng, MapStruct `unmappedTargetPolicy=ERROR`, không trả raw Entity.
- Bổ sung read-only entities/repositories cho document title, content revisions,
  template và template version; bổ sung các field/query đọc cần thiết ở version/evidence/receipt.
- Đổi resolver đọc queue thành `StaffReadAccessResolver` và gom `StaffReadPolicy`
  cho API-115/API-116; không thay đổi mutation authorization hoặc business transitions.
- Config authority đọc chung: `S01_READ_ACCESS_CONTEXT_URI`, `S04_READ_ALLOW_INSECURE_HTTP`.
  Giữ fallback queue/review config cũ. Timeout riêng `S04_REVIEW_CONTEXT_QUERY_TIMEOUT_MS`
  default 2000ms; Compose đã forward các biến mới và giữ được explicit false.
- Springdoc mở đúng path context; Gateway GET allowlist và CORS exposed ETag đã có sẵn.
  Canonical OpenAPI và inventory cập nhật riêng API-116; không tự mở internal routes.
- CI hiện có cập nhật expected migration count 6; không thêm dependency hoặc workflow CI mới.
  Harness bootstrap test cô lập được tái sử dụng; API-116 có collection/scenario và
  output riêng, default runner queue vẫn giữ luồng queue.

## Kiểm chứng local

| Kiểm tra | Kết quả | Bằng chứng |
| --- | --- | --- |
| Full S04 regression cuối — `mvn -B -ntp verify` | PASS 136 tests, 0 failure/error/skip; build 23:47:55 UTC+7, Maven exit 0 | `target/liv145-etag-final-verify.log`; gồm full Submit/Review regression |
| API-116 và shared policy/mapper | Bao gồm trong 136 tests: 18 context integration + 1 mapper + 2 policy | `ReviewContextApiIntegrationTest`, `ReviewContextMapperTest`, `StaffReadPolicyTest` |
| Tái hiện trước sửa → regression cuối | 7 cases ban đầu FAIL đúng bug; cả 7 PASS trong lượt cuối, hash đối chiếu body HTTP thực | `target/liv145-etag-bug-reproduction.log`, `target/liv145-etag-final-verify.log` |
| Canonical OpenAPI | PASS 6 YAML schemas | `openapi-spec-validator 0.7.2` |
| Runtime OpenAPI | PASS: method/path, operationId, parameters, statuses/headers và concrete response shapes so với canonical | `runtimeOpenApiMatchesCanonicalContextShapesAndHeaders` |
| Gateway static validation | PASS 114 catalogued APIs, 91 method/path, 19 route groups; không expose internal | `scripts/validate_gateway_openapi.py` |
| Compose static config | PASS: forward shared read URI, explicit false transport flag và context timeout override 750 | Config với env file tạm rỗng và placeholder, không đọc secret thật |
| Gateway/S01/S04 + Newman — artifact cuối | PASS 13 runner checks; 23 requests / 67 assertions, 0 failure/skip; 23:48:13–23:50:40 UTC+7; exit code 0 | `target/api116-s01-integration.json`, `target/liv145-etag-final-integration.log`; metadata invalidation, authorized/revoked wildcard và context ETag → Return/resubmit |
| Whitespace/UTF-8/local links | PASS | `git diff --check`, Java/report UTF-8 và local links |

Component tests dùng JWT ký bằng test key, authority/source test adapters và PostgreSQL
thật. Có kiểm scope trước dữ liệu, wrong tenant/workspace/repository, selected template
ngoài scope, missing resource, JWT trust/expiry, upstream errors/malformed, NULL legacy,
body/format constraints, conditional GET revocation, tenant pool reuse, DB timeout/recovery,
baseline không chọn version mới hơn candidate, ETag → Decision và revision cũ bị 412.
Sáu biến thể context đổi giữ candidate revision là document title, template name/rules,
baseline content, evidence và candidate content. SQL content mutation không bump revision
chỉ là fixture kiểm validator; không thay đổi contract writer. Có test wildcard auth/scope,
weak/list comparison, tag revision-only không được dùng thay context validator, và parser
mutation nhận cũ/mới nhưng vẫn reject weak/malformed/list/cross-version.
Đây không phải bằng chứng S01/S02/FE integration của các component tests.

Collection riêng: [liv145-review-context.postman_collection.json](../../tests/postman/liv145-review-context.postman_collection.json).
Runner riêng: [test_api116_s01_integration.py](../../scripts/test_api116_s01_integration.py).
Chạy từ repository root sau khi build JARs S01/S04/Gateway:

```powershell
python -X utf8 scripts/test_api116_s01_integration.py --newman-cli "$env:TEMP/livingdocs-newman/node_modules/newman/bin/newman.js"
```

Runner tạo PostgreSQL/Redis và process Java riêng rồi cleanup. Dùng S01 register/login,
JWT/API-038 thật, Gateway/S04 thật và API-039 thật cho protected mutations. Không đọc
`.env` hoặc DB đang phát triển. Content/template/baseline/evidence/VERIFIED-grant SQL
fixtures là test setup đã ghi nhãn, không chứng minh production onboarding, artifact
reader hoặc authoring API. Raw credentials/Newman chỉ ở temp; output JSON đã lọc trong
S04 target, có SHA256 các JAR/runner/collection để kiểm tra đúng artifact.

Newman theo phase: canonical context và authorized wildcard **10 request / 30 assertion**;
đổi template metadata không đổi candidate revision, tag cũ nhận 200/body mới và tag mới
nhận 304 **2 / 5**; context ETag → Return → stale revision bị 412 → resubmit **5 / 15**;
revoked repository và conditional/wildcard GET không bypass scope, kèm queue regression
**4 / 11**; revoked Staff **1 / 3**;
S01 unavailable **1 / 3**. Runner so sánh version/content/audit/submission/decision
trước và sau các GET để xác nhận không ghi state; đổi workload secret thành giá trị
sai vẫn đọc context được, xác nhận read không phụ thuộc fresh/workload authorization.
Mỗi phase Newman là process riêng; runner truyền ETag đọc từ HTTP thật giữa các phase,
không giả định environment thay đổi ở phase trước tự tồn tại trong phase sau.

SHA256 S04 JAR cuối đã chạy integration:
`8033b81d5e8f90b07020afbf9eb76a6f69b1c085bef932b795309fa5bb16a137`.
SHA256 collection:
`1d5c3395016e38bcee15291d77ea18cffcb5e5b17108ed30dec30a0a03ee570f`.
Đã đối chiếu lại đủ 7 hash JAR/runner/helper/collection trong JSON cuối với file hiện tại.
Gateway/S01 là JAR nền không sửa source trong task; regression Maven toàn Gateway/S01
không được chạy lại. Missing-JWT tại Gateway kiểm status 401; chưa tuyên bố error body
Gateway đã đầy đủ RFC 9457. Các lỗi S04 trong collection kiểm cả Problem/correlation.

## Giới hạn, ownership và phần còn chờ

| Phần | Trách nhiệm / giới hạn | Ảnh hưởng và điều kiện hoàn tất |
| --- | --- | --- |
| Snapshot legacy / content writers | S04 authoring/version writers phải cung cấp body/format và bump parent revision; GET chỉ đọc | Dữ liệu ref-only giữ NULL; chưa có authoring-endpoint integration hoặc backfill trong task đọc này |
| Source file/entity, commit/diff artifact | S02/S03 cung cấp source/analysis qua contract; S04 giữ canonical refs/binding | Trả đúng evidence đã lưu, chưa tải raw diff/source từ service khác; không báo reference fixture là source integration PASS |
| Template structure artifact / conformance | S04 template capability/contract; context trả selected version và rules thực có trong DB | Chưa đọc external structure artifact hoặc tự xác nhận template conformance PASS |
| Comparison UI/diff | Context cung cấp baseline/body/ref; FE/comparison capability thực hiện hiển thị/diff theo contract | Chưa nghiệm thu comparison UI hoặc diff computation; baseline selection/DTO còn chờ owner/FE review |
| Review History | API riêng và FE workflow | Context chỉ đọc persisted round; không tuyên bố triển khai history endpoint trong API-116 |
| Ticket enrichment | Integration optional | Thiếu ticket không làm core context thất bại; không gọi provider trong GET |
| Source currentness / protected PASS | Adapter S02 của mutation là dependency riêng | API-116 đọc được khi không có validator; không dùng context GET làm bằng chứng PASS đã tích hợp S02 |
| Read roles khác Staff | Owner/PO quyết định | Giữ Staff trước theo phiên; không tự mở rộng Developer/Manager/Technical Lead |
| Contract/FE/remote CI | Owner/FE/Gateway phối hợp review; required checks sau push | Inventory IN_PROGRESS; local PASS không phải FE acceptance hoặc Jira/Story Done |
| Context validator / wildcard | Đã sửa và kiểm chứng local; không còn là bug OPEN trong report này | Evidence cuối trong report này; chưa đồng nghĩa Owner/FE acceptance hay remote CI. Mutation vẫn guard version revision, không validate context digest |

Không triển khai service khác để lấp dependency hoặc tự điền dữ liệu giả. Nội dung
snapshot, baseline và evidence trong tests đã ghi rõ fixture; kết luận giới hạn theo
nhánh đang kiểm tra, không khẳng định các nhánh riêng của service khác chưa có code.

## Nguồn, tác giả và mức xác thực

| Nguồn | Tác giả / phiên bản / nơi đăng | Giá trị và giới hạn |
| --- | --- | --- |
| [LIV-145](https://huutiense25.atlassian.net/browse/LIV-145) | Jira; assignee HAI NGUYEN XUAN; updated 27/11/2026 07:01 UTC+7; đọc lại trong phiên | Scope/AC hiện hành, In Progress; không phải runtime hoặc phê duyệt DTO |
| [REQ-07 SRS](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/3702801) | Confluence v4; editor nhan nguyen, 23/10/2026 15:48 UTC | FR-REV-003–006: evidence, history trace, comparison baseline, template/source verification; metadata đã đọc qua Atlassian, chưa có QA/approval độc lập trong phiên |
| [LD-API-001](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/11337744) | Confluence v2/catalog v0.4; editor TIỀN HUỲNH PHẠM HỮU, 24/10/2026 14:57 UTC | ID/path/STAFF_SCOPE và DTO name; candidate inventory không là schema field-level đã nghiệm thu |
| [Master Decision local](../engineering/LivingDocs_Master_Decisions_Technical_Rules_Team_Agent_Handoff_2026-11-13.md) | Snapshot 13/11/2026; Project Owner sở hữu quyết định; người dùng chọn dùng bản repo ngày 27/11 | Chuẩn engineering của triển khai này; đề xuất/chưa chốt vẫn giữ trạng thái, không tự cấp quyền remote actions |
| [Gitflow](../gitflow.md), `docs/implementation/flow1/README.md` | Repository local; Flow 1 do Codex tổng hợp theo nguồn người dùng ngày 22/11, bị ignore và không nằm trong commit | Mapping API/task và quy trình; snapshot lịch sử không thay live refs, Jira hoặc runtime evidence |
| Quyết định trong phiên | Người dùng giao triển khai API-116/report và chọn bổ sung snapshot nội dung trong S04 ngày 27/11 | Căn cứ sửa code/schema trong scope; không tự suy ra quyền commit/push, sửa Jira hoặc merge develop |
| Phạm vi sửa ETag trong phiên | Người dùng, 27/11: đồng ý sửa bộ đọc chung nhận tag cũ/mới sau khi được giải thích code | Cho phép sửa tương thích API-113/120 trong lượt này; không tự nghiệm thu contract/FE/CI |
| [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2) | Fielding/Nottingham/Reschke, IETF/RFC Editor, tháng 6/2022; Internet Standard | Nguồn sơ cấp cho strong validator/If-None-Match; đối chiếu kèm HTTP tests |
| [Spring ServletWebRequest v7.0.9](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/web/context/request/ServletWebRequest.java) | Spring Framework upstream; authors Hoeller/Clozel/Malkusch; đọc 27/11; JAR thực chứa spring-web 7.0.9 | Source sơ cấp giải thích wildcard trong safe-method comparison; behavior đã tái hiện bằng HTTP test, không chỉ suy từ source |

Các tài liệu trên là nguồn nội bộ được đối chiếu qua repo/Atlassian; editor identity
và version metadata không phải chứng thư nghiệm thu hoặc đánh giá uy tín cá nhân.
Evidence runtime do Codex thực hiện local; chưa thay QA độc lập, Owner review hoặc remote CI.
