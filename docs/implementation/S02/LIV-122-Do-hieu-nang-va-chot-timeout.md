# LIV-122 — Đo hiệu năng và chốt timeout

**Yêu cầu Owner: 19/11/2026. Trạng thái: NOT RUN.** Đây là kế hoạch và mẫu báo cáo; chưa có số liệu benchmark. Hoàn thành phép đo và review kết quả trước khi đánh dấu LIV-122 Done theo [HLD §10.4–10.5](S02-LIV-122-HLD-va-ke-hoach-trien-khai.md).

Mục tiêu là chọn timeout phù hợp cho từng phần dựa trên dữ liệu thực tế và mức chờ/lỗi chấp nhận được. Giá trị chính xác phụ thuộc môi trường và tải; không có một con số bảo đảm mọi trường hợp xấu nhất. `LIVINGDOCS_SOURCE_REQUEST_TIMEOUT_SECONDS=15` hiện là cấu hình local tạm thời, không phải kết quả benchmark.

## 1. Chuẩn bị trong quá trình hoàn thiện task

- Khi nối HTTP/auth ở bước 5, bổ sung điểm ghi thời gian bằng đồng hồ đơn điệu và correlation ID xuyên suốt yêu cầu. Ghi thời gian cả lúc thành công, lỗi và timeout; ghi nhận tác vụ còn chạy sau khi bên gọi hết hạn nếu có.
- Chuẩn bị runner gọi API-069/070 với tham số endpoint, dữ liệu thử nghiệm, số lượt, thời gian và tải đồng thời; có lệnh tái lập, giới hạn tốc độ, điều kiện dừng và file kết quả. Ghi phiên bản công cụ và mã nguồn đã đo. Không nhúng token vào script hoặc báo cáo.
- Dùng PostgreSQL và repository GitHub thử nghiệm riêng, HTTPS và service identity thật. Thống nhất môi trường, tải dự kiến, mức lỗi/timeout chấp nhận được và xin quyền chạy tải theo quy tắc dự án trước khi chạy.
- Dữ liệu đo lưu thời gian, tên chặng, loại API/kịch bản và outcome; bí mật được loại bỏ. Correlation ID dùng trong log/trace, không dùng làm nhãn metrics có số lượng giá trị không giới hạn.

## 2. Các phần phải đo

| Phần | Phạm vi cần phân biệt |
|---|---|
| Toàn yêu cầu phía gọi | Từ lúc gửi đến khi nhận đầy đủ phản hồi, gồm mạng/TLS; tách resolve và validate |
| Toàn yêu cầu trong S02 | Từ entry point xác thực đến phản hồi; bao gồm mọi thời gian xếp hàng/chờ cấp worker nếu có |
| Xác thực và phân quyền | Thời gian xử lý thật; nếu có truy cập phụ thuộc bên ngoài, ghi riêng |
| Lấy connection DB | Thời gian chờ pool, mở kết nối mới và lỗi lấy connection |
| Giao dịch A | Đọc scope/cấu hình/đích theo RLS; tách truy vấn khỏi chờ connection |
| GitHub token | Cache lạnh/ấm, thời gian cấp token và lỗi |
| GitHub REST và GraphQL | Từng request thực tế, số trang/số lần gọi, kết nối/TLS mới hoặc tái sử dụng, chờ header và đọc body; phần chưa quan sát được phải ghi rõ |
| Giao dịch B | Đọc lại scope/cấu hình, chờ khóa, CAS và commit; phân biệt xung đột với timeout |
| Tác vụ sau hết hạn | Độ trễ hủy và số tác vụ chưa kết thúc, thời gian giữ worker/connection |

Các chặng lồng nhau phải được ghi rõ để tránh tính hai lần. Không cộng phân vị p99 của các chặng để suy ra p99 toàn yêu cầu; đo toàn yêu cầu trực tiếp. Không ghi SHA, tenant, repository ID hoặc correlation ID thành nhãn metrics không giới hạn.

## 3. Kịch bản và cách thu mẫu

1. Chạy branch và PR qua từng API, phân biệt token lạnh/ấm và kết nối mới/tái sử dụng; bao gồm PR từ fork khi thuộc fixture được cấp quyền. Đo khởi động riêng, không trộn vào nhóm đã làm nóng.
2. Đo tuần tự, sau đó tải đồng thời dự kiến và gần giới hạn tài nguyên đã được duyệt. Ghi số lượt, tốc độ đến, số đang chạy, thời gian chạy, số lần lặp và mức sử dụng CPU/bộ nhớ/pool. Giữ kiểm tra SHA/kết quả nghiệp vụ để phát hiện phản hồi nhanh nhưng sai.
3. Kiểm lỗi có kiểm soát: HTTP chậm header/body, DB chờ khóa/pool cạn, GitHub unavailable/rate limit. Gây lỗi bằng fixture trong môi trường cô lập; không cố làm cạn quota GitHub thật. Tách rõ kết quả mô phỏng và đo phụ thuộc thật.
4. Với mỗi nhóm, báo số mẫu thành công/lỗi/timeout, p50/p95/p99 và max quan sát được. Ghi đủ timeout kể cả khi công cụ phía gọi ngừng chờ; giải thích khác biệt giữa số liệu client và server. Nếu quá ít mẫu hoặc các lần đo chưa ổn định, chưa chốt p99.
5. Timeout đang đặt có thể che độ trễ thực của lượt bị hủy. Đánh dấu những mẫu này là hết hạn, không coi thời gian ngưỡng là thời gian xử lý hoàn tất. Nếu cần đo lại với ngưỡng lớn hơn, chỉ điều chỉnh trong môi trường thử nghiệm đã được duyệt, có giới hạn bảo vệ tài nguyên và ghi cấu hình của từng lần chạy.

## 4. Quy tắc chọn timeout và kiểm chứng lại

- Chọn ngưỡng cho từng phần từ phân bố đo được, tỷ lệ timeout chấp nhận được, chi phí giữ tài nguyên và giới hạn phụ thuộc. Ghi rõ lý do chọn phân vị và biên dự phòng; không dùng công thức cố định khi chưa có căn cứ.
- Đánh giá riêng timeout kết nối, HTTP đọc/toàn request, chờ pool, SQL/chờ khóa, tổng deadline S02 và thời gian chờ của caller. Các giới hạn socket, statement hoặc lock không tự thay thế giới hạn tổng thời gian.
- Chặng con phải tôn trọng ngân sách tổng còn lại khi cơ chế deadline được áp dụng. Caller cần đủ thời gian nhận phản hồi lỗi và phần truyền mạng; không tự lấy biên 2 giây hiện tại làm kết luận đã đo.
- Một giá trị dùng ở nhiều nơi chỉ tiếp tục dùng chung khi kết quả đo chứng minh phù hợp. Nếu cần tách cấu hình từng phần, ghi biến/thuộc tính dự kiến và tác động đến consumer để triển khai, review trong phạm vi task. TTL bằng chứng được đánh giá theo chính sách độ mới riêng.
- Chạy lại cùng ma trận sau hiệu chỉnh: kiểm độ trễ, tỷ lệ lỗi/timeout, tài nguyên, hành vi fail closed và dữ liệu sau lỗi. Chỉ chốt khi đáp ứng mục tiêu đã thống nhất; nếu chưa đạt thì ghi nút thắt và việc còn phải làm.

## 5. Báo cáo kết quả cần điền

| Thông tin phép đo | Giá trị |
|---|---|
| Người thực hiện / ngày / mã nguồn | CHƯA ĐO |
| Môi trường: CPU, RAM, JVM, DB, vùng mạng, TLS, replicas, pool | CHƯA ĐO |
| Runner / phiên bản / lệnh chạy / fixture | CHƯA CÓ |
| Cấu hình timeout, cache và retry của từng lần chạy | CHƯA GHI NHẬN |
| Tải dự kiến và mục tiêu độ trễ/lỗi đã thống nhất | CHƯA CHỐT |
| File kết quả đã lọc / log hoặc trace / dashboard | CHƯA CÓ |

Sao chép bảng dưới cho từng API, chặng và nhóm kịch bản. Đơn vị thời gian: ms.

| API / chặng / kịch bản | Số lượt / thời lượng / tải | Thành công / lỗi / timeout | p50 | p95 | p99 | Max quan sát | Tài nguyên / ghi chú |
|---|---|---|---|---|---|---|---|
| CHƯA ĐO | — | — | — | — | — | — | — |

| Loại timeout / phần | Giá trị hiện tại | Đề xuất | Số liệu tham chiếu / biên dự phòng / lý do | Biến hoặc thuộc tính cấu hình | Kết quả đo lại |
|---|---|---|---|---|---|
| Tổng deadline S02 | 15 giây, tạm thời | CHƯA CHỐT | CHƯA ĐO | `LIVINGDOCS_SOURCE_REQUEST_TIMEOUT_SECONDS` | NOT RUN |
| Các timeout GitHub, DB/pool/khóa và caller | Ghi cấu hình thực khi chạy | CHƯA CHỐT | CHƯA ĐO | Ghi riêng từng phần | NOT RUN |

## 6. Điều kiện hoàn tất phép đo

- [ ] Runner, điểm đo và hướng dẫn tái lập đã có; phân biệt bằng chứng API thật với fixture/mô phỏng.
- [ ] Thu đủ số liệu theo chặng và toàn yêu cầu dưới tải đã thống nhất, gồm trường hợp lỗi, timeout và tài nguyên sau hủy.
- [ ] Có đề xuất timeout từng phần, căn cứ và phạm vi môi trường/tải; phân biệt với TTL và mục tiêu NFR của sản phẩm.
- [ ] Cấu hình đã hiệu chỉnh và đo lại, các ca timeout/fail closed vẫn đúng; kết quả được reviewer/Owner review.
- [ ] Đính kèm báo cáo vào bằng chứng hoàn thành LIV-122. Thiếu điều kiện ghi `BLOCKED/NOT RUN` và dependency; không đánh dấu hoàn tất dựa trên kiểm thử local trước đây.

Đo lại khi thay đổi đáng kể hạ tầng, mạng, tải, pool, cache, retry hoặc cách gọi provider. Kết quả của LIV-122 chỉ kết luận phạm vi S02; độ trễ Publish qua S04 được nghiệm thu thêm trong LIV-123/LIV-99.
