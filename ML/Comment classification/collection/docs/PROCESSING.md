# Gộp và tiền xử lý bình luận

Chạy các lệnh từ thư mục `ML/Comment classification`.

## Gộp các file CSV được chọn

Chạy từ `ML/Comment classification`, truyền ít nhất hai CSV sau `--inputs` và file mới sau `--output`:

```powershell
python -B collection/merge_comments.py --inputs data/collected/comments1.csv data/collected/comments2.csv data/collected/comments3.csv --output data/collected/comments_merged.csv
```

Script chỉ gộp các file bạn chỉ định, theo đúng thứ tự truyền vào. Giữ mọi dòng, gồm cả bình luận trùng, nội dung Unicode/emoji/xuống dòng, metadata và nhãn. Không tự làm sạch hoặc loại trùng. Các file phải có cùng tập tên cột (bao gồm `free_text`); thứ tự cột khác nhau vẫn được gộp, đầu ra theo thứ tự cột của file đầu tiên. Không trộn các CSV `.removed.csv` có thêm `filter_reason` với CSV dữ liệu thông thường.

Script kiểm tra CSV và chuẩn bị file tạm trước khi xuất kết quả; nếu có lỗi, không tạo file kết quả chưa đầy đủ. File đầu ra phải là tên mới, khác các đầu vào; không ghi đè file có sẵn và không sửa các file gốc. Không truyền lặp cùng một file đầu vào. Nếu sau đó tiền xử lý cả `data/collected`, hãy tránh xử lý đồng thời file gộp và các file nguồn để không tính dữ liệu hai lần; có thể truyền riêng `--input data/collected/comments_merged.csv` cho script tiền xử lý.

Nên dừng crawler ghi vào các CSV được chọn trước khi gộp. Script in số dòng từng file và tổng số dòng sau khi gộp thành công; không cần cài thêm thư viện.

## Tiền xử lý comment đã thu thập

Script loại nội dung trùng trước, rồi lọc các nhóm ít thông tin bên dưới. Để loại trùng giữa nhiều CSV, gộp các file đã chọn trước và truyền một file gộp vào script. Khi truyền cả thư mục, việc loại trùng được thực hiện riêng cho từng CSV.

Khóa trùng là nội dung `free_text` sau chuẩn hóa Unicode NFC và gom khoảng trắng, kèm `label_id` nếu có; giữ bản đầu tiên theo thứ tự dòng. Không đổi chữ hoa/thường, không bỏ dấu, từ đệm, dấu câu hoặc emoji để ép các câu thành giống nhau. `Quế Vân` và `Quế Vân á` không bị gộp. Nếu cùng nội dung nhưng khác nhãn (kể cả nhãn trống khác nhãn có giá trị), giữ cả hai để tránh mất nhãn; cần rà soát mâu thuẫn trước khi huấn luyện. Dữ liệu có cùng nội dung nhưng khác ngữ cảnh vẫn có thể mang sắc thái khác, vì vậy nên kiểm tra các dòng `duplicate` trong file bị loại.

Sau khi crawler dừng ghi CSV, chạy từ `ML/Comment classification`:

```powershell
# Mặc định: đọc mọi CSV trực tiếp trong data/collected.
python -B collection/preprocess_comments.py

# Chỉ xử lý một CSV; đầu ra vẫn mặc định vào data/processed.
python -B collection/preprocess_comments.py --input data/collected/comments2.csv

# Chọn riêng thư mục đầu ra, ví dụ khi chạy lại.
python -B collection/preprocess_comments.py --input data/collected/comments2.csv --output-dir data/processed_run2
```

Script dùng thư viện chuẩn Python, đọc các CSV trực tiếp trong `data/collected`, xuất từng cặp file mới vào `data/processed`: `<tên>.clean.csv` chứa dữ liệu được giữ và `<tên>.removed.csv` chứa dữ liệu bị loại kèm cột `filter_reason`. Không sửa dữ liệu gốc, nội dung được giữ hoặc các nhãn/cột đã có. Không ghi đè file đầu ra đã tồn tại; để chạy lại, dùng thư mục mới:

```powershell
python -B collection/preprocess_comments.py --input data/collected/comments2.csv --output-dir data/processed_run2
```

| Lý do loại | Ví dụ |
| --- | --- |
| `duplicate` | Nội dung và nhãn trùng sau chuẩn hóa Unicode/khoảng trắng; chỉ giữ bản đầu tiên, thực hiện trước các bộ lọc khác |
| `name_only` | Chỉ có tên trong danh sách `NAME_ONLY`: hiện gồm `Trấn Thành`, `Quế Vân`; không phân biệt hoa/thường, chấp nhận dấu câu bao quanh |
| `icon_only` | `🥰`, `❤️`, `👍🏽`, `1️⃣`, `:)))`, `:D`, `:v`, `<3`; chỉ có emoji/ký hiệu/emoticon và dấu câu/khoảng trắng |
| `timestamp_only` | `1:14:22`, `3: 00`; nhiều mốc cách nhau bằng khoảng trắng/xuống dòng cũng được nhận diện |
| `timestamp_reference` | `xem đoạn 3:00`, `Xem đoạn: 1:14:22.`, `xem doan 3: 00`; toàn câu chỉ dẫn mốc thời gian |
| `punctuation_only` | `.`, `...`, `!!!`, `,`, `--`; chỉ có dấu câu/khoảng trắng |
| `single_letter` | `h`, `H`, `v`, `D`, `h...`, `h ❤️`; chỉ còn một chữ cái ASCII ngoài danh sách giữ lại sau khi bỏ qua icon/dấu câu |
| `empty` | Rỗng, chỉ khoảng trắng hoặc ký tự định dạng vô hình |

Giữ bình luận có nhận xét như `Hay quá ❤️`, `tôi thích Trấn Thành` hoặc `xem đoạn 3:00, phần giải thích rất hay`. Quy tắc tên riêng là tiêu chí lọc dữ liệu của dự án, không phải kết luận rằng mọi câu chỉ nhắc tên đều không có sắc thái. Có thể thêm tên đã xác định vào tập `NAME_ONLY` trong `preprocess_comments.py`; script không tự đoán mọi tên người. `Quế Vân á` hoặc `Quế Vân ❤️` vẫn được giữ theo quy tắc hiện tại. Số đơn thuần (`1`, `123`), các từ ngắn như `ừ`, `ở`, `ý`, `à`, `ok`, `ko` được giữ. Quy tắc một chữ cái ASCII giữ riêng `a`, `e`, `i`, `k`, `o`, `u` (không phân biệt hoa/thường) vì có thể là cách xưng hô, viết tắt hoặc từ có nghĩa; đây là quy tắc lọc có thể bỏ nhầm chữ viết tắt khác, cần rà soát `.removed.csv`. Không xóa emoji hoặc mốc thời gian khỏi câu được giữ. Nhận diện icon dựa trên nhóm ký tự Unicode và một số emoticon phổ biến, không tra cứu toàn bộ bảng emoji. Chỉ chấp nhận phần giây/phần phút sau dấu hai chấm trong khoảng 0–59.

Script in số dòng đọc, giữ và từng lý do loại cho mỗi CSV. Không chạy trong lúc crawler còn ghi cùng file. Nếu gặp lỗi CSV giữa chừng, các đầu ra của file đó có thể chưa đầy đủ và cần kiểm tra trước khi dùng.

## Dữ liệu đầu ra và quyền sử dụng

CSV UTF-8 giữ `source`, `source_name`, `source_id`, `document_id`, `parent_id`, `free_text`, `published_at`, `collected_at` (UTC), `url`, `context`, `label_id`. Với replies API, context là bình luận cha; với HTML, context là phần được chọn trên trang. Các API không được yêu cầu trả tên/profile tác giả. Nội dung bình luận vẫn có thể chứa thông tin cá nhân và cần rà soát trước khi chia sẻ. Văn bản được giữ nguyên, gồm emoji và xuống dòng; khi mở bằng spreadsheet hãy nhập cột văn bản dưới dạng Text.

`label_id` để trống. Bước tiếp theo là rà soát dữ liệu tiếng Việt, thông tin cá nhân, trùng nội dung với các split ViHSD, rồi gán nhãn theo [hướng dẫn ViHSD trong dự án](../../docs/README.md). Chia tập theo thread/video/bài viết để hạn chế rò rỉ ngữ cảnh. Script chỉ loại trùng ID trong đầu ra, không tự loại trùng văn bản với ViHSD và không tự phát hiện ngôn ngữ.

Quyền đọc qua API không tự xác nhận quyền huấn luyện hoặc phân phối lại dataset. Kiểm tra phạm vi sử dụng, lưu trữ, làm mới/xóa dữ liệu theo thỏa thuận áp dụng trước khi thu thập thực tế. Script chưa tự thực hiện vòng đời lưu trữ/xóa dữ liệu. Tham khảo [YouTube Developer Policies](https://developers.google.com/youtube/terms/developer-policies), [Comments API](https://developers.google.com/youtube/v3/docs/comments/list), [Meta Comments edge](https://developers.facebook.com/docs/graph-api/reference/object/comments/) và [Meta Content Library](https://www.icpsr.umich.edu/sites/somar/meta-content-library). Tài liệu Meta Comments đã bị HTTP 429 khi tra cứu trong phiên xây dựng; cần đối chiếu quyền/version trong dashboard và tài liệu truy cập được trước khi dùng token thật.
