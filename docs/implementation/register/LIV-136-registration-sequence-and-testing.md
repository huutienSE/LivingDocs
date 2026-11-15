# LIV-136 — Sequence và hướng dẫn kiểm thử Register

## Luồng API-001

`AuthController` nhận request trực tiếp tại S01. Spring MVC bind `RegisterRequest` (setter email trim khoảng trắng ASCII) rồi chạy `@Valid` trước khi gọi method Controller. Service interface được Spring proxy chuyển tới implementation; `@Transactional` đặt trên `RegistrationServiceImpl.registerLocal`.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client (Postman/FE)
    participant MVC as Spring MVC / Jakarta Validation
    participant Controller as AuthController
    participant Service as RegistrationService
    participant Impl as RegistrationServiceImpl
    participant Email as EmailNormalization
    participant Encoder as PasswordEncoder
    participant Repo as IdentityRepository
    participant DB as PostgreSQL
    participant Handler as GlobalExceptionHandler

    Client->>MVC: POST /api/v1/auth/register (JSON)
    MVC->>MVC: Bind RegisterRequest; trim email; @Valid
    alt Validation hoặc JSON không hợp lệ
        MVC->>Handler: Validation/binding exception
        Handler-->>Client: HTTP 400 application/problem+json
    else Request hợp lệ
        MVC->>Controller: register(request)
        Controller->>Service: registerLocal(email, rawPassword)
        Service->>Impl: Spring proxy dispatch
        Note over Service,Impl: @Transactional trên registerLocal
        Impl->>Email: trimOuterSpaces(email)
        Email-->>Impl: normalizedEmail
        Impl->>Encoder: encode(rawPassword)
        Encoder-->>Impl: BCrypt hash
        Impl->>Repo: saveAndFlush(new Identity(email, hash))
        Repo->>DB: INSERT; unique index LOWER(BTRIM(email))
        alt INSERT thành công
            DB-->>Repo: Identity đã lưu
            Repo-->>Impl: Identity có identityId
            Impl-->>Service: UUID
            Service-->>Controller: UUID
            Controller->>Controller: RegisterResponse; SuccessResponse.of(...)
            Controller-->>Client: HTTP 201 application/json
        else Trùng email theo uq_identities_email
            DB-->>Repo: SQLSTATE 23505
            Repo-->>Impl: DataIntegrityViolationException
            Impl->>Impl: Kiểm tra SQLSTATE và constraint name
            Note over Impl,DB: Transaction rollback
            Impl-->>Handler: IdentityAlreadyExistsException
            Handler-->>Client: HTTP 409 application/problem+json
        end
    end
```

Nhánh 400 không lưu Identity. Nhánh 409 chỉ được gán cho đúng unique constraint của email; các lỗi database khác không bị gắn nhầm thành trùng email. Register không cấp JWT hoặc quyền tenant.

## Điều kiện và kiểm tra nhanh

Tại thư mục gốc repository: Docker Desktop, S01 và PostgreSQL đã chạy; Java 21 và Node/npm sẵn có. Python 3 cần `PyYAML` và `openapi-spec-validator` để kiểm tra contract (CI dùng `PyYAML==6.0.3`, `openapi-spec-validator==0.7.2`).

**macOS:**

```bash
curl -fsS http://127.0.0.1:8081/actuator/health
```

**Windows PowerShell:**

```powershell
curl.exe -fsS http://127.0.0.1:8081/actuator/health
```

## Maven và PostgreSQL integration tests

**macOS** (chọn JDK 21 nếu máy có nhiều JDK):

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd backend/identity-organization-service
./mvnw -B -ntp clean verify
cd ../..
```

**Windows PowerShell** (đảm bảo `java -version` là 21):

```powershell
Set-Location backend/identity-organization-service
.\mvnw.cmd -B -ntp clean verify
Set-Location ../..
```

PASS khi Maven kết thúc `BUILD SUCCESS` và không có test failures/errors. `RegistrationIntegrationTest` dùng PostgreSQL 17 qua Testcontainers, chạy Flyway và Hibernate schema validation; đây là bằng chứng DB thật, khác với Postman HTTP tests.

## Postman Collection Runner

Import [collection](../../../tests/postman/liv136-register.postman_collection.json) và chọn Environment local có `baseUrl=http://127.0.0.1:8081`. Trên macOS, chạy `bash tests/postman/setup-liv136-env.sh` tại repository root rồi import Environment JSON ở dòng `Postman environment:`. Trên Windows, tạo Environment tương đương trong Postman UI; collection cũng có `baseUrl` fallback cùng giá trị. Chạy **16 requests đúng thứ tự, 1 iteration**, kỳ vọng **0 failed assertions**.

`password` là collection variable dành cho dữ liệu test. Pre-request script của request thành công sinh `registerEmail` duy nhất; ba request duplicate dùng lại email đó (nguyên dạng, khác hoa thường, thêm khoảng trắng ASCII). Các positive boundary requests cũng sinh email mới, nên có thể chạy lại trên cùng database.

## Newman tự động

Newman chạy chính collection trên bằng CLI. Hai lệnh chạy liên tiếp phát hiện va chạm dữ liệu hoặc phụ thuộc trạng thái DB. Báo cáo JSON nằm ở `/tmp` trên macOS và `%TEMP%` trên Windows.

**macOS:**

```bash
env_file="$(bash tests/postman/setup-liv136-env.sh | sed -n 's/^Postman environment: //p')"
npx --yes newman run tests/postman/liv136-register.postman_collection.json -e "$env_file" --reporters cli,json --reporter-json-export /tmp/liv136-newman-1.json
npx --yes newman run tests/postman/liv136-register.postman_collection.json -e "$env_file" --reporters cli,json --reporter-json-export /tmp/liv136-newman-2.json
```

**Windows PowerShell:**

```powershell
$collection = "tests/postman/liv136-register.postman_collection.json"
npx.cmd --yes newman run $collection --env-var "baseUrl=http://127.0.0.1:8081" --reporters cli,json --reporter-json-export "$env:TEMP\liv136-newman-1.json"
npx.cmd --yes newman run $collection --env-var "baseUrl=http://127.0.0.1:8081" --reporters cli,json --reporter-json-export "$env:TEMP\liv136-newman-2.json"
```

Mỗi lần PASS khi Newman exit code `0`, CLI báo không có failed assertions và JSON report có `run.failures` rỗng. Suite hiện có 16 requests/83 assertions; con số phải được kiểm tra lại khi collection thay đổi. Newman không chứng minh hash, rollback hoặc uniqueness đồng thời; các trường hợp đó thuộc Java integration tests.

## OpenAPI và Swagger

Nếu Python environment chưa có validator packages, cài đúng phiên bản CI: macOS `python3 -m pip install openapi-spec-validator==0.7.2 PyYAML==6.0.3`; Windows PowerShell `py -3 -m pip install openapi-spec-validator==0.7.2 PyYAML==6.0.3`.

**macOS:**

```bash
python3 -m openapi_spec_validator contracts/openapi/s01-identity.yaml
python3 scripts/validate_gateway_openapi.py
```

**Windows PowerShell:**

```powershell
py -3 -m openapi_spec_validator contracts/openapi/s01-identity.yaml
py -3 scripts/validate_gateway_openapi.py
```

Static Swagger UI: <http://127.0.0.1:8090>. S01 Springdoc runtime: <http://127.0.0.1:8081/v3/api-docs>. Validator kiểm tra YAML/schema và Gateway mapping; chỉ request HTTP/Postman mới xác minh response runtime. So sánh API-001 theo status, media type, required fields và `data.identityId` UUID; tên component static `RegisterApiResponse` khác tên generic do Springdoc sinh nhưng JSON tương đương.

## Checklist nghiệm thu local

- [ ] Maven Java 21 PASS; PostgreSQL Testcontainers integration PASS.
- [ ] Postman Runner và hai lần Newman PASS, không có failed assertions.
- [ ] OpenAPI 3.0.3 và Gateway validator PASS; YAML, Springdoc và HTTP API-001 khớp.
- [ ] Không đưa secrets, Environment JSON tạm hoặc reports vào Git.
- [ ] Ghi trạng thái GitHub Actions CI riêng; local PASS không đồng nghĩa CI PASS.
