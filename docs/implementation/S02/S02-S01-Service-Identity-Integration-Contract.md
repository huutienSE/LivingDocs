# S02--S01 Service Identity Integration Contract

> **Status:** DRAFT --- cần S01, S02 và consumer S03/S04 review trước
> khi chốt\
> **Ngày cập nhật:** 2026-11-20\
> **Phạm vi:** Tích hợp service-to-service authentication/authorization
> cho S02 Repository Integration Service\
> **Tài liệu liên quan:** LIV-122, S01 Identity, S02 Repository
> Integration

------------------------------------------------------------------------

## 1. Mục đích

Tài liệu này xác định contract tích hợp danh tính dịch vụ giữa S01 và
S02 để S03/S04 có thể gọi các internal API của S02 bằng service access
token.

Mục tiêu:

-   Tách **User Access Token** khỏi **Service Access Token**.
-   Để S01 chịu trách nhiệm đăng ký/xác thực service client và phát hành
    token theo quyền được cấp.
-   Để S02 xác minh token, kiểm tra scope và ràng buộc token với đúng
    tenant, workspace và repository connection.
-   Không dùng dữ liệu tenant/workspace/role do request body hoặc
    identity header tự khai báo làm căn cứ cấp quyền.
-   Giữ cơ chế **fail-closed** khi issuer, JWKS, claim mapping hoặc
    trust configuration chưa được thiết lập.

**Đây là bản đề xuất để thống nhất giữa các owner, chưa phải production
contract đã được S01 phê duyệt.** Tên claim, token endpoint, cơ chế xác
thực service client và TTL phải được các bên xác nhận trước khi triển
khai tích hợp production.

## 2. Phạm vi và ngoài phạm vi

### 2.1. Trong phạm vi

-   Service identity của S03/S04 khi gọi S02.
-   Cấp và xác minh Service Access Token.
-   Issuer, audience, signing key và JWKS.
-   Scope cho hai nghiệp vụ S02:
    -   `source:resolve`
    -   `source:validate`
-   Ràng buộc quyền theo tenant, workspace và repository connection.
-   Trách nhiệm của S01, S02 và service consumer.
-   Negative tests và điều kiện nghiệm thu tích hợp.

### 2.2. Ngoài phạm vi

-   Thay thế hoặc thiết kế lại User Login/Register hiện tại.
-   Thiết kế toàn bộ tenant/workspace RBAC và các API quản trị
    membership.
-   Thay đổi nghiệp vụ resolve/validate SHA của S02.
-   Cấp quyền truy cập PostgreSQL hoặc GitHub credential cho S03/S04.
-   Quyết định cuối cùng về OAuth grant, mTLS/private-key authentication
    hoặc nền tảng workload identity. Đây là quyết định cần S01/security
    platform xác nhận.

## 3. Baseline đã quan sát trong repository

Các ghi nhận dưới đây phản ánh code và tài liệu đã review trong
`huutienSE/LivingDocs` tại thời điểm cập nhật tài liệu. Đây là mô tả
hiện trạng, không đồng nghĩa mọi phần đã được nghiệm thu production.

### 3.1. S01 --- User Access Token hiện tại

Code `JwtTokenIssuer` trên `develop` đang phát hành JWT người dùng với:

  -----------------------------------------------------------------------
  Thuộc tính                          Hiện trạng quan sát
  ----------------------------------- -----------------------------------
  Issuer                              `urn:livingdocs:s01`

  Audience                            `livingdocs-api`

  Subject                             UUID của Identity

  Signing algorithm                   `RS256`

  Key ID                              Có `kid` trong JOSE header

  `iat`, `nbf`, `exp`                 Có

  Thời hạn access token               900 giây (15 phút)

  JWKS endpoint                       `/.well-known/jwks.json`

  Tenant/role/permission claims       Không có trong User Access Token
                                      hiện tại
  -----------------------------------------------------------------------

`JwtIssuerTest` xác nhận token được verify bằng public key tương ứng, có
`iss`, `aud`, `sub`, `iat`, `nbf`, `exp`, `kid` và không chứa
`tenant_id`, `roles`, `permissions`.

`JwtKeyManager` hỗ trợ công bố public key hiện tại và public key trước
đó khi được cấu hình. Private key không được công bố qua JWKS.

**Kết luận:** User Access Token hiện tại phục vụ user authentication qua
Gateway. Không sử dụng token này thay cho Service Access Token gọi S02.

### 3.2. S01 --- Tenant/membership

Migration `V2__create_tenant_membership_tables.sql` đã tạo
`identity.tenants` và `identity.tenant_memberships`, kèm RLS cho các
bảng này.

Sự tồn tại của bảng tenant/membership không tự chứng minh toàn bộ
workspace membership, role assignment, grant evaluation hoặc service
authorization đã hoàn thiện. Các khả năng đó cần được owner S01 xác nhận
theo implementation và Jira tương ứng.

### 3.3. Gateway

`TrustedIdentityHeaderFilter` loại bỏ các identity headers không đáng
tin cậy như `X-User-Id`, `X-Role`, `X-Tenant-Id`, `X-Workspace-Id` và
các biến thể liên quan trước khi forward request.

Gateway có trách nhiệm xác minh User Access Token theo cấu hình hiện
tại. Việc xác minh user token không thay thế xác minh Service Access
Token ở S02.

### 3.4. S02 --- trạng thái tích hợp hiện tại

Trong nhánh triển khai LIV-122 đã review:

-   Đã có `SourceServiceAuthentication` để biểu diễn service principal,
    tenant/workspace scope, repository connection và authorities.
-   Đã có `SourceServiceIdentityMapper` làm điểm tích hợp claim
    contract.
-   Đã có Spring Security stateless, kiểm tra Bearer token, quyền thao
    tác và resource scope.
-   Khi thiếu trust/decoder/mapper production, đường chạy thực tế từ
    chối mặc định; không tự cấp quyền.
-   Các claim `test_*` và khóa RSA sinh trong test chỉ phục vụ HTTP
    integration test. Chúng không phải production contract.
-   HTTP local test không thay thế kiểm thử TLS thật hoặc S03/S04
    end-to-end.

**Kết luận:** S02 đã chuẩn bị điểm tích hợp và enforcement; còn thiếu
contract production được S01 xác nhận cùng token thật để hoàn thành
adapter.

## 4. Contract Service Access Token đề xuất

### 4.1. Nguyên tắc

1.  Token phải được phát hành bởi issuer tin cậy đã thống nhất với S01.
2.  Token phải dành riêng cho S02 qua audience riêng; không tái sử dụng
    audience `livingdocs-api`.
3.  Token phải biểu diễn workload/service principal, không phải user
    principal.
4.  Token chỉ cấp các operation scope cần thiết.
5.  Token phải bị giới hạn trong một tenant, một workspace và repository
    connection được cấp.
6.  S02 vẫn phải kiểm tra repository connection trong dữ liệu do S02 sở
    hữu; JWT không thay thế kiểm tra trạng thái và ownership tại S02.
7.  Không chấp nhận identity hoặc authorization lấy từ request headers
    do caller tự gửi.
8.  Thiếu claim bắt buộc, sai chữ ký hoặc không xác định được trust
    configuration thì không cấp quyền.

### 4.2. Claim set ứng viên

Bảng dưới đây là **đề xuất để review**, không phải tên/kiểu claim đã
được S01 phê duyệt.

  ----------------------------------------------------------------------------------------
  Claim                        Kiểu đề xuất            Ý nghĩa và kiểm tra tại S02
  ---------------------------- ----------------------- -----------------------------------
  `iss`                        string                  Issuer của S01; phải khớp issuer
                                                       cấu hình tin cậy. Giá trị hiện tại
                                                       quan sát được là
                                                       `urn:livingdocs:s01`, cần S01 xác
                                                       nhận có dùng chung cho service
                                                       token hay không.

  `sub`                        string                  Định danh ổn định của service
                                                       principal/workload. Không dùng UUID
                                                       user làm subject cho service token.

  `aud`                        string hoặc array of    Audience dành riêng cho S02; giá
                               strings                 trị đề xuất `livingdocs-s02`. S02
                                                       phải từ chối token chỉ có
                                                       `livingdocs-api`.

  `iat`                        NumericDate             Thời điểm phát hành.

  `nbf`                        NumericDate             Thời điểm bắt đầu hiệu lực; nếu có
                                                       thì phải được kiểm tra.

  `exp`                        NumericDate             Thời điểm hết hạn; bắt buộc kiểm
                                                       tra.

  `jti`                        string                  Token identifier. Hữu ích cho
                                                       audit/tracing hoặc revocation nếu
                                                       hệ thống có cơ chế tra cứu tương
                                                       ứng; không tự tạo replay protection
                                                       nếu không có stateful control.

  `client_id`                  string                  Định danh OAuth/service client đã
                                                       được S01 xác thực. Phải phân biệt
                                                       client gọi token endpoint với
                                                       resource server nhận token (`aud`).

  `principal_type`             string                  Claim phân biệt service principal
                                                       với user principal; giá trị ứng
                                                       viên `service`.

  `scope`                      string                  Danh sách scope theo dạng
                                                       space-delimited; ví dụ
                                                       `source:resolve source:validate`.
                                                       S02 chỉ cho phép operation nằm
                                                       trong scope.

  `tenant_id`                  UUID string             Tenant được S01 cấp cho service
                                                       principal trong token này.

  `workspace_id`               UUID string             Workspace được cấp trong tenant
                                                       tương ứng.

  `repository_connection_id`   UUID string             Repository connection cụ thể mà
                                                       token được phép thao tác trong S02.
  ----------------------------------------------------------------------------------------

**Lưu ý về claim names:** `principal_type`, `tenant_id`, `workspace_id`
và `repository_connection_id` là custom claims đề xuất. S01/S02 cần
thống nhất chính xác tên, kiểu dữ liệu và quy tắc phát hành trước khi
viết mapper production.

### 4.3. Payload minh họa --- chưa phải token contract đã chốt

``` json
{
  "iss": "urn:livingdocs:s01",
  "sub": "s04-workload",
  "aud": ["livingdocs-s02"],
  "iat": 1795170000,
  "nbf": 1795170000,
  "exp": 1795170300,
  "jti": "example-token-id",
  "client_id": "s04",
  "principal_type": "service",
  "scope": "source:resolve source:validate",
  "tenant_id": "11111111-1111-4111-8111-111111111111",
  "workspace_id": "22222222-2222-4222-8222-222222222222",
  "repository_connection_id": "33333333-3333-4333-8333-333333333333"
}
```

Các giá trị trong ví dụ chỉ minh họa cấu trúc. Không dùng các ID,
subject, issuer/audience hoặc thời hạn này làm cấu hình production nếu
chưa được các owner phê duyệt.

### 4.4. Header và signing

Đề xuất giữ cơ chế bất đối xứng đang có tại S01:

-   Signing algorithm: `RS256`, nếu S01 tiếp tục duy trì cấu hình hiện
    tại.
-   JOSE header phải có `kid` để S02 chọn public key.
-   S02 chỉ allowlist thuật toán đã thống nhất; không lấy thuật toán từ
    token làm cấu hình tin cậy.
-   JWKS chỉ chứa public key.
-   Key rotation phải có khoảng overlap đủ để token đã phát hành bằng
    key cũ được verify đến khi hết hạn.
-   S02 phải xử lý JWKS refresh/cache theo chính sách thống nhất; không
    fallback sang key không xác minh được.

Nếu các bên chọn áp dụng profile JWT Access Token theo RFC 9068 thì cần
tuân thủ profile đó đầy đủ, bao gồm `typ: at+jwt` và các claim/header
bắt buộc tương ứng. Không tuyên bố token tuân thủ RFC 9068 chỉ vì có một
số claim trùng tên.

## 5. Quy tắc cấp token và authorization

### 5.1. Trách nhiệm S01 khi cấp token

S01 cần:

1.  Đăng ký và quản lý service client/workload identity của S03/S04.
2.  Xác thực service client bằng cơ chế được security platform phê
    duyệt.
3.  Xác định service client có được phép xin scope và resource tương ứng
    hay không.
4.  Lấy tenant/workspace/repository grant từ nguồn authorization tin
    cậy; không tin các ID do caller tự khai báo.
5.  Chỉ phát hành scope và resource restriction nằm trong grant đã xác
    minh.
6.  Ký token bằng key được quản lý an toàn và công bố public key qua
    JWKS.
7.  Định nghĩa TTL, clock skew, key rotation và hành vi khi client/grant
    bị thu hồi.
8.  Cung cấp tài liệu về token endpoint, request/response và lỗi cấp
    token cho consumer.

### 5.2. Trách nhiệm S02 khi nhận request

Với mỗi request tới internal API, S02 cần thực hiện tối thiểu:

1.  Yêu cầu Bearer Service Access Token.
2.  Verify chữ ký, thuật toán allowlist, `kid`, issuer, audience và thời
    hạn.
3.  Từ chối user token hoặc token có `principal_type` không phải
    service.
4.  Kiểm tra scope tương ứng với operation:
    -   `POST /internal/v1/source/resolve` cần `source:resolve`.
    -   `POST /internal/v1/source/validate` cần `source:validate`.
5.  Đọc tenant/workspace/repository connection từ authenticated service
    identity đã map.
6.  So sánh `repositoryConnectionId` trong request với
    `repository_connection_id` trong token.
7.  Kiểm tra repository connection tồn tại, thuộc đúng tenant/workspace
    và đang ở trạng thái cho phép trong dữ liệu S02.
8.  Thiết lập tenant scope/RLS theo context đã xác minh.
9.  Chỉ gọi GitHub sau khi authentication, operation authorization và
    resource authorization thành công.

Token không thay thế kiểm tra trạng thái connection hoặc ownership trong
database S02. Việc token có scope không cho phép service truy cập trực
tiếp database hay GitHub credentials.

### 5.3. Request body và headers

Request body của hai API tiếp tục chỉ mang dữ liệu nghiệp vụ và
repository connection ID theo OpenAPI S02 hiện hành.

-   Không thêm `tenantId`, `workspaceId`, `role` hoặc `permissions` vào
    body để làm căn cứ cấp quyền.
-   Không dùng `X-Tenant-ID`, `X-Workspace-ID`, `X-User-Role` hoặc
    identity headers tương tự để cấp quyền.
-   Tenant/workspace lấy từ authenticated service identity.
-   `repositoryConnectionId` trong body phải khớp resource claim trong
    token và bản ghi connection trong S02.

## 6. Trách nhiệm theo service

  -----------------------------------------------------------------------
  Thành phần                          Trách nhiệm
  ----------------------------------- -----------------------------------
  **S01**                             Quản lý service client identity;
                                      xác thực client; đánh giá grant;
                                      phát hành Service Access Token; ký
                                      token; công bố JWKS; quản lý
                                      rotation và chính sách token.

  **S02**                             Verify Service Access Token; map
                                      claim vào
                                      `SourceServiceAuthentication`;
                                      enforce operation scope và resource
                                      scope; kiểm tra ownership/trạng
                                      thái connection; áp dụng tenant
                                      isolation/RLS; fail-closed khi
                                      thiếu trust.

  **S03/S04 --- consumer**            Được đăng ký làm service client;
                                      xin token theo cơ chế S01 cung cấp;
                                      gọi đúng HTTPS internal endpoint;
                                      gửi token trong Authorization
                                      Bearer; chỉ gọi operation/resource
                                      được cấp; không log hoặc lưu token
                                      không an toàn.

  **API Gateway / platform**          Giữ internal route ngoài public
                                      exposure; duy trì trust/network/TLS
                                      policy; không cho identity headers
                                      từ client trở thành nguồn
                                      authorization.
  -----------------------------------------------------------------------

## 7. HTTP security behavior đề xuất

Các status dưới đây phải được đối chiếu với Problem Details và error
codes đã định nghĩa trong OpenAPI S02. Không tạo thêm mã lỗi trùng nghĩa
nếu contract hiện tại đã có mã tương ứng.

  -----------------------------------------------------------------------
  Tình huống                          Kết quả mong đợi
  ----------------------------------- -----------------------------------
  Thiếu Bearer token                  `401 INVALID_SERVICE_IDENTITY`

  Token sai chữ ký, hết hạn, sai      `401 INVALID_SERVICE_IDENTITY`
  issuer/audience hoặc sai loại       
  principal                           

  Thiếu scope cho operation           `403 FORBIDDEN_SCOPE`

  Token không bao phủ repository      `403 FORBIDDEN_SCOPE`
  connection trong request            

  Connection không tồn tại trong      Dùng response không tiết lộ sự tồn
  tenant/workspace đã xác minh hoặc   tại, theo quy tắc S02 hiện hành
  không được phép lộ ra ngoài         (`404 UNKNOWN_CONNECTION` khi áp
                                      dụng).

  Trust/JWKS/decoder/production claim Fail-closed; không cấp quyền. Dùng
  mapper chưa được cấu hình           lỗi dependency/configuration theo
                                      error contract S02 hiện hành.

  Token hợp lệ nhưng connection không Trả lỗi nghiệp vụ theo contract
  còn ở trạng thái cho phép           S02; không gọi GitHub.
  -----------------------------------------------------------------------

Response lỗi cần giữ Problem Details, correlation ID và
`WWW-Authenticate: Bearer` cho `401` theo HTTP contract hiện hành. Không
đưa access token, private key, credential hoặc raw upstream error vào
response/log thông thường.

## 8. Cấu hình và vận hành

Các giá trị cụ thể cần lấy từ environment/secret manager của môi trường
triển khai, không hard-code trong source.

  Cấu hình                                           Bên cung cấp/xác nhận
  -------------------------------------------------- ---------------------------------
  Service-token issuer                               S01
  JWKS URI và CA/trust path nếu cần                  S01/platform
  S02 audience                                       S01 + S02 thống nhất
  Signing algorithm và key ID policy                 S01 + S02
  Service client ID / principal mapping              S01
  Scope names và semantics                           S01 + S02 + consumer
  Tenant/workspace/repository claim names và types   S01 + S02
  Token TTL và clock skew                            Security owner + S01 + S02
  Service client authentication method               Security platform + S01
  Key rotation/overlap/JWKS cache behavior           S01 + S02/platform
  Revocation behavior                                Security owner + S01 + consumer

Không ghi token hoặc private key vào log. Log chỉ nên chứa service
principal ID đã lọc, operation, scope decision, correlation ID, kết quả
và lý do từ chối không nhạy cảm.

## 9. Test matrix tích hợp tối thiểu

  ----------------------------------------------------------------------------
  Nhóm                    Test case                    Kết quả cần xác nhận
  ----------------------- ---------------------------- -----------------------
  Token presence          Không có token / Bearer rỗng `401`; không đọc hoặc
                                                       xử lý nghiệp vụ.

  Signature               Sai chữ ký / key không tồn   `401`; không gọi GitHub
                          tại / `kid` không resolve    hoặc ghi database.
                          được                         

  Standard claims         Sai `iss`, sai `aud`, hết    `401`.
                          hạn, `nbf` trong tương lai   

  Principal               User Access Token dùng gọi   `401`; user token không
                          internal API; principal type được coi là service
                          thiếu/sai                    identity.

  Operation scope         Chỉ có `source:resolve` gọi  `403`; không lookup
                          validate; token thiếu scope  connection/GitHub.

  Resource scope          Token cấp connection A nhưng `403`; không tiết lộ dữ
                          request connection B         liệu B.

  Tenant/workspace        Tenant/workspace token không Từ chối theo error
                          khớp ownership của           contract S02; không
                          connection                   truy cập dữ liệu ngoài
                                                       scope.

  Local ownership         Connection không tồn tại,    Kết quả theo business
                          inactive hoặc đã đổi liên    contract S02; không gọi
                          kết                          GitHub nếu chưa đủ điều
                                                       kiện.

  Header spoofing         Gửi `X-Tenant-ID`,           Các header không làm
                          `X-Workspace-ID`,            thay đổi authenticated
                          `X-User-Role` giả            scope hoặc
                                                       authorization result.

  JWKS rotation           Token ký bằng current key và Cả hai verify đúng
                          previous key trong overlap   trong thời hạn policy;
                                                       key hết overlap bị từ
                                                       chối.

  Fail closed             Thiếu                        Không cấp quyền; lỗi
                          issuer/JWKS/decoder/mapper   cấu hình/dependency
                          production config            theo contract.

  Consumer integration    S04/S03 lấy token từ S01 và  Resolve/validate thành
                          gọi S02 qua HTTPS            công chỉ với
                                                       scope/resource được
                                                       cấp.

  Negative end-to-end     S04/S03 dùng sai token,      Request bị từ chối;
                          scope hoặc resource          không tạo side effect
                                                       ngoài phạm vi.
  ----------------------------------------------------------------------------

Mỗi test cần lưu commit, môi trường, thời điểm, request đã lọc
token/secret, response, correlation ID và kết quả kiểm tra side effect.
Không dùng test issuer hoặc `test_*` claims để chứng minh production
integration.

## 10. Điều kiện nghiệm thu

### 10.1. S01

-   [ ] Owner S01 phê duyệt service-token profile và claim names/types.
-   [ ] Có cơ chế đăng ký và xác thực service client.
-   [ ] Có quyết định rõ về grant evaluation cho operation + tenant +
    workspace + repository connection.
-   [ ] Có token issuance contract và lỗi tương ứng.
-   [ ] JWKS, signing key, `kid`, rotation và TTL được tài liệu hóa.
-   [ ] Không phát hành quyền dựa trên tenant/workspace/resource do
    caller tự khai báo mà chưa được xác minh.

### 10.2. S02

-   [ ] Production `JwtDecoder` được cấu hình với issuer/JWKS/audience
    tin cậy.
-   [ ] `SourceServiceIdentityMapper` map đúng production claims; không
    phụ thuộc `test_*`.
-   [ ] Chỉ chấp nhận service principal và operation scope phù hợp.
-   [ ] Token resource scope khớp `repositoryConnectionId` trong
    request.
-   [ ] Tenant/workspace lấy từ authenticated identity và được kiểm tra
    với dữ liệu S02.
-   [ ] RLS và local ownership/status checks vẫn được áp dụng.
-   [ ] Thiếu trust hoặc claim mapping thì fail-closed.
-   [ ] TLS thật, JWKS rotation và negative tests được chạy trong môi
    trường tích hợp.

### 10.3. Consumer S03/S04

-   [ ] Consumer được S01 đăng ký/cấp quyền.
-   [ ] Consumer lấy token theo flow đã thống nhất và không tự ký token.
-   [ ] Consumer gọi đúng HTTPS endpoint, audience, scope và resource.
-   [ ] Token không xuất hiện trong log, URL hoặc response nghiệp vụ.
-   [ ] Có integration test thành công và negative end-to-end.

Chỉ đánh dấu phần tích hợp production hoàn thành khi có bằng chứng từ
S01, S02 và ít nhất một consumer thật. HTTP local tests của S02 là bằng
chứng cho implementation local, không thay thế nghiệm thu liên dịch vụ.

## 11. Các quyết định còn mở

  ----------------------------------------------------------------------------------
  \#                Câu hỏi cần chốt             Owner chính       Trạng thái
  ----------------- ---------------------------- ----------------- -----------------
  1                 Service Access Token dùng    S01               OPEN
                    issuer hiện tại                                
                    `urn:livingdocs:s01` hay                       
                    issuer riêng?                                  

  2                 Token endpoint và grant flow S01/platform      OPEN
                    nào được sử dụng?                              

  3                 Service client xác thực bằng Security          OPEN
                    mTLS, private-key JWT hay    platform + S01    
                    workload identity?                             

  4                 `sub` biểu diễn service      S01               OPEN
                    principal theo định dạng                       
                    nào?                                           

  5                 Có chốt `principal_type`     S01 + S02         OPEN
                    không; tên và giá trị chính                    
                    xác là gì?                                     

  6                 Scope là claim `scope` dạng  S01 + S02         OPEN
                    space-delimited hay format                     
                    khác?                                          

  7                 Chốt tên/kiểu `tenant_id`,   S01 + S02         OPEN
                    `workspace_id`,                                
                    `repository_connection_id`                     
                    hay resource claim khác?                       

  8                 Một token giới hạn một       S01 + S02 +       OPEN
                    repository connection hay hỗ consumer          
                    trợ nhiều resource?                            

  9                 Token TTL, clock skew,       Security owner +  OPEN
                    revocation và JWKS           S01 + S02         
                    cache/rotation policy?                         

  10                S03 và S04 service client    S01 + S03/S04     OPEN
                    nào cần `source:resolve` và                    
                    `source:validate`?                             

  11                Có áp dụng đầy đủ RFC 9068   Security owner +  OPEN
                    JWT Access Token Profile     S01               
                    không?                                         
  ----------------------------------------------------------------------------------

Không triển khai mapper production dựa trên các giá trị đang để OPEN.

## 12. Trình tự triển khai đề xuất

1.  **S01/S02 review contract:** Chốt claim names/types, issuer,
    audience, scope, resource restriction và client authentication.
2.  **S01 implementation:** Cung cấp service client registration, token
    issuance và JWKS/rotation theo contract.
3.  **S02 adapter:** Cấu hình production decoder và implement
    `SourceServiceIdentityMapper`; giữ fail-closed trong toàn bộ giai
    đoạn chuyển tiếp.
4.  **Consumer integration:** S03/S04 lấy token thật và gọi đúng API nội
    bộ.
5.  **Joint verification:** Chạy test matrix, TLS/JWKS rotation,
    negative authorization và end-to-end.
6.  **Evidence and sign-off:** Lưu kết quả, commit, correlation IDs, môi
    trường và xác nhận của owner liên quan.

Không cần viết lại User Login/JWT hiện tại để thực hiện các bước trên.
Chỉ mở rộng S01 theo service-token contract đã được thống nhất.

## 13. Tài liệu và code tham chiếu

### S01 / Gateway trên `develop`

-   [JwtTokenIssuer.java](https://github.com/huutienSE/LivingDocs/blob/develop/backend/identity-organization-service/src/main/java/com/livingdocs/identity/security/JwtTokenIssuer.java)
-   [JwtKeyManager.java](https://github.com/huutienSE/LivingDocs/blob/develop/backend/identity-organization-service/src/main/java/com/livingdocs/identity/security/JwtKeyManager.java)
-   [JwksController.java](https://github.com/huutienSE/LivingDocs/blob/develop/backend/identity-organization-service/src/main/java/com/livingdocs/identity/controller/JwksController.java)
-   [JwtIssuerTest.java](https://github.com/huutienSE/LivingDocs/blob/develop/backend/identity-organization-service/src/test/java/com/livingdocs/identity/JwtIssuerTest.java)
-   [V2 tenant/membership
    migration](https://github.com/huutienSE/LivingDocs/blob/develop/backend/identity-organization-service/src/main/resources/db/migration/V2__create_tenant_membership_tables.sql)
-   [TrustedIdentityHeaderFilter.java](https://github.com/huutienSE/LivingDocs/blob/develop/infrastructure/api-gateway/src/main/java/com/livingdocs/gateway/filter/TrustedIdentityHeaderFilter.java)
-   [S01
    OpenAPI](https://github.com/huutienSE/LivingDocs/blob/develop/contracts/openapi/s01-identity.yaml)

### S02 / LIV-122 implementation branch

-   [LIV-122 implementation
    status](https://github.com/huutienSE/LivingDocs/blob/feature/LIV-122-source-sha-validation/backend/repository-integration-service/LIV-122-source-validation.md)
-   [S02 LIV-122
    HLD](https://github.com/huutienSE/LivingDocs/blob/feature/LIV-122-source-sha-validation/docs/implementation/S02/S02-LIV-122-HLD-va-ke-hoach-trien-khai.md)
-   [S02
    OpenAPI](https://github.com/huutienSE/LivingDocs/blob/develop/contracts/openapi/s02-repository.yaml)

### Standards reference

-   [RFC 9068 --- JSON Web Token (JWT) Profile for OAuth 2.0 Access
    Tokens](https://www.rfc-editor.org/rfc/rfc9068)

------------------------------------------------------------------------

**Trạng thái cuối tài liệu:** DRAFT / OPEN FOR S01--S02 REVIEW.\
**Không dùng tài liệu này làm bằng chứng rằng Service Access Token
production đã được triển khai hoặc nghiệm thu.**
