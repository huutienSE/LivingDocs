# LivingDocs Frontend

React + TypeScript (Vite). Mọi lời gọi backend đi qua **API Gateway**: dev server proxy `/api` → `VITE_GATEWAY_URL`.

```bash
cp .env.example .env          # VITE_GATEWAY_URL=http://127.0.0.1:8081
npm ci
npm run dev                   # http://127.0.0.1:5173
npm test                      # Vitest + React Testing Library
npm run typecheck && npm run build
```

## Cấu trúc (theo SAD)

```
src/
├── app/            App shell, styles
├── routes/         routes + guards (RequireAuth)
├── api/client/     http.ts (Bearer, refresh, If-Match, Idempotency-Key), session.ts, problem.ts
└── features/
    ├── auth/       LoginPage
    └── review/     Staff Review — LIV-119 (API-115/116/120)
```

## Quy tắc bảo mật phía client

- Access JWT chỉ giữ trong memory. Không lưu vào localStorage/sessionStorage, không đưa lên URL. Refresh token là cookie HttpOnly do S01 cấp, JS không đọc được.
- Không bao giờ gửi header role hoặc tenant. Scope do server xác định từ token.
- Mutation gửi `If-Match` (ETag đọc được) và `Idempotency-Key`. Retry cùng một ý định dùng lại cùng key.
- 403 và 404 hiển thị cùng một thông báo trung tính, để không lộ việc tài liệu có tồn tại ở tenant khác.

Contract giả định và các điểm cần chốt: `docs/implementation/LIV-119-staff-review-contract-notes.md`.
