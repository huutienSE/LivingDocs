# LivingDocs — OpenAPI Sprint 3 (LIV-133)

## Nguồn và trạng thái

- Thiết kế nghiệp vụ: [LD-API-001 v0.4](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/11337744), LIV-80.
- Chuẩn hóa và phối hợp tích hợp: [LIV-133](https://huutiense25.atlassian.net/browse/LIV-133), parent LIV-100.
- Danh mục máy đọc được: `sprint3-route-inventory.json` (112 API; 91 public method/path được allowlist).
- Sai khác FE–Gateway–Service: `sprint3-inventory.md` và `docs/implementation/LIV-133-contract-alignment.md`.

**Lưu ý:** `CANDIDATE`, `PLANNED`, `IN_PROGRESS` không đồng nghĩa với đã có controller. `x-contract-state` đánh dấu tình trạng. Tên DTO có trong LD-API-001 chưa chắc đã có schema field-level; các operation như vậy sử dụng `CandidateObject` và bắt buộc owner xác nhận DTO trước khi codegen/đánh dấu API stable. Không tự nhận một YAML rộng với schema placeholder là hợp đồng đã hoàn tất.

## File theo service

| File | Nội dung |
| --- | --- |
| `s01-identity.yaml` | S01 auth/tenant/workspace/roles; cookie endpoints ở trạng thái blocked |
| `s02-repository.yaml` | S02 repository connections, GitHub, authoritative source SHA internal |
| `s03-intelligence.yaml` | S03 analysis jobs, drift, evidence (candidate) |
| `s04-document.yaml` | S04 version, review queue/context, approval, publish, export, collections |
| `s04-document-review.yaml` | API-120 Staff Decision thuộc PR LIV-118; **không duplicate** vào `s04-document.yaml`, sau merge file từ PR #20 trở thành authoritative |
| `s05-ai.yaml` | Health baseline runtime S05; AI internal candidate, không route Gateway |

## Security & routing

- Public `/api/v1/` dùng explicit Method + Path; cấm route wildcard `/api/v1/**`.
- Gateway coarse authentication; S01–S05 vẫn xác thực và kiểm resource/business authorization bên trong service.
- **Không public**: `/internal/v1/**`, `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/openapi.json`, S05 internal APIs.
- GitHub webhook API-068 vẫn bị chặn khi chưa có S02 HMAC raw body, replay/duplicate guard.
- Cookie-bearing `/api/v1/auth/refresh`, `/logout`, OAuth start/callback **chưa đưa lên Gateway** đến khi S01 session rotation + CSRF + CORS credentials đã được tích hợp và kiểm chứng. Không bật `allowCredentials=true` hoặc public refresh để “chạy demo”.
- API candidate tuy đã định tuyến có thể trả 404 từ downstream, không phải PASS nghiệp vụ. Nếu service chưa chạy, có thể trả 503.
- S03 DNS trong Compose Gateway phải dùng `http://source-intelligence-service:8080`, không dùng loopback giữa các container.

## Quy ước HTTP

- RFC 9457: `application/problem+json`, mở rộng `code` và `correlationId`.
- GET version/review context và protected mutation phải trả strong `ETag`; `If-Match` thiếu → 428, stale → 412.
- `Idempotency-Key` cho quyết định review/approval/publish và async jobs: cùng key + payload khác → 409.
- EvidenceRefs của Staff Review là **S04 `document_evidence_id` UUID**, đúng tenant và version; không gửi source path/link tự do.
- Bảo vệ quyền tenant/workspace/repository/role và current authorization của Manager/Publish phải được thực thi trong service.
- TLS/HTTPS bắt buộc ở staging/demo/production theo LD-API-001 §2.2; local loopback HTTP không phải bằng chứng TLS.

## CI, bằng chứng và quy trình cập nhật

Workflow `.github/workflows/openapi-contract-ci.yaml`:
1. Lint/validate OpenAPI YAML 3.0.3;
2. Chạy `scripts/validate_gateway_openapi.py` để so các route Gateway với inventory, chặn đường dẫn internal/webhook/cookie-sensitive;
3. Không coi syntax validation là E2E PASS.

**macOS** — từ root repo:

```bash
python3 -m pip install openapi-spec-validator==0.7.2 PyYAML==6.0.3
python3 scripts/validate_gateway_openapi.py
```

**Windows PowerShell** — từ root repo:

```powershell
py -3.12 -m pip install openapi-spec-validator==0.7.2 PyYAML==6.0.3
py -3.12 scripts/validate_gateway_openapi.py
```

Khi một API được triển khai: update OpenAPI, DTO, Gateway allowlist, frontend caller và regression test trong cùng đợt tích hợp, đồng thời chuyển trạng thái inventory sau khi có evidence; owner từng service phê duyệt contract. Không tự merge API-115/116 nếu chưa có S04 implementation.
