# Gán nhãn bình luận theo ViHSD

Ngày thực hiện: 08/11/2026.

Đây là bản gán nhãn nháp của ChatGPT, dựa trên việc đọc văn bản từng bình luận theo lô và kiểm tra lại một số ranh giới nhãn. Không dùng bộ lọc từ khóa để tự sinh nhãn, không chạy mô hình đã huấn luyện, không thực hiện bỏ phiếu nhiều người. Chưa phải nhãn chuẩn do con người thống nhất.

## Căn cứ và cách áp dụng

Nguồn chính: [bài báo ViHSD, phiên bản v4, mục 3.2 và Bảng 1](https://arxiv.org/html/2103.11528#S3.SS2). Ánh xạ mã nhãn đối chiếu với đặc tả dự án trong `../../../docs/README.md`.

- `0 = CLEAN`: không có nội dung quấy rối/công kích. Nhận xét tiêu cực về cách diễn, cách chơi, chất lượng chương trình hoặc chỉ trích hành vi không tự động thành lời xúc phạm.
- `1 = OFFENSIVE`: nội dung xúc phạm/tục tĩu nhưng không có mục tiêu công kích cụ thể. Trong bản nháp này, tiếng tục dùng để cảm thán hoặc tăng cường lời khen cũng được xét ở lớp này và thường được đánh dấu cần rà soát.
- `2 = HATE`: nội dung công kích/hạ nhục nhắm cá nhân hoặc nhóm; gồm chửi trực tiếp, miệt thị ngoại hình, so sánh hạ thấp và một số công kích hàm ý. Không giới hạn ở công kích sắc tộc, tôn giáo hay giới tính.

Các diễn giải áp dụng trên là quyết định gán nhãn cho lô dữ liệu này, không phải nguyên văn hướng dẫn. Không mặc định mọi từ như “thằng”, “con”, “điên”, “khùng”, “lùn”, tên người hay nhận xét không thích ai đó đều tạo thành HATE. Cần xét cách dùng, mục tiêu và chức năng của cả câu. Lời thuật lại hoặc trích dẫn để phản đối không tự động mang nhãn của câu công kích được trích.

Chỉ dùng nội dung trong bản ghi để đánh giá; chưa xem toàn bộ video, bài đăng hoặc hội thoại gốc. Việc gán nhãn không xác nhận những cáo buộc trong bình luận là đúng.

## Tệp kết quả

- `comments_merged.clean.csv`: đầu vào, giữ nguyên.
- `comments_merged.clean.labeled.csv`: toàn bộ bản ghi theo đúng thứ tự ban đầu, giữ nguyên các cột và nội dung, chỉ điền `label_id`.
- `comments_merged.clean.review.csv`: các dòng cần người đọc rà soát, giữ nhãn nháp và bổ sung `data_row`, `review_reason`.
- `comments_merged.clean.annotation.json`: sổ nhãn/checkpoint, mã băm đầu vào và các điều chỉnh khi kiểm tra tính nhất quán.

`data_row` bắt đầu từ 1, không tính header; không phải số dòng vật lý của CSV vì một bản ghi có thể chứa xuống dòng. Lý do rà soát được ghi theo nhóm tình huống, không phải giải thích chi tiết riêng cho từng câu. Dòng không có trong tệp review vẫn là nhãn AI nháp, không đồng nghĩa đã được con người xác nhận.

## Thống kê

| label_id | Nhãn | Số bình luận | Cần rà soát |
|---|---|---:|---:|
| 0 | CLEAN | 2717 | 592 |
| 1 | OFFENSIVE | 160 | 153 |
| 2 | HATE | 296 | 245 |
| | **Tổng** | **3173** | **990** |

SHA-256 đầu vào: `c1288f4911b737e1d9499bcc0cb20431fa6e7d947314a696b58000cba27df095`.

Đã kiểm tra: đủ nhãn cho 3173 bản ghi; mọi nhãn thuộc 0/1/2; đọc lại được hai CSV; thứ tự và mọi trường ngoài `label_id` khớp đầu vào; mã băm đầu vào không đổi.

## Giới hạn trước khi dùng huấn luyện

Chưa có đánh giá độ chính xác, độ đồng thuận giữa người gán nhãn hay phân xử độc lập. Ưu tiên rà soát tệp review, đặc biệt ám chỉ tình dục, lời đùa thân mật trong gameshow, biệt danh, trích dẫn và ranh giới giữa lời chê với công kích. Sau đó lấy thêm mẫu ở cả ba lớp ngoài danh sách review để kiểm tra lỗi còn sót; không coi các nhãn này là gold label hoặc số liệu đã thẩm định.
