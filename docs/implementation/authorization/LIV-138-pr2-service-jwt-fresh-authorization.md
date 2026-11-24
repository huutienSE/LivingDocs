# LIV-138 PR2 — Service JWT, API-039 và hướng dẫn kiểm thử

> **Chọn đúng đường chạy.** Máy đã có PostgreSQL V6, workload secrets và fixture B: dùng [đường chạy lại](#đường-chạy-lại-trên-database-đã-có-v6). Máy mới: hoàn thành [PR1 runbook](LIV-138-s01-authority-and-testing.md) để có Tenant/Workspace và named Operator trước, sau đó làm các bước PR2 bên dưới. Không tạo lại Identity, Tenant, key, operator manifest hoặc migration trên database đã chuẩn bị.
>
> **Phạm vi E2E trong tài liệu:** HTTP thực đến S01 và Gateway bằng Postman/Newman, với PostgreSQL/RLS và workload registry thật. Đây là PR2 technical E2E; chưa phải Golden Flow S01→S02→S04, API-069/070 receiver hay Flow 2 Kafka E2E.

## PR2 làm gì và kiểm đến đâu

PR2 thêm hai internal POST ở S01: cấp Service JWT cho workload đã đăng ký và đánh giá quyền người dùng hiện hành bằng API-039. User JWT vẫn chỉ mang Identity; JWT service có trust profile riêng. S01 đọc membership, role, permission, Repository Grant và tenant revision từ snapshot PostgreSQL nhất quán. Domain service tự xác định resource scope và business guards. V5 thêm REPOSITORY_CONNECT cho DEVELOPER/TECHNICAL_LEAD; V6 tăng revision và ghi audit cho tenant cũ vì catalog đã thay đổi.

**Điểm dừng nghiệp vụ:** S01 chỉ quyết định authority. S02 sở hữu Repository Connection, GitHub binding và authoritative SHA; S04 sở hữu Document state, review, approval, publish và audit. Repository Grant PENDING không cấp quyền. Không tạo Verified Grant hay tuyên bố Golden Flow PASS từ bộ test PR2.

## Phạm vi và ranh giới

S01 xác thực workload credential rồi cấp Service JWT RS256 trong 60 giây. API-039 xác thực đồng thời User JWT và Service JWT, đọc authority hiện hành từ PostgreSQL/RLS và trả `ALLOW` hoặc `DENY`. Flyway V5 cấp `REPOSITORY_CONNECT` cho `DEVELOPER` và `TECHNICAL_LEAD` trong Workspace; V6 tăng revision và audit của tenant đã tồn tại vì catalog toàn cục thay đổi. S01 không xác minh GitHub binding/SHA và không sửa dữ liệu S02/S04.

```mermaid
sequenceDiagram
  participant S02 as S02 caller
  participant S01 as S01
  participant DB as S01 PostgreSQL/RLS
  S02->>S01: POST /internal/v1/auth/service-tokens + Basic workload credential
  S01->>S01: BCrypt registry policy; sign Service JWT
  S01-->>S02: Service JWT (60s, no-store)
  S02->>S01: POST API-039 + User JWT + Service JWT
  S01->>S01: Verify both profiles, actor hash, exact action and scope
  S01->>DB: REPEATABLE READ; SET LOCAL tenant; active ancestors, role, grant, revision
  DB-->>S01: One authority snapshot
  S01-->>S02: 200 ALLOW/DENY, or 503 fail closed
```

S04 dùng cùng API-039 cho Document actions sau khi tự xác định resource scope. S03/S04 có thể xin Service JWT `source:resolve`/`source:validate` để gọi S02, không cần User JWT ở authentication layer của API-069/070. S02 vẫn phải kiểm JWT, binding, source SHA và business conditions trong implementation riêng. Một UUID trong S01 `identity.repository_grants.repository_connection_id` chỉ là logical reference; Connection thật thuộc S02 `repository.repository_connections`.

## Contract để bàn giao

| Operation | Credential gửi đến S01 | Request chính | Kết quả |
| --- | --- | --- | --- |
| `POST /internal/v1/auth/service-tokens` | `Authorization: Basic base64(workloadId:rawSecret)` | `audience`, `operation`, `method`, `path`, `tenantId`, `workspaceId`; thêm `repositoryConnectionId`, `action`, `actorTokenSha256` khi operation yêu cầu | `200` `data.accessToken`, `tokenType=Bearer`, `expiresIn=60`; `400/401/403/503` Problem JSON |
| `POST /internal/v1/authorization/evaluate` | `Authorization: Bearer <User JWT>` và `X-LD-Service-Assertion: Bearer <Service JWT>` | `action`, `tenantId`, `workspaceId`, `repositoryConnectionId` cho Document | `200` `data.decision=ALLOW/DENY`; `400/401/403/503` Problem JSON |

Service JWT có `iss=urn:livingdocs:s01`, `sub=workload:s02|s03|s04`, exact `aud`, `token_use=service`, `operation`, `http_method`, `http_path`, `tenant_id`, `workspace_id`, optional `repository_connection_id`/`action`/`actor_token_sha256`, cùng `iat`, `nbf`, `exp`, `jti`. API-039 yêu cầu hash SHA-256 của **raw User JWT** trong Service JWT. `REPOSITORY_CONNECT` không có `repositoryConnectionId` vì Connection chưa tồn tại; Document actions bắt buộc có ID và effective `ACTIVE/VERIFIED` Repository Grant. `DENY` là quyết định quyền hợp lệ; database unavailable trả 503 và caller phải fail closed.

Workload secret gốc ở secret mount của caller, ví dụ local `~/.livingdocs-pr2/s02.secret`. S01 đọc BCrypt hash và issuance policy từ `s01_workloads.json` ngoài Git. Hai file đó không phải JWT. `s02WorkloadBasic` trong Postman là Base64 của `s02:<raw secret>`; `invalidWorkloadBasic` là Base64 của `s02:wrong-secret` dùng cho case 401. Không đưa raw secret, Basic value, environment JSON hoặc JWT vào Git/report.

### Handoff nhanh cho service owners

| Caller → receiver | Operation / audience / path | User JWT và scope | Việc receiver phải làm |
| --- | --- | --- | --- |
| S02 → S01 | `authorization:evaluate` / `livingdocs-s01-internal` / `POST /internal/v1/authorization/evaluate` | User JWT ở `Authorization`, Service JWT ở `X-LD-Service-Assertion`; `REPOSITORY_CONNECT` có tenant/workspace, **không** có repository ID | S02 gọi trước API-047, sau ALLOW tự xác minh GitHub ownership và tạo Connection |
| S04 → S01 | Cùng API-039 | Hai JWT; Document action bắt buộc tenant/workspace/repository ID và active VERIFIED Grant | S04 tự kiểm state/ETag, reviewer eligibility và No Self Final Approval; DENY/503 không mutate |
| S03 → S02 | `source:resolve` / `livingdocs-s02-internal` / `POST /internal/v1/source/resolve` | Service JWT trong `Authorization`; không cần User JWT; có repository ID | S02 xác minh JWT và trả source evidence theo contract API-069 của S02 |
| S04 → S02 | `source:validate` / `livingdocs-s02-internal` / `POST /internal/v1/source/validate` | Service JWT trong `Authorization`; không cần User JWT ở authentication layer; có repository ID | S02 xác minh JWT và current authoritative SHA theo API-070 |

Receiver của API-069/070 phải kiểm RS256/JWKS, issuer, đúng một audience, `token_use=service`, workload subject, operation, exact method/path, scope, thời gian và `jti`. PR2 chỉ cấp JWT; **không** sửa runtime S02/S04. Contract wire chính xác nằm ở [S01 OpenAPI](../../../contracts/openapi/s01-identity.yaml); các API khác ghi `CANDIDATE`/`BLOCKED` chưa phải endpoint chạy thật.

# Đường chạy PR2 lần đầu trên baseline PR1

## Bước 0 — Kiểm tra công cụ, fixture và secrets

**Mục đích:** chỉ đọc trạng thái trước khi build hoặc nâng local database. Nếu PR1 chưa có Tenant/Workspace và named Operator, hoàn thành [PR1 runbook](LIV-138-s01-authority-and-testing.md) trước. PR2 không tạo lại bootstrap hay Tenant.

- Java 21, Docker Desktop/Compose, Node.js `npx`, Python 3, Postman; macOS dùng `jq` cho các lệnh kiểm tra.
- B có ACTIVE Identity, Tenant Membership, Review Workspace Membership và role `TECHNICAL_LEAD` được gán tường minh; Other Workspace không có quyền connect của B. Nếu thiếu, dùng named Operator CLI theo PR1 để tạo operation mới có audit, không sửa SQL runtime. Fixture local hiện tại đã có các điều kiện này.
- Local secret files `~/.livingdocs-pr2/s02.secret`, `s03.secret`, `s04.secret` và S01 BCrypt registry nằm ngoài repository. Compose S01 mount registry read-only. Local Compose cho phép HTTP nội bộ để thử nghiệm; staging/production cần TLS.
- `LIV138_PR2_REPOSITORY_ID` của fixture hiện tại trỏ tới S01 PENDING grant, chưa có row Connection tương ứng trong S02. Các case source trong Postman chỉ xác nhận S01 cấp JWT có scope, không gọi API-069/070.

**macOS — từ repo root:**

```bash
pwd
docker compose version
python3 --version
node --version
jq --version
test -f infrastructure/docker/.env
test -r ../livingdocs-secrets/s01_workloads.json
for service in s02 s03 s04; do test -r "$HOME/.livingdocs-pr2/$service.secret"; done
```

**Windows PowerShell — từ repo root:**

```powershell
Get-Location
docker compose version
py -3 --version
node --version
Test-Path infrastructure/docker/.env
Test-Path (Join-Path (Resolve-Path ..).Path 'livingdocs-secrets/s01_workloads.json')
foreach ($service in 's02','s03','s04') { Test-Path (Join-Path $HOME ".livingdocs-pr2/$service.secret") }
```

**Điểm dừng:** mọi file cần tồn tại; secret gốc không nằm trong `s01_workloads.json`, registry phải chứa BCrypt hash và policy tenant/audience/operation thật. Không tạo registry giả chỉ để test PASS. Nếu Compose mount secrets ở đường khác, kiểm `S01_JWT_SECRETS_DIR` trong `.env`. Không in secret hoặc đưa environment JSON vào Git.

## Bước 1 — Java/Testcontainers trước HTTP (macOS)

Từ repository root, kiểm Java/Testcontainers trước:

```bash
cd backend/identity-organization-service
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -ntp clean verify
cd ../../infrastructure/api-gateway
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -ntp clean verify
cd ../..
```

**Điểm dừng:** Java 21 và Docker Desktop phải chạy; S01 `clean verify` kỳ vọng 45 tests, Gateway 27 tests, không có failure/error. Testcontainers dùng PostgreSQL riêng, không mutate Compose DB.

## Bước 2 — Backup rồi chạy S01/Compose (chỉ khi V6 chưa có)

Khối lệnh này ghi lại thứ tự nâng **một database PR1 chưa có V6**. **Local database hiện tại đã có V6, không chạy lại bước này.** `umask 077` bảo đảm backup mới chỉ Owner đọc được. Dùng `pg_restore` trong Postgres container giải nén archive vào `/dev/null`; không restore hay ghi DB. `config_only` chỉ thỏa Compose interpolation của operator profile đang tắt, không phải DB credential.

```bash
cd infrastructure/docker
export S01_OPERATOR_DB_LOGIN=config_only S01_OPERATOR_DB_PASSWORD=config_only
docker compose --profile services --profile gateway config --quiet
umask 077
BACKUP="$HOME/Desktop/livingdocs-before-v6-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$BACKUP"
docker compose exec -T postgres pg_restore --file=/dev/null < "$BACKUP"
```

**Điểm dừng trước khi nâng DB:** cả hai lệnh backup/pg_restore phải exit 0, file backup có dữ liệu. Đây là kiểm tra archive, chưa phải restore rehearsal. Chỉ sau đó chạy:

```bash
docker compose --profile services --profile gateway up -d --build identity-organization-service api-gateway
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, description, success FROM identity.flyway_schema_history ORDER BY installed_rank"'
curl -fsS http://127.0.0.1:8081/actuator/health
cd ../..
```

**Điểm dừng:** backup đọc được; Flyway history có V1–V6 `success=t`; S01 `/actuator/health` là `UP`. Nếu V6 đã có từ trước, chỉ dùng [đường chạy lại](#đường-chạy-lại-trên-database-đã-có-v6). Không chạy V5/V6 bằng `psql -f`, không xóa volume.

## Bước 3 — Postman Runner với fixture thật (macOS)

Tạo **private environment JSON ngoài Git** bằng script, nhập B Identity password khi được hỏi. Script cũng hỏi email và bốn UUID nếu chưa set `LIV138_PR2_B_EMAIL`, `LIV138_PR2_TENANT_ID`, `LIV138_PR2_WORKSPACE_ID`, `LIV138_PR2_OTHER_WORKSPACE_ID`, `LIV138_PR2_REPOSITORY_ID`. Dùng ID từ PR1 outputs/DB read-only, không lấy UUID ví dụ trong Swagger.

```bash
bash tests/postman/setup-liv138-pr2-env.sh
```

Import hai path script in ra vào Postman, chọn environment mới. Chạy toàn bộ collection theo ba folder: `API-039 real B authority` (11), `Service JWT issuance policy and S04 denial` (14), `Gateway internal route rejection` (2). Request 01 Login lấy User JWT mới; các request kế tiếp cấp Service JWT 60 giây tại lúc chạy. Kỳ vọng **27 requests/27 assertions/0 failed**. Không chạy từng request cần token một cách độc lập.

| Folder | Bằng chứng chính |
| --- | --- |
| API-039 real B authority | Review Workspace `ALLOW` cho B TECHNICAL_LEAD; Other Workspace `DENY`; sai actor binding/scope hoặc thiếu một JWT bị từ chối |
| Service JWT issuance policy and S04 denial | Basic sai `401`; sai audience/operation/tenant/method/path/caller `403`; S03/S04 nhận đúng JWT source; Document không có effective grant nhận `DENY` |
| Gateway internal route rejection | Cả hai internal POST bị Gateway chặn `403` |

**Điểm dừng:** chỉ sang Newman khi Postman Runner PASS. Fixture B phải còn ACTIVE; case Document DENY không chứng minh S04 đã tích hợp.

## Bước 4 — Newman cùng environment (macOS)

Dùng **chính file JSON** đã import vào Postman:

```bash
POSTMAN_ENV='/absolute/path/printed/by/setup/liv138-pr2.postman_environment.json'
npx --yes newman run tests/postman/liv138-pr2.postman_collection.json -e "$POSTMAN_ENV" --reporters cli
```

**Điểm dừng:** kỳ vọng 27 requests, 27 assertions, 0 failed. Đây là S01/Gateway HTTP local, không gọi S02 API-069/070 hay S04 mutation.

## Bước 5 — OpenAPI, Swagger và Gateway inventory (macOS)

Kiểm sau Newman; dùng virtualenv để không đổi Python toàn máy:

```bash
python3 -m venv /tmp/liv138-pr2-openapi
/tmp/liv138-pr2-openapi/bin/pip install openapi-spec-validator==0.7.2 PyYAML==6.0.3
/tmp/liv138-pr2-openapi/bin/python -m openapi_spec_validator contracts/openapi/s01-identity.yaml
/tmp/liv138-pr2-openapi/bin/python scripts/validate_gateway_openapi.py
curl -fsS http://127.0.0.1:8081/v3/api-docs | jq '{issue: .paths["/internal/v1/auth/service-tokens"].post.operationId, evaluate: .paths["/internal/v1/authorization/evaluate"].post.operationId}'
docker compose -f infrastructure/docker/swagger-ui.compose.yaml up -d swagger-ui
open http://127.0.0.1:8090
open http://127.0.0.1:8081/swagger-ui.html
```

**Điểm dừng:** YAML validator `OK`, Gateway inventory không expose internal route; runtime có hai PR2 POST và đúng `issueServiceToken`/`api039_post`. UI tĩnh và runtime phải khớp 5 API đã implemented. Các candidate trong UI tĩnh không phải endpoint chạy thật.

Static UI `:8090` đọc YAML inventory gồm 5 API có Controller và 21 operation chưa triển khai (17 `CANDIDATE`, 4 `BLOCKED_SECURITY`); nhãn `[CANDIDATE]`/`[BLOCKED]` và `x-contract-state` cho biết trạng thái. Runtime UI `:8081` đọc Springdoc: 5 API trên cộng `GET /.well-known/jwks.json` (discovery endpoint hiện hữu, ngoài API inventory). Hai internal POST phải khớp operation ID, security schemes, request/response schemas và ví dụ wire. Ví dụ UUID, revision, thời gian, correlation ID, JWT đều minh họa; không phải fixture. Chọn ví dụ `repositoryConnect` không có `repositoryConnectionId`, còn `documentPublish` có repository scope. Các response 400/401/403/503 có ví dụ Problem với `status` cụ thể; generic schema mặc định trước đây hiển thị `status: 0` chỉ là placeholder, không phải HTTP response thực tế.

## Bước 1 — Java/Testcontainers (Windows PowerShell)

Chạy từ root checkout trên Windows; Docker Desktop phải đang chạy. Các lệnh Windows là hướng dẫn tương ứng, **chưa được chạy trên máy Windows trong evidence PR2 này**. Dùng Java 21.

```powershell
Set-Location backend/identity-organization-service
.\mvnw.cmd -B -ntp clean verify
Set-Location ../../infrastructure/api-gateway
.\mvnw.cmd -B -ntp clean verify
Set-Location ../..
```

**Điểm dừng:** S01 kỳ vọng 45 tests; Gateway 27 tests. Nếu Java khác 21 hoặc Testcontainers không có Docker, xử lý trước khi chạy HTTP.

## Bước 2 — Backup và Compose (Windows; chỉ khi V6 chưa có)

Database hiện tại đã ở V6 thì bỏ qua bước này. Lệnh Windows tạo backup trên database PR1 mới rồi để S01 tự chạy Flyway. File copy ra host cần ACL chỉ Owner đọc được.

```powershell

$env:S01_OPERATOR_DB_LOGIN = 'config_only'
$env:S01_OPERATOR_DB_PASSWORD = 'config_only'
Set-Location infrastructure/docker
docker compose --profile services --profile gateway config --quiet
$Backup = Join-Path $HOME ('Desktop\livingdocs-before-v6-' + (Get-Date -Format yyyyMMdd-HHmmss) + '.dump')
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/liv138-before-v6.dump && pg_restore --file=/dev/null /tmp/liv138-before-v6.dump'
if ($LASTEXITCODE -ne 0) { throw 'Backup archive verification failed; stop before Flyway' }
docker compose cp postgres:/tmp/liv138-before-v6.dump $Backup
if ($LASTEXITCODE -ne 0) { throw 'Backup copy failed; stop before Flyway' }
Write-Host "Backup: $Backup"
```

**Điểm dừng trước khi nâng DB:** backup và copy phải thành công, file host không rỗng. Chỉ sau đó chạy:

```powershell
docker compose --profile services --profile gateway up -d --build identity-organization-service api-gateway
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, description, success FROM identity.flyway_schema_history ORDER BY installed_rank"'
Invoke-RestMethod http://127.0.0.1:8081/actuator/health
Set-Location ../..
```

**Điểm dừng:** backup đọc được, V1–V6 `success=t`, S01 health `UP`; không chạy lại trên local DB đã V6.

## Bước 3 — Postman Runner (Windows)

Tạo environment private tương đương script macOS; nhập **fixture thật của Windows**, không dùng UUID local của Owner nếu database khác. Secret files cần tồn tại trong `$HOME/.livingdocs-pr2` và registry S01 phải có hash tương ứng.

```powershell
$secretDir = Join-Path $HOME '.livingdocs-pr2'
$basic = {
  param($name)
  $raw = (Get-Content -Raw (Join-Path $secretDir "$name.secret")).Trim()
  [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${name}:$raw"))
}
$secure = Read-Host 'B Identity password' -AsSecureString
$bPassword = [System.Net.NetworkCredential]::new('', $secure).Password
$values = [ordered]@{
  s01Base = 'http://127.0.0.1:8081'; gatewayUrl = 'http://127.0.0.1:8080'
  bEmail = (Read-Host 'B Identity email'); bPassword = $bPassword
  tenantId = (Read-Host 'Tenant UUID'); workspaceId = (Read-Host 'Review Workspace UUID')
  otherWorkspaceId = (Read-Host 'Other Workspace UUID')
  repositoryConnectionId = (Read-Host 'S01 PENDING grant reference UUID')
  s02WorkloadBasic = (& $basic 's02'); s03WorkloadBasic = (& $basic 's03')
  s04WorkloadBasic = (& $basic 's04')
  invalidWorkloadBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('s02:wrong-secret'))
}
$PostmanEnv = Join-Path $env:TEMP ('liv138-pr2-' + [guid]::NewGuid().ToString() + '.postman_environment.json')
$doc = @{ name='LIV-138 PR2 Local S01'; _postman_variable_scope='environment'; values=@($values.Keys | ForEach-Object { @{key=$_;value=$values[$_];type='default';enabled=$true} }) }
[IO.File]::WriteAllText($PostmanEnv, ($doc | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
$bPassword = $null
Write-Host "Private Postman environment: $PostmanEnv"
```

Import `tests/postman/liv138-pr2.postman_collection.json` và `$PostmanEnv` vào Postman; chạy đủ ba folder/27 requests. **Điểm dừng:** chỉ sang Newman sau 27 assertions PASS.

## Bước 4 — Newman (Windows)

Dùng cùng collection/environment vừa PASS trong Postman:

```powershell
npx --yes newman run tests/postman/liv138-pr2.postman_collection.json -e $PostmanEnv --reporters cli
```

**Điểm dừng:** 27 requests, 27 assertions, 0 failed. Không lưu hoặc chia sẻ report chứa JWT/Basic value.

## Bước 5 — OpenAPI/Swagger (Windows)

```powershell
$Venv = Join-Path $env:TEMP 'liv138-pr2-openapi'
py -3.12 -m venv $Venv
& "$Venv\Scripts\python.exe" -m pip install openapi-spec-validator==0.7.2 PyYAML==6.0.3
& "$Venv\Scripts\python.exe" -m openapi_spec_validator contracts/openapi/s01-identity.yaml
& "$Venv\Scripts\python.exe" scripts/validate_gateway_openapi.py
$api = Invoke-RestMethod http://127.0.0.1:8081/v3/api-docs
$api.paths.'/internal/v1/auth/service-tokens'.post.operationId
$api.paths.'/internal/v1/authorization/evaluate'.post.operationId
docker compose -f infrastructure/docker/swagger-ui.compose.yaml up -d swagger-ui
Start-Process http://127.0.0.1:8090
Start-Process http://127.0.0.1:8081/swagger-ui.html
```

**Điểm dừng:** validator PASS, hai internal POST xuất hiện ở runtime và không được route qua Gateway. Windows commands chưa có evidence chạy thật trong PR2 này.

Environment/report chứa password, Basic value hoặc JWT phải ở TEMP riêng và không commit/chia sẻ. Windows cần kiểm ACL của file TEMP trước khi giữ lại lâu dài.

# Đường chạy lại trên database đã có V6

Đây là đường của Owner hiện tại. **Không** tạo key/secret/registry mới, không tạo `.env`, không tạo manifest/operation ID mới, không backup để chạy lại migration, không chạy `psql -f V5/V6`. Nếu S01 đang `UP`, chỉ kiểm trạng thái rồi chạy Postman/Newman; nếu container dừng, khởi động lại với cùng volume và cấu hình hiện hữu.

## R1 — Đọc trạng thái V6, fixture và HTTP

**macOS — từ repo root:**

```bash
cd infrastructure/docker
export S01_OPERATOR_DB_LOGIN=config_only S01_OPERATOR_DB_PASSWORD=config_only
docker compose --profile services --profile gateway ps
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM identity.flyway_schema_history ORDER BY installed_rank"'
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT tenant_id, authz_revision FROM identity.tenants ORDER BY tenant_id"'
curl -fsS http://127.0.0.1:8081/actuator/health
cd ../..
```

**Windows PowerShell — từ repo root:**

```powershell
Set-Location infrastructure/docker
$env:S01_OPERATOR_DB_LOGIN = 'config_only'
$env:S01_OPERATOR_DB_PASSWORD = 'config_only'
docker compose --profile services --profile gateway ps
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM identity.flyway_schema_history ORDER BY installed_rank"'
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT tenant_id, authz_revision FROM identity.tenants ORDER BY tenant_id"'
Invoke-RestMethod http://127.0.0.1:8081/actuator/health
Set-Location ../..
```

**Điểm dừng:** V1–V6 đều `success=t`, health `UP`; local fixture đã kiểm `authz_revision=20` và một `CATALOG_CHANGED` audit. Revision ở máy khác có thể khác 20. Nếu S01 đang tắt nhưng DB đã ở V6, dùng `docker compose --profile services --profile gateway up -d identity-organization-service api-gateway` từ `infrastructure/docker` rồi kiểm lại R1; không reset volume. Nếu V6 chưa có, dừng và dùng Bước 2 có backup trước khi nâng.

## R2 — Chạy lại đúng gate đã PASS

Trở về repo root. Nếu source code đổi từ lần test cuối, chạy lại **Bước 1** của OS tương ứng. Chạy **Bước 3 → 4 → 5** với cùng fixture ACTIVE: Postman Runner trước, Newman sau, rồi OpenAPI/Swagger. Có thể tạo environment private mới bằng setup script; nó không provision hay mutate DB. Không chạy named Operator CLI, không chuyển PENDING thành VERIFIED để làm test xanh.

## Khi lỗi, kiểm đúng lớp trước khi thử lại

| Triệu chứng | Kiểm tra | Kỳ vọng hoặc xử lý |
| --- | --- | --- |
| Java compile lỗi Lombok hoặc JDK | `java -version`, `JAVA_HOME` | Dùng Java 21; Java 26 từng làm Lombok không sinh getter ở local |
| Compose thiếu `S01_OPERATOR_DB_*` | Biến môi trường shell/PowerShell | `config_only` chỉ để parse operator profile; không phải credential DB |
| S01 `503 AUTHORITY_UNAVAILABLE` | PostgreSQL, RLS role, registry file và health | Fail closed; không sửa JWT/DB grant bằng tay |
| Service Token `401` | Basic là `workloadId:rawSecret`, registry giữ BCrypt hash | Không dùng hash làm Basic secret; không dùng User JWT thay Service JWT |
| Service Token `403` | Tenant allowlist, audience, operation, exact POST path/action/repository scope | Kiểm policy thật; không nới policy để một bad case PASS |
| API-039 `200 DENY` | Membership, role, Workspace status, active VERIFIED grant nếu Document | DENY là kết quả nghiệp vụ; REPOSITORY_CONNECT không cần grant của Connection chưa tồn tại |
| API-039 `401` | User JWT còn hạn và Service JWT mới trong 60 giây, actor hash khớp | Chạy nguyên Runner theo thứ tự; token không dùng lại sau timeout |
| Postman/Newman thiếu biến | Import đúng private environment JSON, B email/password và UUID thật | Không dùng UUID example trên Swagger; không commit environment |
| Gateway internal route trả 403 | Gateway đang chạy | Đây là PASS cho request 26–27; internal APIs chỉ gọi trực tiếp S01 |
| Swagger 8090 có nhiều API hơn 8081 | `x-contract-state` và nhãn `CANDIDATE`/`BLOCKED` | 8090 là inventory; chỉ 5 API chung đã implemented, JWKS chỉ ở runtime |

## Evidence local 24/11/2026 và CI

| Gate | Kết quả | Giới hạn |
| --- | --- | --- |
| S01 `./mvnw clean verify` trên Java 21/Testcontainers | PASS 45/45 sau V6 và Staff/Manager/Publish role tests; targeted Swagger integration PASS 5/5 sau annotation DTO cuối | Không dùng Compose DB cho integration tests; full suite chưa chạy lại sau các annotation tài liệu cuối |
| Gateway `./mvnw clean verify` | PASS 27/27 theo output Owner | Gồm CORS preflight test đã bật lại |
| Compose S01 Flyway V1–V6 và health | PASS | Kiểm read-only schema history: V6 success, tenant fixture revision 20 và một `CATALOG_CHANGED` audit; không chạy lại migration |
| Backup trước V6 | PASS archive read/decompress vào `/dev/null`; Flyway history trong dump có V1–V5, không có V6 | Đây là kiểm tra archive, chưa phải restore rehearsal; file local mode `0644` nhưng Desktop directory mode `0700`. Nên đặt file `0600` để vẫn kín khi di chuyển/sao chép |
| V5→V6 upgrade trên PostgreSQL Testcontainers có hai tenant cũ | PASS 1/1 | Mỗi tenant tăng revision/audit đúng một lần; rerun V6 không lặp |
| Postman Runner PR2 trên S01 sau V6 | PASS toàn bộ collection theo Owner | Fixture B ACTIVE; không gồm revoke mutation |
| Newman cùng collection/environment sau V6 | PASS toàn bộ collection theo Owner; lượt trước có 27 request, 27 assertions, 0 failed | HTTP local, không phải S02/S04 E2E; output lượt mới chưa lưu artifact |
| S01 YAML validation và route inventory | PASS; 114 APIs, 91 Gateway entries, 19 route groups, no internal exposure | Static contract check |
| Runtime S01 `/v3/api-docs` và UI tĩnh `:8090` | PASS: 5 shared operation IDs/security/status/request-response fields/required khớp; hai PR2 POST có request/success/error examples khớp; `:8090` phục vụ đúng byte YAML hiện hành | Runtime có thêm JWKS; YAML có 17 CANDIDATE và 4 BLOCKED; không phải E2E |
| CI workflow local YAML parse và `git diff --check` | PASS | Remote CI **NOT RUN** trước push/PR |
| S01→S02 API-069/070, S01→S04 và Golden Flow | NOT RUN | S02 binding/receiver và S04 runtime integration thuộc slice riêng |

`auth-chain-ci.yaml` kiểm 6 migrations và đúng hai role `DEVELOPER,TECHNICAL_LEAD` cho `REPOSITORY_CONNECT`. `backend-ci.yaml` chạy S01 Maven/Testcontainers; `openapi-contract-ci.yaml` validate YAML/Gateway. Local database đã áp dụng V5 rồi V6; giữ nguyên checksum V5 và không chạy lại V6. Remote CI chỉ có bằng chứng sau khi Owner cho phép push và tạo PR.

## Phần chưa được PR2 nghiệm thu

- **Tiến / S02:** cần tích hợp API-039 trước API-047 và verify Service JWT tại API-069/070; S02 chứng minh GitHub binding/current SHA bằng dữ liệu của chính S02. Không suy quyền từ User JWT cũ của Kafka consumer.
- **Hải / S04:** cần tích hợp fresh API-039 cho Staff/Manager/Publish; đóng read transaction trước HTTP S01, sau ALLOW mở write transaction để recheck scope/state/ETag rồi mới mutate/audit. Giữ No Self Final Approval và gọi S02 API-070 để xác minh SHA.
- **S01 P0 sau PR2:** Tenant/Workspace Membership và Role Administration HTTP APIs (LIV-84); Repository Grant Create/List/Verify/Revoke lifecycle, S02 verified-binding integration (LIV-85). Không tự chuyển PENDING thành VERIFIED bằng SQL hoặc từ JWT.
