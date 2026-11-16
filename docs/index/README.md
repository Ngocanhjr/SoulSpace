# So sánh SoulSpace có và không có index bổ sung

Để tự chạy từ đầu và chụp ảnh từng bước gửi giáo viên, xem
[hướng dẫn chụp minh chứng](huong-dan-chup-minh-chung.md).

Để chuyển đúng bộ dữ liệu lớn đã dùng sang máy khác, xem
[gói sao lưu và hướng dẫn khôi phục](transfer/README.md).

Dùng một bản source, hai database và cùng một bộ dữ liệu để đo tác động của các index trong [V2__query_indexes.sql](../../soulspace/src/main/resources/db/migration/V2__query_indexes.sql). Hướng dẫn này dành cho database benchmark local, không thực hiện trên database đang dùng chung hoặc chứa dữ liệu thật.

| Database | Vai trò |
| --- | --- |
| `soulspace_with_index` | Chạy đầy đủ V1–V5, giữ các index V2. |
| `soulspace_without_index` | Sao chép từ bản trên, sau đó bỏ đúng các index V2. |

“Không index” trong tài liệu này nghĩa là không có **index bổ sung V2**. Cả hai bản vẫn giữ PRIMARY KEY, UNIQUE, FOREIGN KEY, CHECK, procedure, trigger, view và dữ liệu. PostgreSQL tự tạo index để thực thi PRIMARY KEY/UNIQUE; giữ chúng để username và các quan hệ like/share vẫn có tính duy nhất. Xem [unique indexes của PostgreSQL](https://www.postgresql.org/docs/17/indexes-unique.html).

### File SQL đo trực tiếp

Sau khi chuẩn bị cặp database theo các bước dưới đây, có thể mở
[compare-index.sql](compare-index.sql) trong SQL console IntelliJ/pgAdmin hoặc psql.
Kết nối bằng `soulspace_runtime`, chạy toàn bộ file trên bản có V2, lưu các bảng kết
quả, rồi chạy cùng file trên bản không V2. File tự chọn tham số, warmup 5 lần, đo
60 lần/query và trả p50/p95, Planning Time, buffers cùng plan của từng mẫu. Hai bản
phải hiển thị cùng UUID và số hàng; kiểm tra nội dung dữ liệu theo mục 5 trước khi đo.

File chỉ đọc dữ liệu nghiệp vụ và ghi bảng tạm trong session; cần quyền TEMP trên
database. Nó không tự tạo/clone database hoặc bỏ index. Lưu kết quả trước khi đóng
console hoặc chạy lại file vì bảng tạm sẽ mất/bị thay thế. Đảo thứ tự chạy hai bản
giữa các đợt. Lịch chạy này khác 6 block xen kẽ hai database của
[measure_sql.py](measure_sql.py), nên ghi rõ phương pháp khi báo cáo số đo mới.

## 1. Chuẩn bị

- Dùng cùng PostgreSQL instance local để giảm khác biệt CPU, RAM, ổ đĩa và cấu hình. Khi gửi project cho người khác, người đó cũng đo hai bản trên cùng máy của họ.
- JDK 21, PostgreSQL theo major của project, backend và tài khoản quản trị database đã hoạt động. Thiết lập ban đầu theo [hướng dẫn PostgreSQL](../../infra/postgres/README.md).
- Các role `soulspace_migrator` và `soulspace_runtime` đã được provision. Dùng role quản trị cho CREATE DATABASE; role sở hữu index hoặc quản trị cho DROP INDEX/ANALYZE. Backend vẫn dùng runtime và Flyway vẫn dùng migrator.
- Bộ dữ liệu chỉ gồm nội dung demo, không có media hoặc cleanup job. Để trống cấu hình Cloudinary khi chạy benchmark để không phát sinh upload/destroy ảnh thật.
- Ghi lại cấu hình kết nối ban đầu trước khi thay đổi, rồi khôi phục sau benchmark. Không gửi `.env.docker`, `.env.neon` hoặc credential thật cùng source; gửi các file example để người nhận tự cấu hình.

Các lệnh SQL dưới đây chạy trong SQL console của IntelliJ hoặc psql. Với CREATE DATABASE, bật Auto-commit và chạy ngoài BEGIN/COMMIT.

## 2. Tạo bản có index

### 2.1. Tạo database riêng

Kết nối database `postgres` bằng tài khoản quản trị trên instance đã có hai role của SoulSpace:

```sql
CREATE DATABASE soulspace_with_index
    OWNER soulspace_migrator;

GRANT CONNECT ON DATABASE soulspace_with_index
    TO soulspace_runtime;
```

Nếu database này đã tồn tại, kiểm tra nó trước khi tiếp tục; không xóa hoặc ghi đè dữ liệu để chạy hướng dẫn.

Chuyển SQL console sang **soulspace_with_index**, vẫn dùng tài khoản quản trị, rồi cấp quyền schema/default privileges cho các bảng và hàm sắp được Flyway tạo:

```sql
SELECT current_database(), current_user;

DO $$
BEGIN
    IF current_database() <> 'soulspace_with_index' THEN
        RAISE EXCEPTION 'Wrong database: %', current_database();
    END IF;

    REVOKE CREATE ON SCHEMA public FROM PUBLIC;
    ALTER SCHEMA public OWNER TO soulspace_migrator;
    GRANT USAGE ON SCHEMA public TO soulspace_runtime;

    ALTER DEFAULT PRIVILEGES FOR ROLE soulspace_migrator IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO soulspace_runtime;
    ALTER DEFAULT PRIVILEGES FOR ROLE soulspace_migrator IN SCHEMA public
        REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
END;
$$;
```

Đây là phần quyền cần cho database mới, không đổi mật khẩu của role. Role PostgreSQL được dùng chung giữa các database trên một instance; không chạy lại bước ALTER ROLE PASSWORD với mật khẩu khác nếu ứng dụng hiện có vẫn đang dùng các role đó.

### 2.2. Chạy Flyway như bình thường

Trong file cấu hình local, đặt cả hai URL về bản có index, giữ username/password của hai role hiện có:

```properties
DB_URL=jdbc:postgresql://127.0.0.1:5432/soulspace_with_index
FLYWAY_URL=jdbc:postgresql://127.0.0.1:5432/soulspace_with_index
DB_USER=soulspace_runtime
FLYWAY_USER=soulspace_migrator
```

Khởi động backend từ thư mục `soulspace`:

```powershell
.\mvnw.cmd spring-boot:run
```

Nếu dùng Maven đã cài thay cho Wrapper, lệnh tương ứng là `mvn spring-boot:run`. Chỉ tiếp tục khi Flyway đã áp dụng V1–V5 thành công và Hibernate validate không báo lỗi. Truy vấn bằng migrator hoặc quản trị để kiểm tra:

```sql
SELECT version, script, success
FROM public.flyway_schema_history
ORDER BY installed_rank;
```

Giữ nguyên nội dung và tên các file V1–V5. Không xóa/comment V2 hoặc sửa lịch sử Flyway để tạo bản so sánh. Flyway validate đối chiếu tên, version và checksum của migration với lịch sử đã áp dụng. Xem [Flyway Validate](https://documentation.red-gate.com/flyway/reference/commands/validate).

### 2.3. Nạp dữ liệu mẫu một lần

Nạp dữ liệu vào `soulspace_with_index` trước khi sao chép database. Có thể dùng [dataset demo hiện có](../../data/demo-seed/README.md); kiểm tra file env/URL mà seed runner nhận cũng trỏ đúng database này. Không dùng cấu hình mặc định trỏ sang database khác.

Dataset demo hiện có 40 users, 400 blogs, 700 comments, 1.000 blog likes, 200 comment likes và 150 shares; có thể dùng để kiểm tra chức năng và tập đọc plan. Đây chưa phải dataset benchmark lớn.

[Spec nghiệm thu](../spec/06-nghiem-thu.md) đề xuất benchmark riêng với 1.000 users, 10.000 blogs, 10.000 comments và 50.000 blog likes. Đây là quy mô mục tiêu, không phải cam kết script hiện có đã tạo số lượng đó. Nếu mở rộng dữ liệu, giữ seed tái lập, phân bố public/author và độ dài nội dung giống nhau giữa hai bản; tạo một lần ở bản nguồn rồi sao chép.

## 3. Sao chép sang bản không có index bổ sung

1. Dừng backend và seed runner sau khi nạp xong dữ liệu.
2. Trong IntelliJ, disconnect data source/SQL console đang giữ kết nối tới `soulspace_with_index`. Database tools có thể tự mở lại kết nối, nên kiểm tra trước khi clone.
3. Mở một kết nối quản trị tới database `postgres` và chạy ngoài transaction:

```sql
CREATE DATABASE soulspace_without_index
    WITH TEMPLATE soulspace_with_index
    OWNER soulspace_migrator;

GRANT CONNECT ON DATABASE soulspace_without_index
    TO soulspace_runtime;
```

PostgreSQL yêu cầu không có session khác kết nối vào database nguồn khi sao chép theo TEMPLATE. Nếu báo database đang được sử dụng, đóng các kết nối đó rồi thử lại; không tự terminate session của người khác. Clone cùng instance giữ các đối tượng và chủ sở hữu bên trong database, nhưng quyền CONNECT ở cấp database được cấp riêng như trên. Xem [CREATE DATABASE](https://www.postgresql.org/docs/17/sql-createdatabase.html).

Không sao chép database chứa media/jobs của môi trường đang dùng sang một backend có worker và cùng Cloudinary namespace. Benchmark này dùng dữ liệu text, không cần Cloudinary.

## 4. Bỏ 19 index V2 trên bản benchmark

Kết nối SQL console vào **soulspace_without_index**, dùng migrator hoặc quản trị. Dừng các backend đang dùng database này trong lúc thay đổi index.

```sql
SELECT current_database(), current_user;

DO $$
BEGIN
    IF current_database() <> 'soulspace_without_index' THEN
        RAISE EXCEPTION 'Wrong database: %', current_database();
    END IF;

    DROP INDEX IF EXISTS
        public.blogs_public_feed_idx,
        public.blogs_author_idx,
        public.blogs_public_author_idx,
        public.comments_blog_idx,
        public.comments_parent_idx,
        public.comments_author_idx,
        public.blog_likes_user_idx,
        public.comment_likes_user_idx,
        public.blog_shares_blog_idx,
        public.blog_shares_user_time_idx,
        public.media_assets_owner_idx,
        public.media_assets_stale_idx,
        public.cleanup_ready_idx,
        public.cleanup_expired_idx,
        public.notifications_recipient_time_idx,
        public.notifications_unread_idx,
        public.notifications_blog_idx,
        public.notifications_comment_idx,
        public.notifications_actor_idx;
END;
$$;
```

Chạy cả khối DO để giữ kiểm tra database. Danh sách trên lấy từ V2 tại ngày 15/11/2026; nếu V2 thay đổi, đối chiếu lại danh sách trước khi dùng. Không dùng CASCADE hoặc script xóa mọi index trong schema. DROP INDEX thông thường khóa bảng trong lúc thực hiện, nên thao tác này dành cho bản benchmark offline. Xem [DROP INDEX](https://www.postgresql.org/docs/17/sql-dropindex.html).

Việc bỏ index ở đây là thiết lập benchmark có chủ đích, thực hiện ngoài thư mục migration. Hai database vẫn giữ cùng lịch sử V1–V5; không dùng cách này để quản lý thay đổi schema của môi trường triển khai thật.

## 5. Kiểm tra hai bản trước khi đo

Chạy các truy vấn sau trên cả hai database và lưu kết quả kèm tên database.

```sql
SELECT current_database(), current_user, version();

SELECT 'users' AS table_name, COUNT(*) AS row_count FROM public.users
UNION ALL SELECT 'blogs', COUNT(*) FROM public.blogs
UNION ALL SELECT 'comments', COUNT(*) FROM public.comments
UNION ALL SELECT 'blog_likes', COUNT(*) FROM public.blog_likes
UNION ALL SELECT 'comment_likes', COUNT(*) FROM public.comment_likes
UNION ALL SELECT 'blog_shares', COUNT(*) FROM public.blog_shares
UNION ALL SELECT 'notifications', COUNT(*) FROM public.notifications
ORDER BY table_name;

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;

SELECT conrelid::regclass AS table_name, conname, contype
FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
ORDER BY table_name, conname;
```

Hai bản phải có cùng số hàng, constraints và lịch sử Flyway; danh sách index khác nhau đúng 19 index V2. Sau một lần clone trực tiếp, nội dung/ID/timestamp giống nhau; so sánh số hàng chỉ là kiểm tra nhanh, không chứng minh hai dataset độc lập có nội dung giống hệt. Không tạo/sửa/xóa dữ liệu trên UI ở một bản rồi dùng bản còn lại làm đối chứng cũ.

Chạy bằng migrator hoặc quản trị trên **mỗi bản**:

```sql
ANALYZE;
```

ANALYZE cập nhật thống kê mà planner dùng để chọn plan. Runtime role có thể không có quyền ANALYZE bảng; không coi một lệnh chỉ báo skip là đã cập nhật thống kê. Xem [ANALYZE](https://www.postgresql.org/docs/17/sql-analyze.html).

## 6. Đo cùng query và tham số

Kết nối bằng cùng role khi đo, ưu tiên `soulspace_runtime` để gần với backend. Giữ cùng PostgreSQL/config/timeout/connection pool. Đóng các tác vụ ghi và chạy hai bản lần lượt.

Chọn UUID ở bản nguồn và dùng chính UUID đó ở cả hai bản. Thay `BLOG_UUID`/`AUTHOR_UUID` bằng giá trị thật trước khi chạy.

### Bình luận của một bài

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    id, blog_id, author_id, parent_id, content,
    likes_count, created_at, updated_at, deleted_at, version
FROM public.comments
WHERE blog_id = 'BLOG_UUID'::uuid
ORDER BY created_at DESC, id DESC;
```

Đối chiếu khả năng dùng `comments_blog_idx`. Query trả cả placeholder như API đọc bình luận, không tự thêm bộ lọc xóa mềm.

### Bài công khai của một tác giả

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM public.blogs
WHERE author_id = 'AUTHOR_UUID'::uuid
  AND published = true
ORDER BY created_at DESC, id DESC;
```

Đối chiếu `blogs_public_author_idx` với các plan thay thế. Đây là query đọc blog, chưa gồm các truy vấn batch lấy tác giả/ảnh/like của response API.

### Danh sách chia sẻ

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT b.*, s.shared_at
FROM public.blog_shares s
JOIN public.blogs b ON b.id = s.blog_id
WHERE s.user_id = 'AUTHOR_UUID'::uuid
  AND b.published = true
ORDER BY s.shared_at DESC, s.blog_id DESC;
```

Đối chiếu khả năng dùng `blog_shares_user_time_idx` cho lọc user/thứ tự chia sẻ và PK blogs cho join. Planner có thể chọn cách join/scan khác tùy phân bố dữ liệu.

EXPLAIN ANALYZE thực sự chạy query; các ví dụ trên đều là SELECT. Nó không đo đầy đủ thời gian mạng, chuyển DTO, tải ảnh hoặc render React. Khi đo API/UI, giữ cùng endpoint, tài khoản, dữ liệu, tham số và số request đồng thời. Xem [Using EXPLAIN](https://www.postgresql.org/docs/17/using-explain.html).

Không thêm LIMIT chỉ vào một bản hoặc thay query cho bản có index. Nếu đo query có LIMIT khác API hiện tại, ghi rõ đó là query benchmark riêng; các danh sách core hiện trả array đầy đủ.

## 7. Ghi kết quả để so sánh công bằng

1. Warmup mỗi query trên mỗi bản vài lần; ví dụ 5 lần rồi đo 30 lần. Ghi rõ số lượt đã dùng, không lấy một lần chạy đầu làm kết luận.
2. Đảo thứ tự hai bản giữa các đợt đo để giảm lợi thế cache. Không gọi số đo này là cold-cache nếu chưa kiểm soát cache của PostgreSQL và hệ điều hành.
3. Giữ planner mặc định. Không dùng `enable_seqscan=off` hoặc `enable_bitmapscan=off` để đo tốc độ thực tế.
4. Lưu query, tham số, số hàng trả về, plan, Planning Time, Execution Time và Buffers của các lượt đo. Ghi thêm CPU/RAM, PostgreSQL/image, commit source và cấu hình có ảnh hưởng.
5. So sánh p50/p95 trên cùng chỉ số. Có thể tính hệ số `p50_khong_index / p50_co_index`; số lớn hơn 1 nghĩa là bản có index nhanh hơn trong phép đo đó.

| Query và tham số | Số hàng trả về | Không index V2: p50/p95 (ms) | Có index V2: p50/p95 (ms) | Plan/buffers và ghi chú |
| --- | --- | --- | --- | --- |
| Comments của BLOG_UUID | Chưa đo | Chưa đo | Chưa đo | |
| Bài public của AUTHOR_UUID | Chưa đo | Chưa đo | Chưa đo | |
| Share của AUTHOR_UUID | Chưa đo | Chưa đo | Chưa đo | |

Seq Scan có thể là lựa chọn hợp lý khi bảng nhỏ hoặc query trả phần lớn dữ liệu. Index không bảo đảm mọi query đều nhanh hơn; INSERT/UPDATE/DELETE cũng có chi phí bảo trì index. Bỏ index phục vụ FK/cascade có thể làm một số thao tác ghi/xóa chậm hơn. Muốn kết luận về ghi, dùng workload riêng và đưa hai bản về cùng trạng thái dữ liệu sau mỗi đợt.

Không quy khác biệt giữa hai máy hoặc độ trễ Neon cho lợi ích của index. Đo local và đo kết nối Neon thành hai báo cáo riêng, theo [tiêu chí benchmark của spec](../spec/06-nghiem-thu.md).

## 8. Chạy ứng dụng trên từng bản và bàn giao

Backend luôn kết nối một database tại một thời điểm. Để chạy bản không có index V2, đổi cả hai URL rồi khởi động lại backend:

```properties
DB_URL=jdbc:postgresql://127.0.0.1:5432/soulspace_without_index
FLYWAY_URL=jdbc:postgresql://127.0.0.1:5432/soulspace_without_index
```

Giữ các thiết lập còn lại giống nhau giữa hai bản. Sau khi thử chức năng có ghi dữ liệu, chuẩn bị lại một cặp dataset giống nhau trước khi tiếp tục đo đọc. Không chạy lại seed vào bản đã clone có dữ liệu chỉ để “đồng bộ”.

Gửi source, hướng dẫn này, file cấu hình example và dataset demo cần dùng. Người nhận dùng database/role/mật khẩu của họ và thực hiện quy trình trên. Khi báo cáo kết quả, kèm thông tin máy đo; không so sánh thời gian trên hai máy rồi kết luận chỉ do index.

## 9. Khôi phục index và giới hạn kiểm chứng

Muốn tạo lại index trên bản không index, dừng backend của bản đó và chạy trực tiếp nội dung file V2 trên đúng `soulspace_without_index` bằng migrator/quản trị. Chỉ làm khi xác nhận toàn bộ 19 index đã được bỏ; V2 dùng CREATE INDEX không có IF NOT EXISTS nên không chạy nguyên file nếu mới khôi phục một phần. Chạy trong transaction để lỗi không để lại một đợt tạo index dở dang.

Không trông chờ khởi động lại ứng dụng sẽ tạo lại V2: migration này đã được ghi nhận là chạy thành công trong lịch sử clone. Không dùng Flyway repair hoặc xóa lịch sử để bật/tắt index. Sau khi khôi phục, chạy ANALYZE và kiểm tra lại danh sách index.

Tài liệu được đối chiếu với V2 và spec ngày 15/11/2026. Việc tạo hướng dẫn không tạo database, không chạy DROP INDEX, không thay cấu hình ứng dụng và không tạo số đo benchmark. Chỉ điền kết quả thực tế sau khi thực hiện các bước đo; giữ trạng thái “Chưa đo” cho các trường chưa có bằng chứng.
