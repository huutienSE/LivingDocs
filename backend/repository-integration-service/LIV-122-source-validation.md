# LIV-122 — Phân tích và phần triển khai ban đầu

Ngày: 2026-11-05. Trạng thái: đang triển khai; chưa đủ điều kiện DONE.

Các mục phân tích ban đầu bên dưới được giữ làm lịch sử. Hiện trạng HTTP mới nhất nằm ở **Cập nhật ngày 2026-11-20 — bước 5** cuối tài liệu và HLD §9.2.

## Mục tiêu và nguồn tham chiếu

- [LIV-122](https://huutiense25.atlassian.net/browse/LIV-122): S02 xác định repositoryConnectionId, kiểm tra head hiện hành, không chuyển credential GitHub sang S04.
- [LIV-99](https://huutiense25.atlassian.net/browse/LIV-99): Publish chỉ từ APPROVED, có kiểm chứng source và quyền; cần test GitHub thật, S04, lịch sử/audit bền vững và review độc lập.
- [LIV-85](https://huutiense25.atlassian.net/browse/LIV-85): authoritative PR head/branch head, không thay PR head bằng merge SHA.
- [LIV-90](https://huutiense25.atlassian.net/browse/LIV-90): source thay đổi phải qua revalidation; kết quả cũ không ghi đè kết quả mới.
- [LIV-96 / S3-EN01](https://huutiense25.atlassian.net/browse/LIV-96): prerequisite vẫn In Progress khi kiểm tra; scaffold và migration Done, auth/gateway vẫn đang làm.
- [LD-API-001 v0.4](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/11337744): API-069 `/internal/v1/source/resolve`, API-070 `/internal/v1/source/validate`; service identity, scope, TLS, receipt/fence và giới hạn race GitHub. Tài liệu này vẫn là contract candidate, không tự coi là Accepted ADR.

## Đã triển khai

`SourceValidationService` nằm tại `service/` và xử lý nghiệp vụ xác minh nguồn. Các mô hình nằm ở `model/`. Hai giao diện đọc dữ liệu S02 là `ConnectionLookup` và `ConfigurationRevisionLookup` nằm ở `repository/`. Giao diện đọc đầu nguồn GitHub `AuthoritativeHeadLookup` nằm ở `client/`.

`JpaConnectionLookup` tại `repository/` dùng hai thành phần truy xuất dữ liệu để đọc kết nối kho mã nguồn và kết nối GitHub. `ConnectionLookupConfiguration` tại `config/` đăng ký phần tra cứu với Spring. Mỗi lần tra cứu đặt `app.tenant_id` theo phạm vi đã xác thực và dùng giao dịch đọc riêng ở mức `REPEATABLE READ` để đọc trạng thái và phiên bản cấu hình từ cùng một ảnh chụp dữ liệu.

Kết nối không tồn tại/sai tenant/sai workspace trả rỗng; kết nối không hoạt động trả trạng thái không sẵn sàng. Kết quả nghiệp vụ không chứa credential_ref, mã truy cập hoặc Entity. Phạm vi truyền vào vẫn phải do tầng xác thực cung cấp, phần tra cứu không thay thế kiểm tra quyền.

V1 chỉ quy định chuỗi trạng thái GitHub không trống, chưa có danh sách trạng thái sẵn sàng. Thuộc tính `livingdocs.source.lookup.github-ready-statuses` phải được cấu hình theo quyết định tích hợp; mặc định danh sách rỗng nên không kết nối nào được coi là sẵn sàng. Trạng thái kho mã nguồn yêu cầu `ACTIVE` theo V1. `READY_TEST` trong kiểm thử chỉ là fixture, không phải trạng thái chuẩn được phê duyệt.

V2 cấp phiên bản cấu hình cho kết nối kho và kết nối GitHub, đồng thời cập nhật phiên bản kết nối kho khi nhánh theo dõi thay đổi. `ConfigurationRevisionLookup` đọc cả hai giá trị trong PostgreSQL theo tenant, workspace và kết nối kho. Bên gọi xem chuỗi kết quả là giá trị dùng để so khớp nguyên vẹn. Kết nối sẵn sàng nhưng thiếu phiên bản cấu hình dẫn tới `FAIL / SOURCE_UNAVAILABLE` (không đạt / nguồn chưa xác minh được).

Chưa có phần gọi GitHub thật. Dịch vụ xác minh chưa đăng ký thành bean tự chạy vì còn thiếu phần lấy head hiện hành và chính sách hiệu lực được thống nhất. Không có giá trị SHA/config/fence giả trong cấu hình production.

Core thực hiện:

1. Kiểm tra SHA đầy đủ 40 ký tự hex, ref branch đầy đủ hoặc số PR dương, config revision không trống.
2. Resolve connection theo tenant/workspace/repository; connection khác scope được xem như không tồn tại. Connection inactive hoặc config đổi bị chặn trước khi gọi provider.
3. Mỗi lần validation gọi provider lại; không dùng webhook, source_revisions hoặc cache làm bằng chứng head hiện hành.
4. So khớp scope/repository/kind/ref/config của observation. Thiếu SHA hợp lệ hoặc source revision/fence thì FAIL.
5. Chỉ SHA trùng mới PASS/CURRENT. SHA cũ trả FAIL/STALE_SHA, giữ nguyên expected SHA và trả head quan sát được để giải thích; không cập nhật candidate.
6. Ref không tồn tại, dependency lỗi, binding sai hoặc validation quá hạn đều có reason cụ thể, fail closed. Lỗi provider không được đưa message/credential vào kết quả.

Kết quả có validationId, binding, checkedAt/expiresAt và sourceRevision. Đây là observation trong bộ nhớ, **chưa phải receipt đã ký hoặc lưu bền vững**. UUID không chứng minh tính xác thực. Chỉ sau khi nối qua authenticated service response hoặc signed/verified receipt mới có thể sử dụng làm evidence giữa S02 và S04.

`../../contracts/openapi/s02-repository.yaml` khai báo API-070 trong danh mục Sprint 3. Bản đề xuất bổ sung tại `../backend/repository-integration-service/LIV-122-source-validation-candidate.yaml` được lưu để S02 và S04 đối chiếu các trường dữ liệu, thời hạn hiệu lực và cách trả lỗi.

## Việc tiếp theo để hoàn tất work item

1. Nối auth S3-EN01: domain service xác thực issuer/audience/scope/expiry; scope từ authorization, không tin body/header chưa xác thực. Không route internal API ra public Gateway; dùng TLS theo security decision.
2. Thống nhất danh sách trạng thái GitHub sẵn sàng và kiểm tra nhánh theo dõi khi đã biết targetKind/targetRef; credential_ref chỉ dùng trong S02.
3. Implement fresh GitHub lookup với credential resolver trong S02: giới hạn timeout, HTTPS/certificate verification, không theo redirect làm rò Authorization. BRANCH đọc đúng branch head; PR đọc đúng PR head. 403/rate limit/timeout/provider lỗi không được coi là missing ref hoặc PASS. Recheck config/fence quanh provider call.
4. Chốt cách cập nhật sourceRevision khi S02 quan sát nguồn thay đổi và kiểm tra lại configRevision sau khi đọc GitHub.
5. Lưu evidence/reference hoặc ký receipt theo contract được review. V1 source_validation_receipts yêu cầu non-null head SHA và FK connection, nên chưa biểu diễn được unknown connection/head. Cần quyết định durable failed-attempt evidence thay vì ghi SHA giả để qua constraint.
6. Nối API-069 resolve (snapshot đúng explicit SHA) và API-070 validate; kiểm tra malformed JSON, auth/scope negatives, no-store, correlation ID, API/OpenAPI conformance.
7. S04 (LIV-123) đối chiếu binding/config/fence/expiry, kiểm ETag và APPROVED, fresh recheck sát transition; transaction local ghi PUBLISHED + audit + outbox. Receipt cũ hết hạn hoặc fence đổi phải bị chặn. Không có ACID transaction chung với GitHub; source push sau check vẫn là residual race cần retry/revalidation/invalidation.
8. Test repository GitHub thật, DB/RLS integration, provider permission failure, concurrent config/source change, S04 publish/race và evidence review bởi QA/reviewer. Chưa xác minh được GitHub test connection trong lần làm này.
9. **Bắt buộc trước khi đánh dấu LIV-122 hoàn thành — yêu cầu Owner ngày 19/11/2026:** chuẩn bị runner và điểm ghi thời gian khi nối HTTP/auth, sau đó đo từng chặng và toàn yêu cầu trong bước nghiệm thu. Bàn giao số liệu theo tải, tỷ lệ lỗi/timeout, bảng đề xuất timeout từng phần và kết quả đo lại sau hiệu chỉnh theo [HLD §10.5](../../docs/implementation/S02/S02-LIV-122-HLD-va-ke-hoach-trien-khai.md#105-đo-hiệu-năng-và-chốt-timeout-trước-khi-hoàn-thành-liv-122) và [mẫu báo cáo](../../docs/implementation/S02/LIV-122-Do-hieu-nang-va-chot-timeout.md). Trạng thái **NOT RUN**; 15 giây là cấu hình tạm, chưa phải timeout đã được nghiệm thu.

Validity là tham số bắt buộc, không có TTL mặc định do chưa có security decision. Thời gian trong unit test chỉ là fixture.

## Kiểm chứng

Unit tests kiểm tra current/stale SHA, fresh lookup mỗi lần, unknown/inactive connection, tenant/workspace/repository mismatch, ref/kind mismatch, config thay đổi, source/fence không hợp lệ, lỗi dependency không lộ secret, ref không rõ nghĩa, PR head và hết hạn ngay tại boundary.

Kết quả local ngày 2026-11-05: **30 tests, 0 failures, 0 errors** với `-Dtest=SourceValidationServiceTests test`, dùng Maven 3.10.0 đã cache, JDK 25 và compiler release 21 của project. Chạy Maven offline với `-Daether.syncContext.named.factory=noop` để không ghi lock vào cache ngoài workspace. CI vẫn dùng Java 21 theo workflow hiện tại.

Lần chạy toàn bộ `test`: 31 tests, trong đó 30 tests mới pass; `RepositoryIntegrationServiceApplicationTests.contextLoads` lỗi `Failed to determine a suitable driver class` vì lần chạy local không có datasource/profile DB. Không sửa hoặc bỏ qua test này trong code; integration/CI cần PostgreSQL và profile `db` như workflow hiện có.

Contract đã parse thành công bằng SnakeYAML, kiểm tra version OpenAPI 3.0.3 và toàn bộ local `$ref` đều resolve được. Chưa chạy full OpenAPI schema validator hoặc HTTP conformance test vì chưa có HTTP adapter.

Không dùng unit test để tuyên bố đã kiểm chứng GitHub thật, TLS, DB/RLS, receipt persistence hoặc S04 end-to-end.

### Cập nhật ngày 2026-11-06

Sau khi tách mô hình/giao diện nghiệp vụ và thêm phần tra cứu kết nối bằng JPA, chạy `verify` với PostgreSQL 18 tạm riêng và vai trò `ld_s02_app`: **46 kiểm thử đạt, không lỗi, không bỏ qua**. Bao gồm 31 kiểm thử dịch vụ xác minh, 7 kiểm thử tra cứu kết nối, 7 kiểm thử Entity/Repository và 1 kiểm thử khởi động ứng dụng.

Kiểm thử tra cứu dùng dữ liệu cơ sở dữ liệu thật, xác nhận sai tenant/workspace/ID bị ẩn, trạng thái chưa sẵn sàng bị chặn, phiên bản cấu hình thiếu/không hợp lệ bị từ chối và giao dịch bên gọi giữ nguyên ngữ cảnh tenant. Bên cung cấp phiên bản cấu hình và head GitHub trong các ca thành công vẫn là fixture kiểm thử, chưa phải tích hợp production. PostgreSQL tạm được dừng và dọn sau kiểm thử; dữ liệu dự án không được sử dụng.

### Cập nhật ngày 2026-11-13

Chạy Maven offline `verify` với PostgreSQL 18.6 tạm và vai trò ứng dụng `ld_s02_app`. Flyway áp dụng V1 trên schema mới. Cả 47 kiểm thử đạt, không lỗi và không bỏ qua. Kiểm thử bổ sung xác nhận truy vấn kế thừa `findById` vẫn chịu RLS, `save` yêu cầu giao dịch hiện hữu và lỗi lập trình ngoài dự kiến không bị chuyển thành `SOURCE_UNAVAILABLE`. Chưa kiểm thử GitHub thật hoặc luồng xuất bản S04.

### Cập nhật ngày 2026-11-14

Sau khi nối truy vấn `configRevision` với V2, Maven offline `verify` đạt 48 kiểm thử trên PostgreSQL 18.6 tạm, không lỗi và không bỏ qua. Kiểm thử xác nhận phiên bản cũ trả `CONFIG_CHANGED` trước khi đọc đầu nguồn nếu nhánh mặc định hoặc tham chiếu bí mật thay đổi. Phần đọc đầu nguồn trong kiểm thử vẫn dùng dữ liệu giả lập.

### Cập nhật ngày 2026-11-19 — ngân sách yêu cầu nguồn

Hai nghiệp vụ `SourceResolveService.resolve()` và `SourceValidationService.validate()` đã sử dụng `livingdocs.source.request-timeout`, lấy từ `LIVINGDOCS_SOURCE_REQUEST_TIMEOUT_SECONDS=15` trong môi trường S02. TTL bằng chứng tiếp tục có cấu hình riêng.

`SourceRequestExecutor` tạo deadline bằng đồng hồ đơn điệu và chạy phần I/O trên virtual thread. Bên gọi kết thúc chờ khi hết ngân sách, tác vụ được yêu cầu ngắt và kết quả về muộn bị loại. Số tác vụ chưa kết thúc được giới hạn theo `spring.datasource.hikari.maximum-pool-size`, mặc định 10. Suất xử lý được trả khi tác vụ thực sự kết thúc để tránh tích lũy I/O chưa dừng.

GitHub HTTP dùng timeout nhỏ hơn giữa 2 giây hiện có và ngân sách còn lại. PostgreSQL đặt `statement_timeout` và `lock_timeout` cục bộ theo thời gian còn lại trong giao dịch. Connection pool cùng PG JDBC có giới hạn lấy connection, kết nối và đọc socket lấy từ cùng biến môi trường. Có kiểm tra hết hạn trước/sau ghi quan sát, lỗi khi hết hạn được trả qua `SOURCE_UNAVAILABLE`.

Maven offline `verify` với PostgreSQL 18 tạm riêng đạt 112 kiểm thử, không lỗi, và bỏ qua 1 kiểm thử GitHub live do chưa bật opt-in. Bao gồm HTTP thực trên localhost bị chậm header/body, tác vụ không dừng ngay khi interrupt, ngân sách độc lập giữa các lượt, resolve/validate hết hạn trước ghi và truy vấn chờ khóa bị hủy mà giữ nguyên quan sát cũ. Cluster kiểm thử đã được dừng và dọn sau phép đo.

Lần đóng gói đầu gặp file JAR trong `target` bị khóa khi đổi tên. Chạy lại cùng mã nguồn bằng POM kiểm thử với thư mục build riêng trong `target/deadline-verified-build` đã đóng gói thành công. Các đường dẫn source, resource và test vẫn trỏ vào source của service.

Kiểm thử này xác minh luồng nghiệp vụ và I/O local. Tầng HTTP nội bộ chưa nối ở bước 5, nên chưa có bằng chứng deadline cho toàn entry point xác thực hoặc S04 xuyên service. Việc ngắt I/O là best effort, một commit đã được DB chấp nhận trước khi mất phản hồi có thể đã lưu quan sát, các lần thử tiếp theo vẫn áp CAS. Chưa dùng phép đo này để khẳng định độ trễ GitHub thật hoặc thời hạn cứng khi JVM/hệ điều hành ngừng chạy.

### Cập nhật ngày 2026-11-20 — bước 5: HTTP nội bộ, từ chối mặc định

Theo yêu cầu triển khai bước 5 và lựa chọn Owner ngày 19/11/2026 khi chưa có hợp đồng claim dịch vụ, đã nối `POST /internal/v1/source/resolve` và `/validate` với controller mỏng, DTO Bean Validation, MapStruct và hai giao diện nghiệp vụ hiện có. Thành công trả `SuccessResponse<T>`; lỗi dùng RFC 9457, mã lý do và correlation ID, không chứa lỗi thô/credential. Có giới hạn 16 KiB cho cả chunked, JSON nghiêm ngặt, `no-store`, kiểm quyền thao tác và scope của repository.

Spring Security dùng Bearer, stateless và hai điểm tích hợp `JwtDecoder`/`SourceServiceIdentityMapper`. **Đường chạy thực tế từ chối khi thiếu trust hoặc hợp đồng ánh xạ danh tính.** Không tự thêm claim production vào S01/S03/S04; các claim `test_*` chỉ nằm trong test với khóa RSA sinh riêng. HTTPS được yêu cầu mặc định; HTTP component test khai báo riêng `livingdocs.source.http.allow-insecure-transport=true`. Kiểm nhánh secure bằng MockMvc chưa phải chứng cứ TLS thật.

OpenAPI chính thức đã thay schema ứng viên chung của API-069, hoàn thiện request và receipt API-070, wrapper, status và header. Runtime Springdoc được đối chiếu với YAML qua HTTP test. Hai API vẫn giữ `INTERNAL_CANDIDATE` chờ review và tích hợp production.

Kiểm chứng ngày 20/11: Maven offline `verify` với PostgreSQL tạm riêng đạt **140 kiểm thử: 139 đạt, 0 lỗi, 1 GitHub live bị bỏ qua**; gồm 24 ca `SourceHttpTests` và 3 ca `SourceHttpDefaultsTests`. Build thành công tại `target/http-verified-build` bằng POM tạm trỏ cùng source. Cluster test đã dừng và dọn. Ca Gateway chặn internal hiện có đạt **1/1** bằng JWT/downstream giả lập; validator tĩnh xác nhận không công khai API nội bộ. OpenAPI S02 qua schema validator.

Bằng chứng nằm tại `target/step5-full-verify.log`, `target/liv122-http-runtime-openapi.json` và `../../infrastructure/api-gateway/target/liv122-gateway-internal-verify.log`. Xem [HLD §9.2](../../docs/implementation/S02/S02-LIV-122-HLD-va-ke-hoach-trien-khai.md) để biết phạm vi và các nhóm file.

Còn phải chốt contract JWT dịch vụ, tích hợp decoder/mapper và kiểm HTTPS thật, hoàn tất các ca GitHub thật, consumer S04 và đo hiệu năng trước Done. Giới hạn thời gian hiện tại vẫn ở tầng nghiệp vụ; chưa có bằng chứng deadline bao trùm auth/đọc body/ghi phản hồi. Kế hoạch đo ở HLD §10.5 vẫn **NOT RUN**; chưa chạy CI hoặc review độc lập, chưa đánh dấu task hoàn thành.
