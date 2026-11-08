# Thu thập bình luận

Script `crawl_comments.py` thu thập theo danh sách video, bài viết hoặc thread bạn chọn. Dữ liệu thô được lưu riêng, chưa gán nhãn và chưa gộp vào ViHSD.

Với hai link bài Facebook công khai bạn cung cấp, xem [script dùng trình duyệt](#facebook-public): `crawl_facebook_public.py`. Script này đọc giao diện công khai bằng Chromium, không dùng Page access token. Adapter `facebook` trong bảng dưới vẫn là luồng Graph API dành cho nội dung có quyền API phù hợp.

## Chọn script và file nguồn

| Script | Cách lấy dữ liệu | Key/token | File nguồn |
| --- | --- | --- | --- |
| `crawl_comments.py` | YouTube/Facebook qua API; báo/diễn đàn qua HTML cấu hình riêng | YouTube cần `YOUTUBE_API_KEY`; Facebook API cần Page token và quyền phù hợp | YouTube: thêm `--sources-file collection/SOURCE.MD`. Chỉ lấy link YouTube và chuyển thành video ID. |
| `crawl_facebook_public.py` | Đọc bình luận Facebook công khai qua Chromium/Playwright | Không dùng key/token trong `.env` | Mặc định đọc `collection/download.md`, chỉ lấy link Facebook. Đổi file bằng `--urls-file`. |

Các file nguồn có thể chứa cả YouTube và Facebook: danh sách đánh số, URL thuần hoặc link Markdown đều được hỗ trợ; hai script lọc theo tên miền và không sửa file nguồn. Tiêu đề `## Youtube`/`## Facebook` chỉ giúp bạn quản lý; việc lọc không phụ thuộc tiêu đề. Các ví dụ YouTube bên dưới dùng [SOURCE.MD](../SOURCE.MD); Facebook mặc định dùng [download.md](../download.md). Nếu `download.md` chỉ có link YouTube, cần thêm link bài Facebook trước khi chạy script Facebook.

Từ thư mục `ML/Comment classification`, các lệnh để **bạn chủ động chạy** là:

```powershell
# YouTube: bật youtube_selected_videos trong config.json và điền key vào .env.
# --sources-file thay danh sách video_ids trong bộ nhớ cho lần chạy này.
python -B collection/crawl_comments.py --source youtube_selected_videos --sources-file collection/SOURCE.MD

# Facebook công khai: đọc collection/download.md, tối đa 1.000 bình luận mới tổng cộng.
# Cài requirements-facebook.txt và Chromium theo mục Facebook công khai bên dưới trước lần chạy đầu.
python -B collection/crawl_facebook_public.py --limit 1000
```

Nếu tên nguồn YouTube trong cấu hình của bạn khác `youtube_selected_videos`, thay giá trị `--source` cho khớp. `--sources-file` chỉ cung cấp link video; vẫn cần bật nguồn YouTube trong `config.json`. Dữ liệu đầu ra của API dùng đường dẫn `output` trong JSON; script Facebook công khai mặc định ghi `data/collected/facebook_public.csv`.

### `video_ids` khi dùng hoặc không dùng file nguồn

| Cách chạy YouTube | Giá trị `video_ids` trong `config.json` |
| --- | --- |
| Có `--sources-file collection/SOURCE.MD` | Có thể để `[]`. Script lấy ID từ các link YouTube trong file và thay danh sách trong bộ nhớ cho lần chạy đó; không ghi sửa JSON. |
| Không có `--sources-file` | Phải điền ít nhất một ID video hợp lệ. Để `[]` sẽ báo lỗi cấu hình. |

Ví dụ kiểm tra cấu hình khi dùng file nguồn, không gọi API:

```powershell
python -B collection/crawl_comments.py --source youtube_selected_videos --sources-file collection/SOURCE.MD --validate-only
```

### Cơ chế lấy bình luận YouTube

1. Xử lý video lần lượt theo thứ tự link YouTube trong `SOURCE.MD` (hoặc thứ tự `video_ids` nếu không dùng file nguồn). Không tự chia đều số lượng giữa các video.
2. Gọi `commentThreads.list`, mỗi trang tối đa 100 bình luận chính, sắp xếp `order=time` theo thời gian, mới trước. Theo `nextPageToken` để tải trang tiếp theo.
3. Với `include_replies: true`, sau mỗi bình luận chính, script gọi `comments.list` để lấy các replies của bình luận đó trước khi sang bình luận chính tiếp theo. Với `false`, chỉ lấy bình luận chính.
4. `max_pages: 10` giới hạn 10 trang cho mỗi video và riêng từng chuỗi replies của một bình luận chính. Nếu API còn trang sau khi chạm giới hạn, nguồn được báo là thu thập chưa đủ; dữ liệu đã ghi vẫn được giữ.
5. `--limit` mặc định 1.000 dòng mới cho cả lần chạy, gồm bình luận chính và replies của tất cả video/nguồn đã chọn. Có thể đạt giới hạn ngay trong video đầu tiên; không phải 1.000 cho mỗi video. `max_comments` nếu cấu hình giới hạn riêng số dòng mới của nguồn YouTube, không phải từng video.
6. Script còn dừng khi đạt `max_requests`, hết dữ liệu API trả về, hoặc gặp lỗi quyền/quota/kết nối. Vì vậy mặc định không bảo đảm lấy toàn bộ bình luận của từng video.

Khi chạy lại, script quét lại từ đầu và bỏ qua ID đã có trong CSV; `--limit` tiếp tục đếm dòng mới. Script không lưu checkpoint phân trang và không tự cập nhật/xóa bình luận cũ. Chỉ lấy nội dung API cho phép truy cập; bình luận bị xóa/ẩn hoặc video tắt bình luận không được khôi phục.

## Nguồn hỗ trợ

| Nguồn | Đầu vào cần điền | Phạm vi thực hiện |
| --- | --- | --- |
| YouTube | `video_ids`, biến môi trường `YOUTUBE_API_KEY` | Bình luận chính bằng `commentThreads.list`, replies bằng `comments.list`, có phân trang. Không tìm kiếm video tự động. |
| Facebook Page | `post_ids`, `FACEBOOK_PAGE_ACCESS_TOKEN`, `META_GRAPH_VERSION` | Graph API `/POST_ID/comments` và `/COMMENT_ID/comments`. Token phải có quyền đọc nội dung tương ứng; không cấp thêm quyền hoặc đọc tùy ý trang/nhóm/tài khoản. |
| Báo điện tử | `start_urls`, CSS selectors đúng với website | Bình luận có sẵn trong HTML, theo trang bình luận tiếp theo nếu cấu hình. |
| Diễn đàn | `start_urls`, CSS selectors đúng với diễn đàn | Nội dung bài trả lời trong HTML; giữ ID thread xuyên các trang phân trang. |

Đây là adapter dùng chung cho báo/diễn đàn, chưa phải bộ trích xuất đã xác minh cho một website cụ thể. Các selector trong cấu hình mẫu chỉ minh họa. Bình luận tải bằng JavaScript/API riêng sẽ cần adapter theo website sau khi xác định endpoint và quyền sử dụng. Script không dùng cookies đăng nhập, trình duyệt, proxy, giải CAPTCHA hoặc endpoint ẩn. Facebook ở đây dùng Pages Graph API, không phải Meta Content Library; MCL cần quyền và môi trường nghiên cứu riêng.

## Cài đặt và cấu hình

Chạy PowerShell từ thư mục `ML/Comment classification`:

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r collection/requirements.txt
Copy-Item collection/config.sources.example.json collection/config.json
```

Sau khi kích hoạt, các lệnh `python` trong phần còn lại của hướng dẫn sẽ chạy trong `.venv`. Khi làm xong, dùng `deactivate` để thoát môi trường ảo.

Trong `collection/config.json`, đổi `enabled` thành `true` cho nguồn muốn chạy, điền danh sách ID/URL và thay `user_agent` bằng thông tin dự án/liên hệ thật. Mẫu mặc định tắt mọi nguồn để tránh gọi API bằng giá trị minh họa. Không ghi key/token vào JSON hoặc source code.

Chưa có key/token? Xem [hướng dẫn tạo YouTube API key và Facebook Page access token](#credentials), gồm bước tạo trên cổng nhà phát triển, kiểm tra quyền và nhập vào máy.

Ví dụ phần YouTube sau khi cấu hình (thay ID minh họa bằng video bạn chọn):

```json
{
  "name": "youtube_selected_videos",
  "type": "youtube",
  "enabled": true,
  "video_ids": ["VIDEO_ID_11"],
  "include_replies": true,
  "max_pages": 10
}
```

`video_ids` nhận ID 11 ký tự, không nhận URL. `post_ids` nhận ID số hoặc dạng `PAGE_ID_POST_ID`. Chọn version Graph API còn được hỗ trợ theo dashboard của app Meta, dạng `vXX.X`; script không tự chọn version.

Lưu key/token vào `.env` ngay trong thư mục `ML/Comment classification`. Nếu chưa có file, sao chép mẫu:

```powershell
Copy-Item .env.example .env
```

Mở `.env` và điền các giá trị cần dùng; chỉ lấy YouTube thì chỉ cần `YOUTUBE_API_KEY`:

```dotenv
YOUTUBE_API_KEY=your_youtube_api_key
FACEBOOK_PAGE_ACCESS_TOKEN=
META_GRAPH_VERSION=
```

Script tự đọc đúng file `.env` cạnh thư mục `collection` bằng `python-dotenv`, không cần các lệnh `$env:...` mỗi lần mở PowerShell. Biến đã đặt trong PowerShell được ưu tiên hơn `.env`; nếu cần dùng giá trị trong file, hãy xóa biến cũ ở phiên PowerShell hoặc mở một phiên mới. Script không nội suy `${...}` trong giá trị key/token. `.env` đã được ignore Git; `.env.example` chỉ chứa giá trị trống được giữ làm mẫu. Không sao chép đè `.env` đã có key/token.

Đối với báo/diễn đàn:

- `access_basis`: ghi căn cứ truy cập thực tế, ví dụ URL điều khoản cho phép hoặc thông tin chấp thuận của đơn vị quản lý. Đây là ghi chú do người cấu hình cung cấp, không phải xác nhận pháp lý của script.
- `start_urls`: danh sách URL HTTPS chính thức của từng bài/thread.
- `comment_selector`: chọn từng khối bình luận; `text_selector`: chọn riêng nội dung bên trong khối đó, tránh lấy tên tác giả và các replies lồng vào.
- `id_attribute`, `parent_attribute`: thuộc tính trên khối bình luận chứa ID và ID cha. Nếu không có ID, script dùng hash của URL trang và nội dung; hai bình luận giống hệt trên cùng trang có thể bị gộp.
- `time_selector`: phần tử chứa thuộc tính `datetime` trong mỗi bình luận; bỏ trường này nếu không có.
- `context_selector`: tiêu đề/ngữ cảnh của trang, mặc định `title`.
- `next_selector`: link trang bình luận tiếp theo; bỏ hoặc đặt chuỗi rỗng nếu không có phân trang. Mỗi URL tiếp theo phải cùng origin với URL bắt đầu.

Script đọc `robots.txt` cho mỗi origin; từ chối trang bị cấm, áp dụng `Crawl-delay`/`Request-rate`. Robots trả 404 được xem là không có quy tắc; lỗi truy cập robots khác làm dừng nguồn. `robots.txt` không phải giấy phép tái sử dụng nội dung. Redirect bị từ chối; hãy cung cấp URL HTTPS canonical ngay từ đầu.

## Chạy

Khi chạy, script báo ngay nguồn bắt đầu, số ID có sẵn trong CSV và video YouTube đang xử lý. Tiến độ được in sau mỗi 25 bản ghi đọc được hoặc khi nhận bản ghi mới sau ít nhất 3 giây kể từ thông báo trước:

```text
[YouTube] Video 1/4: r1rmnvgJcI4
[Progress] Saved: 125/1000 new | Duplicates skipped: 10 | Requests: 8
```

`Saved` là số bình luận mới đã ghi CSV trong lần chạy này, không bao gồm dữ liệu có sẵn. `Duplicates skipped` là số ID trùng đã bỏ qua; bình luận rỗng không tính vào số này. `Requests` là số request thực hiện (gồm retry), không phải số bình luận. Số request cuối cùng vẫn được tính nếu request đó gặp lỗi. Có tổng kết khi kết thúc, chạm giới hạn hoặc dừng bằng Ctrl+C; khi đang chờ một request, thông báo chỉ cập nhật sau khi nhận dữ liệu hoặc lỗi. Các dòng tiến độ được flush ngay ra terminal.

Tiến trình đang chạy dùng phiên bản mã khi nó khởi động. Nếu vừa cập nhật script, dừng phiên cũ bằng Ctrl+C và chạy lại để thấy thông báo mới; các ID đã ghi CSV sẽ được bỏ qua khi chạy lại, còn `--limit` vẫn đếm số dòng mới cho lần chạy đó.

```powershell
# Chỉ kiểm tra cấu hình, không kết nối mạng hay kiểm tra key/token.
python -B collection/crawl_comments.py --validate-only

# Chạy riêng YouTube, tối đa 200 bình luận mới.
python -B collection/crawl_comments.py --source youtube_selected_videos --limit 200

# Chạy tất cả nguồn đã bật.
python -B collection/crawl_comments.py --limit 1000

# Chạy kèm file source
python -B collection/crawl_comments.py --source youtube_selected_videos --sources-file collection/SOURCE.MD --limit 1000
```

`--source` có thể lặp lại. `--config` là đường dẫn tương đối với thư mục đang chạy; `output` trong JSON là đường dẫn tương đối với thư mục chứa JSON. Giá trị mẫu lưu vào `data/collected/comments.csv`. File cấu hình cá nhân và thư mục dữ liệu thu thập đã được ignore Git.

### Mục tiêu 1.000 bình luận từ bốn nguồn đã chọn

`config.sources.example.json` đặt mục tiêu tạm chia đều: YouTube 250, Facebook 250, Báo Mới 250, Kênh14 250. `max_comments` giới hạn số dòng mới của từng nguồn trong một lần chạy; `--limit 1000` giới hạn tổng số dòng mới. Mẫu vẫn tắt các nguồn và để trống ID/URL/selector còn thiếu. Đây là cấu hình chuẩn bị, chưa phải bằng chứng đã thu được dữ liệu hoặc đã xác nhận hỗ trợ hai website.

```powershell
# Dùng mẫu này để tạo cấu hình bốn nguồn khi chưa có config.json riêng.
Copy-Item collection/config.sources.example.json collection/config.json
python -B collection/crawl_comments.py --limit 1000
```

Nếu đã có `config.json`, hãy bổ sung các mục cần thiết vào file đang có để giữ thông tin đã cấu hình. `max_comments` đếm ID mới trong CSV, không xác nhận văn bản khác nhau hoặc mẫu đã đủ chất lượng tiếng Việt. Script không tự chuyển suất còn thiếu của nguồn bị lỗi sang nguồn khác và không tự dừng ở tổng 1.000 dòng đã lưu qua nhiều lần chạy; trước khi chạy tiếp hãy chọn nguồn và giảm giới hạn theo số còn thiếu.

Kiểm tra ban đầu ngày 08/11/2026: [Báo Mới mô tả mình là trang tổng hợp thông tin](https://baomoi.com/staticpages/intro.epi). Nội dung bài báo tổng hợp và các câu trích dẫn phản ứng cư dân mạng trong bài Kênh14 không được coi là bình luận độc giả gốc. Các trang công khai đã đọc chưa xác nhận được danh sách bình luận có thể trích xuất; yêu cầu lấy HTML trực tiếp trong môi trường chạy script cũng gặp lỗi mạng. Vì vậy chưa đặt selector thật hoặc bật hai nguồn báo. Cần URL bài có phần bình luận thực tế và xác minh cách tải trước khi bổ sung adapter phù hợp.

`max_pages` giới hạn mỗi chuỗi phân trang (video, replies của một bình luận, bài Page hoặc thread). `max_requests` giới hạn tổng request, gồm robots, replies, retry; `delay_seconds` đặt khoảng cách tối thiểu giữa các request. HTTP 429 và 5xx được thử lại tối đa hai lần; lỗi quyền truy cập/quota không được né bằng đổi token/proxy. Page limit hoặc lỗi nguồn trả exit code 1, vẫn giữ các dòng đã lưu. Row limit là dừng có chủ đích, kết quả được thông báo là một phần dữ liệu. Ctrl+C trả code 130.

Chạy lại sẽ đọc file CSV và bỏ qua ID đã lưu theo `(source, source_name, source_id)`. Script gọi lại các trang từ đầu, không lưu checkpoint phân trang và không tự cập nhật/xóa bình luận đã thay đổi hoặc bị gỡ. Giữ nguyên `source_name` khi chạy lại cùng nguồn. Nguồn lỗi không ngăn các nguồn tiếp theo chạy nếu còn ngân sách request.


<a id="credentials"></a>

## Tạo API key và Page access token

Chạy các lệnh dưới đây từ thư mục `ML/Comment classification`. Key/token lưu cục bộ trong `.env`, không gửi vào chat, ảnh chụp, Git hoặc file cấu hình JSON. Script tự đọc `.env` mỗi lần chạy; biến môi trường đã đặt trong PowerShell sẽ được ưu tiên.

Nếu chưa có `.env`, tạo từ mẫu và mở bằng trình soạn thảo:

```powershell
Copy-Item .env.example .env
notepad .env
```

Không sao chép đè nếu file đã chứa key/token. Cài/cập nhật dependencies bằng `python -m pip install -r collection/requirements.txt` trong môi trường Python đang dùng.

### YouTube: tạo API key

1. Đăng nhập [Google Cloud Console](https://console.cloud.google.com/).
2. Mở bộ chọn project trên thanh đầu trang → **New project**, đặt tên, ví dụ `SoulSpaceResearch`, rồi chọn project vừa tạo.
3. Vào **APIs & Services → Library**, tìm **YouTube Data API v3**, chọn **Enable**.
4. Vào **APIs & Services → Credentials → Create credentials → API key**. Không cần tạo OAuth client cho tác vụ đọc bình luận công khai của script này.
5. Trong cấu hình key, đặt **API restrictions → Restrict key → YouTube Data API v3**, rồi lưu. Google khuyến nghị cả giới hạn API và giới hạn ứng dụng; với script Python chạy trên máy, chọn giới hạn ứng dụng phù hợp môi trường thực tế, không chọn HTTP referrer dành cho website.
6. Sao chép key, điền vào dòng `YOUTUBE_API_KEY` trong `.env` rồi lưu:

```dotenv
YOUTUBE_API_KEY=your_youtube_api_key
```

Các bước project, bật API và tạo key được mô tả trong [YouTube authentication](https://developers.google.com/youtube/data/guides/authentication); các lựa chọn hạn chế key xem [Google Cloud API keys](https://docs.cloud.google.com/docs/authentication/api-keys). Nhãn giao diện có thể thay đổi theo tài khoản/ngôn ngữ.

Trong `collection/config.json`, tìm nguồn `youtube` (hoặc `youtube_selected_videos` nếu dùng mẫu chung), đặt `enabled: true` và điền `video_ids`. Ví dụ URL `https://www.youtube.com/watch?v=abcdefghijk` tương ứng ID minh họa `abcdefghijk`; hãy thay bằng ID video thật bạn chọn. Không dán cả URL vào `video_ids`.

Kiểm tra key bằng một lần lấy thử ít dữ liệu (vẫn tiêu thụ quota):

```powershell
python -B collection/crawl_comments.py --source youtube --limit 5
```

Dùng đúng tên nguồn trong config nếu khác `youtube`. Nếu nguồn hết bình luận, script có thể lưu ít hơn 5 dòng.

| Kết quả | Cách xử lý |
| --- | --- |
| `Missing environment variable: YOUTUBE_API_KEY` | Điền key vào `.env` cạnh thư mục `collection`, lưu file và kiểm tra tên biến. Biến rỗng đã đặt ở PowerShell có thể che giá trị trong file. |
| HTTP 400/403 | Kiểm tra key, project đã bật YouTube Data API v3 chưa, API/application restrictions và quota trong Console. Script cố ý không in nguyên URL lỗi có key. |
| Bình luận bị tắt hoặc không được phép truy cập | Đổi sang video công khai có bình luận truy cập được; không né hạn chế. |

Xem quota thực tế của project tại **APIs & Services → YouTube Data API v3 → Quotas**; [YouTube getting started](https://developers.google.com/youtube/data/getting-started) giải thích quota và cách xin tăng nếu cần.

### Facebook: lấy Page access token

Hướng dẫn này dành cho Page mà tài khoản Facebook của bạn có quyền quản lý/phân quyền phù hợp. Tạo app hoặc lấy token không tự cấp quyền đọc bình luận của fanpage bất kỳ, ví dụ fanpage Kênh14. Nếu bạn chưa có quyền với Page muốn lấy, nguồn Facebook vẫn cần một phương án truy cập được Meta/chủ thể liên quan cho phép.

1. Đăng nhập [Meta for Developers](https://developers.facebook.com/), hoàn thành đăng ký nhà phát triển nếu được yêu cầu.
2. Mở [My Apps](https://developers.facebook.com/apps/), tạo app và cấu hình trường hợp sử dụng/product liên quan **Facebook Login/Pages API** phù hợp với app. Màn hình tùy loại tài khoản và use case; đừng chọn một loại app chỉ để mong xuất hiện quyền đang thiếu.
3. Mở [Graph API Explorer](https://developers.facebook.com/tools/explorer/), chọn app vừa tạo và phiên bản API mà app hỗ trợ. Ghi lại version dạng `vXX.X`.
4. Tạo **User access token** cho tài khoản có quyền trên Page. Kiểm tra các quyền `pages_show_list` (liệt kê Page), `pages_read_engagement` và `pages_read_user_content` (nội dung do người dùng đăng, gồm bình luận) theo yêu cầu của endpoint/version đang dùng. Cấp quyền cho đúng Page trong hộp thoại đăng nhập. Một số quyền hoặc phạm vi sử dụng cần App Review/Advanced Access; nếu bị yêu cầu, phải hoàn thành quy trình tương ứng.
5. Trong Explorer, dùng User token vừa cấp để gửi GET:

```text
me/accounts?fields=id,name,access_token,tasks
```

6. Chọn đúng Page trong `data`. Trường `access_token` của Page đó là **Page access token** cần dùng. Không dùng App access token hay App Secret thay thế. Quy trình User token → Page token này có trong [bộ Facebook API chính thức của Meta trên Postman](https://www.postman.com/meta/facebook/documentation/r56bjfd/facebook-api?entity=request-23987686-0b79260c-96bd-49de-875b-6076213785fc).
7. Điền Page token và version vào các dòng tương ứng trong `.env`. Giữ nguyên dòng YouTube đã điền trước đó:

```dotenv
FACEBOOK_PAGE_ACCESS_TOKEN=your_page_access_token
META_GRAPH_VERSION=
```

Điền version thực tế từ app, dạng `vXX.X`, vào sau `META_GRAPH_VERSION=`; không dùng nguyên chữ `vXX.X` làm version.

Đối chiếu phạm vi quyền với [Pages API getting started](https://developers.facebook.com/docs/pages-api/getting-started/) và [Comments edge](https://developers.facebook.com/docs/graph-api/reference/object/comments/). Hai trang Developer Docs có thể trả HTTP 429 trong công cụ đọc; bộ Postman chính thức xác nhận quy trình lấy Page token, nhưng quyền đọc comment phải được kiểm tra trong app/phiên bản của bạn.

Trước khi nhập nhiều bài vào config, dùng Page token trong Explorer thử GET cho một bài mà Page cho phép đọc:

```text
PAGE_ID_POST_ID/comments?fields=id,message,created_time&limit=1
```

Thay `PAGE_ID_POST_ID` bằng ID thật. Nếu được trả dữ liệu, điền ID đó vào `post_ids`, bật nguồn `facebook` rồi chạy:

```powershell
python -B collection/crawl_comments.py --source facebook --limit 5
```

Nếu `me/accounts` trả rỗng, kiểm tra đúng User token, tài khoản/Page được chọn và quyền `pages_show_list`. Nếu GET comments báo thiếu quyền, xem mã lỗi trong Explorer và quyền app thực sự được cấp; Page token hợp lệ vẫn có thể không được phép đọc bài đó. Token có thể hết hạn hoặc bị thu hồi; có thể kiểm tra bằng [Access Token Debugger chính thức](https://developers.facebook.com/tools/debug/accesstoken/), không dùng website kiểm tra token bên ngoài.

### Báo Mới và Kênh14

Adapter HTML hiện tại không yêu cầu API key/token chung. Hai nguồn này vẫn tắt trong mẫu cho đến khi xác định URL bài có bình luận độc giả, cách tải dữ liệu và selector/adapter phù hợp. Không dùng Facebook token để gọi endpoint riêng của hai website, và không lấy văn bản bài báo làm bình luận.

### Chạy sau khi thiết lập

Nếu bắt đầu cấu hình mới cho bốn nguồn đã chọn:

```powershell
Copy-Item collection/config.sources.example.json collection/config.json
```

Điền ID/URL và bật các nguồn đủ điều kiện trước, rồi kiểm tra:

```powershell
python -B collection/crawl_comments.py --validate-only
python -B collection/crawl_comments.py --limit 1000
```

`--validate-only` chỉ kiểm tra cấu hình, không xác nhận key/token hợp lệ. Giới hạn bình luận chỉnh bằng `--limit` và `max_comments`; tên file cấu hình không chứa số lượng.

Nếu key/token bị lộ, thu hồi hoặc tạo lại tại cổng chính thức. Script hiện không tự làm mới token và không tự xử lý yêu cầu lưu trữ/xóa dữ liệu của từng nền tảng.

<a id="facebook-public"></a>

## Facebook công khai bằng trình duyệt

`crawl_facebook_public.py` là script riêng cho link bài viết công khai. Nó dùng Playwright/Chromium để đọc phần bình luận trên trang, không dùng Page access token hoặc yêu cầu quản trị Page. Khả năng đọc phụ thuộc nội dung thực sự được Facebook hiển thị; không bảo đảm lấy toàn bộ bình luận/replies. Đây là cách truy cập khác với adapter Pages Graph API trong `crawl_comments.py`.

### Cài đặt

Từ thư mục `ML/Comment classification`, dùng môi trường `.venv` đã có:

```powershell
.\.venv\Scripts\Activate.ps1
python -m pip install -r collection/requirements-facebook.txt
python -m playwright install chromium
```

Các lệnh cài đặt không thu thập bình luận. Xem [Playwright Python](https://playwright.dev/python/docs/library) để biết cách cài thư viện và Chromium.

### Chạy khi bạn muốn thu thập

Script mặc định đọc `collection/download.md`. File có thể chứa cả mục YouTube và Facebook, danh sách đánh số, link Markdown `[tên](URL)` hoặc URL thuần. Script chỉ lấy tên miền `facebook.com`/`www.facebook.com`/`m.facebook.com`, bỏ qua link YouTube và loại trùng URL. File cần có ít nhất một link bài Facebook; nếu chỉ chứa link YouTube, script báo không tìm thấy bài Facebook. Tên mục chỉ để bạn quản lý, việc lọc dựa trên tên miền. Script chỉ chạy thu thập khi bạn chủ động gọi lệnh:

```powershell
python -B collection/crawl_facebook_public.py --limit 1000
```

Để dùng một file danh sách khác, truyền `--urls-file collection/ten_file.md`. Các file nguồn chỉ được đọc; script không sửa nội dung link của bạn.

Ví dụ ghi rõ file nguồn mặc định trong lệnh:

```powershell
python -B collection/crawl_facebook_public.py --urls-file collection/download.md --limit 1000
```

Mặc định mở cửa sổ Chromium, giới hạn 1.000 dòng mới cho cả hai bài, tối đa 100 vòng tải mỗi bài. Điều chỉnh bằng tham số:

```powershell
python -B collection/crawl_facebook_public.py --limit 200 --max-rounds 30
```

Kết quả lưu vào `data/collected/facebook_public.csv`, cùng schema với crawler API: nguồn, ID bình luận, ID/URL bài, ID cha nếu xác định được, văn bản, thời gian thu thập, URL bình luận, tiêu đề làm ngữ cảnh và nhãn trống. `published_at` để trống vì giao diện có thể chỉ hiển thị thời gian tương đối. Script không xuất tên/profile tác giả thành trường riêng; nội dung bình luận vẫn có thể chứa tên/thông tin cá nhân do người viết nhắc tới.

### Cách hoạt động và giới hạn

- Dùng locale `en-US`; nhận diện khối `role=article` có nhãn `Comment by ...` và nội dung `span[lang]`. Đây là selector theo giao diện đã quan sát, có thể thay đổi.
- Đóng popup đăng nhập tùy chọn nếu popup che nội dung công khai. Nếu đăng nhập/checkpoint hoặc CAPTCHA thực sự chặn nội dung, script dừng; không đăng nhập tự động, không dùng cookies tài khoản, proxy hoặc endpoint nội bộ.
- Chọn `All comments` khi có menu sắp xếp; mở `See more`, replies và cuộn vùng danh sách để tải thêm.
- Chỉ ghi bình luận có ID và văn bản nhận diện được; giữ emoji có `alt`. Bình luận chỉ có ảnh/sticker hoặc không có ID/văn bản phù hợp có thể bị bỏ qua. Bình luận chưa mở được `See more` được bỏ qua để tránh lưu đoạn cắt.
- Dừng sau 5 vòng không có ID mới hoặc khi chạm giới hạn. Đây là dấu hiệu dừng, không chứng minh đã tải hết. Số đếm bình luận trên Facebook có thể khác số dòng CSV vì replies, nội dung ẩn/đã xóa và cách lọc.
- Ghi nối tiếp, flush từng dòng và loại trùng theo ID qua `Output` hiện có. `--limit` đếm dòng mới trong mỗi lần chạy, không phải tổng số dòng sẵn trong file. Dữ liệu browser có `source_name=facebook_public_browser` để phân biệt với dữ liệu API.

Script không tự gán nhãn hay gộp ViHSD. Quyền xem công khai không tự xác nhận quyền phân phối lại/huấn luyện; kiểm tra điều kiện sử dụng áp dụng cho dữ liệu trước khi đưa vào dataset chia sẻ.

### Kiểm tra đã thực hiện

Kiểm tra parser bằng HTML mô phỏng và kiểm tra luồng bằng mock; không chạy script này để thu thập trên hai bài thật. Việc quan sát UI trước đó xác nhận có phần bình luận công khai, không xác nhận script chạy ổn định hoặc lấy đủ bình luận.

## Kiểm tra offline

```powershell
$env:PYTHONPATH = 'collection'
python -B -m unittest discover -s collection/test -p 'test_*.py' -v
python -B -m unittest discover -s collection -p 'test_source_links.py' -v
```

Các kiểm tra mô phỏng phân trang API, replies, HTML, robots, retry và ghi CSV khi chạy lại; không gọi API thật hay xác minh selector của website thực tế.
