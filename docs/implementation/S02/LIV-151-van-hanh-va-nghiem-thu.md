# LIV-151 — Luồng thực thi và checklist nghiệm thu

Phạm vi: API-046 và năm operation onboarding theo thiết kế B v1 đã duyệt. API-047 vẫn chịu trách nhiệm xác minh lại người dùng, installation và repository tại thời điểm connect. API-046 không tạo connection/grant hoặc cam kết repository đã sẵn sàng.

## Luồng tổng thể

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant FE as FE cùng origin HTTPS
    participant G as Gateway
    participant S as S02
    participant A as S01 API-038
    participant DB as PostgreSQL S02 / RLS
    participant GH as GitHub
    FE->>G: GET authorization?workspaceId + JWT S01
    G->>S: Xác thực JWT, bỏ identity headers tự khai
    S->>A: Cùng Bearer đã xác thực, lấy quyền hiện hành
    S->>GH: App JWT: GET /app (cache metadata <=30s)
    S-->>FE: Required profile, USER_AUTHORIZATION_REQUIRED
    FE->>S: POST authorization-contexts, Origin chính xác
    S->>A: Kiểm tra lại Developer/TL + WORKSPACE_VIEW
    S->>DB: RLS, cap 3, state/nonce/PKCE mã hóa, TTL <=15 phút
    S-->>FE: 201, authorizationUrl, installationUrl, nonce cookie
    FE->>GH: Chuyển trình duyệt tới authorizationUrl
    U->>GH: Chấp thuận hoặc từ chối
    GH->>G: GET callback?state&code (hoặc error), nonce cookie
    G->>S: Callback GET duy nhất không yêu cầu JWT LivingDocs
    S->>DB: Kiểm state/cookie/binding; CAS PENDING -> EXCHANGING
    S->>GH: Đổi code + PKCE; GET /user
    S->>DB: CAS -> AUTHORIZED, chỉ lưu user token đã mã hóa
    S-->>FE: 303 tới completion cố định, chỉ contextId
    FE->>S: GET context installations?page, JWT S01
    S->>A: Quyền workspace hiện hành
    S->>GH: User token: một trang GET /user/installations
    S-->>FE: Các installation người dùng được truy cập
    FE->>S: GET authorization?workspaceId&authorizationContextId&installationId&installationPage
    S->>A: Kiểm scope/owner/context còn hiệu lực
    S->>GH: User token: đọc lại đúng trang chứa lựa chọn
    Note over S,GH: Không có ID trên trang -> 409, chưa App probe
    S->>GH: App JWT: GET /app/installations/{id}
    S->>S: Đối chiếu App/account, so sánh category/name/level
    S->>A: Kiểm lại scope/context sau provider
    S-->>FE: VERIFIED_SUFFICIENT / VERIFIED_INSUFFICIENT / INSTALLATION_SUSPENDED
    FE->>S: GET context installations/{id}/repositories?page
    S->>GH: User token, chỉ một trang; không lấy quyền App thay người dùng
    FE->>S: DELETE context (hủy lặp lại vẫn 204 nếu đúng owner)
    S->>DB: Xóa credential mã hóa khỏi context
```

## Cấu hình và App thật

Tái sử dụng App của LIV-122, evidence SHA lịch sử trong [biên bản kiểm thử](LIV-122-Kiem-thu-GitHub-that.md). Không cần tạo App mới. App JWT dùng `LIVINGDOCS_SOURCE_GITHUB_CLIENTID` và key directory hiện hữu; `LIVINGDOCS_GITHUB_AUTHORIZATION_APP_KEY_REFERENCE` chọn tên file khi có nhiều key. Không đưa key/token/secret vào repository hoặc collection.

App registration cần đúng repository permissions `metadata:read`, `contents:write`, `pull_requests:write`, `checks:write` và events `push`, `pull_request`. Server đọc metadata thật bằng `GET /app`; sai profile trả 503 cấu hình thay vì công bố quyền giả. Đổi quyền App là thao tác quản trị bên ngoài và cần Owner chấp thuận riêng; installation có thể cần chấp thuận bộ quyền mới.

Các tham chiếu bổ sung: pinned S01 JWKS HTTPS, pinned S01 base HTTPS, browser origin HTTPS, callback HTTPS `/api/v1/integrations/github/authorization/callback`, completion HTTPS cùng origin và không có query, client-secret file, hai thư mục AES key riêng với `<version>.key` (Base64 của 32 byte). Giữ key version cũ khi còn context dùng chúng. Không dùng App private key làm AES key. User access token hết hạn được yêu cầu cấp quyền lại; không lưu refresh token.

Compose chỉ mount secret directory read-only. Bố cục: `app/<filename>.pem`, `client-secret`, `state/v1.key`, `credential/v1.key`; cấu hình `S02_GITHUB_SECRETS_DIR` trỏ ra ngoài repository. Các biến HTTPS phải trỏ tới địa chỉ tin cậy; không đổi thành HTTP Docker DNS để vượt kiểm tra trust. Triển khai TLS trực tiếp hoặc cấu hình trust proxy giới hạn ở hạ tầng đã duyệt; không bật tin mọi X-Forwarded-* từ client. Local fixture chỉ bật `allow-local-http` trên loopback.

Gateway chỉ mở đúng method/path của sáu operation. Callback dùng state + cookie, các operation còn lại yêu cầu JWT S01. Không mở S01 OAuth callback hoặc source workload routes. CORS credentials vẫn false: FE onboarding chạy cùng origin với Gateway. Response timeout route 12 giây, filter bao trùm cả response body; các route khác giữ 5 giây. Nếu body đã commit rồi bị timeout, kết nối bị hủy, không viết chèn Problem vào JSON dở.

## Checklist kiểm thử cuối task

Chỉ chạy sau khi code toàn bộ nghiệp vụ xong. File test có commit riêng. PASS fixture phải ghi rõ ranh giới giả lập; không thay bằng bằng chứng provider thật.

| Nhóm | Điều kiện phải kiểm | Bằng chứng cần ghi |
| --- | --- | --- |
| JWT/S01 | RS256/kid/issuer/audience/sub/time; quyền Developer/TL, tenant/workspace; revoke; malformed projection; deadline | S02 HTTP suite, số ca và log |
| HTTP | method/query/body/Accept/Origin, POST 4KiB, callback/query bounds; HTTP/2 END_STREAM; 401/403/409/429/503, Retry-After/no-store/correlation | HTTP và schema fixtures |
| Context | cap 3 nguyên tử; CAS/replay; cookie/state/PKCE/AAD; owner/tenant/ws; cancel/expiry/config race; không resurrect | PostgreSQL thật, RLS runtime/migrator |
| Maintenance | chỉ expired purge, active giữ nguyên; runtime không unscoped SELECT/BYPASSRLS | Persistence suite |
| Discovery | user token, 100/trang; Link/count/pin; selection đổi ->409 trước App probe; repo pull=false không công bố readable | Adapter và orchestration suite |
| Permission | đủ/thiếu/suspended; higher/lower levels; extra categories; unknown schema fail closed; không cache verified grants | HTTP/domain/provider suite |
| Provider faults | revoked user token ->409, App 401/404/403/429/5xx/timeout/redirect/malformed ->503; không FE logout | Adapter suite |
| OpenAPI | 6 operation, static/runtime/DTO/HTTP, 201/204/303, 6 verification states, inventory exact method/path | Python validators và runtime artifact |
| Gateway | callback GET không JWT; operation khác 401; wrong method/nearby paths không public; Bearer/cookie/Origin/correlation giữ đúng; 12s whole-body | Gateway suite và TCP tests |
| No mutation/secrets | GET không ghi connection/grant; không token/ref/key/refresh trong response/log/evidence | DB assertions và artifact scan |
| Hồi quy LIV-122 | token vẫn chỉ contents/PR read + một repository, source JWT riêng, SHA/ref vẫn đúng | Full S02 suite; live nếu cấu hình hiện hữu cho phép |
| E2E thật | JWT S01 thật qua Gateway, consent browser, callback, discovery, đủ/thiếu quyền trên chính App LIV-122 | Biên bản riêng; NOT RUN/BLOCKED nếu chưa đủ cấu hình |

## Quy tắc diễn giải kết quả

Trước consent chỉ có verification.status. Verified branch mới có installation, grantedPermissions, missingPermissions, checkedAt và repositoryAccessVerified=false. `VERIFIED_SUFFICIENT` không đồng nghĩa đã connect hoặc đã xác minh repository cụ thể. Không dùng NOT_INSTALLED khi GitHub 404; không dùng READY. Hoàn tất code hoặc suite fixture PASS chưa đủ kết luận nghiệm thu E2E thật.
