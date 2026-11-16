# Báo cáo so sánh SoulSpace có và không có index V2

Ngày thực hiện: **16/11/2026**, múi giờ Asia/Ho_Chi_Minh (UTC+7). Source: `cfec4e4a3880f0d12b4180835728f4717e677259`. Đọc và thực hiện theo [README](README.md), đối chiếu [V2](../../soulspace/src/main/resources/db/migration/V2__query_indexes.sql) và [tiêu chí nghiệm thu](../spec/06-nghiem-thu.md).

## 1. Kết luận chính

**Máy chạy được đồng thời hai backend SoulSpace, mỗi backend nối một database riêng trên cùng PostgreSQL 17.11.** Đã kiểm chứng bằng khởi động thật, Hibernate validate, health check và lượt tải đọc liên tục 30 giây. Lượt tải cuối thực hiện **15.891 request**, toàn bộ HTTP 200, nội dung đúng với đối chứng.

Với dữ liệu lớn, index V2 giúp giảm rõ thời gian thực thi SQL đọc bình luận và bài public theo tác giả: p50 lần lượt nhanh hơn **9,89×** và **106,04×** trong các query/tham số đã chọn. Ở API, lợi ích nhỏ hơn: bài public theo tác giả nhanh hơn khoảng **1,53×** trong lượt đo lần lượt. Chia sẻ chưa cho thấy cải thiện API đáng kể. Không suy rộng hệ số SQL thành tốc độ toàn website hoặc lợi ích của cả 19 index.

## 2. Kiểm tra máy và môi trường thực tế

| Thành phần | Kết quả |
|---|---|
| Hệ điều hành | Ubuntu 26.04.1 LTS, x86_64 |
| CPU | Intel Core i5-1334U, 10 lõi / 12 luồng |
| RAM vật lý | 14,77 GiB; lúc kiểm tra đầu còn khoảng 8,1 GiB khả dụng |
| Swap | Khoảng 4 GiB |
| Ổ đĩa workspace | `/dev/nvme0n1p2`, khoảng 369 GiB trống khi bắt đầu |
| PostgreSQL có sẵn | 18/main, cổng 5432; giữ nguyên |
| Docker | Có công cụ nhưng tài khoản phiên làm việc không có quyền socket; sudo cần xác thực tương tác |
| PostgreSQL benchmark | **17.11**, biên dịch riêng từ source chính thức đã kiểm SHA-256 |
| Java | OpenJDK **21.0.13.0.3**, giải nén gói Ubuntu trong thư mục tạm |
| Backend | Cùng source/classpath; Spring Boot 4.1.1, Hibernate 7.4.5.Final |
| Tiến trình | PostgreSQL ở `127.0.0.1:55437`; backend ở `127.0.0.1:18081` và `:18082` |
| JVM mỗi backend | `-Xms128m -Xmx512m`; tắt DevTools restart |
| Pool mỗi backend | Hikari tối đa 5, tối thiểu 0, theo source |

Do không dùng được Docker trong phiên này, đã chọn một PostgreSQL 17.11 độc lập chạy bằng tài khoản người dùng. Không đổi quyền Docker, không dừng PostgreSQL 18 và không dùng database local hiện có hoặc Neon. Đây là phép đo PostgreSQL native, **không phải** kết quả trên image Docker của dự án.

Giai đoạn chuẩn bị có tải công cụ vào thư mục tạm. Backend được biên dịch lại thành công bằng JDK 21; Flyway thực tế chạy V1–V5 và Hibernate validate thành công. Cấu hình process dùng profile `benchmark,web-local`, truyền URL riêng qua environment; không nạp profile `db-local` có import `.env.docker`. Cloudinary credentials để trống, Google tắt, embedding worker tắt. Dataset không có media hoặc cleanup job.

Cấu hình PostgreSQL: `shared_buffers=128MB`, `work_mem=4MB`, `effective_cache_size=4GB`, `max_connections=40`, `random_page_cost=4`, `seq_page_cost=1`, `default_statistics_target=100`, `jit=on`, `fsync=on`, `synchronous_commit=on`. Runtime giữ `statement_timeout=5s`, `lock_timeout=2s`. Không ép tắt Seq Scan/Bitmap Scan/Index Scan. Build không bật ICU, SSL, readline, zlib; database dùng UTF-8 và locale C.UTF-8.

## 3. Quy trình đã thực hiện

1. Kiểm tra CPU/RAM/đĩa, cổng, quyền Docker, Java và PostgreSQL trước khi tạo môi trường thử.
2. Khởi tạo PostgreSQL 17.11 riêng ở cổng 55437; provision migrator/runtime bằng SQL dự án, dùng mật khẩu ngẫu nhiên riêng. Host authentication dùng SCRAM; socket Unix riêng trong thư mục tạm dùng trust cho công cụ quản trị local.
3. Tạo `soulspace_with_index`, cấp quyền schema/default privileges, chạy backend để Flyway áp dụng đủ V1–V5; dừng backend sau khi health UP và Hibernate validate đạt.
4. Chạy seed demo ba đợt bằng runtime; đối soát trả `seeded`, sau đó `verified`.
5. Khi không còn kết nối nguồn, clone bằng `CREATE DATABASE ... TEMPLATE ...` sang `soulspace_without_index`. Trên bản sao, trong transaction có kiểm tra tên database, bỏ đúng 19 tên index lấy từ V2; giữ mọi PK/UNIQUE/FK/CHECK và lịch sử Flyway.
6. `ANALYZE` cả hai bằng migrator; kiểm số hàng, hash nội dung từng bảng, constraints, functions, triggers, lịch sử Flyway và danh sách index. Đo SQL khi các backend đã dừng, rồi khởi động hai backend để đo API.
7. Tạo cặp lớn riêng `soulspace_benchmark_with_index` / `soulspace_benchmark_without_index`; chạy lại quy trình migrate → nạp một lần → clone → bỏ riêng V2 → ANALYZE. SQL tạo dữ liệu được lưu tại [large-dataset.sql](large-dataset.sql).
8. Lượt lớn đầu quan sát có bảo trì tự động. Trước lượt lớn dùng làm kết quả chính, tắt `autovacuum` **chỉ trên instance thử nghiệm riêng**, đợi worker bảo trì kết thúc và chạy `VACUUM (ANALYZE)` trên cả hai database. Không dùng `VACUUM FULL`, không chỉnh planner. Đo lại toàn bộ SQL/API và tải đồng thời; lượt đầu lưu riêng để truy vết.
9. Sau mọi lượt tải, đối chiếu lại hash nội dung, schema, index và Flyway của cặp lớn: không thay đổi. Dừng hai backend và PostgreSQL benchmark khi hoàn tất.

Các file V1–V5, cấu hình ứng dụng và `.env.docker` không bị sửa. Không dùng Flyway repair/baseline hoặc xóa lịch sử để bật/tắt index.

## 4. Dữ liệu và điều kiện đối chứng

| Bảng/đặc điểm | Bộ demo | Bộ lớn |
|---|---:|---:|
| users | 40 | 1.000 |
| blogs | 400 | 10.000 |
| blogs public | 350 (87,5%) | 8.750 (87,5%) |
| comments | 700 | 10.000 |
| blog_likes | 1.000 | 50.000 |
| comment_likes | 200 | 2.000 |
| blog_shares | 150 | 5.000 |
| notifications do trigger tạo | 1.898 | 65.750 |
| categories / blog_categories | 16 / 640 | 0 / 0 |
| media, ảnh, cleanup jobs, external identities | 0 | 0 |

Bộ lớn dùng ID từ MD5 chuỗi cố định, nội dung tiếng Việt mô phỏng với độ dài thay đổi, timestamp cố định, mỗi user 10 bài có cả public/private; 10.000 comment phân bố trên 2.000 bài. Giữ trigger và constraints trong khi nạp, không tự ghi counters/notifications. Session nạp được nâng timeout lên 120 giây; session đo giữ 5 giây. Notifications có UUID/thời gian do DB sinh, nên một lần dựng lại nguồn có thể khác; clone đảm bảo hai bản trong cùng phép so sánh có cả các trường này giống hệt nhau.

Mỗi cặp đạt:

- Nội dung **13 bảng nghiệp vụ và bảng lịch sử Flyway** có SHA-256 trùng nhau sau khi sắp xếp biểu diễn JSON của từng hàng; không chỉ so COUNT.
- **82 constraints**, **18 trigger ứng dụng**, **15 function/procedure** có định nghĩa tương ứng trùng nhau.
- Flyway có 5 migration thành công, cùng version/script/checksum; dữ liệu lịch sử được clone.
- Bản có V2 có **44 index**, bản đối chứng có **25 index**; chênh lệch đúng **19 index V2**, không thiếu index khác.
- Các query SQL trả cùng số hàng và hash kết quả. API trả JSON cùng nội dung trên hai bản.

“Không index” trong mọi bảng kết quả nghĩa là **không có index bổ sung V2**; bản này vẫn có 25 index PK/UNIQUE. Bộ lớn là workload mô phỏng phục vụ ba query, chưa mô phỏng phân bố dữ liệu người dùng thật hoặc nghiệp vụ chủ đề/media.

## 5. Cách đo và tham số

### SQL

Dùng chính ba SELECT trong README, không thêm LIMIT và không thay bộ lọc. Kết nối bằng `soulspace_runtime`, tắt auto-prepare của driver để không trộn hiệu ứng prepared statement. Mỗi query/mỗi database warmup **5 lần**, sau đó đo **60 lần**, chia 6 block × 10. Các block đảo thứ tự có/không rồi không/có. Không có backend hoặc tác vụ ghi nghiệp vụ chạy trong giai đoạn SQL.

Lưu mọi `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`, Planning Time, Execution Time và Buffers. p50 là median; p95 là phần tử thứ `ceil(0,95 × n)` sau sắp xếp. Hệ số = `p50_không_V2 / p50_có_V2`; lớn hơn 1 là bản có V2 nhanh hơn **trên chỉ số đang xét**.

Chọn bài public có nhiều comment nhất, tác giả có nhiều bài public nhất và user có nhiều share nhất; hòa thì chọn UUID nhỏ hơn. Đây là mẫu có chọn lọc, không phải lấy ngẫu nhiên hoặc duyệt toàn bộ user.

| Bộ dữ liệu | Query | UUID dùng ở cả hai bản |
|---|---|---|
| Demo | comments | `019f1f18-6400-7fd0-96ae-28864e386c38` |
| Demo | author | `019dee23-8b00-7461-b446-8026ff133a93` |
| Demo | shares | `019e1379-a600-75e1-885d-27cf60f71cb2` |
| Lớn | comments | `0057a3bb-cd04-a660-7e5c-f388b7d44ce2` |
| Lớn | author | `000495f6-f275-a17c-f9b0-6c34271e9d20` |
| Lớn | shares | `0079be5a-4a99-d57e-509c-e2cff600cdea` |

### API

GET ẩn danh cùng các UUID trên: `/api/v1/blogs/{blog}/comments`, `/api/v1/blogs/{author}/blogs`, `/api/v1/users/{user}/shared-blogs`. Không đăng nhập hoặc tạo/sửa/xóa dữ liệu. Mỗi request mở kết nối HTTP local mới, đo từ lúc gửi đến khi đọc xong body.

Warmup 10 request/endpoint/backend. Đo lần lượt 60 request/endpoint/backend theo 6 block đảo thứ tự. Tiếp đó đo đồng thời 30 request/endpoint/backend, một client chờ response rồi mới gửi request kế tiếp cho mỗi backend. Lượt tải dài 30 giây cũng dùng hai client như vậy, luân phiên ba endpoint trên mỗi client, kiểm hash từng response.

Đây là đo sau warmup, **không phải cold-cache**. Không xóa page cache của OS hoặc PostgreSQL. Cache hai database vẫn có thể tranh chỗ; số shared read/hit được giữ trong dữ liệu gốc. Java JIT, GC, tác vụ desktop khác và lịch CPU vẫn tạo nhiễu. API bao gồm xử lý backend/DTO/JSON/loopback; không đo tải ảnh hoặc render React.

## 6. Kết quả SQL

### Bộ demo

| Query | Số hàng | Không V2: p50 / p95 (ms) | Có V2: p50 / p95 (ms) | Hệ số p50 |
|---|---:|---:|---:|---:|
| Bình luận một bài | 12 | 0.0280 / 0.0670 | 0.0200 / 0.0280 | 1.40× |
| Bài public của tác giả | 17 | 0.0310 / 0.0740 | 0.0360 / 0.0610 | 0.86× |
| Danh sách chia sẻ | 7 | 0.0200 / 0.0450 | 0.0220 / 0.0430 | 0.91× |

Bảng nhỏ nên chênh lệch tuyệt đối chỉ vài phần nghìn đến vài phần trăm ms. Comments dùng Bitmap Index Scan qua `comments_blog_idx` thay cho Seq Scan, shared hit giảm 24 → 10; bài tác giả dùng `blogs_public_author_idx`, giảm 97 → 66. Tuy vậy p50 bài tác giả có index vẫn hơi chậm hơn. Share dùng Seq Scan trên bảng share và join PK blogs ở cả hai bản, shared hit cùng 23. Không có cơ sở từ bộ demo để khẳng định mọi query nhanh hơn.

### Bộ lớn — lượt chính sau chuẩn bị lại

| Query | Số hàng | Không V2: p50 / p95 (ms) | Có V2: p50 / p95 (ms) | Hệ số p50 |
|---|---:|---:|---:|---:|
| Bình luận một bài | 5 | 0.1780 / 0.2080 | 0.0180 / 0.0260 | 9.89× |
| Bài public của tác giả | 9 | 2.5450 / 3.4490 | 0.0240 / 0.0320 | 106.04× |
| Danh sách chia sẻ | 6 | 0.0440 / 0.0600 | 0.0280 / 0.0390 | 1.57× |

| Query | Không V2 | Có V2 | Buffers ở node gốc |
|---|---|---|---|
| Comments | Index Scan qua UNIQUE `comments_id_blog_id_key`, rồi Sort | Bitmap Heap/Index Scan qua `comments_blog_idx`, rồi Sort | Không V2: 86 hit; có V2: 7 hit; read = 0 |
| Bài public của tác giả | Seq Scan blogs, loại 9.991 hàng, rồi Sort | Bitmap Heap/Index Scan `blogs_public_author_idx`, rồi Sort | Không V2: 6.893–7.009 hit, 0–116 read; có V2: 11 hit, 0 read |
| Shares | Bitmap scan `blog_shares_pkey`, join `blogs_pkey`, Sort | Index Only Scan `blog_shares_user_time_idx`, join `blogs_pkey` | Không V2: 26 hit; có V2: 21 hit; read = 0 |

Điểm đáng chú ý: bản đối chứng của comments **vẫn sử dụng index UNIQUE**, dù `blog_id` không phải cột đầu. Không thể mô tả phép đo này thành “Seq Scan so với Index Scan” cho mọi query. Với bài public theo tác giả, index giảm mạnh số trang cần truy cập và số hàng phải lọc. Với share, PK đã hỗ trợ lọc `user_id`, nên lợi ích bổ sung nhỏ hơn.

Các số trên là **Execution Time**, chưa cộng chi phí lập plan. Planning Time p50 của bộ lớn:

| Query | Không V2 (ms) | Có V2 (ms) |
|---|---:|---:|
| Bình luận một bài | 0.0300 | 0.0370 |
| Bài public của tác giả | 0.0380 | 0.0485 |
| Danh sách chia sẻ | 0.1230 | 0.1705 |

Planning của share có V2 cao hơn; lợi thế Execution Time nhỏ không tự chuyển thành lợi thế tổng thời gian query/API. Số buffer là lượt truy cập block, không phải số trang duy nhất hoặc số lần đọc đĩa vật lý; shared read có thể được OS phục vụ từ cache. Bảng blogs có heap khoảng 54,76 MiB sau các lần trigger cập nhật counters lúc nạp dữ liệu; kích thước này giống nhau giữa hai bản và ảnh hưởng chi phí Seq Scan. VACUUM thường không nén lại heap như VACUUM FULL.

## 7. Kết quả API và chạy đồng thời

### API bộ demo, chạy request lần lượt

| Endpoint | Không V2: p50 / p95 (ms) | Có V2: p50 / p95 (ms) | Hệ số p50 |
|---|---:|---:|---:|
| Bình luận một bài | 6.831 / 9.839 | 6.668 / 8.956 | 1.02× |
| Bài public của tác giả | 7.274 / 8.947 | 7.406 / 10.115 | 0.98× |
| Danh sách chia sẻ | 5.438 / 7.018 | 5.871 / 7.275 | 0.93× |

### API bộ lớn, chạy request lần lượt

| Endpoint | Không V2: p50 / p95 (ms) | Có V2: p50 / p95 (ms) | Hệ số p50 |
|---|---:|---:|---:|
| Bình luận một bài | 7.336 / 9.093 | 6.125 / 7.861 | 1.20× |
| Bài public của tác giả | 10.968 / 13.302 | 7.158 / 8.754 | 1.53× |
| Danh sách chia sẻ | 5.980 / 6.958 | 6.072 / 6.800 | 0.98× |

Bài public theo tác giả giảm p50 khoảng 3,81 ms; comments giảm khoảng 1,21 ms. Share gần như ngang nhau, p50 có V2 còn hơi cao hơn. SQL nhanh 106× ở query tác giả chỉ dẫn tới API nhanh khoảng 1,53× vì API còn nhiều công việc và truy vấn khác. Comments demo trả 1 node gốc có cây reply, trong khi SQL trả 12 hàng; ở bộ lớn, SQL và API comments đều có 5 comment gốc.

### API bộ lớn, hai backend nhận request đồng thời

Mỗi endpoint/backend 30 mẫu; giữ làm bằng chứng khả năng chạy hai bản, không lấy làm phép đo cô lập index:

| Endpoint | Không V2: p50 / p95 (ms) | Có V2: p50 / p95 (ms) | Hệ số p50 |
|---|---:|---:|---:|
| Bình luận một bài | 7.695 / 8.911 | 7.476 / 8.285 | 1.03× |
| Bài public của tác giả | 11.863 / 13.628 | 7.626 / 8.678 | 1.56× |
| Danh sách chia sẻ | 6.999 / 7.874 | 6.874 / 8.176 | 1.02× |

### Lượt tải hỗn hợp 30 giây

| Chỉ số | Kết quả ghi nhận |
|---|---|
| Có V2 | 10.236 request, khoảng 341,2 request/s |
| Không V2 | 5.655 request, khoảng 188,5 request/s |
| Tổng | **15.891 request**, 0 lỗi HTTP, 0 hash nội dung sai |
| RSS hai backend cao nhất trong mẫu | **888.7 MiB**; RSS có tính cả trang chia sẻ |
| RAM khả dụng thấp nhất trong mẫu | **3.63 GiB** |
| CPU trung bình của riêng hai JVM | Khoảng **2.49 lõi tương đương**; chưa cộng PostgreSQL/client/ứng dụng khác |
| Theo dõi tài nguyên | Mỗi khoảng 250 ms trong lượt tải |

| Endpoint trong tải hỗn hợp | Không V2: p50 / p95 (ms) | Có V2: p50 / p95 (ms) |
|---|---:|---:|
| Bình luận một bài | 2.841 / 6.394 | 2.065 / 5.133 |
| Bài public của tác giả | 7.736 / 12.346 | 2.272 / 6.093 |
| Danh sách chia sẻ | 2.949 / 7.366 | 2.294 / 5.905 |

Hai client chạy kiểu chờ response rồi gửi tiếp, nên backend nhanh hơn nhận nhiều request hơn. Đây không phải phép thử cùng tốc độ request đầu vào hoặc năng lực tối đa. JIT tiếp tục nóng lên và tải hỗn hợp khác lượt đo API ngắn nên độ trễ hai bảng không được ghép thành một tập mẫu. Máy có khoảng 37,9 MiB swap đã dùng trong lượt tải cuối; SwapFree không đổi trong các mẫu của lượt này. Không gán toàn bộ biến động RAM hệ thống cho hai backend.

## 8. Chi phí dung lượng và giới hạn

Tổng dung lượng index public ở cặp lớn: có V2 khoảng **31,47 MiB**, không V2 khoảng **15,70 MiB**, chênh **15.77 MiB**. Đây gồm cả các index V2 trên notifications và bảng khác. Database tương ứng khoảng 108,98 / 93,21 MiB; kích thước vật lý có thể dao động theo metadata và bảo trì.

Chưa đo INSERT/UPDATE/DELETE, cascade, notification inbox, queue, tìm kiếm hoặc toàn bộ 19 index riêng lẻ. Chưa đo Neon, Docker, TLS, media, UI React hoặc nhiều người dùng đăng nhập. Không có kết luận về chi phí bảo trì index khi ghi, độ bền tải kéo dài hay giới hạn số user đồng thời. Một workload với một bộ UUID cho mỗi query không thay thế kiểm thử nhiều phân bố/selectivity. Không có kiểm định thống kê để khẳng định các chênh lệch vài microsecond hoặc vài phần trăm ở API là ổn định.

## 9. Kết luận và đề xuất sử dụng

1. **Có thể chạy hai bản song song trên máy này** trong cấu hình đã thử: hai JVM giới hạn heap 512 MiB, hai database trên một PostgreSQL 17.11. Nên dùng hai cổng backend và tên database rõ ràng; khi so sánh hiệu năng chính, tiếp tục đo lần lượt và đảo thứ tự.
2. **Giữ các index V2 trong ứng dụng.** Kết quả lớn hỗ trợ rõ `comments_blog_idx` và `blogs_public_author_idx` cho hai truy vấn chọn lọc đã đo. Không dùng bộ demo nhỏ để kết luận index vô ích.
3. **Không hứa hẹn toàn website nhanh 106×.** Số này thuộc Execution Time của một SELECT trên dữ liệu/tham số cụ thể; API tương ứng cải thiện khoảng 1,53× ở lượt chính.
4. **Không loại `blog_shares_user_time_idx` chỉ vì API share gần như ngang nhau.** PK đã hỗ trợ lọc user; lợi ích của index thứ tự phụ thuộc số share/user, query và phân bố. Cần workload phù hợp trước khi đánh giá bỏ index.
5. Trước kết luận cho triển khai thật, đo thêm nhiều user/bài, dữ liệu lệch, request đăng nhập và workload ghi; giữ hai trạng thái dữ liệu đồng nhất. Với dữ liệu lớn hơn, xem xét phân trang vì các API core đang trả array đầy đủ.

## 10. Bằng chứng, chạy lại và trạng thái bàn giao

- [Môi trường và cấu hình](results/environment.json), [đối soát và khởi động](results/checks.txt), [tóm tắt có thể đọc bằng công cụ](results/summary.json).
- Số liệu từng mẫu và toàn bộ plan: [demo.json.gz](results/demo.json.gz), [large.json.gz](results/large.json.gz). Lượt tải cuối: [concurrent.json.gz](results/concurrent.json.gz).
- Truy vết bổ sung: [lượt lớn đầu trước chuẩn hóa bảo trì](results/initial-large.json.gz), [lượt kiểm công cụ SQL trước lượt chính cuối](results/recheck-sql.json.gz). Hai file này **không được trộn vào bảng kết quả chính**.
- [SQL tạo bộ lớn](large-dataset.sql), [công cụ đo SQL chỉ đọc](measure_sql.py). Dữ liệu gốc không chứa mật khẩu, password hash thực tế hoặc nội dung từng hàng; hash bảng/kết quả được lưu để đối chiếu.

Để chạy lại, chuẩn bị một cặp database riêng theo README, dùng cùng PostgreSQL major 17. Bộ lớn được nạp bằng runtime trên database trống tên `soulspace_benchmark_with_index`, sau migrate, rồi mới clone. Cấp mật khẩu qua environment, không ghi vào command line hoặc Git. Ví dụ cho phần nạp/đo trên môi trường người chạy đã chuẩn bị:

```bash
# PGHOST/PGPORT và credential được cấp qua environment riêng.
# Nạp một lần; PGUSER=soulspace_runtime, PGPASSWORD lấy từ DB_PASSWORD.
PGUSER=soulspace_runtime PGDATABASE=soulspace_benchmark_with_index \
  PGOPTIONS='-c statement_timeout=120s' \
  psql -v ON_ERROR_STOP=1 -f docs/index/large-dataset.sql

# Sau clone, bỏ đúng V2 và ANALYZE/VACUUM cả hai bằng migrator.
# Dùng cùng trạng thái autovacuum cho hai bản; chỉ thay trên instance thử riêng.
# Công cụ dùng DB_PASSWORD và FLYWAY_PASSWORD, không đọc .env dự án.
python docs/index/measure_sql.py --stage recheck \
  --with-db soulspace_benchmark_with_index \
  --without-db soulspace_benchmark_without_index
```

Công cụ cần `psycopg` từ [requirements demo seed](../../data/demo-seed/requirements.txt); chạy bằng Python/venv đã cài bộ đó. File kết quả SQL mới có hậu tố `-sql.json`; công cụ từ chối ghi đè file kết quả đã tồn tại. API/tải đồng thời được thực hiện theo số client, thứ tự endpoint và cách tính thời gian ở mục 5; bản công cụ bàn giao chỉ tự động hóa phần SQL.

Khi kết thúc: hai backend thử nghiệm và PostgreSQL 17.11 riêng đã dừng; PostgreSQL 18 ở cổng 5432 vẫn chạy. Không đổi cấu hình kết nối ban đầu nên không cần khôi phục `.env`. Dữ liệu thử nằm trong thư mục tạm `/tmp/soulspace-index-20261116-wKmYO9/pgdata`; có thể bị hệ điều hành dọn, không dùng làm dữ liệu triển khai. Các file báo cáo/bằng chứng ở `docs/index` là phần bàn giao lâu dài.
