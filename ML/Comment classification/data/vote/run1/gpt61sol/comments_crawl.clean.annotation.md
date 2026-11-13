# Gán nhãn comments_crawl.clean.csv theo ViHSD

Ngày: 13/11/2026. Đây là nhãn do ChatGPT gán trực tiếp khi đọc văn bản, chưa phải nhãn chuẩn được người gán nhãn độc lập thống nhất.

## Căn cứ

Nguồn gốc: [A Large-scale Dataset for Hate Speech Detection on Vietnamese Social Media Texts, v4, mục 3.2, Bảng 1 và mục 3.5](https://arxiv.org/html/2103.11528#S3.SS2). [Hugging Face uitnlp/vihsd](https://huggingface.co/datasets/uitnlp/vihsd) xác nhận bộ dữ liệu có ba lớp. Không dùng prompt Qwen hay một định nghĩa HATE khác để thay thế hướng dẫn gốc.

| label_id | Nhãn | Tóm tắt hướng dẫn gốc |
|---|---|---|
| 0 | CLEAN | Không có nội dung quấy rối/công kích. |
| 1 | OFFENSIVE | Có nội dung xúc phạm hoặc tục tĩu nhưng không công kích đối tượng cụ thể. |
| 2 | HATE | Có nội dung xúc phạm/quấy rối nhắm cá nhân hoặc nhóm; hướng dẫn cũng bao gồm lời tục nhắm đối tượng cụ thể, nghĩa hàm ý và nghĩa bóng. |

HATE trong ViHSD không chỉ giới hạn ở sắc tộc, quốc tịch hay tôn giáo. Việc có tên người hoặc có nhận xét tiêu cực không tự nó đủ để gán HATE.

## Cách thực hiện và phạm vi diễn giải

Đã đọc toàn bộ 3.788 bình luận theo thứ tự và lưu nhãn theo lô. Gán mới từ `free_text`; không nhập hay sao chép nhãn từ file lần trước, từ thư mục người gán nhãn khác hoặc từ mô hình phân loại. Sau đó kiểm tra lại các trường hợp dễ nhầm và tính nhất quán. Một lượt tìm từ đáng chú ý chỉ dùng để chọn câu đọc lại, không dùng để quyết định nhãn bằng từ khóa.

Giữ nguyên mọi bản ghi, thứ tự và cột ban đầu; chỉ điền `label_id`. Không chạy tiền xử lý, không loại câu chỉ có tên hoặc câu ngắn. `context` được giữ nguyên nhưng không dùng để bổ sung mục tiêu còn thiếu; không xem video, ảnh, bài đăng hay tin ngoài file để đoán người bị công kích. Với câu mà văn bản không đủ xác định mục tiêu, không mặc định người trong video là đối tượng.

Cách xử lý từng câu là quyết định của người gán nhãn AI dựa trên hướng dẫn ngắn của bài báo, không phải hướng dẫn chi tiết bổ sung do tác giả ViHSD công bố. Ví dụ: phân biệt chê phong độ với chửi cầu thủ; phân biệt trích dẫn để thuật lại/phản đối với lời chửi do chính người bình luận dùng; không tự xem mọi từ chỉ tuổi, bệnh, bộ phận cơ thể, tên động vật hay tên nói lái là xúc phạm. Từ tục cảm thán không công kích người cụ thể được xét ở OFFENSIVE. Các điểm còn tranh luận được lưu để phân xử, không khẳng định bài báo đã quy định riêng tất cả cách dùng tiếng lóng đó.

Nhãn đánh giá ngôn ngữ của bình luận, không xác nhận các cáo buộc, thông tin lịch sử, chính trị hoặc thể thao là đúng.

## Kết quả

| label_id | Nhãn | Toàn bộ | Trong danh sách rà soát |
|---|---|---:|---:|
| 0 | CLEAN | 3132 | 791 |
| 1 | OFFENSIVE | 227 | 212 |
| 2 | HATE | 429 | 314 |
| | **Tổng** | **3788** | **1317** |

- `comments_crawl.clean.labeled.csv`: kết quả đầy đủ; schema của đầu vào được giữ nguyên.
- `comments_crawl.clean.review.csv`: danh sách đề nghị rà soát, vẫn có nhãn AI đề xuất. Bổ sung `data_row`, `label_name`, `review_reason`.
- `comments_crawl.clean.annotation.json`: checkpoint nhãn, các lô đã đọc, thay đổi sau kiểm tra nhất quán và bằng chứng kiểm tra dữ liệu.

`data_row` là số thứ tự bản ghi bắt đầu từ 1, không tính header; không phải số dòng vật lý trong CSV vì bình luận có thể chứa xuống dòng. Phần lớn lý do rà soát được ghi theo nhóm vấn đề; các điều chỉnh cuối có giải thích riêng theo câu. Danh sách được chọn thận trọng để kiểm tra cả những câu tưởng dễ; không có nghĩa mọi dòng trong đó đều sai hoặc chưa gán nhãn. Những dòng ngoài danh sách cũng chưa được con người xác nhận.

## Kiểm tra và giới hạn

Đã đọc lại hai CSV sau xuất: đủ 3.788 bản ghi; mọi nhãn thuộc 0/1/2; tất cả giá trị ngoài `label_id` và thứ tự khớp đầu vào; file gốc không thay đổi. Các bình luận trùng sau chuẩn hóa chữ hoa/thường và khoảng trắng không có nhãn mâu thuẫn. Không có file tạm cần xóa; checkpoint và báo cáo được giữ làm bằng chứng có thể dùng lại.

Chưa có gán nhãn độc lập, biểu quyết nhiều người, đo độ đồng thuận hay đo độ chính xác. Hướng dẫn công khai của ViHSD ngắn và có những trường hợp cần phân xử. Trước khi dùng làm nhãn chuẩn, cần người đọc độc lập kiểm tra danh sách rà soát và lấy thêm mẫu ở từng lớp ngoài danh sách; không xem nhãn AI này là gold label đã được thẩm định.

SHA-256 đầu vào: `be3891765be201486995b4dccf9946c5f853946310a646f9bcffd889f8fb53a9`.

SHA-256 kết quả: `03129290d5c5d1573f2a337e477990f57baf2f2a9ad7d03a2b3832bc233168b5`.
