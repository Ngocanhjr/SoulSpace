# Đặc tả dữ liệu phân loại bình luận

## 1. Phạm vi và căn cứ

Tài liệu này ghi lại dữ liệu và kết quả đã lưu của [notebook huấn luyện](../../training/train_model_classification_comments.ipynb), gồm nguồn dữ liệu, cấu trúc bản ghi, nhãn, cách chia tập và tiền xử lý cho từng mô hình.

Đây là bản ghi lịch sử tại ngày 31/10/2026; các số liệu dưới đây phản ánh đầu ra được lưu tại thời điểm đó.

## 2. Nguồn và mục đích sử dụng

Bản ghi huấn luyện sử dụng bộ dữ liệu Hugging Face có định danh `uitnlp/vihsd`.

Bài toán là phân loại một bình luận tiếng Việt vào một trong ba lớp. Mỗi mẫu chỉ dùng văn bản và nhãn, không dùng ảnh, thông tin người dùng, bài đăng gốc hoặc chuỗi hội thoại làm đầu vào.

Tại thời điểm lập đặc tả, không có thông tin `revision` hoặc mã băm của dữ liệu được lưu lại; tài liệu cũng không có mô tả nguồn thu thập, quy trình gán nhãn hoặc giấy phép. Những thông tin đó cần được kiểm tra từ tài liệu gốc của bộ dữ liệu trước khi phân phối lại hay mở rộng phạm vi sử dụng. Thư mục này chứa đặc tả, không chứa bản sao dữ liệu huấn luyện.

## 3. Cấu trúc bản ghi và nhãn

### 3.1. Dữ liệu đầu vào

| Trường      | Kiểu/giá trị được sử dụng | Ý nghĩa                                                                                                              |
| ----------- | ------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `free_text` | Chuỗi sau bước lọc        | Nội dung bình luận tiếng Việt, có thể chứa emoji, ký hiệu, từ viết tắt, tiếng lóng, lỗi chính tả hoặc chữ không dấu. |
| `label_id`  | Nhãn số thuộc `{0, 1, 2}` | Lớp phân loại của bình luận.                                                                                         |

ViHSD dùng ánh xạ mã nhãn sau. Các quy tắc gán nhãn gốc dưới đây được tóm tắt từ mục 3.2 của [bài báo ViHSD](https://arxiv.org/abs/2103.11528):

| Mã  | Tên nhãn    | Quy tắc gán nhãn gốc ViHSD (tóm tắt)                                                                                                                                                                                                                                      |
| --- | ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `0` | `CLEAN`     | Không có nội dung quấy rối hay công kích.                                                                                                                                                                                                                                 |
| `1` | `OFFENSIVE` | Có nội dung quấy rối hoặc từ ngữ tục tĩu nhưng không nhắm công kích một đối tượng cụ thể.                                                                                                                                                                                 |
| `2` | `HATE`      | Có nội dung quấy rối/xúc phạm nhắm vào cá nhân hoặc nhóm. Bài báo cũng xếp vào lớp này trường hợp dùng từ tục tĩu để công kích một đối tượng cụ thể, hoặc biểu đạt thù ghét/phân biệt chủng tộc một cách hàm ý hay nghĩa bóng, không nhất thiết có từ tục tĩu tường minh. |

Hướng dẫn gốc là quy tắc của bộ dữ liệu; không nên xem đây là định nghĩa pháp lý hay chuẩn phổ quát. Bài báo ghi nhận tiếng lóng, viết tắt và nghĩa bóng có thể khiến người gán nhãn bất đồng.

Prompt Qwen là một quy tắc phân loại riêng được dùng để dự đoán tập validation. Prompt này định nghĩa `HATE` theo hướng công kích/thù ghét dựa trên các đặc điểm như sắc tộc, tôn giáo, quốc tịch, giới hoặc xu hướng tính dục; vì vậy không hoàn toàn tương đương với hướng dẫn gốc, vốn cũng tính đến lời tục tĩu nhắm vào một cá nhân hay nhóm cụ thể và ý nghĩa hàm ý/nghĩa bóng. Quy tắc Qwen không phải nguồn định nghĩa nhãn gốc và không thay đổi các nhãn có sẵn trong ViHSD. Không gộp dự đoán từ prompt Qwen vào nhãn ViHSD nếu chưa rà soát khác biệt tiêu chí.

### 3.2. Dữ liệu đưa vào mô hình phân loại

Sau tokenization và đổi tên cột, một bản ghi có các trường sau:

| Trường           | Dạng dữ liệu   | Vai trò                                                                 |
| ---------------- | -------------- | ----------------------------------------------------------------------- |
| `free_text`      | Chuỗi gốc      | Được giữ trong dataset; dùng để tạo token hoặc làm đầu vào prompt Qwen. |
| `labels`         | Nhãn số        | Đổi tên từ `label_id` để `Trainer` dùng tính loss.                      |
| `input_ids`      | Dãy mã token   | Kết quả tokenizer tương ứng với từng mô hình.                           |
| `attention_mask` | Dãy số `0`/`1` | Phân biệt vị trí token được chú ý và vị trí padding.                    |

Các đầu ra tokenization đã lưu cho thấy bản ghi giữ `free_text` và bổ sung các trường đầu vào của mô hình. Với PhoBERT, `free_text` vẫn là chuỗi gốc; văn bản đã tách từ chỉ là biến trung gian để tạo token, không được lưu thành cột riêng.

## 4. Chia tập và quy mô dữ liệu

Quy trình dùng trực tiếp ba split có sẵn, không tự chia lại, gộp tập hoặc lấy mẫu lại.

| Split        | Vai trò                                                | Số mẫu ban đầu | Số mẫu sau lọc — snapshot đầu ra |
| ------------ | ------------------------------------------------------ | ----------------------: | ------------------------------: |
| `train`      | Huấn luyện mô hình phân loại                           |                  24.048 |                          24.046 |
| `validation` | Đánh giá trong quá trình huấn luyện và báo cáo so sánh |                   2.672 |                           2.672 |
| `test`       | Được chuẩn bị trong nhánh PhoBERT                      |                   6.680 |                           6.680 |
| **Tổng**     |                                                        |              **33.400** |                      **33.398** |

Tỷ lệ chia ban đầu tương ứng 72% train, 8% validation và 20% test. Hai mẫu train bị loại bởi điều kiện chỉ giữ `free_text` là chuỗi; đầu ra lưu lại không cho biết nội dung hoặc kiểu cụ thể của hai mẫu đó.

### 4.1. Phân bố nhãn tập train trước lọc

Đầu ra phân bố nhãn train đã lưu là trước bước lọc:

| Nhãn            | Số mẫu | Tỷ lệ trên 24.048 mẫu |
| --------------- | -----: | --------------------: |
| `0 — CLEAN`     | 19.886 |                82,69% |
| `1 — OFFENSIVE` |  1.606 |                 6,68% |
| `2 — HATE`      |  2.556 |                10,63% |

Chưa có thống kê nhãn của 24.046 mẫu train sau lọc, nên không xác định hai mẫu bị loại thuộc lớp nào.

### 4.2. Phân bố nhãn tập validation

Cột `support` trong các báo cáo validation đã lưu ghi nhận:

| Nhãn            | Số mẫu | Tỷ lệ trên 2.672 mẫu |
| --------------- | -----: | -------------------: |
| `0 — CLEAN`     |  2.190 |               81,96% |
| `1 — OFFENSIVE` |    212 |                7,93% |
| `2 — HATE`      |    270 |               10,10% |

Tỷ lệ được làm tròn đến hai chữ số thập phân. Không có phân bố nhãn tập test trong các đầu ra được lưu. Dữ liệu train và validation lệch về `CLEAN`; quy trình không triển khai tăng mẫu, giảm mẫu hay trọng số lớp. Khi đánh giá cần xem thêm precision, recall và F1 từng lớp, đặc biệt `OFFENSIVE`, thay vì chỉ dựa vào accuracy.
