# Flow 1 — Tích hợp repo grant S01, source S02 và LIV-151

Ngày xác nhận: **28/11/2026**. Hoàn thiện luồng xuyên service trước, kiểm thử tổng sau; không tiếp tục chia thành lát phát triển hoặc lặp kiểm thử từng lát.

## Kết quả đã chạy

**PASS E2E local:** S01 và Gateway thật, S02 production receiver thật, PostgreSQL 18/RLS và Redis riêng, GitHub App LIV-122 thật. Không dùng controlled receiver để thay S02. [Evidence đã lọc bí mật](LIV-151-Flow1-E2E-evidence.json).

1. Register/Login thật qua Gateway; fixture authority được seed trong database test riêng.
2. Technical Lead request Grant → ACTIVE/PENDING, effective=false; self-verify bị từ chối.
3. Owner verify → S01 tự ký Service JWT → S02 đọc connection binding hiện hành → ACTIVE/VERIFIED, effective=true.
4. API-038 đưa connection vào repositoryIds; API-039 DOCUMENT_AUTHOR trả ALLOW khi có đủ quyền workspace và Grant.
5. S01 cấp JWT cho S03 resolve và S04 validate → S02 gọi GitHub thật, trả SHA `351ee46328288729148635e218491be161767f77` và receipt PASS.
6. Sai operation, user token dùng cho source, sai connection, public internal route và SHA cũ bị từ chối đúng status.
7. Disconnect → Grant không hiệu lực. Khôi phục connection → Grant VERIFIED cũ vẫn không hồi sinh. Revoke → API-039 tiếp tục DENY.

Lượt cuối dùng database `livingdocs_liv151_flow1_1128023114`; chỉ tạo dữ liệu riêng cho fixture. Không đổi App settings, repository GitHub hoặc database của Owner. Process S01/S02/Gateway, Redis và PostgreSQL fixture đã dừng sau lượt chạy. Database và log giữ lại để đối chiếu.

Giới hạn: đây là HTTP loopback local, chưa nghiệm thu TLS production; authority và connection là fixture có chủ đích, chưa đi qua API-047. Không chạy vòng đời Document/review/publish của S04 hoặc toàn Golden Flow 1. OAuth consent browser của LIV-151 chưa chạy thật vì [App registration còn thiếu profile đã duyệt](LIV-151-cau-hinh-App-that-can-ap-dung.md).

## Điểm nối các service

| Bên gọi → bên nhận | Contract và trách nhiệm |
| --- | --- |
| FE/Gateway → API-046 S02 | User JWT S01 + workspace; current API-038 policy; context/installation proof; required và verified permissions tách riêng. Chưa có connection thì không đòi repo grant. |
| S01 → S02 binding | POST `/internal/v1/repository-bindings/verify`; Service JWT `workload:s01`, operation `repository-binding:verify`, exact POST/path và tenant/workspace/connection claims trùng body. 200 ACTIVE với positive bindingRevision và observedAt; 404/409 invalid; 503 unavailable. Không public qua Gateway. |
| S03 → S02 source | Xin JWT S01 operation `source:resolve`, subject workload:s03, audience livingdocs-s02-internal, exact POST `/internal/v1/source/resolve`, tenant/workspace/connection scope. S02 giữ ownership GitHub credential và authoritative source. |
| S04 → S01 authority | Với Document action, gửi User JWT cùng Service Assertion operation authorization:evaluate; actor_token_sha256 phải gắn đúng raw user token, action và scope. S01 sở hữu authority và effective repo grant; S04 không lấy API-038 snapshot cũ để thay đánh giá hiện hành. |
| S04 → S02 source | Xin JWT S01 operation source:validate, workload:s04, exact POST `/internal/v1/source/validate`. Gửi repositoryConnectionId, targetKind/ref, boundHeadSha, configRevision. Chỉ dùng receipt PASS đúng scope/target/SHA/config và còn hạn. |
| API-047 tương lai | REPOSITORY_CONNECT dựa workspace permission, chưa đòi grant cho connection chưa tồn tại. Xác minh lại user/installation/repository tại lúc connect; không lấy VERIFIED_SUFFICIENT làm repository authorization. |

S01 đã bàn giao contract ở [PR2](../authorization/LIV-138-pr2-service-jwt-fresh-authorization.md) và [PR3](../authorization/LIV-162-pr3-grant-lifecycle.md). Receiver S02 hiện đã thay được controlled receiver trong luồng chạy local kể trên; các giới hạn E2E lịch sử trong runbook PR3 không phải kết quả mới.

## Tính nhất quán binding

V5 không sửa V1–V4. `binding_revision` được cấp khi đã giữ khóa repository row. GitHub mutation nâng parent lock để chặn FK insert/rebind trong lúc invalidation, rồi cập nhật các repository theo thứ tự khóa. `binding_parent_revision` lưu phiên bản parent đã quan sát; receiver so lại với parent hiện hành để fail closed nếu snapshot REPEATABLE READ bỏ sót child mới. Mismatch trả 409 CONFIG_CHANGED, không phát positive ACTIVE evidence. Thao tác reconnect/config update có chủ đích tạo revision mới; không khôi phục Grant VERIFIED cũ.

Không dùng max của hai revisions độc lập vì sequence có thể cấp trước commit. Regression đã kiểm commit visibility, NOWAIT lock và child nằm ngoài snapshot. S01 không truy cập database S02, S02 không truy cập database S01.

## Cấu hình runtime

S02: bật `LIVINGDOCS_SOURCE_S01_ENABLED=true`, đặt `LIVINGDOCS_SOURCE_S01_JWK_SET_URI` về JWKS HTTPS tin cậy của S01. Profile cố định RS256, issuer `urn:livingdocs:s01`, audience duy nhất `livingdocs-s02-internal`, JWT 60 giây, iat/nbf/exp/kid/jti và exact claims. Human JWT có profile riêng. Thiếu cấu hình vẫn fail closed.

Chỉ fixture loopback mới bật `LIVINGDOCS_SOURCE_S01_ALLOW_LOCAL_HTTP=true` và `livingdocs.source.http.allow-insecure-transport=true`. S01 cấu hình binding URL/audience/operation theo PR3. Production cần TLS ở đúng hop, không tin forwarding headers tùy ý. CI S02 hiện kiểm 5 Flyway migrations và 11 RLS tables.

## Tái chạy cùng một luồng

Runner Windows cần JDK 21+, Python 3, Docker/image redis:8.2-alpine, PostgreSQL binaries, App/key references hiện hữu và cluster test riêng `target/liv151-postgres-20261127` có role `liv151_test_admin`, `ld_s02_migrator`, `ld_s02_app`. Đây là fixture riêng đã có trong phiên, không trỏ runner vào cluster của Owner. `LIV151_PG_BIN` và `OPENSSL_BIN` cho phép chọn công cụ; `JAVA_HOME` chọn JDK. Không thay `.env` thật để chạy test.

```powershell
# Đứng ở repository root. Build outputs riêng, không đụng JAR đang chạy.
./tests/e2e/build-flow1-fixtures.ps1 -MavenCommand 'C:/path/to/mvn.cmd'
python tests/e2e/run_flow1_s01_s02.py
```

Nếu key directory có nhiều `.pem`, thêm `--key-reference ten-key-hien-huu.pem`; không truyền nội dung key. Runner chỉ đọc hai cấu hình public reference trong `.env`, runtime S02 nạp credential theo cơ chế đã có. Tên database test mới được tạo mỗi lượt. Các port 18480/18481/18482/18491/58479 phải trống; runner từ chối tiếp cận service có sẵn trên các port này. Không in JWT, Basic secret, App key hoặc credential reference vào evidence.

## Kiểm chứng cuối

| Hạng mục | Kết quả |
| --- | --- |
| Full S02 PostgreSQL + HTTP + provider + JWT + RLS | **260 ca, 0 failures/errors, 2 skipped live legacy**; 258 ca thực thi PASS, `target/liv151-full-final.log` |
| Receiver PR2/PR3 trust | **14/14 PASS**, gồm issuer URN thực, audience, thời hạn, claims và exact scope |
| Binding concurrency | **3/3 PASS**, gồm snapshot miss và cấp revision sau khóa |
| Gateway | **30/30 PASS**, `target/liv151-gateway-final.log` |
| OpenAPI/inventory/schema HTTP | **PASS**: 125 catalogued APIs, 101 exact public entries, 24 route groups; 6 operations LIV-151 khớp static/runtime và HTTP fixtures |
| E2E S01/Gateway/S02/GitHub | **PASS** 9 nhóm checks trong evidence JSON |
| Full Golden Flow 1 với S04 Document lifecycle | **NOT RUN**, không suy ra từ API-039/SHA PASS |
| LIV-151 consent browser + App profile | **BLOCKED cấu hình**, chưa thay App ngoài repository |
| Remote CI/deploy | **NOT RUN**, không push/deploy trong phiên |

Code và tests có commit riêng. Không ghi Task Done cho browser acceptance khi App chưa khớp; không tạo thêm quy trình lát để che phần cấu hình còn thiếu.
