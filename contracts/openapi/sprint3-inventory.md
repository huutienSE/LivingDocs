
# Sprint 3 — API Integration Inventory

Nguồn: LD-API-001 v0.4; LIV-115, LIV-118, LIV-119, LIV-120, LIV-122, LIV-123.

| API ID | Method | Path | Owner | Trạng thái |
| --- | --- | --- | --- | --- |
| API-002 | POST | /api/v1/auth/login | S01 | CANDIDATE — JWT integration pending |
| API-115 | GET | /api/v1/review-queue | S04 | CANDIDATE — FE đã sử dụng |
| API-116 | GET | /api/v1/document-versions/{versionId}/review-context | S04 | CANDIDATE — FE đã sử dụng |
| API-120 | POST | /api/v1/document-versions/{versionId}/staff-decisions | S04 | IN_PROGRESS — LIV-118 |
| TBD | POST | /api/v1/document-versions/{versionId}/manager-decisions | S04 | PLANNED — LIV-120 |
| TBD | POST | /api/v1/document-versions/{versionId}/publish | S04 | PLANNED — LIV-123 |
| TBD | TBD | S02 authoritative SHA lookup | S02 | IN_PROGRESS — LIV-122 |
| Operations | GET | /health | S05 | IMPLEMENTED — LIV-117 |

## Các điểm lệch cần xử lý

| ID | Vấn đề | Owner cần xác nhận |
| --- | --- | --- |
| M01 | API-115 frontend dùng nhưng Gateway và S04 chưa triển khai đầy đủ | S04, Gateway, FE |
| M02 | API-116 frontend cần strong `ETag`, Gateway và S04 chưa triển khai đầy đủ | S04, Gateway, FE |
| M03 | Frontend dự kiến mã lỗi `FORBIDDEN_SCOPE`, trong khi S04 PR LIV-118 định nghĩa `REVIEW_FORBIDDEN` | S04, FE |
| M04 | Frontend `DocumentVersionView` có `documentId`, `sourceBinding` tùy chọn; S04 decision response ở PR LIV-118 chỉ có `versionId`, `state`, `reviewStage`, `revision` | S04, FE |
| M05 | S01 JWT issuer, JWKS, audience và claim mapping chưa có evidence end-to-end | S01, Gateway |
| M06 | S02 authoritative SHA lookup cần chốt wire contract và kiểu response/denial | S02, S04 |

Không coi route Gateway tồn tại là bằng chứng endpoint backend đã hoạt động. Mọi mismatch phải có owner và được giải quyết hoặc ghi blocker trước khi đánh dấu integration PASS.