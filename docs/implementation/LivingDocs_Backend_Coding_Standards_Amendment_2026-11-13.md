# LivingDocs — Backend Coding Standard Amendment (Owner Decision)

**Ngày:** 2026-11-13\
**Phạm vi:** Java Spring Boot domain services **S01–S04**, áp dụng cho code mới và refactor trong các task đang triển khai.\
**Trạng thái:** Quy chuẩn theo quyết định trực tiếp của Project Owner; Phase H đồng bộ vào Master Decisions, Standards v3, Coding Agent Guide và AGENTS.md trong worktree. AGENTS.md hiện bị Git ignore; Owner quyết định việc phân phối/tracking riêng. **Không tự thay đổi Git/Jira** khi chưa được giao.

## 1. Thay đổi so với tài liệu cũ

**Snapshot lịch sử đã được thay thế:** Master Decisions §4 và Standards v2 từng khuyên không sinh ServiceInterface + ServiceImpl chỉ để đủ layer; Standards v2 cũng từng khuyến khích tránh `ServiceImpl` khi chỉ có một implementation. **Project Owner đã thay đổi quy ước cho business services**: **Service interface và Service implementation là mẫu bắt buộc**, kể cả khi trước mắt chỉ có một implementation. Master Decisions, Standards v3 và AGENTS.md worktree nay phản ánh quy tắc mới; không tự ý refactor toàn bộ dự án trong một PR, áp dụng theo use case với diff/test riêng.

## 2. Cấu trúc chuẩn

```text
com.livingdocs.<service>/
├── controller/               # @RestController, @Valid, HTTP mapping
├── dto/
│   ├── request/              # Request classes + Jakarta Bean Validation
│   └── response/             # Response classes đúng OpenAPI
├── service/
│   ├── RegistrationService.java          # interface: business contract
│   └── impl/
│       └── RegistrationServiceImpl.java  # @Service, implements interface
├── entity/                   # @Entity, mapping đúng schema
├── repository/               # JpaRepository, scoped queries
├── exception/                # Typed domain exceptions + error code + advice
├── config/                   # @Configuration, PasswordEncoder, typed config
├── security/                 # security filters, JWT, authorization policy
├── validation/               # Custom Constraint / ConstraintValidator khi cần
└── model/                    # Domain enums/policies khi thực sự cần
```

Tạo các package theo nhu cầu của use case; **không sinh file skeleton rỗng**. Riêng Gateway WebFlux và S05 FastAPI có convention tương ứng, không ép theo Java JPA layout.

## 3. Annotation-first validation (đúng tầng)

- **Request DTO** là `class`, `private` fields, Lombok có chọn lọc. Dùng Jakarta Bean Validation đúng loại dữ liệu và policy đã duyệt: `@NotBlank`, `@NotNull`, `@Email`, `@Size`, `@Pattern`, `@Min`, `@Max`, `@Past`, `@Future`, `@Valid` với nested DTO. Tránh annotation dư thừa hoặc regex phức tạp khi chưa có policy.
- **Controller**: dùng `@Valid @RequestBody` (hoặc `@Validated` cho validation groups/method parameters khi cần); không tự viết hàng loạt `if (email == null)` thay cho annotation.
- **Service method validation**: chỉ dùng `@Validated`/parameter constraints khi method thực sự có constraints; kiểm thử proxy/interface khi đặt constraints trên Service interface.
- **Custom validation**: khi policy không thể diễn đạt chính xác bằng standard constraints, dùng constraint riêng + `ConstraintValidator` theo policy được Owner chốt; ví dụ giới hạn byte UTF-8 phù hợp thuật toán hash. Worktree Register hiện có `@Utf8ByteLength(max=72)` và `@Size(min=12,max=64)`; đây là implementation cần review policy/Unicode/BCrypt, không tự coi quyết định security production đã chốt.
- **Business rules** (duplicate identity, membership revoked, collision, permission, active state) nằm ở **Service Impl/domain policy**, không nhét vào Request validator.
- **Email normalization** là chuyển đổi dữ liệu, không phải Bean Validation. Phải thống nhất với DB index `LOWER(BTRIM(email))`, kiểm thử Unicode/collation và race. Không dùng `@Column(unique=true)` để thay unique expression index.
- **Entity**: dùng JPA annotations; `@Column(nullable=false,length=...)` phản ánh schema nhưng không thay Bean Validation Request hay unique index. Không lạm dụng `@Data` trên Entity.

**Mapping:** dùng MapStruct cho Entity/domain ↔ DTO nhiều field hoặc mapping được tái sử dụng, với `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)`. Không tạo mapper rỗng, không đặt DB access/authorization/hash trong mapper, không expose Entity/credential/passwordHash qua Controller. UUID → `RegisterResponse` một field có thể dùng constructor trực tiếp.

## 4. Service Interface + Impl

- Interface định nghĩa **hợp đồng use case** (method, input/output, tên nghiệp vụ), không chứa SQL/HTTP framework logic.
- Impl duy nhất đặt tại `service/impl`, có `@Service`, `@RequiredArgsConstructor` cho `private final` dependencies, `@Transactional` trên public use case mutation; query-only use case dùng `@Transactional(readOnly=true)` khi phù hợp.
- Controller inject interface (`private final RegistrationService registrationService`), không phụ thuộc class Impl. Không field injection. Không dùng `new` tạo Spring managed service.
- Không đưa `ResponseEntity`, servlet request/response hoặc HTTP status vào Service API. Service trả kết quả nghiệp vụ; Controller chuyển thành DTO response/HTTP semantics.
- Không giữ transaction mở qua external HTTP/provider call tùy tiện; hiểu Spring proxy/self-invocation, flush, rollback và RLS transaction-local.

## 5. Custom exception và HTTP error boundary

- **Mỗi điều kiện lỗi nghiệp vụ có ý nghĩa** phải được biểu diễn bởi exception rõ tên, ví dụ `IdentityAlreadyExistsException`, `IdentityNotFoundException`, `InactiveIdentityException`, `InsufficientScopeException`. Có thể kế thừa base `BusinessException` thống nhất `ErrorCode`, nhưng **không** `throw new RuntimeException(...)`, `IllegalArgumentException(...)` hoặc `BusinessException("string")` để thay business type.
- `@RestControllerAdvice` ánh xạ các typed exceptions và các lỗi validation framework (`MethodArgumentNotValidException`, `ConstraintViolationException`, JSON malformed...) sang Problem Details RFC 9457, `application/problem+json`; có stable machine-readable `code`, `correlationId`. Không làm lộ password, email, SQL, token, stack trace trong response.
- **401/403 từ Spring Security filter chain** do `AuthenticationEntryPoint` và `AccessDeniedHandler` xử lý theo contract, không dựa duy nhất vào ControllerAdvice.
- DB unique conflict: tối ưu precheck UX nếu cần nhưng **PostgreSQL unique index mới chống race**. Khi `INSERT/flush` lỗi, chỉ map **SQLSTATE 23505 + tên unique index `uq_identities_email`** thành `IdentityAlreadyExistsException`; các lỗi DB khác phải được giữ là lỗi persistence/infra, không được báo nhầm email trùng. Thiết kế boundary bắt lỗi phù hợp transaction state và bảo đảm rollback; không tiếp tục query trong transaction đã bị PostgreSQL đánh dấu aborted.
- HTTP mapping Register `201/400/409` và response DTO phải khớp contract Owner đã duyệt. Cân nhắc tradeoff account enumeration khi dùng 409.

**Success contract LD-D12 [ĐÃ CHỐT]:** JSON success thông thường S01–S04 dùng `SuccessResponse<T>` với đúng `code`, `message`, `data`. Service-owned `model.SuccessCode` chứa `wireCode` và `defaultMessage`; tạo response bằng `SuccessResponse.of(SuccessCode, data)`, dùng `getWireCode()` thay `enum.name()`. HTTP status thuộc Controller/`ResponseEntity`, không nằm trong enum; không thêm boolean `success/status`. Lỗi giữ RFC 9457 Problem. HTTP 204 không có body; redirect, file/stream, webhook và protocol-specific responses theo contract riêng. Mỗi JSON success có body cần concrete schema; đối chiếu static OpenAPI YAML, runtime Springdoc, Controller DTO và HTTP tests. Swagger hiển thị không tự chứng minh API IMPLEMENTED; giữ Docker Swagger hiện tại và không bulk-refactor S02–S05 trong LIV-136.

## 6. Register/LIV-136 — checklist

1. DTO `RegisterRequest` dùng Bean Validation; `@Email`, `@NotBlank`, `@Size` và `@Utf8ByteLength(max=72)` đang có trong worktree. Review password bytes/normalization theo quyết định Owner trước khi thay policy; không suy ra các follow-up đã Done.
2. `RegistrationService` là **interface**; `RegistrationServiceImpl` xử lý chuẩn hóa email, policy, encode mật khẩu và lưu Identity bằng Repository trong transaction.
3. PasswordEncoder là Spring bean, không tự hash thủ công; không trim/lowercase plaintext password. `password_hash` nullable hỗ trợ OAuth-only ở phase sau; **Register** là local-only.
4. Thành công tạo Identity UUID, không tự tạo tenant/workspace/role/JWT. API-001 trả HTTP 201 với `code: "IDENTITY_REGISTERED"`, `message: "Registration completed"`, `data.identityId` UUID; lỗi giữ HTTP 400/409 và RFC 9457. Chỉ expose response DTO đã duyệt.
5. Yêu cầu evidence cho duplicate/race, failed insert, null/invalid request, Unicode/password bytes và security leaks bằng unit + PostgreSQL integration tests phù hợp; không mặc định xem tất cả case đã PASS. `BUILD SUCCESS` riêng lẻ không chứng minh DB integration.
6. Không sửa V1/V2 Flyway cũ; không thêm OAuth tables/code trước LIV-139; không mở API-005/006 chưa nghiệm thu security.

## 7. Quy trình cho Codex (bắt buộc)

1. **Read-only audit**: đọc AGENTS.md, Master Decisions, coding standard mới, Jira/AC, current code/diff; **nêu xung đột** tài liệu trước khi code.
2. **HLD**: business scope, layer responsibility, exception matrix, DTO validation matrix, transaction boundary, DB invariants, negative tests, files affected.
3. **Approval gate LD-D18**: read-only audit → exact file allowlist + expected diff từng file → Owner approval → implementation → verification → stop. Không tự ghi file, tự mở rộng scope, chuyển branch/worktree, commit, push, merge.
4. Sau Owner duyệt: triển khai **một vertical slice có thể kiểm thử**, đủ file cho interface+impl+typed exceptions+tests; không có giới hạn cứng 1–3 file, nhưng chỉ sửa đúng allowlist được duyệt. Nếu cần thêm file ngoài phạm vi, hỏi lại.
5. Kiểm thử Maven + PostgreSQL/Flyway + endpoint/contract khi đã có, review diff, ghi PASS/FAIL chính xác; không tuyên bố DONE chỉ vì compile thành công.

**Thứ tự ưu tiên sau khi đồng bộ:** Owner decision mới này → các approved product/security contracts/ADRs (nếu xung đột cần Owner reconcile) → Master Decisions/Engineering Standards đã chỉnh → prompt của Codex → code samples. Không tự thay đổi API contract hoặc password/account-enumeration policy khi chưa được chốt.


# LIV-136 — Coding Standards Correction & Architecture Alignment

> **Snapshot lịch sử (superseded workflow):** Đoạn prompt được thêm trước Phase H để yêu cầu audit-only và chờ correction plan. Không áp dụng câu “Bước hiện tại chỉ audit” dưới đây làm trạng thái hiện hành của mọi task. Quy tắc đang áp dụng là LD-D18, Owner approval theo phase/file và các chuẩn ở §1–7 phía trên; giữ nguyên prompt này để truy vết lịch sử.

Bạn đang triển khai S01 Real Registration cho LivingDocs.

Project Owner đã cập nhật coding standards bắt buộc cho toàn bộ Java Backend S01–S04.

**Ưu tiên cao nhất hiện tại là kiểm tra và điều chỉnh code đang viết cho đúng tiêu chuẩn, trước khi tiếp tục triển khai nghiệp vụ mới.**

## 1. Coding standards bắt buộc

### Layered Architecture

- Controller
- DTO Request/Response
- Service Interface
- Service Implementation trong `service/impl`
- Entity
- Repository
- Exception
- Config/Security/Validation khi cần

**Mọi business service phải có Interface và Impl.** Không viết toàn bộ nghiệp vụ trong một concrete Service class như quy ước cũ.

Interface mô tả use-case contract; Impl thực hiện business logic.

Controller inject Interface. Impl dùng `@Service`, constructor injection, Lombok `@RequiredArgsConstructor` và `@Transactional` đúng vị trí.

### Annotation-first Validation

Sử dụng Jakarta Bean Validation khi có annotation phù hợp:

- `@NotBlank`
- `@NotNull`
- `@Email`
- `@Size`
- `@Pattern`
- `@Valid`
- `@Validated` khi cần

Không tự viết validation thủ công thay cho các annotation đã hỗ trợ.

Với policy đặc thù như password UTF-8 byte limit, đề xuất custom constraint annotation + `ConstraintValidator` sau khi Owner duyệt policy.

Phân biệt transport validation, business validation và database constraints.

### Custom Business Exceptions

Bắt buộc dùng exception cụ thể và có ý nghĩa nghiệp vụ, ví dụ:

- `IdentityAlreadyExistsException`
- `InactiveIdentityException`
- Các exception khác chỉ khi use case thật cần.

Có thể xây dựng base `BusinessException` và `ErrorCode` nhất quán, nhưng không thay exception cụ thể bằng generic RuntimeException hoặc message string.

GlobalExceptionHandler dùng `@RestControllerAdvice`, xử lý business exceptions, Bean Validation exceptions và lỗi request theo RFC 9457.

Không trả SQL, stack trace, password, token hoặc thông tin nhạy cảm trong Problem Details.

Chỉ chuyển unique violation đúng `uq_identities_email` thành IdentityAlreadyExistsException. Không bắt mọi DataIntegrityViolationException rồi báo email trùng.

### Persistence và Security

- Spring Data JPA + Hibernate.
- PostgreSQL + Flyway là schema authority.
- Entity mapping chính xác DB schema.
- `ddl-auto=validate`, `open-in-view=false`.
- PasswordEncoder là bean.
- Không lưu password plaintext.
- Không tạo tenant/role/JWT khi Register.
- Không sửa Flyway V1/V2 đã áp dụng.

## 2. Audit code hiện tại

Hãy đọc toàn bộ thay đổi chưa commit trong worktree hiện tại, đặc biệt:

- `pom.xml`
- `application-db.yaml`
- `Identity.java`
- `IdentityRepository.java`
- Các file Service, Exception, Controller, DTO nếu đã được tạo.

Đọc thêm Master Decision Log và coding standards hiện có. Lưu ý: tài liệu cũ khuyến cáo tránh ServiceInterface + ServiceImpl; quyết định mới của Project Owner **thay thế quy tắc đó**.

Lập báo cáo gồm:

1. File nào đang đúng chuẩn.
2. File nào vi phạm quy chuẩn mới.
3. Annotation nào nên sử dụng thay validation thủ công.
4. Exception nào đang quá chung chung hoặc xử lý sai boundary.
5. Service nào cần tách Interface/Impl.
6. Những thay đổi cần thiết để giữ nguyên logic và DB schema hiện có.
7. Danh sách file cần tạo/sửa và test cần chạy.

Không giới hạn cứng tối đa 3 file nếu việc đó làm sai kiến trúc. Nhưng không tạo skeleton, generic utility hoặc abstraction ngoài use case.

## 3. Quy tắc thực thi

**Bước hiện tại chỉ audit và lập correction plan. Chưa sửa file.**

Không tự xóa, di chuyển hoặc ghi đè code đã có.

Không tự chuyển branch/worktree.

Không commit, push hoặc merge.

Dừng sau khi đưa ra correction plan và chờ Project Owner phê duyệt.

Sau khi được duyệt, mới thực hiện refactor có phạm vi và chứng minh bằng Maven tests, PostgreSQL integration tests và diff review.

Mục tiêu: code mới của LIV-136 trở thành implementation tham chiếu phù hợp để áp dụng thống nhất cho S01–S04, không phải một implementation ngoại lệ.
