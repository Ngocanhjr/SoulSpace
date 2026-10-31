# Phân loại chủ đề bài viết

Ngày bổ sung: 30/10/2026. Cập nhật: 31/10/2026. Trạng thái: đặc tả giai đoạn AI sau migration core; schema chủ đề và
confidence đã đưa vào V1 trước lần chạy Flyway đầu tiên, model/API/frontend triển khai theo thiết kế này. Tài liệu bổ
sung cho [nghiệp vụ](01-nghiep-vu.md), [API](02-api.md), [PostgreSQL](03-postgresql.md) và [ERD](erd.md).

## Phạm vi

Tự động nhận dạng không hoặc nhiều chủ đề từ nội dung văn bản của bài viết. Người dùng đăng bài như hiện tại, không chọn chủ đề bắt buộc và không xác nhận kết quả model. Tác giả có thể đổi hoặc bỏ chủ đề trong phần chỉnh sửa bài viết.

Chủ đề là dữ liệu nền cho gợi ý nội dung sau này. Phiên bản này chưa triển khai feed cá nhân hóa, phân tích xu hướng, collaborative filtering, phân loại ảnh hoặc tìm kiếm ngữ nghĩa. Danh mục ban đầu được seed và ánh xạ với nhãn model; chưa thêm giao diện quản trị danh mục.

## Quy tắc nghiệp vụ

| Mã    | Quy tắc                                                                                                                                                                                                                                                                              |
|-------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| TOP01 | Tạo bài không yêu cầu categoryIds. Lưu bài và trả response trước; phân loại chạy ở nền sau DB commit. Lỗi hoặc timeout model không làm mất bài hay rollback thao tác đăng.                                                                                                           |
| TOP02 | Mỗi bài có 0..n chủ đề; mỗi chủ đề thuộc 0..n bài. Model phân loại đa nhãn (multi-label), chỉ tự gắn các chủ đề đủ tin cậy thuộc danh mục hợp lệ; không tạo danh mục từ chuỗi do model trả về.                                                                                       |
| TOP03 | Nội dung trống, chỉ có ảnh, không thuộc danh mục hoặc dự đoán chưa đủ tin cậy thì giữ tập chủ đề rỗng (không có hàng blog_categories). Không ép vào một nhãn tùy ý; UI ẩn chip chủ đề và không hiện cảnh báo bắt người dùng xử lý.                                                   |
| TOP04 | Tác giả có thể chọn nhiều chủ đề hoặc chọn “Bỏ tất cả chủ đề”. Cả hai hành động đặt category_mode=MANUAL, kể cả khi tập chủ đề rỗng; worker không được ghi đè lựa chọn này.                                                                                                          |
| TOP05 | Tùy chọn “Tự động phân loại” chuyển category_mode=AUTO, giữ tập MANUAL để đối chiếu, xóa tập AUTO cũ rồi phân loại lại sau commit. Không có bước xác nhận bắt buộc trong luồng đăng bài.                                                                                             |
| TOP06 | Khi sửa nội dung, xóa tập AUTO cũ và phân loại lại từ nội dung mới; giữ tập MANUAL của tác giả. category_mode quyết định tập nào hiển thị. Chỉ đổi ảnh hoặc published không cần phân loại lại văn bản.                                                                               |
| TOP07 | Trước khi ghi kết quả, khóa hàng blog và kiểm bài còn tồn tại, nội dung hiện tại giống snapshot đã suy luận; chỉ cập nhật các hàng nguồn AUTO và không đổi category_mode. Nếu không khớp thì bỏ kết quả cũ; không tái tạo bài đã xóa. Không gọi model trong transaction giữ khóa DB. |
| TOP08 | Phân loại không đổi published, thời gian đăng, updated_at của chỉnh sửa bài hoặc counters. Chủ đề không cấp thêm quyền đọc; bài private vẫn chỉ chủ bài được xem.                                                                                                                    |

Ví dụ: người dùng đăng “Mai thi rồi mà giờ vẫn chưa học xong”; bài xuất hiện ngay, sau đó có thể được gắn “Học tập”. Một bài về học lập trình có thể đồng thời được gắn “Học tập” và “Công nghệ”. Nếu người dùng tự chọn lại tập chủ đề, model giữ nguyên lựa chọn đó ở các lần xử lý tiếp theo cho đến khi họ bật lại chế độ tự động.

## Dữ liệu mở rộng

Schema V1 gồm categories (3 cột), blog_categories (4 cột, gồm confidence) và blogs.category_mode; tổng **11 bảng, 65 cột
**, blogs có 10 cột. Người dùng xác nhận mới chạy Docker Compose, chưa chạy Flyway nên đưa phần này trực tiếp vào V1.
Sau khi migration đã áp dụng, thay đổi tiếp theo phải dùng version mới. Không đặt category_id trên blogs; quan hệ
nhiều–nhiều đi qua blog_categories.

### Bảng categories

| Cột | Kiểu và ràng buộc | Ý nghĩa |
| --- | --- | --- |
| id | UUID, PK, do server/seed cấp | Khóa liên kết, không dùng tên hiển thị để nối dữ liệu. |
| name | VARCHAR(100), NOT NULL, CHECK char_length(btrim(name)) BETWEEN 1 AND 100 | Tên hiển thị, ví dụ “Học tập”. |
| slug | VARCHAR(100), NOT NULL, UNIQUE, CHECK slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' | Tên dùng trong URL/ánh xạ nhãn, ví dụ hoc-tap; chữ thường không dấu, phân tách bằng dấu gạch ngang. |

Slug ổn định khi đổi tên hiển thị; không tự sinh lại slug mỗi lần sửa tên. Danh mục ví dụ gồm Học tập, Công việc, Tình cảm, Gia đình, Giải trí; cần chốt tập nhãn với dữ liệu huấn luyện trước khi seed chính thức. Chưa thêm trang chủ đề hoặc route frontend theo slug trong phiên bản này.

### Cột bổ sung vào blogs

| Cột           | Kiểu và ràng buộc                                               | Ý nghĩa                                                                                                                  |
|---------------|-----------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| category_mode | VARCHAR(6), NOT NULL DEFAULT 'AUTO', CHECK IN ('AUTO','MANUAL') | Chọn nguồn nhãn hiển thị; MANUAL bảo vệ lựa chọn tác giả kể cả tập rỗng, AUTO vẫn có thể được cập nhật riêng để so sánh. |

category_mode áp dụng cho toàn bộ tập chủ đề của bài: MANUAL bảo vệ cả tập rỗng; model thay thế tập nguồn AUTO để giữ
kết quả dự đoán riêng. Giữ riêng hai tập qua blog_categories.category_source; chỉ hiển thị tập khớp category_mode, không
trộn hai tập. AUTO với tập rỗng có thể là chưa xử lý, lỗi hoặc không có nhãn đủ tin cậy; UI không suy diễn thành trạng
thái “đang xử lý” vô hạn. Confidence lưu theo từng nhãn trong blog_categories; chưa có lịch sử dự đoán hoặc bảng sở
thích người dùng.

### Bảng nối blog_categories

| Cột             | Kiểu và ràng buộc                                                                      | Ý nghĩa                                                               |
|-----------------|----------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| blog_id         | UUID, NOT NULL, FK blogs(id) ON DELETE CASCADE                                         | Bài viết được gán chủ đề.                                             |
| category_id     | UUID, NOT NULL, FK categories(id) ON DELETE RESTRICT                                   | Chủ đề thuộc danh mục hợp lệ.                                         |
| category_source | VARCHAR(6), NOT NULL; CHECK IN ('AUTO','MANUAL'); thuộc PK ghép                        | Nguồn của liên kết; cho phép giữ cả dự đoán AI và lựa chọn tác giả.   |
| confidence      | DOUBLE PRECISION; CHECK 0 <= confidence AND confidence <= 1; AUTO có điểm, MANUAL NULL | Điểm của nhãn AI hiện tại; không tự gán điểm 1 cho lựa chọn thủ công. |

PRIMARY KEY (blog_id, category_id, category_source) ngăn gán trùng cùng nguồn cho một chủ đề/bài; không thêm id riêng.
Xóa bài sẽ xóa các liên kết của bài, không xóa danh mục. Không xóa danh mục còn được tham chiếu. Tập AUTO hoặc MANUAL
rỗng không có hàng cho nguồn đó; không dùng hàng có category_id=NULL.

Tạo bài không truyền category_mode thì mặc định AUTO, tập chủ đề rỗng. Mỗi hàng blog_categories phải có category_source
rõ ràng; cột nguồn không có DEFAULT. Phân loại dữ liệu đã có bằng batch có giới hạn, có thể chạy lại; không gọi model
trong Flyway. PK ghép phục vụ tra cứu theo blog_id; lọc category_source=blogs.category_mode khi đọc tập đang hiển thị;
chỉ thêm index bắt đầu bằng category_id khi có query và bằng chứng EXPLAIN cần nó.

Confidence thuộc liên kết (blog_id, category_id, category_source). AUTO bắt buộc có điểm hữu hạn trong [0,1]; MANUAL bắt
buộc NULL. Không nhận source/confidence từ request sửa nhãn của client; service tự gán MANUAL. Một bài có thể có AUTO
Học tập=0.92, AUTO Công nghệ=0.76 và MANUAL Học tập=NULL. PK ghép cho phép hai hàng Học tập khác nguồn.
blogs.category_mode chọn nguồn hiển thị; MANUAL rỗng không fallback sang AUTO.

Khi người dùng chỉnh nhãn, chỉ thay tập MANUAL và đặt mode=MANUAL; giữ tập AUTO để so sánh. Bật lại AUTO chỉ đổi mode,
vô hiệu tập AUTO cũ rồi lên lịch suy luận; giữ tập MANUAL để đối chiếu. Khi nội dung đổi, xóa tập AUTO đã cũ và phân
loại lại kể cả mode MANUAL; chỉ hiển thị nguồn theo mode, worker không ghi đè MANUAL. Khi ghi kết quả phải khóa blog và
kiểm snapshot nội dung còn khớp, chỉ thay hàng nguồn AUTO trong cùng transaction. Thay ảnh/published không tự phân loại
lại.

Hai tập hiện tại giúp phân tích nhãn trùng/khác và phân bố confidence để cải thiện model. Chưa lưu lịch sử nhiều lần suy
luận, model version hoặc snapshot nội dung; không dùng dữ liệu này để tái lập điểm của model cũ. Nhãn MANUAL là lựa chọn
tác giả, không mặc định ground truth đã được chuyên gia kiểm chứng. Việc lưu lịch sử nhiều phiên bản cần thiết kế riêng
khi có nhu cầu.

## ERD mở rộng

Sơ đồ dưới hiển thị đủ cột của ba bảng liên quan. Những quan hệ core của blogs với users/comments/media/likes/shares giữ như [ERD đầy đủ](erd.md). Một category có 0..n bài và một blog có 0..n category qua bảng nối. Mỗi hàng blog_categories bắt buộc tham chiếu đúng một blog và một category.

```mermaid
erDiagram
    blogs ||--o{ blog_categories : has
    categories ||--o{ blog_categories : classifies
    blog_categories {
        UUID blog_id PK, FK
        UUID category_id PK, FK
        VARCHAR(6) category_source PK "AUTO or MANUAL"
        DOUBLE confidence "nullable; 0..1"
    }
    categories {
        UUID id PK
        VARCHAR(100) name
        VARCHAR(100) slug UK
    }
    blogs {
        UUID id PK
        UUID author_id FK
        TEXT content
        BOOLEAN published
        BIGINT likes_count
        BIGINT comments_count
        BIGINT shares_count
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
        VARCHAR(6) category_mode "AUTO or MANUAL"
    }
```

[SVG mở rộng không cần Mermaid](assets/erd/phan-loai-chu-de.svg).

## Hợp đồng API bổ sung

Base path vẫn là /api/v1. Đây là hai endpoint giai đoạn AI, ngoài 35 endpoint core; tổng thiết kế khi triển khai cả hai giai đoạn là 37 endpoint. Không thêm public endpoint gọi model hoặc cho client ghi trực tiếp kết quả AUTO.

| Endpoint                         | Quyền                        | Request/response                                                                                                                                                                                                                      |
|----------------------------------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| GET /categories                  | Guest và người đăng nhập     | Array trực tiếp gồm {id,name,slug}; sắp name rồi id để kết quả ổn định.                                                                                                                                                               |
| PATCH /blogs/{blogId}/categories | Chỉ tác giả, session và CSRF | Request {mode:"MANUAL",categoryIds:["UUID-1","UUID-2"]} để thay thế toàn bộ tập MANUAL; {mode:"MANUAL",categoryIds:[]} để bỏ tất cả; {mode:"AUTO"} để bật lại tự động. Trả HTTP 200 với BlogDTO hiện tại sau commit, không chờ model. |

MANUAL bắt buộc có categoryIds là mảng UUID, cho phép []; null, thiếu field, phần tử trùng hoặc UUID không thuộc danh
mục trả 400. AUTO không nhận categoryIds. UUID/enum/body sai trả 400; phải kiểm toàn bộ danh sách trước khi ghi, không
cập nhật một phần. Guest ghi nhận 401; user khác sửa bài public nhận 403; bài không tồn tại hoặc private không có quyền
xem nhận 404. Không nhận categoryMode/điểm dự đoán do client tự gửi. Nếu có nhiều thao tác đổi nhãn đồng thời, khóa hàng
blog trước khi thay tập chủ đề và category_mode trong cùng transaction; trạng thái commit sau cùng được giữ, không để lộ
tập cập nhật dở. Worker cũng khóa cùng hàng blog và luôn tuân thủ TOP07.

BlogDTO và SharedBlogDTO bổ sung categories:[{id,name,slug}] từ nguồn khớp blogs.category_mode (mảng rỗng [] nếu nguồn
đó không có nhãn, sắp theo slug rồi id) và categoryMode:"AUTO"|"MANUAL". MANUAL rỗng không hiển thị thay bằng AUTO. Các
DTO/endpoint core còn lại giữ contract; không tự công khai tập nhãn so sánh hoặc confidence. Bài share đọc chủ đề hiện
tại của bài gốc, không lưu snapshot nhãn. Phân trang blogs trước, rồi batch lấy categories theo các blogId và đúng
nguồn; không phân trang trực tiếp trên JOIN bảng nối vì sẽ lặp bài và sai số lượng. Bài chưa có nhãn vẫn xuất hiện,
không gây N+1. POST tạo bài hiện tại vẫn không bắt buộc gửi nhãn.

## Xử lý nền, model và bảo mật

1. Sau transaction tạo/sửa bài hoặc bật AUTO thành công, gửi công việc phân loại với blogId và snapshot nội dung. Có timeout và số lần retry hữu hạn; không chặn request đăng bài.
2. Model chỉ nhận nội dung văn bản cần phân loại; không nhận mật khẩu, cookie, profile, danh sách người tương tác hoặc ảnh. Bản đầu chạy model do hệ thống quản lý; không gửi bài private sang dịch vụ bên thứ ba. Bài private vẫn được xử lý nội bộ và giữ nguyên quyền truy cập.
3. Đầu ra model là danh sách nhãn và điểm từng nhãn. Kiểm toàn bộ đầu ra: nhãn thuộc danh mục, không trùng và điểm hợp lệ theo contract; đầu ra sai thì không ghi kết quả và xử lý như lỗi model. Áp dụng ngưỡng từng nhãn được chọn từ tập validation, cho phép không nhãn nào vượt ngưỡng; không dùng softmax/argmax chỉ chọn một lớp. Điểm do model trả về không mặc nhiên là xác suất đã hiệu chỉnh.
4. Ghi kết quả theo TOP07 trong transaction ngắn: thay các hàng blog_categories nguồn AUTO của bài kèm confidence từng
   nhãn, giữ nguyên các hàng MANUAL, không cộng dồn nhãn từ các lần suy luận cũ; lỗi ghi thì rollback toàn bộ tập. Ghi
   lặp kết quả cho cùng nội dung không tăng counters hoặc tạo dữ liệu trùng. Xóa bài/tài khoản khi tác vụ đang chạy
   không được tạo lại nội dung.
5. Nếu tiến trình dừng sau commit nhưng trước khi nhận việc, cho phép batch có giới hạn phân loại bù các bài chưa có
   hàng nguồn AUTO, kể cả mode MANUAL để phục vụ đối chiếu. Chỉ ghi tập AUTO, không đổi tập đang hiển thị. Batch có giới
   hạn tốc độ, phạm vi và số lượt; không quét lại vô hạn các bài thấp confidence. Chưa cam kết giao việc exactly-once
   hoặc tạo thêm queue service trong bản đầu.

Phương án thử nghiệm ban đầu: TF-IDF + One-vs-Rest Logistic Regression cho phân loại đa nhãn, so sánh trên dữ liệu tiếng Việt được gán tập nhãn theo cùng bộ categories (mỗi bài có thể nhiều nhãn). Đây là lựa chọn baseline, chưa phải model đã được chọn qua thực nghiệm. Tách train/validation/test theo bài, tránh bài trùng hoặc gần trùng rơi vào nhiều tập; báo cáo micro-F1/macro-F1, precision/recall từng nhãn, confusion matrix nhị phân từng nhãn và tỷ lệ model không gắn nhãn. Chọn model và ngưỡng trên validation; chỉ dùng test để đánh giá cuối cùng, chưa đặt con số accuracy thành cam kết.

Không dùng dữ liệu demo core 24 bài để khẳng định chất lượng model. Việc lấy bài thực tế làm dữ liệu huấn luyện cần phạm vi sử dụng phù hợp; không log toàn văn bài private vào log vận hành hoặc báo cáo nghiệm thu.

## Triển khai và nghiệm thu riêng

Sau khi hoàn tất core: chốt tập nhãn/dataset → dùng schema chủ đề có sẵn trong V1 và seed categories → bổ sung API/DTO →
nối tác vụ nền/model → thêm các chip và lựa chọn nhiều chủ đề trong chỉnh sửa → nghiệm thu. Không tạo migration lặp các
bảng V1 đã có. Các caller cần cập nhật gồm form sửa bài, feed, chi tiết bài, hồ sơ và danh sách share; tái sử dụng
component/API client hiện có. Không cài dependency ML hay đổi runtime cho đến bước triển khai model.

| Mã      | Kiểm tra                                                                    | Kết quả cần có                                                                                                                                                            |
|---------|-----------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| TOP-T01 | Model chậm, lỗi, timeout hoặc tiến trình dừng sau commit                    | Bài vẫn được lưu; request không chờ suy luận, batch bù có thể xử lý lại có kiểm soát.                                                                                     |
| TOP-T02 | Dự đoán hợp lệ/không đủ tin cậy/nhãn lạ; bài chỉ ảnh                        | Chỉ gắn nhãn hợp lệ đủ tin cậy; bài không có nhãn đủ tin cậy giữ tập rỗng; đầu ra nhãn lạ bị từ chối, không ghi một phần, không bắt xác nhận.                             |
| TOP-T03 | Đổi nhãn, bỏ nhãn rồi chạy worker; bật lại AUTO                             | MANUAL kể cả [] không bị ghi đè; AUTO cho phép phân loại lại. Tập AUTO/MANUAL cùng tồn tại để đối chiếu; worker không đổi mode hiển thị.                                  |
| TOP-T04 | Sửa nội dung hoặc chuyển MANUAL trong lúc suy luận                          | Kết quả cho nội dung cũ bị bỏ; worker chỉ cập nhật tập AUTO, lựa chọn MANUAL và mode hiển thị được giữ.                                                                   |
| TOP-T05 | Xóa bài/tài khoản trong lúc worker chạy                                     | Không ghi hoặc tái tạo bài đã xóa.                                                                                                                                        |
| TOP-T06 | Guest/user khác/CSRF sai; truy cập bài private                              | Áp dụng quyền/status như contract; không lộ bài private qua categories, DTO hoặc log.                                                                                     |
| TOP-T07 | Migration trên Docker và Neon và constraint chủ đề                          | V1 tạo đủ 11 bảng/65 cột, gồm blog_categories.confidence; PK ghép, FK, cascade liên kết khi xóa bài, RESTRICT danh mục, slug unique, source enum và MANUAL/tập rỗng đúng. |
| TOP-T08 | Feed/detail/profile/share với 0/1/nhiều chủ đề và phân trang                | Không mất hoặc lặp bài, không sai phân trang, không N+1; share dùng nhãn hiện tại, tự gắn nhãn không đẩy bài lên đầu feed.                                                |
| TOP-T09 | Đánh giá model trên tập test giữ riêng                                      | Có dữ liệu/nhãn/version model, micro-F1/macro-F1, kết quả từng nhãn và tỷ lệ bỏ gắn nhãn; phân biệt kết quả thật với ví dụ.                                               |
| TOP-T10 | Một bài nhiều chủ đề; một chủ đề nhiều bài; cùng cặp bài/chủ đề ở hai nguồn | N–N hợp lệ; PK ghép cho phép AUTO và MANUAL cùng tồn tại, từ chối trùng bộ (blog_id,category_id,category_source).                                                         |
| TOP-T11 | MANUAL thiếu/null/trùng/UUID lạ; lỗi giữa lúc thay tập                      | Trả 400 với request sai; lỗi ghi rollback toàn bộ, không lộ tập dở.                                                                                                       |
| TOP-T12 | Chạy lặp worker; hai thao tác thay tập đồng thời                            | Không gán trùng hoặc cộng dồn kết quả cũ; ghi theo khóa blog, tập nhãn và source nhất quán.                                                                               |
| TOP-T13 | Confidence âm, >1, NaN/Infinity; AUTO và MANUAL                             | CHECK chặn điểm ngoài miền/không hữu hạn; AUTO lưu điểm từng nhãn; MANUAL để NULL. Score không được client tự gán hoặc trả thêm trong DTO công khai khi chưa có contract. |

Các ca này là tiêu chí bổ sung cho giai đoạn AI; không đổi Definition of Done của migration core và chưa được đánh dấu PASS.
