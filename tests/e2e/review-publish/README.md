# LIV-119 · Staff Review — API & UI acceptance tests

Kiểm thử nghiệm thu cho **S3-US01-T02 (LIV-119)**: Staff Review qua API Gateway, tenant-scope.
AC: *Staff action visible, cross-tenant action denied with no state mutation.*

| File | Loại | Chạy với |
|---|---|---|
| `LIV-119-staff-review.postman_collection.json` | API qua Gateway (34 request, 77 assertion) | Newman |
| `staff-review.spec.ts` | UI E2E (6 kịch bản) | Playwright |
| `LIV-119-local.postman_environment.example.json` | Environment mẫu, **không có secret** | — |

Không mock service/DB trong các test này (LD-SPR3-GUIDE-001 §6.1). Unit/component test của UI nằm ở `frontend/` (Vitest).

## 1. Điều kiện chạy

Cần stack thật: PostgreSQL (Flyway S01/S04) + S01 (login/refresh) + S04 (API-115/116/120) + Gateway (route + JWT) — tức LIV-114/115/118 đã có.

### Seed data tối thiểu

| Biến | Dữ liệu |
|---|---|
| Tenant A, Tenant B | 2 tenant, mỗi tenant 1 workspace |
| `staffA*` / `LD_STAFF_A_*` | Identity có role **Staff** trong workspace của tenant A |
| `staffB*` / `LD_STAFF_B_*` | Identity có role **Staff** trong workspace của tenant B |
| `developerA*` / `LD_DEVELOPER_A_*` | Identity tenant A, **không** có role Staff |
| `versionIdAPass`, `versionIdAReturn` | 2 Document Version tenant A ở `IN_REVIEW / STAFF_REVIEW` (dùng cho Postman) |
| `LD_VERSION_A_UI_PASS`, `LD_VERSION_A_UI_RETURN` | 2 version **khác** cũng ở `STAFF_REVIEW` (dùng cho Playwright, vì test làm đổi state) |
| `versionIdB` / `LD_VERSION_B` | 1 version tenant B ở `STAFF_REVIEW` |

Các test PASS/RETURN làm thay đổi state, nên mỗi lần chạy cần reset seed (chạy lại Flyway + seed script).

## 2. API (Postman / Newman)

```bash
cd tests/e2e/review-publish
npm ci
cp LIV-119-local.postman_environment.example.json LIV-119-local.postman_environment.json   # file này đã nằm trong .gitignore
# điền password + versionId vào file vừa copy (KHÔNG commit)
npm run test:api
```

Trong CI, password lấy từ secret store, ví dụ: `newman run … -e env.json --env-var staffAPassword=$STAFF_A_PASSWORD`.

Nhóm test:
- **00** đăng nhập 3 actor (refresh token không được nằm trong JSON).
- **01** queue/detail đúng tenant, có strong ETag; queue của A không chứa version B.
- **02** thiếu token hoặc token sai → 401; header giả `X-User-Role`/`X-Tenant-Id` bị bỏ qua.
- **03** **cross-tenant**: B đọc/PASS/RETURN version của A (và ngược lại) → 403/404. Sau **mỗi** lần bị từ chối đều có bước *readback* xác nhận state và ETag **không đổi**.
- **04** Developer → 403; thiếu `If-Match` → 428; ETag cũ → 412; RETURN thiếu feedback, feedback chỉ có khoảng trắng, hoặc `REJECT` → 400/422; readback không đổi.
- **05** PASS → `MANAGER_APPROVAL` và có ETag mới; replay cùng key → cùng kết quả; cùng key nhưng payload khác → 409 `IDEMPOTENCY_CONFLICT`; PASS lần 2 → 409 `STATE_TRANSITION_FORBIDDEN`; version rời khỏi queue; RETURN → `DRAFT`.

## 3. UI (Playwright)

```bash
cd tests/e2e/review-publish
npm ci && npx playwright install chromium        # hoặc LD_CHROME_PATH=/path/to/chrome
export LD_GATEWAY_URL=http://127.0.0.1:8081
export LD_STAFF_A_EMAIL=… LD_STAFF_A_PASSWORD=… LD_STAFF_B_EMAIL=… LD_STAFF_B_PASSWORD=…
export LD_DEVELOPER_A_EMAIL=… LD_DEVELOPER_A_PASSWORD=…
export LD_VERSION_A_UI_PASS=… LD_VERSION_A_UI_RETURN=… LD_VERSION_B=…
npm run test:ui     # tự chạy Vite dev (frontend/) với proxy /api -> Gateway
```

Biến tùy chọn: `LD_UI_URL` (dùng UI đang chạy sẵn), `LD_CHROME_PATH`, `LD_PW_VIDEO=off`.

Kịch bản: S3-UI-01 PASS (hiển thị trên UI + readback DB qua API + rời queue) · S3-UI-02 RETURN (UI chặn khi thiếu feedback, sau đó về DRAFT) · S3-UI-10 cross-tenant qua UI (không thấy dữ liệu/nút, không có POST nào, B không đổi) · S3-UI-11 cross-tenant gọi API trực tiếp (403/404, B không đổi) · S3-UI-12 Developer bị từ chối · S3-UI-13 chưa đăng nhập bị chuyển về Login; JWT không nằm trong storage/URL.

## 4. Bằng chứng (evidence) cho Jira

- `results/newman-junit.xml`, `results/junit.xml`, `playwright-report/` (đã gitignore — đính kèm vào PR/Jira hoặc lấy từ artifact CI).
- Ghi kèm commit SHA, môi trường và thời điểm chạy, theo mẫu Sprint Test Report (LD-SPR3-GUIDE-001 §6.4).
