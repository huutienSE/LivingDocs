# LIV-162 PR3 — Repository Grant lifecycle, S02 binding và hướng dẫn kiểm thử

> **Chọn đúng đường chạy.** Database đã có Flyway V7: dùng [đường chạy lại](#đường-chạy-lại-trên-database-đã-có-v7). Database còn ở V6: dùng [đường chạy lần đầu](#đường-chạy-lần-đầu-từ-pr2v6) sau khi backup. Máy mới cần hoàn thành [PR1 runbook](LIV-138-s01-authority-and-testing.md) và [PR2 runbook](LIV-138-pr2-service-jwt-fresh-authorization.md) trước. Không reset Docker volume hoặc chạy tay migration V7.
>
> **Giới hạn E2E:** S01 49/49 và Gateway 28/28 PASS; route smoke 5/5 chỉ chứng minh Gateway yêu cầu JWT. S01 integration dùng controlled S02 HTTP receiver. Production S01↔S02 và Golden Flow 1 với S04 **chưa chạy**.

## PR3 làm gì và kiểm đến đâu

PR3 thêm HTTP request/list/get/verify/revoke Repository Grant, writer boundary PostgreSQL có RLS và audit/revision, cùng lookup binding live từ S02. Request tạo ACTIVE/PENDING. Chỉ Grant ACTIVE/VERIFIED có binding S02 đúng Tenant, Workspace, Connection và revision mới có thể hiệu lực. API-038 giữ HTTP 200 và hạ effective permission khi S02 unavailable; API-039 trả 503 khi cần lookup mà S02 unavailable. REPOSITORY_CONNECT vẫn dựa trên Workspace permission, không đòi Grant của Connection chưa tồn tại.

S01 sở hữu Grant và authority; S02 sở hữu GitHub Connection, binding và current SHA; S04 sở hữu Document state, review, approval, publish và audit. API-070 SHA không thay thế binding evidence. PR3 không tạo Connection hoặc triển khai nghiệp vụ S02/S04. LIV-163/PR4 tiếp nhận Tenant/Workspace Membership, Role và administration HTTP bằng writer boundary sau migration quyền riêng.

~~~mermaid
sequenceDiagram
  participant Lead as Technical Lead
  participant Owner as Tenant Owner
  participant S01 as S01 HTTP
  participant DB as S01 PostgreSQL/RLS
  participant S02 as S02 binding receiver
  Lead->>S01: POST Grant + User JWT + Idempotency-Key
  S01->>DB: Writer transaction; scope, audit, revision
  DB-->>S01: ACTIVE/PENDING
  S01-->>Lead: 201; effective=false
  Owner->>S01: POST verify + User JWT
  S01->>S01: Owner, self-verify, scope and state checks
  S01->>S02: POST binding verify + scoped Service JWT
  Note over S02: Production receiver thuộc LIV-155; test dùng controlled receiver
  S02-->>S01: ACTIVE + bindingRevision + observedAt
  S01->>DB: New writer transaction; recheck row version and authority
  S01-->>Owner: 200 ACTIVE/VERIFIED
~~~

## HTTP contract và policy

Prefix chung: /api/v1/tenants/{tenantId}/workspaces/{workspaceId}/repository-grants. Cả năm operation dùng User JWT, trả SuccessResponse gồm code/message/data; lỗi dùng RFC 9457 Problem JSON. Contract tĩnh nằm ở [S01 OpenAPI](../../../contracts/openapi/s01-identity.yaml); trạng thái IN_PROGRESS trong route inventory không chứng minh production S02 E2E.

| Method/path sau prefix | Operation ID | Actor và request | Success; lỗi chính |
| --- | --- | --- | --- |
| POST / | liv162_grant_request | Technical Lead tự request hoặc Tenant Owner request cho Workspace Member ACTIVE; Idempotency-Key UUID; body gồm workspaceMembershipId, repositoryConnectionId, provenance 10–2000 ký tự | 201 ACTIVE/PENDING, effective=false; 400/401/403/409 |
| GET / | liv162_grant_list | Owner hoặc recipient, đúng Tenant/Workspace | 200 danh sách scoped; 401/403 |
| GET /{grantId} | liv162_grant_get | Owner hoặc recipient, đúng scope | 200 chi tiết; 401/403 |
| POST /{grantId}/verify | liv162_grant_verify | Tenant Owner khác người request; không có body; cần binding live | 200 ACTIVE/VERIFIED; 401/403/409/503 |
| POST /{grantId}/revoke | liv162_grant_revoke | Tenant Owner hoặc active recipient; không có body | 200 REVOKED; 401/403 |

Cùng Idempotency-Key và payload trả Grant cũ với 201, không tạo row mới. Đổi payload cùng key hoặc tạo ACTIVE Grant trùng nhận 409. Verify đọc evidence ngoài write transaction, rồi mở transaction mới kiểm lại actor, row version, scope và state trước khi ghi. Revoke lặp là idempotent. PENDING, REVOKED, inactive ancestor hoặc stale binding không cấp Document permission.

| Ví dụ nghiệp vụ cho Swagger | status | verificationStatus | effective | effectiveReason |
| --- | --- | --- | --- | --- |
| Vừa request | ACTIVE | PENDING | false | GRANT_NOT_VERIFIED |
| Binding S02 live còn đúng revision | ACTIVE | VERIFIED | true | null |
| Đã revoke | REVOKED | VERIFIED | false | GRANT_REVOKED |

Đây là **ví dụ đề xuất cho tài liệu**, chưa phải example được khai báo trong Springdoc. VERIFIED cần verifiedBindingRevision dương và verifiedAt; một response về sau có thể chuyển effective=false nếu S02 disconnect hoặc unavailable.

### Contract bàn giao Tiến / S02 LIV-155

Đây là **working contract cần S02 xác nhận**, chưa có bằng chứng receiver production. S01 gọi POST tới path mặc định /internal/v1/repository-bindings/verify trên S02. Request JSON gồm tenantId, workspaceId, repositoryConnectionId. Authorization Bearer chứa Service JWT RS256 do S01 ký, sống 60 giây: issuer urn:livingdocs:s01, subject workload:s01, audience livingdocs-s02-internal, token_use service, operation repository-binding:verify, http_method POST, http_path đúng path và ba scope claims đúng body. User JWT không dùng cho lookup này.

| S02 receiver cần làm | S01 xử lý |
| --- | --- |
| Xác minh JWKS/signature, issuer, audience, thời gian, workload subject, operation, exact method/path và cả ba scope claims trước khi đọc Connection | Token sai không được dùng làm evidence |
| Đọc Connection authoritative; 200 trả data gồm tenantId, workspaceId, repositoryConnectionId, status, bindingRevision, observedAt | Chỉ nhận đúng scope, ACTIVE, revision dương và observedAt trong cửa sổ 5 giây |
| Tăng revision khi disconnect, rebind hoặc authority binding đổi; không trả cache ACTIVE cũ | Revision khác lúc verify → Grant không hiệu lực; cần revoke rồi request Grant mới |
| 404/409 khi binding không hợp lệ; lỗi khác/timeout khi dependency unavailable | Invalid → DENY/không verify; unavailable → verify/API-039 503, API-038 200 với BINDING_UNVERIFIED |

S01 không dùng TTL cache hay production mock. Timeout mặc định 1500 ms, không tự retry; production yêu cầu HTTPS, Compose cho phép HTTP trên mạng nội bộ. Callback/invalidation đáng tin cậy và retry thuộc dependency tích hợp; S01 không dựa callback để ALLOW vì API-039 lookup live. Vẫn có race giữa lookup cuối và S02 disconnect đồng thời; S02 phải làm trạng thái authoritative quan sát được ngay. Đổi wire schema cần hai bên review.

## Database deployment và quyền ghi

DBA chạy [writer role bootstrap](../../../infrastructure/docker/http-writer-role.sql) **trước** Flyway V7 trên database mới hoặc database V6 đang nâng. Role ld_s01_http_writer là NOLOGIN/NOBYPASSRLS; ld_s01_app được SET ROLE nhưng không inherit. [V7](../../../backend/identity-organization-service/src/main/resources/db/migration/V7__repository_grant_lifecycle.sql) chỉ cấp DML cho Grant và HTTP idempotency table, không cấp Tenant/Workspace administration DML. HTTP dùng transaction riêng, SET LOCAL ROLE và tenant/actor context trên connection thực; trigger ghi audit và tăng authz_revision. Rollback hoàn tác dữ liệu, audit và revision; connection pool trở lại ld_s01_app.

V7 giữ operator_identity_id và Grant cũ, không sửa V1–V6 hoặc giả mạo named CLI operator. VERIFIED Grant cũ thiếu verified_binding_revision vẫn tồn tại nhưng không hiệu lực; muốn dùng lại phải revoke rồi request/verify qua HTTP. ProvisioningServiceImpl.apply() và CLI vẫn là đường named operator riêng. Upgrade test và CLI regression kiểm tính tương thích.

# Đường chạy lần đầu từ PR2/V6

Các lệnh dưới đây dành cho **macOS, Docker Desktop, Java 21**. Đây là hướng dẫn, không phải bằng chứng các lệnh đã chạy trên máy của người đọc. Không chạy bước nâng nếu identity.flyway_schema_history đã có V7 success=t.

## Bước 0 — Kiểm tra công cụ, database và fixture

~~~bash
cd /Users/huutien/Documents/02_Projects/LivingDocs
git status --short
docker compose version
java -version
cd infrastructure/docker
export S01_OPERATOR_DB_LOGIN=config_only S01_OPERATOR_DB_PASSWORD=config_only
docker compose --profile services --profile gateway config --quiet
docker compose --profile platform up -d --wait postgres redis
docker compose ps
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM identity.flyway_schema_history ORDER BY installed_rank"'
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT rolname, rolcanlogin, rolbypassrls FROM pg_roles WHERE rolname = '\''ld_s01_http_writer'\''"'
cd ../..
~~~

**Điểm dừng:** PostgreSQL chạy trên volume hiện có, V1–V6 success=t, V7 chưa có. Nếu thiếu baseline roles/schema, theo PR1 runbook, không tạo role ngẫu hứng. Fixture positive cần hai Identity đã Register với Tenant Membership ACTIVE: Technical Lead có Workspace Membership/Role ACTIVE, Tenant Owner khác người request, và Repository Connection ACTIVE **thật trong S02** cùng Tenant/Workspace. PR3 không tự provision fixture này; UUID Swagger chỉ là ví dụ.

## Bước 1 — Java/Testcontainers trước migration local

~~~bash
cd /Users/huutien/Documents/02_Projects/LivingDocs/backend/identity-organization-service
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -ntp verify
cd ../../infrastructure/api-gateway
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -ntp verify
cd ../..
~~~

**Điểm dừng:** S01 49 tests và Gateway 28 tests, không failure/error/skip. S01 Testcontainers dùng PostgreSQL riêng, gồm writer RLS/audit/rollback/pool reset, V6→V7 upgrade, CLI regression, verify/revoke race và controlled S02 receiver; không mutate Compose DB.

## Bước 2 — Backup, bootstrap role và để Flyway áp V7

Chỉ làm trên database **V6 chưa có V7**, với DBA quyền tạo role. Backup ngoài Git và mode riêng tư. Không chạy trực tiếp V7 bằng psql.

~~~bash
cd /Users/huutien/Documents/02_Projects/LivingDocs/infrastructure/docker
export S01_OPERATOR_DB_LOGIN=config_only S01_OPERATOR_DB_PASSWORD=config_only
umask 077
BACKUP="$HOME/Desktop/livingdocs-before-v7-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$BACKUP"
test -s "$BACKUP"
docker compose exec -T postgres pg_restore --file=/dev/null < "$BACKUP"
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' < http-writer-role.sql
docker compose --profile services --profile gateway up -d --build identity-organization-service api-gateway
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM identity.flyway_schema_history WHERE version = '\''7'\''"'
cd ../..
~~~

**Điểm dừng:** backup đọc được, writer role tồn tại, V7 success=t. pg_restore tới /dev/null chỉ kiểm archive, không phải restore rehearsal. Giữ nguyên volume. config_only chỉ để Compose parse operator profile đang tắt, không phải DB credential.

## Bước 3 — Runtime, Gateway và Newman route smoke

~~~bash
cd /Users/huutien/Documents/02_Projects/LivingDocs
curl -fsS http://127.0.0.1:8081/actuator/health
curl -fsS -o /dev/null -w 'JWKS %{http_code}\n' http://127.0.0.1:8081/.well-known/jwks.json
curl -fsS -o /dev/null -w 'OpenAPI %{http_code}\n' http://127.0.0.1:8081/v3/api-docs
npx --yes newman run tests/postman/liv162-grant-route-smoke.postman_collection.json --env-var gatewayUrl=http://127.0.0.1:8080 --reporters cli
~~~

**Điểm dừng:** S01 health UP, JWKS/OpenAPI 200, route smoke **5 requests/5 assertions/0 failed** với năm Grant routes thiếu JWT đều 401. Route smoke không chứng minh lifecycle hoặc S02 binding. Gateway management chỉ expose health/info; route inventory được kiểm ở Bước 5.

## Bước 4 — Collection lifecycle trên test environment có S02 thật

[Collection lifecycle](../../../tests/postman/liv162-grant-lifecycle.postman_collection.json) có 6 request theo thứ tự: Lead request 201 ACTIVE/PENDING; Lead self-verify 403; Owner verify 200 VERIFIED; API-038 thấy repository effective; Lead revoke 200 REVOKED; API-038 không còn repository effective. Collection **không** tự kiểm duplicate/idempotency, wrong scope/revision, S02 timeout, API-039 hoặc RLS; các case đó có trong S01 integration tests với controlled receiver. Sáu request này không phải toàn bộ Golden Flow.

Private Postman environment ngoài Git cần baseUrl, tenantId, workspaceId, workspaceMembershipId, repositoryConnectionId, requestKey, leadJwt và ownerJwt. baseUrl là S01 localhost:8081 hoặc test environment tương ứng. Hai User JWT còn hạn thuộc hai Identity khác nhau, lấy qua login thật; không dùng Service JWT, UUID Swagger, PR2 PENDING repository reference hoặc token hết hạn. requestKey là UUID mới mỗi lượt tạo Grant. S02 cần Connection ACTIVE đúng scope và production receiver LIV-155, hoặc controlled receiver triển khai **chỉ trên test environment** với trust tương đương. Repo hiện **không có** setup-liv162 environment script; script PR2 không tạo đủ biến/Connection này. Không commit/chia sẻ environment hay in JWT.

~~~bash
cd /Users/huutien/Documents/02_Projects/LivingDocs
POSTMAN_ENV='/absolute/private/path/liv162.postman_environment.json'
test -r "$POSTMAN_ENV"
npx --yes newman run tests/postman/liv162-grant-lifecycle.postman_collection.json -e "$POSTMAN_ENV" --reporters cli
~~~

**Điểm dừng:** chỉ báo PASS khi chính lượt chạy đó có **6 requests/6 assertions/0 failed**. Hiện collection này **NOT RUN** vì thiếu fixture và S02 production receiver đã xác nhận. Controlled receiver JUnit không thay production E2E.

## Bước 5 — OpenAPI, Springdoc và Gateway inventory

~~~bash
cd /Users/huutien/Documents/02_Projects/LivingDocs
python3 -m venv /tmp/liv162-pr3-openapi
/tmp/liv162-pr3-openapi/bin/python -m pip install openapi-spec-validator==0.7.2 PyYAML==6.0.3
/tmp/liv162-pr3-openapi/bin/python -m openapi_spec_validator contracts/openapi/s01-identity.yaml
/tmp/liv162-pr3-openapi/bin/python scripts/validate_gateway_openapi.py
curl -fsS http://127.0.0.1:8081/v3/api-docs | jq '.paths | with_entries(select(.key | contains("repository-grants"))) | map_values(with_entries(select(.key == "get" or .key == "post") | .value = {operationId: .value.operationId, responses: (.value.responses | keys)}))'
git diff --check
~~~

**Điểm dừng:** static validator PASS: 119 catalogued APIs, 96 exact Gateway method+path entries, 21 route groups, no internal exposure. Runtime có đủ năm operation ID đúng method/path. **Contract parity hiện PARTIAL:** POST Request Grant thực trả 201 nhưng Springdoc mô tả 200; runtime chưa khai báo response lỗi 4xx/503 như YAML. Test Springdoc hiện chỉ assert operation IDs và required DTO fields, nên 49/49 PASS không phủ sai lệch này. Swagger Example Value là placeholder tự sinh, không phải fixture. Cần đồng bộ response annotation và test parity trước khi báo final contract PASS; không cần đổi runtime behavior.

# Đường chạy lại trên database đã có V7

Đây là đường local hiện tại. Không chạy lại bootstrap/migration/backup, không reset volume.

~~~bash
cd /Users/huutien/Documents/02_Projects/LivingDocs/infrastructure/docker
export S01_OPERATOR_DB_LOGIN=config_only S01_OPERATOR_DB_PASSWORD=config_only
docker compose --profile services --profile gateway ps
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM identity.flyway_schema_history WHERE version = '\''7'\''"'
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT rolname, rolcanlogin, rolbypassrls FROM pg_roles WHERE rolname = '\''ld_s01_http_writer'\''"'
curl -fsS http://127.0.0.1:8081/actuator/health
cd ../..
~~~

**Điểm dừng:** V7 success=t, role NOLOGIN/NOBYPASSRLS, health UP. Nếu services dừng nhưng DB còn V7, từ infrastructure/docker chạy docker compose --profile services --profile gateway up -d identity-organization-service api-gateway rồi kiểm lại. Sau source change chạy lại Bước 1; Bước 4 chỉ PASS khi fixture/S02 thật sẵn sàng.

## Khi lỗi, kiểm đúng lớp

| Triệu chứng | Kiểm tra / xử lý |
| --- | --- |
| Compose thiếu S01_OPERATOR_DB_* | Export config_only như trên để parse profile; không dùng làm DB password |
| Flyway V7 thiếu writer role | Kiểm pg_roles; DBA chạy idempotent http-writer-role.sql trước khi S01 khởi động |
| Grant request 403 hoặc 409 | Kiểm active ancestors/role, member đúng Workspace, Idempotency-Key và ACTIVE Grant trùng; không sửa Grant bằng SQL |
| Verify 409 | Kiểm S02 binding đúng scope, ACTIVE, revision/freshness và Grant chưa đổi/revoke; API-070 không thay evidence |
| Verify/API-039 503 | Kiểm S02 receiver, Service JWT audience/path, TLS/timeout; giữ fail closed |
| API-038 200 nhưng effective=false | Kiểm BINDING_UNVERIFIED, availability và revision; đây là degradation an toàn |
| Route smoke PASS nhưng lifecycle chưa chạy | Route smoke chỉ kiểm 401 không JWT; cần private environment và Connection S02 thật |
| Swagger POST hiển thị 200 | Runtime response annotation chưa khớp Controller/static 201; final contract gap đang mở |

## Evidence local 27/11/2026 và phần chưa nghiệm thu

| Gate | Kết quả | Giới hạn |
| --- | --- | --- |
| S01 Maven verify, Java 21/Testcontainers | **PASS 49/49**, 0 fail/error/skip theo Surefire | Lượt Maven trước; không chạy lại khi cập nhật tài liệu |
| Gateway Maven verify | **PASS 28/28**, 0 fail/error/skip theo Surefire | Lượt Maven trước |
| Writer RLS/audit/rollback/pool, V6→V7 và CLI | **PASS**: AuthorityWriterPrivilegeIntegrationTest 1/1, RepositoryGrantUpgradeMigrationTest 1/1, ProvisioningIntegrationTest 2/2 | Testcontainers PostgreSQL, không phải production DB |
| Compose DB hiện tại | **PASS** read-only: V7 7/t, writer NOLOGIN/NOBYPASSRLS, operator_identity_id còn | Không chạy lại migration trong lần kiểm |
| Gateway/OpenAPI validator | **PASS** 119 APIs / 96 exact routes / 21 groups / no internal exposure | Static check, không chứng minh JWT/domain/E2E |
| Springdoc runtime 5 Grant APIs | **PARTIAL**: method/path/operationId và fields khớp; POST 201↔200 và error responses chưa khớp | Cần sửa final contract gate |
| Newman route smoke | **PASS 5 requests/5 assertions** theo lượt Owner đã chạy | Chỉ unauthenticated routes |
| Newman lifecycle | **NOT RUN** | Thiếu fixture và S02 production receiver đã xác nhận |
| S01 + controlled S02 HTTP receiver | **MOCK-BASED PASS** trong RepositoryGrantLifecycleIntegrationTest 2/2 | Signed JWT test/fail closed; không phải real E2E |
| S01↔production S02 LIV-155; Golden Flow 1 với S04 | **BLOCKED / NOT RUN** | Chờ S02 receiver/Connection thật và S04 protected Document action |
| Remote CI | **NOT RUN** | Chưa có push/PR; workflow local có V7, 12 RLS tables và writer bootstrap |

PR3 chỉ nên báo **S01 local capability đã kiểm**, không gọi S01 ready là Golden Flow 1 E2E PASS. Sau khi runtime contract khớp và S02 xác nhận handoff, Owner mới chốt PR3 merge gate. PR4 LIV-163 tiếp nhận onboarding/admin HTTP; không chuyển P0 Grant còn thiếu sang PR5.
