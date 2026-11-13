# LIV-118 — Bàn giao S04 Staff Review persistence

Cập nhật: 08/11/2026 (UTC+7). Người soạn: Codex; tài liệu implementation, không phải independent approval.

## Hành vi và phạm vi bàn giao

- API-120 ghi Staff PASS/RETURN; version, decision và audit cùng transaction.
- PASS chỉ từ `IN_REVIEW / STAFF_REVIEW` sang `MANAGER_APPROVAL`; RETURN về `DRAFT`,
  stage null, bắt buộc feedback. Giữ content/decision/audit history.
- Kiểm active tenant/workspace, Staff role và repository grant khi document gắn repository.
  Document không gắn repository dùng Staff role đúng workspace; không thêm document-level ACL.
- Khóa resource, conditional revision update, strong ETag, durable idempotency response snapshot.
- Flyway V2/V3, OpenAPI và unit/PostgreSQL/API/restart tests.
- API-106/115/116, UI, Final Approval, Publish, Gateway và API xác minh SHA phía S02 không có
  implementation trong slice này. Phân công API đọc cần được xác nhận trước tích hợp UI.

Chi tiết chạy service, request và claim mapping:
[README S04](../../backend/document-governance-service/README.md).

## Điểm tích hợp và giới hạn còn lại

| Điểm | Hành vi hiện tại | Việc cần hoàn tất khi tích hợp |
| --- | --- | --- |
| Identity/quyền S01 | JWT được kiểm signature/issuer/audience/expiration; mapping tenant_active/workspace_grants là candidate. Chưa xác minh revoke quyền tức thời. | Owner S01/S04 đối chiếu claim contract và kiểm chứng token S01 thật. |
| Source validation | Repository-backed PASS mới cần trusted validator; thiếu adapter/kết quả không hợp lệ trả 503, SHA thay đổi trả 409; không ghi mutation. RETURN/exact replay không gọi check mới. | Nối adapter S04 với contract S02 của LIV-122; kiểm chứng authority, binding và freshness bằng source thật. Không trao GitHub credentials cho S04. |
| SourceBinding | DB chỉ lưu repository connection/head SHA; các trường binding khác trả null. | Đối chiếu schema source binding với owner trước tích hợp; không tự suy ref/config/time từ SHA. |
| API đọc/UI | Slice chỉ có POST API-120, chưa có API đọc version/queue/context cấp ETag cho UI. | Lead/S04/LIV-119 xác nhận owner và contract API-106/115/116; không coi các API này đã hoàn thành. |

Giới hạn này cần được phân biệt với AC của LIV-118: guarded mutation, state/audit persistence và
code/test/evidence được review. Chưa có bằng chứng luồng end-to-end của parent LIV-97 hoàn chỉnh.

## Migration và bằng chứng kiểm thử

V2 từ chối dữ liệu V1 có `IN_REVIEW` nhưng review_stage null. Trước nâng cấp, người sở hữu dữ liệu
cần xác định stage đúng từ history; migration không tự suy đoán. Test mới xác nhận rollback khi
V2 bị từ chối: dữ liệu vẫn nguyên, cột revision chưa được thêm, Flyway history chưa ghi V2.
Sau khi sửa fixture về stage đã biết, V2/V3 chạy thành công và giữ lịch sử cũ.

Evidence được giữ tại [LIV-118-test-evidence.json](LIV-118-test-evidence.json): kết quả local,
source hashes, reports/logs và CI theo commit được tách riêng. Source hashes không bao gồm chính
evidence JSON để tránh tự tham chiếu. Các kết quả cũ có thể truy vết qua Git history của file.

Restart test kiểm chứng đóng/mở Spring context/server/pool với cùng DB; không mô phỏng restart
JVM/PostgreSQL. Local tests không chứng minh CI cho commit mới, tích hợp S01/S02 thật hoặc peer approval.

## Nguồn và mức tin cậy

| Nguồn | Tác giả / thời gian / nơi đăng | Giá trị và giới hạn |
| --- | --- | --- |
| [LIV-118](https://huutiense25.atlassian.net/browse/LIV-118) | Reporter TIỀN HUỲNH PHẠM HỮU; cập nhật 06/11/2026; Jira LivingDocsPro; đọc trực tiếp 08/11/2026. | Nguồn chính cho scope DocumentVersion/ReviewDecision/DocumentAudit, guarded PASS/RETURN, unit+DB tests và state/audit sau restart. Không phải bằng chứng implementation PASS. |
| [PR #26](https://github.com/huutienSE/LivingDocs/pull/26) | Tác giả tài khoản nguyenxuanhai-arch; tạo 07/11/2026; GitHub huutienSE/LivingDocs; đọc trực tiếp 08/11/2026. | Code tại 69d0c03 và check runs là nguồn trực tiếp cho implementation/CI của commit đó; không bao phủ thay đổi local sau commit. |
| [LD-API-001](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/11337744) | Confluence LPKB; v0.4 đề ngày 24/10/2026; đọc trực tiếp 07/11/2026. Metadata tác giả TIỀN HUỲNH PHẠM HỮU được ghi trong bản bàn giao trước, chưa xác minh lại lần cập nhật này. | Thiết kế API-120, ETag/idempotency/Problem; tài liệu tự ghi candidate, chưa Accepted ADR/G2/runtime evidence. Không xác nhận claim S01 hay wire contract S02 đã chốt. |
| [REQ-05](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/3702785) | Confluence LPKB; bản bàn giao trước ghi author TIỀN HUỲNH PHẠM HỮU, version 6 ngày 20/10/2026, đọc 05/11/2026. Không đọc lại lần cập nhật này. | Tham chiếu transition T-DOC-03/04B; metadata kế thừa có thể cũ, không dùng thay test code. |
| [LD-ARCH-005](https://huutiense25.atlassian.net/wiki/spaces/LPKB/pages/10715150) | Confluence LPKB; bản bàn giao trước ghi cùng author, version 1 ngày 23/10/2026, đọc 05/11/2026. Không đọc lại lần cập nhật này. | Tham chiếu scope/RLS/service ownership; nguồn ghi reviewed candidate/pending ADR. |

Độ tin cậy được xét theo quyền sở hữu nguồn dự án và trạng thái phê duyệt; không suy diễn chuyên môn
cá nhân từ tên tác giả. Code/report/log có giá trị cho phiên bản và môi trường cụ thể, không tự chứng minh
task Done hoặc reviewer approval.
