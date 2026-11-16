# Chuyển bộ dữ liệu benchmark sang máy khác

File gửi cho người nhận: **[soulspace-benchmark-transfer.zip](soulspace-benchmark-transfer.zip)**.
Giải nén để có bản sao `soulspace-benchmark.dump`, các script SQL và manifest
ghi số hàng, SHA-256, phiên bản PostgreSQL cùng kết quả thử khôi phục.

Nếu đang dùng Docker Compose của repository, xem [mục 7 — nạp vào Docker](#7-nạp-vào-docker-compose-của-repository) để có lệnh PowerShell dùng ngay.

Đây là **bộ lớn đã dùng để benchmark**, không phải bộ demo 40 người dùng. Bản sao
gồm schema, dữ liệu, 44 index, constraints, triggers, procedures, view, quyền của
hai role ứng dụng và lịch sử Flyway V1–V5. Giữ nguyên ID/timestamp, kể cả 65.750
thông báo. Không xuất PostgreSQL roles/passwords hoặc file `.env` của máy nguồn.
1.000 user mô phỏng có `password_hash=NULL`, dùng cho phép đo đọc, không đăng nhập.

| Bảng | Số hàng |
|---|---:|
| users | 1.000 |
| blogs | 10.000 |
| comments | 10.000 |
| blog_likes | 50.000 |
| comment_likes | 2.000 |
| blog_shares | 5.000 |
| notifications | 65.750 |

## 1. Chuẩn bị máy nhận

Dùng **PostgreSQL 17**, ưu tiên 17.11 như máy đo, và `pg_restore` 17. Trong
pgAdmin có thể thực hiện tương đương bằng Query Tool và Restore. Không dùng
Import CSV để mở file `.dump`. Host/port và mật khẩu do người nhận tự cấu hình.

Đối với các lệnh terminal, mở tại thư mục vừa giải nén chứa file dump. Có thể cấp
`PGHOST`, `PGPORT` qua environment; các lệnh dưới dùng mặc định local:5432 nếu
chưa đặt. Thay `postgres` bằng tài khoản quản trị PostgreSQL của người nhận.
`-W` hỏi mật khẩu, không cần ghi mật khẩu vào lệnh.

## 2. Tạo hai role

```bash
psql -U postgres -d postgres -W -v ON_ERROR_STOP=1 -f 00-create-roles.sql
```

Script tạo `soulspace_migrator` và `soulspace_runtime` nếu chưa có; không thay
mật khẩu hoặc cấu hình role đã tồn tại. Hai role cần có trước khi restore vì dump
giữ quyền sở hữu/quyền truy cập bằng tên role này. Không dùng tùy chọn bỏ owner
hoặc bỏ ACL nếu muốn giữ mô hình quyền của source.

Nếu cần chạy backend, đặt mật khẩu riêng bằng giao diện quản trị hoặc mở `psql`
bằng tài khoản quản trị rồi gõ hai lệnh sau; mật khẩu được nhập ẩn:

```text
\password soulspace_migrator
\password soulspace_runtime
```

## 3. Tạo database trống và restore

```bash
psql -U postgres -d postgres -W -v ON_ERROR_STOP=1 -f 01-create-database.sql

pg_restore -U postgres -W --exit-on-error --single-transaction \
  --dbname=soulspace_benchmark_with_index soulspace-benchmark.dump
```

Nếu database đã tồn tại, dừng và kiểm tra; không dùng `--clean` hoặc DROP để ghi
đè dữ liệu cũ. Restore bằng tài khoản quản trị có quyền gán owner và GRANT.
Không chạy Flyway V1–V5 hay nạp `large-dataset.sql` trước khi restore: dump này
đã chứa đủ schema, lịch sử Flyway và dữ liệu. Nếu restore lỗi, transaction được
rollback; kiểm tra lỗi rồi mới thử lại trên database phù hợp.

Trong pgAdmin: tạo role và database bằng hai file SQL; chọn database mới →
Restore → chọn `soulspace-benchmark.dump`, định dạng Custom, giữ khôi phục owner
và privileges. Bản thử nghiệm round-trip được kiểm bằng lệnh terminal ở trên.

## 4. Kiểm tra dữ liệu sau restore

Đăng nhập bằng migrator, chạy toàn bộ `verify-benchmark.sql`. Đối chiếu số hàng
và `content_sha256` của 14 bảng với `manifest.json` → `tables`. Hash phải trùng
với manifest; bản có V2 có 44 index, 82 constraints, 18 trigger ứng dụng.

```bash
psql -U soulspace_migrator -d soulspace_benchmark_with_index -W \
  -v ON_ERROR_STOP=1 -f verify-benchmark.sql
```

Manifest cũng có SHA-256 của file `.dump` để kiểm file truyền không bị thay đổi.
SHA-256 của file và SHA-256 nội dung bảng là hai phép kiểm khác nhau.

## 5. Tạo bản đối chứng không V2

Dừng backend và ngắt mọi kết nối đến nguồn. Mở kết nối quản trị vào database
`postgres`, bật Auto-commit, chạy `02-clone-without-index.sql`. Nếu bị báo nguồn
đang được sử dụng, ngắt các session của chính bạn; không tự terminate người khác.

Sau đó nối **soulspace_benchmark_without_index** bằng migrator và chạy toàn bộ
`03-drop-v2.sql`. File có kiểm tra tên database và chỉ bỏ đúng 19 index V2.
Nó không sửa lịch sử Flyway. Chạy một lần; nếu đã bỏ index thì không cần chạy lại.

Trên **mỗi database**, dùng migrator và chạy ngoài transaction:

```sql
VACUUM (ANALYZE);
```

Chạy `verify-benchmark.sql` trên hai bản: hash/số hàng phải giống nhau; index
44/25, constraints 82/82. Cuối cùng nối runtime trên mỗi bản và chạy
`compare-index.sql` để tự đo, lưu kết quả trước khi đóng console.

Chỉ so sánh thời gian giữa hai bản **trên cùng máy nhận**; không dùng thời gian
máy gửi và máy nhận để kết luận lợi ích index. Nên ghi RAM/CPU/PostgreSQL và
tham số thực tế vào báo cáo riêng của lần đo mới.

## 6. Các file khác trong gói

- `archive-contents.txt`: danh sách đối tượng trong bản sao.
- `large-dataset.sql`: tùy chọn tạo lại nguồn mô phỏng từ đầu; không chạy sau restore.
- `V2__query_indexes.sql`: bản tham chiếu index tại lúc xuất, không tự động áp dụng lại.
- `manifest.json`: số hàng/hash của nguồn và kết quả kiểm thử restore ở instance mới.

Nếu chỉ muốn gửi **bộ demo nhỏ**, các file CSV có sẵn ở `data/demo-seed/*.csv`.
Gửi kèm cả thư mục demo-seed và hướng dẫn seed của nó; CSV không chứa schema,
trigger hoặc đầy đủ trạng thái thông báo như bản sao database lớn ở gói này.

## 7. Nạp vào Docker Compose của repository

Gói này chứa **10.000 bài mô phỏng dùng đo index**, khác bộ 350 bài/ground truth trong `semantic-search/experiment/data/`. Restore gói này không tạo lại bộ 350 bài. Nếu cần tiếp tục đánh giá 15 query với nhãn hiện tại, chuyển đúng dữ liệu gốc từ Neon, giữ nguyên ID và nội dung.

Các bước dưới tạo database riêng `soulspace_benchmark_with_index` trong container PostgreSQL, giữ nguyên database `soulspace`. Không nạp file dump vào database đã có schema V1–V5. Không chạy `large-dataset.sql`, clone hoặc drop index để thực hiện việc restore này.

### 7.1. Khởi động container và giải nén

Chạy từ thư mục gốc; `.env.docker` phải có cấu hình Docker đang dùng:

```powershell
cd D:\3-SoulSpace
docker compose --env-file .env.docker up -d postgres
docker compose --env-file .env.docker ps

Expand-Archive -LiteralPath docs/index/transfer/soulspace-benchmark-transfer.zip -DestinationPath docs/index/transfer/unpacked
$transferDir = (Resolve-Path -LiteralPath docs/index/transfer/unpacked/soulspace-benchmark-transfer).Path
```

Chỉ giải nén một lần. Nếu đã giải nén, bỏ qua `Expand-Archive` và chỉ đặt lại `$transferDir`. Hai công cụ `psql` và `pg_restore` được chạy **bên trong container** nên không cần cài PostgreSQL client riêng trên Windows.

### 7.2. Tạo database trống

Docker của repository đã có sẵn role cần thiết, nên bỏ qua `00-create-roles.sql`. Chỉ tạo database trống để nhận bản dump:

```powershell
Get-Content -LiteralPath "$transferDir/01-create-database.sql" -Raw -Encoding utf8 | docker compose --env-file .env.docker exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw "Không tạo được database mới. Nếu đã tồn tại, kiểm tra nó; không ghi đè." }
```

### 7.3. Chép file dump vào container và restore

```powershell
docker compose --env-file .env.docker cp "$transferDir/soulspace-benchmark.dump" postgres:/tmp/soulspace-benchmark.dump
if ($LASTEXITCODE -ne 0) { throw "Không chép được dump vào container." }

docker compose --env-file .env.docker exec -T postgres pg_restore -U postgres --exit-on-error --single-transaction --dbname=soulspace_benchmark_with_index /tmp/soulspace-benchmark.dump
if ($LASTEXITCODE -ne 0) { throw "Restore thất bại; transaction được rollback. Kiểm tra lỗi trước khi thử lại." }
```

Không dùng `Get-Content` để truyền file `.dump` nhị phân. `docker compose cp` giữ nguyên dữ liệu nhị phân. Restore giữ owner/ACL trong gói, không dùng `--clean`, `--create`, `--no-owner` hay `--no-acl`.

### 7.4. Kiểm tra sau restore

```powershell
docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace_benchmark_with_index -v ON_ERROR_STOP=1 -c "SELECT current_database(), (SELECT count(*) FROM public.users) AS users, (SELECT count(*) FROM public.blogs) AS blogs, (SELECT count(*) FROM public.comments) AS comments;"
```

Kỳ vọng: **1.000 users, 10.000 blogs, 10.000 comments**. Để đối chiếu đầy đủ hash/index/constraints, chạy bằng role sở hữu schema:

```powershell
"SET ROLE soulspace_migrator;`n" + (Get-Content -LiteralPath "$transferDir/verify-benchmark.sql" -Raw -Encoding utf8) | docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace_benchmark_with_index -v ON_ERROR_STOP=1
```

Đối chiếu kết quả với `manifest.json` trước khi bổ sung bảng hoặc dữ liệu khác.

### 7.5. Nếu muốn thử embedding với bộ benchmark này

Sửa **cả hai URL** trong `.env.docker`, giữ mật khẩu role đang có và `JINA_API_KEY`:

```properties
DB_URL=jdbc:postgresql://127.0.0.1:5432/soulspace_benchmark_with_index
FLYWAY_URL=jdbc:postgresql://127.0.0.1:5432/soulspace_benchmark_with_index
```

Sau đó tạo bảng vector/job trên **đúng database vừa restore** và chỉ nạp thử 3 bài:

```powershell
Get-Content -LiteralPath semantic-search/sql/setup-embeddings.sql -Raw -Encoding utf8 | docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace_benchmark_with_index -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw "Không tạo được bảng embedding; dừng trước khi gọi Jina." }

.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/scripts/embeddings.py --env-file .env.docker --limit 3
```

Bổ sung bảng vector/job sẽ thay đổi số lượng bảng/index/constraints so với manifest benchmark gốc; chỉ đối chiếu manifest trước bước này. Nếu tiếp tục làm thí nghiệm index, giữ một bản DB benchmark gốc riêng.

1.000 user mô phỏng trong gói có `password_hash=NULL`, không đăng nhập được bằng mật khẩu. Backfill Python không cần đăng nhập web; nếu muốn dùng giao diện, cần tài khoản local có thể đăng nhập.

Không dùng bộ ground truth 350 bài để chấm bộ 10.000 bài này. Các lệnh thử embedding gọi Jina thật và dùng hạn mức API; không nạp toàn bộ bộ lớn chỉ để xác nhận kết nối.
