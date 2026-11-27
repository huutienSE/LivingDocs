# Cấu hình cần áp dụng trên App LIV-122 hiện hữu

Đọc trực tiếp `GET /app` ngày 28/11/2026 bằng App JWT; chỉ lưu metadata công khai. App ID **5829464**, slug **livingdocs-s02-test-tientuiteen**, installation kiểm thử **183597993**. [Trang App](https://github.com/apps/livingdocs-s02-test-tientuiteen). Không cần tạo App mới.

| Mục | Hiện trạng đọc từ GitHub | Thiết kế B v1 đã duyệt |
| --- | --- | --- |
| Repository metadata | read | read |
| Repository contents | read | **write** |
| Repository pull_requests | read | **write** |
| Repository checks | Chưa khai báo | **write** |
| Webhook events | Rỗng | **push, pull_request** |

Đây là thay đổi App registration phục vụ toàn Flow 1. Source installation token của LIV-122 vẫn được S02 thu hẹp ở `contents:read`, `pull_requests:read` và một repository. Mở permission tại registration không được dùng để bỏ giới hạn token nguồn.

Sau khi Owner duyệt thay đổi cấu hình ngoài repository: cập nhật chính App này, chấp thuận bộ quyền mới trên installation kiểm thử, kiểm lại `GET /app` và installation metadata. Không tự thay App settings dựa trên quyền duyệt thiết kế; lượt duyệt v1 trước đó chỉ cho triển khai mã.

Browser consent còn cần các tham chiếu cấu hình: OAuth client secret file, callback HTTPS `/api/v1/integrations/github/authorization/callback`, completion HTTPS cùng browser origin, state/credential AES key rings riêng. Đây là phần khác với App private key đã dùng đọc SHA ở LIV-122. Không gửi nội dung secret qua chat. Các biến/đường dẫn mount nằm trong `.env.example` và [runbook](LIV-151-van-hanh-va-nghiem-thu.md).

Khi App metadata chưa khớp profile, API-046 trả 503 `GITHUB_AUTHORIZATION_CONFIG_UNAVAILABLE` theo thiết kế fail closed. Không đổi code để giả vờ App đã có quyền write/checks hoặc browser consent đã chạy.
