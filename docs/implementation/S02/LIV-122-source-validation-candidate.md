# LIV-122 · Giải thích hợp đồng ứng viên xác minh SHA đầu nguồn

Tài liệu này diễn giải [YAML ứng viên của LIV-122](../../../backend/repository-integration-service/LIV-122-source-validation-candidate.yaml), phiên bản `0.1.0` theo OpenAPI `3.0.3`. Phạm vi tệp là hợp đồng đề xuất cho API-070 của S02. Phần mô tả tiến độ trong YAML phản ánh thời điểm soạn bản ứng viên. Trạng thái triển khai và hợp đồng dự kiến được theo dõi trong [kế hoạch LIV-122](S02-LIV-122-HLD-va-ke-hoach-trien-khai.md) và [OpenAPI S02](../../../contracts/openapi/s02-repository.yaml).

## API này dùng để làm gì?

S04 gửi SHA (mã định danh commit) đã gắn với phiên bản tài liệu được duyệt. S02 tra đúng kết nối, đọc đầu nguồn (head) mới từ GitHub và so với SHA đó. Với yêu cầu hợp nhất (pull request, PR), đầu nguồn là commit của nhánh nguồn của PR. Với nhánh theo dõi, đầu nguồn là commit mà tên nhánh đầy đủ đang trỏ tới.

- **Phương thức và đường dẫn** `POST /internal/v1/source/validate`.
- **Mã thao tác (`operationId`) của bản ứng viên** `validateCurrentSource`.
- **Xác thực** mã truy cập dịch vụ (`Bearer JWT`), đúng bên phát hành, đối tượng nhận là S02, thời hạn, quyền xác minh nguồn và phạm vi khách hàng/không gian làm việc. Cấu hình danh tính dịch vụ phụ thuộc S3-EN01.
- **Đường gọi** mạng nội bộ giữa các dịch vụ. Gateway công khai chặn đường dẫn này.
- **Mã tương quan (`X-Correlation-ID`)** bắt buộc và có định dạng UUID trong bản ứng viên.

## Dữ liệu S04 gửi

Thân yêu cầu dùng `application/json`. `SourceValidationRequest` yêu cầu cả bảy trường và cấu hình `additionalProperties: false`.

| Trường | Kiểu và ý nghĩa |
| --- | --- |
| `tenantId`, `workspaceId` | Hai UUID xác định phạm vi tài nguyên. S02 đối chiếu với quyền dịch vụ đã xác thực. |
| `repositoryConnectionId` | UUID của kết nối kho trong S02. S02 dùng kết nối để xác định kho GitHub cần đọc. |
| `targetKind` | `BRANCH` cho nhánh hoặc `PR` cho yêu cầu hợp nhất. |
| `targetRef` | Chuỗi dài 1–512 ký tự. Nhánh dùng tên đầy đủ như `refs/heads/main`. PR dùng số nguyên dương dạng chuỗi như `"42"`. |
| `expectedHeadSha` | SHA kỳ vọng theo ràng buộc nguồn đã lưu. Mẫu trong YAML chấp nhận đúng 40 ký tự thập lục phân, gồm chữ hoa hoặc chữ thường. |
| `expectedConfigRevision` | Phiên bản cấu hình kết nối do S02 cấp, dài 1–255 ký tự. Mô tả yêu cầu giá trị có nội dung ngoài khoảng trắng. |

```json
{
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "workspaceId": "22222222-2222-4222-8222-222222222222",
  "repositoryConnectionId": "33333333-3333-4333-8333-333333333333",
  "targetKind": "BRANCH",
  "targetRef": "refs/heads/main",
  "expectedHeadSha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "expectedConfigRevision": "v1:42:17"
}
```

## Cách đọc phản hồi

| HTTP | Phản hồi theo YAML ứng viên |
| --- | --- |
| `200` | Một lần xác minh đã hoàn tất. Kiểm cả `validationStatus` và `reason` vì mã HTTP này có thể đi kèm `PASS` hoặc `FAIL`. Phản hồi mô tả `Cache-Control: no-store`. |
| `400` | Yêu cầu bị từ chối, thân lỗi theo `application/problem+json`. |
| `401` | Xác thực dịch vụ thất bại, thân lỗi theo `application/problem+json`. |
| `403` | Danh tính dịch vụ thiếu quyền, thân lỗi theo `application/problem+json`. |
| `503` | Lỗi phụ thuộc xảy ra trước khi tạo được kết quả xác minh, thân lỗi theo `application/problem+json`. |

`SourceValidationResult` trả lại phạm vi, kết nối, đích và SHA kỳ vọng để S04 đối chiếu với phiên bản tài liệu. Các trường còn lại có vai trò sau.

| Trường | Cách dùng |
| --- | --- |
| `validationId` | UUID định danh lần xác minh. Dùng để truy vết. |
| `configRevision` | Phiên bản cấu hình kỳ vọng. `PASS` yêu cầu giá trị này khớp với cấu hình hiện hành tại S02. |
| `validationStatus`, `reason` | Kết quả nghiệp vụ. Chỉ cặp `PASS/CURRENT` đủ điều kiện đi tiếp đến bước kiểm tra xuất bản của S04. |
| `validatedHeadSha` | SHA đầu nguồn đối chiếu được. Mô tả sử dụng trường này với `CURRENT` hoặc `STALE_SHA`. Trường có thể rỗng (`null`) khi nguồn chưa xác minh được. |
| `sourceRevision` | Mốc phiên bản quan sát do S02 quản lý. Mô tả yêu cầu giá trị có nội dung khi `PASS`. Danh sách `required` của OpenAPI chưa thể hiện điều kiện này. |
| `checkedAt`, `expiresAt` | Thời điểm bắt đầu xác minh và hết hạn. Đúng thời điểm `expiresAt` thì bằng chứng đã hết hạn. YAML ứng viên để thời hạn hiệu lực (TTL) theo cấu hình và chờ duyệt khi triển khai. |

Các giá trị `reason` được khai báo gồm:

- `CURRENT`, `STALE_SHA` cho kết quả so SHA với đầu nguồn.
- `INVALID_REQUEST`, `UNKNOWN_CONNECTION`, `CONNECTION_INACTIVE`, `CONFIG_CHANGED`, `UNKNOWN_REF` cho lỗi đầu vào, kết nối, cấu hình hoặc tham chiếu.
- `SOURCE_BINDING_MISMATCH`, `INVALID_SOURCE`, `VALIDATION_EXPIRED`, `SOURCE_UNAVAILABLE` cho bằng chứng sai liên kết, dữ liệu nguồn không hợp lệ, hết hạn hoặc phụ thuộc không khả dụng.

Ví dụ, nếu S04 gửi SHA **A** nhưng S02 đọc được đầu nguồn **B**, bản ứng viên cho phép `200` với `validationStatus=FAIL`, `reason=STALE_SHA` và `validatedHeadSha=B`. S04 dừng xuất bản trong lần thử đó. Mất phản hồi, hết thời gian chờ hoặc JSON không hợp lệ cũng chặn xuất bản.

`Problem` có `type`, `title`, `status`, `detail`, `instance`, `code` và `correlationId`. Phần chi tiết được lọc để bảo vệ thông tin xác thực GitHub, tham chiếu bí mật và lỗi thô của nhà cung cấp.

## Các điểm cần đồng bộ khi triển khai

| Chủ đề | YAML ứng viên `0.1.0` | Hướng trong kế hoạch LIV-122 |
| --- | --- | --- |
| Định danh thao tác | `validateCurrentSource` | `api070_post` theo danh mục và OpenAPI S02. |
| Phạm vi và tên trường | `tenantId` và `workspaceId` nằm trong thân yêu cầu. Dùng `BRANCH`, `expectedHeadSha`, `expectedConfigRevision`. | Lấy phạm vi từ danh tính dịch vụ đã xác thực. Tên trường dự kiến là `TRACKED_BRANCH`, `boundHeadSha`, `configRevision`. |
| Hình dạng phản hồi | `SourceValidationResult` trực tiếp, `200` có thể biểu diễn `FAIL`. | Thành công dùng `SuccessResponse<SourceValidationReceipt>`. Lỗi trả Problem Details với HTTP tương ứng, ví dụ `409 STALE_SHA` và `503 SOURCE_UNAVAILABLE`. |
| Phiên bản nguồn và V3 | Mô tả `sourceRevision` là mốc S02 cấp cho quan sát khi `PASS`. YAML để cách lưu và cơ chế cấp mốc cho thiết kế triển khai. | Giữ V3. `SourceValidationService` lấy `sourceRevision` từ hàng đầu nguồn đã ghi thành công trong PostgreSQL. Nếu một lần khác ghi trước, trả lý do `SOURCE_OBSERVATION_CONFLICT` và giữ quan sát đã được chấp nhận. |

Trước khi dùng bằng chứng, S04 kiểm phạm vi, kết nối, đích, SHA, cấu hình và thời hạn với phiên bản đã duyệt. Quyền xuất bản, `ETag` (mã phiên bản HTTP) và ghi trạng thái, kiểm toán, sự kiện trong cùng giao dịch thuộc luồng xuất bản S04. Ranh giới xác minh SHA của S02 là lần đọc GitHub trong yêu cầu hiện tại.
