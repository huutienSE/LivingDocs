
# LivingDocs — OpenAPI Contracts

## 1. Mục đích

Thư mục này lưu các hợp đồng HTTP API của LivingDocs.

Nguồn thiết kế: LD-API-001 v0.4 (LIV-80).
Task chuẩn hóa Sprint 3: LIV-133.

## 2. Quy tắc quản lý

- Mỗi service sở hữu contract của chính mình.
- Frontend, Gateway và backend phải dùng thống nhất method, path, headers, DTO và error codes.
- Contract được sửa cùng PR khi có thay đổi API.
- Không coi API đã triển khai nếu mới có OpenAPI YAML.
- Không dùng API candidate làm bằng chứng E2E PASS.

## 3. Trạng thái API

- IMPLEMENTED: Có controller/runtime và bằng chứng kiểm thử.
- IN_PROGRESS: Đang triển khai trong PR hoặc branch.
- CANDIDATE: Đề xuất, chờ service owner xác nhận.
- PLANNED: Có trong kế hoạch nhưng chưa triển khai.

Trạng thái API được theo dõi trong `sprint3-inventory.md`.

## 4. Các service

| Service | Trách nhiệm |
| --- | --- |
| S01 | Identity, JWT, tenant và workspace |
| S02 | Repository integration, source validation |
| S03 | Source intelligence |
| S04 | Document review, approval, publish |
| S05 | AI semantic processing |

## 5. Quy ước hợp đồng

- Public API sử dụng prefix `/api/v1`.
- Không đưa `/internal/v1` hoặc Swagger nội bộ ra Gateway.
- Xác thực và phân quyền theo JWT, tenant, workspace và nghiệp vụ.
- Lỗi theo `application/problem+json`, có `code` và `correlationId`.
- Protected transitions phải mô tả `If-Match` và idempotency khi yêu cầu.
- Không lưu credentials hoặc dữ liệu nhạy cảm trong examples.
- Mọi thay đổi breaking phải được frontend và service owner xác nhận.

## Files và kiểm tra

- `s05-ai.yaml`: mô tả `GET /health` đã triển khai. `/metrics` không thuộc OpenAPI runtime do FastAPI cấu hình `include_in_schema=False`.
- `sprint3-inventory.md`: inventory và các mismatch phải giải quyết.
- `s04-document-review.yaml`: đang ở PR LIV-118; tiếp nhận khi PR merge, không tự ý ghi đè.

Workflow `.github/workflows/openapi-contract-ci.yaml` tự kiểm tra mọi `*.yaml` và `*.yml` ở thư mục này bằng `openapi-spec-validator`. Việc kiểm tra cú pháp/schema không thay thế contract test đối chiếu implementation.
