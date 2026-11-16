# Chạy benchmark index từ đầu và chụp minh chứng

Hướng dẫn dành cho **máy Ubuntu hiện tại**, dùng bộ lớn 1.000 user / 10.000 bài.
Các lệnh dưới đây để **bạn tự thực hiện và chụp ảnh**, không phải nhật ký xác nhận
những bước bạn đã chạy. Số đo mới do bạn chạy phải được ghi riêng với báo cáo cũ.

## 1. Kết quả cần làm được

| Database | Vai trò | Số index dự kiến theo source hiện tại |
|---|---|---:|
| `soulspace_benchmark_with_index` | Có đầy đủ V1–V5 | 44 |
| `soulspace_benchmark_without_index` | Clone nguồn, bỏ riêng 19 index V2 | 25 |

Hai bản giữ nguyên dữ liệu, constraints, triggers và lịch sử Flyway. Không có V2
không có nghĩa là bỏ PK/UNIQUE. Kết nối cả hai đến cùng PostgreSQL 17.11.

Lộ trình: **chuẩn bị → tạo schema → sinh dữ liệu → clone → bỏ V2 → đối soát → đo → chụp plan → kết luận**.

Ảnh nên hiện đủ **tên database, câu lệnh và bảng kết quả**. Không chụp mật khẩu
hoặc nội dung file cấu hình có mật khẩu. Xuất thêm CSV/JSON của kết quả đo để đối
chiếu; ảnh chỉ thể hiện một phần bằng chứng.

## 2. Chuẩn bị công cụ và môi trường mới — ảnh 01

PostgreSQL hệ thống hiện là 18 ở cổng 5432. Để thực hiện lại từ đầu mà giữ nguyên
nó và các database benchmark cũ, dùng **instance PostgreSQL 17 riêng ở cổng 55438**.
Các binary 17.11 và JDK 21 bên dưới đã có trên máy ở thời điểm viết hướng dẫn.
Chúng nằm trong `/tmp`; nếu đường dẫn đã bị dọn, cần chuẩn bị lại PostgreSQL 17
và JDK 21 rồi sửa hai biến đường dẫn, không đổi sang binary PostgreSQL 18.

Mở **Terminal A**, chọn Bash. Giữ terminal này đến hết quy trình để giữ các biến:

```bash
cd /home/duypy/Documents/MySource/SoulSpace
export PG_BIN=/tmp/soulspace-index-20261116-wKmYO9/pg/bin
export JAVA_HOME=/tmp/soulspace-index-20261116-wKmYO9/extracted/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PG_BIN:$PATH"
export BENCH_HOME="$HOME/.local/share/soulspace-index-evidence"
export PGHOST=127.0.0.1
export PGPORT=55438

java -version
"$PG_BIN/postgres" --version
free -h
lscpu | head -20
df -h .
git rev-parse HEAD
```

**Chụp `01-moi-truong.png`:** phiên bản Java 21, PostgreSQL 17.11, CPU/RAM và
commit source. Có thể chia thành hai ảnh cho dễ đọc. Đây là thông tin máy đo,
không phải bằng chứng index đã tăng tốc.

## 3. Tạo PostgreSQL riêng — ảnh 02

Các lệnh khởi tạo chỉ chạy **một lần**. Nếu thư mục data đã tồn tại hoặc initdb
báo lỗi, dừng để kiểm tra; không xóa thư mục để ép chạy lại. Kiểm tra cổng 55438
chưa có chương trình khác dùng bằng `ss -ltn`.

```bash
install -d -m 700 "$BENCH_HOME"
"$PG_BIN/initdb" -D "$BENCH_HOME/data" -U index_admin \
  --locale=C.UTF-8 --encoding=UTF8 \
  --auth-local=trust --auth-host=scram-sha-256

"$PG_BIN/pg_ctl" -D "$BENCH_HOME/data" \
  -l "$BENCH_HOME/postgres.log" \
  -o "-p 55438 -h 127.0.0.1 -k $BENCH_HOME -c max_connections=40" -w start

"$PG_BIN/psql" -h "$BENCH_HOME" -p 55438 -U index_admin -d postgres \
  -c '\password index_admin'
```

Lệnh cuối hỏi mật khẩu quản trị hai lần và không hiện ký tự nhập. Chọn mật khẩu
riêng để dùng trong giao diện SQL. Socket Unix trong thư mục riêng dùng trust;
kết nối TCP `127.0.0.1` dùng mật khẩu SCRAM.

```bash
"$PG_BIN/psql" -h "$BENCH_HOME" -p 55438 -U index_admin -d postgres \
  -c 'SELECT current_database(), current_user, version();'
```

**Chụp `02-postgres-rieng.png`:** `server started`, database `postgres`, user
`index_admin`, PostgreSQL 17.11. Không chụp phần nhập mật khẩu.

## 4. Tạo database nguồn và hai role — ảnh 03

Ở Terminal A, nhập hai mật khẩu **khác nhau**, giữ để cấu hình kết nối GUI.
Biến được nhập ẩn; không cần ghi mật khẩu vào file hoặc lệnh SQL:

```bash
read -r -s -p 'Mật khẩu soulspace_runtime: ' DB_PASSWORD
printf '\n'
export DB_PASSWORD
read -r -s -p 'Mật khẩu soulspace_migrator: ' FLYWAY_PASSWORD
printf '\n'
export FLYWAY_PASSWORD

"$PG_BIN/psql" -h "$BENCH_HOME" -p 55438 -U index_admin -d postgres \
  -v ON_ERROR_STOP=1 \
  -c 'CREATE DATABASE soulspace_benchmark_with_index OWNER index_admin;'

"$PG_BIN/psql" -h "$BENCH_HOME" -p 55438 -U index_admin \
  -d soulspace_benchmark_with_index -f infra/postgres/provision.sql

"$PG_BIN/psql" -h "$BENCH_HOME" -p 55438 -U index_admin -d postgres \
  -v ON_ERROR_STOP=1 \
  -c 'ALTER DATABASE soulspace_benchmark_with_index OWNER TO soulspace_migrator;'
```

Đây là **instance mới riêng**, nên provision role không đổi mật khẩu role của
PostgreSQL 18 hoặc instance thử cũ. Nếu database đã tồn tại, dừng và kiểm tra,
không DROP DATABASE để tiếp tục hướng dẫn.

Mở IntelliJ Database, pgAdmin hoặc công cụ SQL đang dùng. Thêm các kết nối sau;
tất cả cùng host `127.0.0.1`, port **55438**:

| Nhãn gợi ý | Database | User | Dùng khi |
|---|---|---|---|
| `ADMIN-55438` | `postgres` | `index_admin` | Tạo/clone database, cấu hình instance riêng |
| `WITH-MIGRATOR` | `soulspace_benchmark_with_index` | `soulspace_migrator` | Đối soát/schema/ANALYZE |
| `WITHOUT-MIGRATOR` | `soulspace_benchmark_without_index` | `soulspace_migrator` | Tạo kết nối sau khi clone |
| `WITH-RUNTIME` | `soulspace_benchmark_with_index` | `soulspace_runtime` | Đo query |
| `WITHOUT-RUNTIME` | `soulspace_benchmark_without_index` | `soulspace_runtime` | Đo query sau khi clone |

Trong console `ADMIN-55438`:

```sql
SELECT datname FROM pg_database
WHERE datname LIKE 'soulspace_benchmark_%' ORDER BY datname;

SELECT rolname, rolcanlogin, rolsuper, rolcreatedb
FROM pg_roles
WHERE rolname IN ('soulspace_migrator', 'soulspace_runtime')
ORDER BY rolname;
```

**Chụp `03-database-va-role.png`:** có database nguồn và hai role đăng nhập,
không phải superuser. Ở thời điểm này chưa có database đối chứng.

## 5. Chạy Flyway và Hibernate validate — ảnh 04

Quay lại Terminal A. Các biến URL được truyền cho process; không sửa `.env.docker`.
Profile `benchmark` tránh import cấu hình kết nối db-local đang dùng.

```bash
export DB_URL=jdbc:postgresql://127.0.0.1:55438/soulspace_benchmark_with_index
export FLYWAY_URL="$DB_URL"
export DB_USER=soulspace_runtime
export FLYWAY_USER=soulspace_migrator
export CLOUD_NAME=''
export CLOUD_API_KEY=''
export CLOUD_API_SECRET=''
export GOOGLE_ENABLED=false

# JDK giải nén dùng trust store có sẵn để Maven tải dependency qua HTTPS.
export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/home/duypy/Desktop/pycharm-2026.2.4/jbr/lib/security/cacerts"

cd soulspace
bash mvnw -q compile dependency:build-classpath \
  -Dmdep.outputFile=target/evidence-classpath.txt -DincludeScope=runtime
```

Chỉ chạy tiếp nếu Maven kết thúc thành công. Không in mật khẩu để kiểm tra cấu hình.

```bash
BENCH_CLASSPATH="target/classes:$(cat target/evidence-classpath.txt)"
java -Xms128m -Xmx512m -Dspring.devtools.restart.enabled=false \
  -cp "$BENCH_CLASSPATH" ctu.soulspace.SoulspaceApplication \
  --spring.profiles.active=benchmark,web-local \
  --server.port=18083 --embedding.worker.enabled=false
```

**Chụp `04-flyway-va-backend.png`:** log áp dụng đủ 5 migration, schema version 5,
JPA khởi tạo thành công và `Started SoulspaceApplication`. Backend mở ở cổng
18083 chỉ để kiểm schema; không cần chạy React cho phép so sánh SQL này.

Trong `WITH-MIGRATOR`, chạy:

```sql
SELECT current_database(), current_user;
SELECT version, script, checksum, success
FROM public.flyway_schema_history
ORDER BY installed_rank;
```

**Chụp `05-lich-su-flyway.png`:** đúng database nguồn, đủ V1–V5, `success=true`.
Sau đó **Ctrl+C dừng backend** ở Terminal A và trở lại thư mục root:

```bash
cd /home/duypy/Documents/MySource/SoulSpace
```

## 6. Sinh dữ liệu lớn — ảnh 06

Chạy một lần ở Terminal A. Lệnh nâng timeout riêng của session nạp để 50.000 like
và các trigger có thời gian hoàn tất. Không đổi timeout của role dùng để đo.

```bash
PGPASSWORD="$DB_PASSWORD" PGOPTIONS='-c statement_timeout=120s' \
  "$PG_BIN/psql" -h 127.0.0.1 -p 55438 -U soulspace_runtime \
  -d soulspace_benchmark_with_index -v ON_ERROR_STOP=1 \
  -f docs/index/large-dataset.sql
```

Mong đợi các dòng `INSERT ...`, kết thúc bằng `COMMIT`. Nếu lỗi giữa transaction,
psql dừng và kết nối đóng sẽ rollback. Không chạy seed lần nữa sau khi đã thành công.

Trong `WITH-MIGRATOR`, chạy:

```sql
SELECT current_database() AS database_name, 'users' AS table_name, count(*) AS rows FROM users
UNION ALL SELECT current_database(), 'blogs', count(*) FROM blogs
UNION ALL SELECT current_database(), 'comments', count(*) FROM comments
UNION ALL SELECT current_database(), 'blog_likes', count(*) FROM blog_likes
UNION ALL SELECT current_database(), 'comment_likes', count(*) FROM comment_likes
UNION ALL SELECT current_database(), 'blog_shares', count(*) FROM blog_shares
UNION ALL SELECT current_database(), 'notifications', count(*) FROM notifications
ORDER BY table_name;

SELECT published, count(*) FROM blogs GROUP BY published ORDER BY published;
```

Mong đợi: users 1.000; blogs 10.000; comments 10.000; blog_likes 50.000;
comment_likes 2.000; shares 5.000; notifications 65.750; public 8.750, private 1.250.

**Chụp `06-du-lieu-nguon.png`:** câu lệnh đếm, tên database và các số hàng.
Có thể chụp thêm đoạn `generate_series` trong `large-dataset.sql` để giải thích
cách sinh dữ liệu. Không gọi đây là dữ liệu người dùng thật.

## 7. Clone sang database đối chứng — ảnh 07

Dừng backend nếu còn chạy. **Disconnect mọi kết nối tới database nguồn**, gồm
`WITH-MIGRATOR`, `WITH-RUNTIME`, console và tab dữ liệu. Đóng tab chưa chắc đã ngắt
connection; công cụ GUI có thể tự kết nối lại. Giữ `ADMIN-55438` nối `postgres`.

Trong `ADMIN-55438`, bật Auto-commit và chạy từng lệnh ngoài transaction:

```sql
SELECT current_database(), current_user;

CREATE DATABASE soulspace_benchmark_without_index
    WITH TEMPLATE soulspace_benchmark_with_index
    OWNER soulspace_migrator;

GRANT CONNECT ON DATABASE soulspace_benchmark_without_index
    TO soulspace_runtime;

SELECT datname FROM pg_database
WHERE datname LIKE 'soulspace_benchmark_%' ORDER BY datname;
```

**Chụp `07-clone-database.png`:** `CREATE DATABASE` thành công và có hai database.
Nếu báo nguồn đang được sử dụng, ngắt các kết nối của chính bạn rồi thử lại;
không terminate session của người khác. Không seed database đối chứng.

## 8. Bỏ đúng 19 index V2 — ảnh 08

Tạo/mở `WITHOUT-MIGRATOR`. Chạy nguyên khối có kiểm tra tên database:

```sql
SELECT current_database(), current_user;

BEGIN;
DO $$
BEGIN
    IF current_database() <> 'soulspace_benchmark_without_index' THEN
        RAISE EXCEPTION 'Wrong database: %', current_database();
    END IF;
    DROP INDEX
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
COMMIT;

SELECT current_database(), count(*) AS remaining_indexes
FROM pg_indexes WHERE schemaname = 'public';
```

**Chụp `08-bo-index-v2.png`:** tên database đối chứng và `remaining_indexes=25`.
Nếu chạy lại bước này sẽ báo index không còn; đó không phải lý do sửa V2 hoặc
Flyway history. Nếu lỗi trong transaction, chạy `ROLLBACK` trước khi xử lý lại.

## 9. Chuẩn hóa thống kê và đối soát — ảnh 09–10

Để gần điều kiện lượt chính trong báo cáo cũ, trong `ADMIN-55438` chạy ngoài transaction:

```sql
SELECT inet_server_port(); -- phải là 55438
ALTER SYSTEM SET autovacuum = 'off';
SELECT pg_reload_conf();
SHOW autovacuum;
SELECT pid, datname FROM pg_stat_activity
WHERE backend_type = 'autovacuum worker';
```

Chỉ thực hiện trên instance mới **55438** này; không thay trên server dùng chung.
Đợi truy vấn cuối không có hàng. Đóng rồi mở lại console nếu SHOW vẫn hiện giá trị cũ.

Trong **mỗi** console `WITH-MIGRATOR` và `WITHOUT-MIGRATOR`, bật Auto-commit,
không đặt trong BEGIN/COMMIT, chạy:

```sql
VACUUM (ANALYZE);
```

Sau đó chạy toàn bộ [verify-benchmark.sql](verify-benchmark.sql) trên **từng bản**.
File tạo bảng tạm chứa số hàng và SHA-256 của nội dung mọi bảng public.

**Chụp `09-doi-soat-du-lieu.png`:** đặt hai bảng số hàng + hash cạnh nhau,
đúng tên database; hash từng bảng phải trùng, kể cả notifications và Flyway history.
Các hash này được tính theo phương pháp của file SQL này; không yêu cầu bằng giá
trị hash do công cụ Python cũ tạo vì cách đóng gói chuỗi đầu vào khác nhau.

**Chụp `10-index-va-constraints.png`:** hai kết quả tổng hợp:

| Bản | Index | Constraints | Trigger ứng dụng |
|---|---:|---:|---:|
| Có V2 | 44 | 82 | 18 |
| Không V2 | 25 | 82 | 18 |

Mở thêm kết quả chi tiết nếu cần xác nhận constraints có cùng tên/định nghĩa và
danh sách index khác đúng 19 tên trong V2. Flyway trên **cả hai bản** vẫn là V1–V5
thành công. Nếu dữ liệu/hash không trùng, không dùng cặp đó để kết luận hiệu năng.

## 10. Chạy phép đo bằng SQL — ảnh 11–12

Mở `WITH-RUNTIME`, kiểm tra đúng role và timeout:

```sql
SELECT current_database(), current_user;
SHOW statement_timeout; -- 5s
SHOW lock_timeout;      -- 2s
```

Mở [compare-index.sql](compare-index.sql), gắn file vào `WITH-RUNTIME`, **chạy
toàn bộ file trong cùng một session**. Không để công cụ mở connection khác cho
mỗi câu lệnh vì file dùng bảng tạm. Với IntelliJ, chọn kết nối/schema đúng ở thanh
công cụ, chọn toàn bộ nội dung rồi chạy như một script; nếu hộp chọn statement
hiện ra, chọn toàn bộ statement.

File trả nhiều bảng kết quả. Tìm bảng tóm tắt có các cột `database_name`,
`query_name`, `samples`, `min_rows`, `max_rows`, `execution_p50_ms`,
`execution_p95_ms`, `planning_p50_ms` và buffers. Nếu GUI chỉ hiện kết quả cuối,
chạy lại riêng SELECT dưới nhãn **KẾT QUẢ 3** trong cùng console/session.

**Chụp `11-do-co-index.png`:** bảng tóm tắt đủ ba query, mỗi query **60 samples**.
Mong đợi số hàng: author 9, comments 5, shares 6. Ghi lại ba `parameter_id`.

Xuất bảng tóm tắt thành `with-round1.csv` và bảng **KẾT QUẢ 4** thành
`with-round1-raw.csv` hoặc JSON. Phải xuất trước khi chạy lại hoặc đóng session.

Chuyển sang `WITHOUT-RUNTIME`, chạy cùng file và xuất:

- `without-round1.csv`
- `without-round1-raw.csv` hoặc JSON.

**Chụp `12-do-khong-index.png`:** bảng tóm tắt, 60 samples/query, cùng ba UUID và
số hàng như bản có V2. Không chụp duy nhất một thời gian chạy rồi gọi đó là p50/p95.

Đây là **5 warmup + 60 lượt/query/database**, mỗi query chạy liên tiếp. Nó khác
lịch xen kẽ 6 block của Python trong báo cáo cũ; phải ghi đúng cách thực hiện mới.

## 11. Đảo thứ tự đo — ảnh 13

Chạy ít nhất một đợt nữa, **không đổi dữ liệu hoặc tham số**:

| Đợt | Thứ tự | File tóm tắt cần lưu |
|---|---|---|
| 1 | WITH → WITHOUT | `with-round1.csv`, `without-round1.csv` |
| 2 | WITHOUT → WITH | `without-round2.csv`, `with-round2.csv` |

Mỗi lần chạy file sẽ thay bảng tạm cũ. Xuất cả số liệu từng mẫu trước khi chạy tiếp.

**Chụp `13-dao-thu-tu.png`:** kết quả đợt 2 và đặt tên ảnh/file theo đúng thứ tự.
Báo cáo p50/p95 **riêng từng đợt** để thấy độ dao động. Nếu gộp các đợt, tính lại
từ toàn bộ mẫu thô; không lấy trung bình các p95 của từng đợt.

## 12. Chụp execution plan rõ ràng — ảnh 14–15

Giữ cùng query và UUID cho cả hai bản. Ví dụ dưới đây đúng với bộ lớn sinh bằng
`large-dataset.sql`; UUID tác giả phải khớp `parameter_id` của dòng `author`.
Trong **WITH-RUNTIME**, rồi **WITHOUT-RUNTIME**, chạy cùng SQL:

```sql
SELECT current_database(), current_user;

EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM public.blogs
WHERE author_id = '000495f6-f275-a17c-f9b0-6c34271e9d20'::uuid
  AND published = true
ORDER BY created_at DESC, id DESC;
```

**Chụp `14-plan-co-index.png`** và **`15-plan-khong-index.png`**, gồm toàn bộ plan,
`Buffers`, `Planning Time`, `Execution Time` và tên database. Có thể dùng tab plan
dạng cây của GUI, nhưng giữ thêm bản text đầy đủ để kiểm tra.

Bản có V2 có thể dùng `blogs_public_author_idx`; bản không V2 có thể Seq Scan rồi
Sort. **Chụp plan thực tế**, không chỉnh `enable_seqscan=off` để làm cho hình đẹp.
Planner có thể chọn plan khác theo thống kê/cache; một plan minh họa không thay
thế 60 mẫu đã đo. Với comments/shares, mở SQL thật ở **KẾT QUẢ 1** của
`compare-index.sql`, thêm `EXPLAIN (ANALYZE, BUFFERS)` phía trước và chạy trên hai bản.

## 13. Lập bảng so sánh và viết kết luận — ảnh 16

Đặt hai bảng tóm tắt cạnh nhau, ghi đầy đủ đơn vị **ms**:

| Query | Số hàng | Không V2 p50/p95 | Có V2 p50/p95 | Hệ số p50 |
|---|---:|---|---|---|
| author | 9 | Điền từ lượt bạn chạy | Điền từ lượt bạn chạy | không / có |
| comments | 5 | Điền từ lượt bạn chạy | Điền từ lượt bạn chạy | không / có |
| shares | 6 | Điền từ lượt bạn chạy | Điền từ lượt bạn chạy | không / có |

Công thức: **hệ số = p50 không V2 / p50 có V2**. Ví dụ nếu bạn đo được 2,0 ms và
0,02 ms thì hệ số là 100×; đây là ví dụ công thức, không phải số đo của bạn.
Giữ riêng Planning Time: lợi ích Execution Time nhỏ có thể bị chi phí planning
lấn át. Nếu p50 hiển thị 0 do độ phân giải, không chia cho 0 hoặc kết luận vô hạn.

**Chụp `16-bang-so-sanh.png`**. Có thể kết luận theo dữ liệu thực tế:

> Với bộ dữ liệu mô phỏng 1.000 người dùng, 10.000 bài, 10.000 bình luận và
> 50.000 lượt thích, hai database có cùng dữ liệu và chỉ khác 19 index V2.
> Sau 5 lần warmup và 60 lần đo cho mỗi truy vấn trên mỗi database, truy vấn ...
> có p50 ... ms khi có V2 và ... ms khi không V2. Plan cho thấy ... . Kết quả chỉ
> phản ánh Execution Time của các SELECT đã đo; chưa kết luận tốc độ toàn website
> hoặc hiệu năng ghi.

Thay các dấu `...` bằng số đo/plan thực tế, không dùng các hệ số của báo cáo cũ
để chú thích ảnh chụp lần chạy mới. Query nào không cải thiện thì ghi đúng kết quả.

## 14. Kết thúc và lưu bằng chứng

Lưu ảnh, CSV/JSON và thông tin môi trường vào một thư mục riêng cho lần chạy.
Đối soát lại bằng `verify-benchmark.sql` để xác nhận không có ghi nghiệp vụ giữa
các lượt. Không cần chạy đồng thời hai backend để chứng minh index SQL; backend
đã dừng sau bước migrate nhằm giảm nhiễu trong thời gian đo.

Trong `ADMIN-55438`, nếu muốn giữ instance này cho buổi sau, bật lại bảo trì:

```sql
ALTER SYSTEM RESET autovacuum;
SELECT pg_reload_conf();
```

Ngắt kết nối GUI đến instance 55438. Ở Terminal A:

```bash
"$PG_BIN/pg_ctl" -D "$BENCH_HOME/data" -m fast -w stop
unset DB_PASSWORD FLYWAY_PASSWORD
```

Lệnh dừng giữ lại dữ liệu trong `$HOME/.local/share/soulspace-index-evidence/data`.
Không xóa thư mục/volume hoặc dừng PostgreSQL 18 ở cổng 5432. Lần sau chỉ start
instance này bằng `pg_ctl`, dùng mật khẩu đã đặt và bỏ qua bước initdb, tạo database,
provision và seed.

## 15. Khi gặp lỗi

| Lỗi | Cách xử lý |
|---|---|
| Không tìm thấy binary/JDK trong `/tmp` | Chuẩn bị lại PostgreSQL 17/JDK 21, sửa biến đường dẫn; không thay bằng PostgreSQL 18 để ghép vào kết quả cũ |
| Cổng 55438 đang dùng | Kiểm tra tiến trình/instance; không dừng dịch vụ không liên quan. Nếu đổi cổng phải đổi mọi kết nối/URL trong hướng dẫn |
| `database already exists` | Kiểm tra instance và database đã chuẩn bị ở lần trước; không tạo/seed lại hoặc DROP cho tiện |
| `database is being accessed by other users` | Ngắt connection nguồn của backend/GUI do bạn mở rồi clone lại |
| `current transaction is aborted` | `ROLLBACK`, sửa nguyên nhân gốc rồi chạy lại đúng bước |
| `permission denied` với Flyway history | Dùng migrator để đối soát; runtime chỉ dùng để đo |
| `null value in parameter_id` khi đo | Dataset chưa có dữ liệu cho một query hoặc đang chọn sai database |
| `relation pg_temp... does not exist` | Chạy toàn bộ file SQL trong cùng session; không reconnect giữa các đoạn |
| `statement timeout` | Kiểm tra plan/tài nguyên. Nếu riêng khối đo DO vượt 5s, có thể dùng `SET statement_timeout='120s'` ở cả hai console đo, chạy lại cả hai và ghi rõ timeout mới; không chỉ nâng một bản |
| Plan không dùng index như mong đợi | Đối chiếu dataset, ANALYZE và planner; báo cáo đúng plan thực tế, không ép planner |

Nguồn thực hiện: [README](README.md), [bộ sinh SQL](large-dataset.sql),
[đối soát SQL](verify-benchmark.sql), [đo SQL](compare-index.sql),
[báo cáo đã chạy trước](report.md).
