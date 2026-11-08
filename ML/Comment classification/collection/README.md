# Công cụ bình luận: thu thập, gộp và tiền xử lý

Chạy các lệnh bên dưới từ thư mục `ML/Comment classification`.

## Tài liệu theo chức năng

| Tài liệu | Nội dung |
| --- | --- |
| [Thu thập bình luận](docs/CRAWLING.md) | YouTube/API, cấu hình `.env`, tạo key/token, Facebook bằng trình duyệt, phân trang, giới hạn, tiến độ và kiểm tra offline. |
| [Gộp và tiền xử lý](docs/PROCESSING.md) | Gộp các CSV được chọn, lọc icon/mốc thời gian, file đầu ra, schema, nhãn và lưu ý dữ liệu. |

## File đầu vào

| File | Vai trò |
| --- | --- |
| [SOURCE.MD](SOURCE.MD) | Danh sách link được truyền cho YouTube bằng `--sources-file`. |
| [download.md](download.md) | Danh sách link mặc định cho script Facebook công khai; có thể đổi bằng `--urls-file`. |
| `config.json` | Nguồn được bật, giới hạn request/phân trang và nơi lưu CSV của crawler API. |
| `config.sources.example.json` | Cấu hình mẫu để tạo `config.json` khi chưa có file riêng. |
| `../.env` | Key/token cục bộ; không đưa vào Git. |

`SOURCE.MD` và `download.md` là danh sách link đầu vào, được giữ riêng với tài liệu hướng dẫn. Hai script lọc link theo tên miền. Chỉ thêm link đúng nền tảng muốn thu thập; file Facebook cần có ít nhất một link bài Facebook.

## Lệnh chạy nhanh

### YouTube qua API

Bật `youtube_selected_videos` trong `config.json`, điền `YOUTUBE_API_KEY` trong `.env`. Khi dùng `--sources-file`, có thể để `video_ids: []`.

```powershell
python -B collection/crawl_comments.py --source youtube_selected_videos --sources-file collection/SOURCE.MD --limit 1000
```

### Facebook công khai qua Chromium

Cần cài `requirements-facebook.txt` và Chromium theo [hướng dẫn thu thập](docs/CRAWLING.md#facebook-public). Script này không dùng Page access token.

```powershell
python -B collection/crawl_facebook_public.py --urls-file collection/download.md --limit 1000
```

### Gộp các CSV được chọn

```powershell
python -B collection/merge_comments.py --inputs data/collected/comments1.csv data/collected/comments2.csv --output data/collected/comments_merged.csv
```

### Tiền xử lý

Loại trùng nội dung trước khi lọc icon, mốc thời gian, mẩu một ký tự và bình luận chỉ có tên trong danh sách. Muốn loại trùng giữa nhiều file, truyền file đã gộp; xem [các quy tắc và lý do loại](docs/PROCESSING.md).

```powershell
# Mặc định: xử lý mọi CSV trực tiếp trong data/collected.
python -B collection/preprocess_comments.py

# Hoặc chọn một file và thư mục đầu ra mới.
python -B collection/preprocess_comments.py --input data/collected/comments_merged.csv --output-dir data/processed_run2
```

Gộp/tiền xử lý xuất file mới, giữ dữ liệu gốc và từ chối ghi đè đầu ra có sẵn. Nếu đã có cả file gộp và file nguồn trong cùng thư mục, hãy truyền riêng file muốn xử lý để tránh tính dữ liệu hai lần. Dừng crawler ghi CSV trước khi gộp/tiền xử lý. Giới hạn crawl đếm bình luận mới cho mỗi lần chạy; xem tài liệu thu thập để biết cơ chế dừng và loại trùng ID.
