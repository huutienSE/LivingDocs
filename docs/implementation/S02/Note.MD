Ở phần đưa dữ liệu từ S04 dến S02 để thực hiện việc publish tài liệu
có 1 vấn đề cần phải giải quyết là việc ghi đè tài liệu
giả sử có 2 yêu cầu và 2 yêu cầu này đều xuất phát từ 1 version code base trên github giả sử là version 8
thì nếu có 1 yêu cầu được publish trước thì tài liệu sẽ được nâng version lên 9 thì yêu cầu còn lại 
sẽ không thể publish được vì version code base đã được nâng lên 9 và yêu cầu này vẫn đang ở version 8
điều này dẫn đến việc nếu yêu cầu trước có các phần chỉnh sửa bao gồm các phần mà yêu cầu sau cũng có chỉnh sửa
thì dẫn đến tài liệu hoặc code của yêu cầu sau được publish lên có thể bị sai giảm độ tin cậy

các promt đã trò chuyện với Chat và vẫn là đề xuất chưa thông nhất điều gì

vậy có logic nào khắc phục điều này 1 cách triệt để không kiểu như khoá phiên bản chẳng hạng nếu có 2 yêu cầu thì 
khi sử lí 1 yêu cầu với version 8 thì sẽ check xem version 8 có yêu cầu nào đang được sử lí không nếu có 1 yêu cầu 
và cũng nên chỉ có 1 yêu cầu để đảm bảo yêu cầu đến sau sẽ không thể đọc được ở version 8 mà phải đợi yêu cầu 1 được chấp thuận 
và version được đưa lên 9 hoặc là khi yêu cầu 1 bị huỷ hoặc lỗi và khoá ở version 8 được mở

những việc xử lí các phần liên quan tới codebase nằm trong S04 thì liệu là khi tài liệu được xuất bảng có ánh xạ đúng version hay không 
kiểu như là khi xuất bảng tài liệu thì tài liệu đó vẫn dựa trên version 8 và có 1 yêu cầu khác cũng xuất bảng tài liệu 
dựa trên version 8 luôn thì khi 1 trong 2 yêu cầu tới được S02 thì version đã chuyển sang 9 những các bước sử lí tài liệu thì 
vẫn là version 8 nhưng mà tài liệu cần xuất bản hoặc codebase đâu thể biết được là tài liệu đã được update thành version 9 
và đã có nhưng thay đổi nhất định thì liệu tài liệu được xử lí ở version 8 nhưng tới sau đó đâu đạt được sự chính xác 
và khi đó ứng dụng trở nên không còn tin cậy nửa bạn có ý tưởng nào để giải quyết điều này không


mình suy nghĩ là khi tài liệu xử lí trên base version 8 nhưng tới S02 thì đã có 1 lần xử lí khác đưa version lên 9 
thì phần tài liệu vẫn xử lí trên base version 8 thì sẽ bị sai version thì S02 sẽ đưa request tới S04 và báo rằng 
tài liệu đã bị lỗi thời rồi sẽ thực hiện lại vòng xác định drift trên base version 9 và xuất bảng lại bỏ qua 
quá trình xác thực trên S02 và thực hiện gửi tới nơi tiếp theo trong quy trình chuẩn để từ 1 pr sinh ra tài liệu rồi 
publish sẽ bỏ qua quá trình xác thực validate trên S02 vì lần PR đó đã được xác thực rồi chẳng qua là khi publish đã lỗi thời rồi 
nhưng nếu có nhiều xử lí như vậy đồng thời thì sẽ xử lí rất nhiều lần gửi lại và so sánh lại và đi lại quy trình từ PR đến xuất bản tài liệu 
nếu ở 1 repo lớn thì lượng PR có thể tới hàng ngàn thì việc này có thể làm chậm hệ thống

ầ kiểu như là khi có sự thay đổi từ version 8 sang version 9 thì những xử lí của base version 8 sẽ được phát hiện khi xử lí webhook 
và truy xuất dữ liệu ở S02 này và S02 sẽ gửi cấc request tới tất cả các service khác là service này có đang xử lí cái PR vẫn ở base version 8 đó không 
nếu có thì sẽ dừng lại và sẽ gửi tới người dùng yêu cầu gửi lại PR với base version mới nhất nhưng mình thấy vậy cũng không hợp lí lắm 
mình muốn là vẫn sẽ có cơ chế như này nhưng mình muốn hơn là kiểu sẽ xem xét là lần nâng version này có liên quan gì tới PR được xử lí 
ở Base version 8 hay không nêu không ảnh hưởng và không có các tài liệu hoặc code ghi đè lên nhau thì sẽ thực hiện nâng version trực tiếp 
có PR hoặc cơ chế gì đó tương tự dù có khác version nhưng không ảnh hưởng vẫn ó thể publish bình thường