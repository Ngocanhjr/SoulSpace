# Nạp CSV mới vào Docker và chạy embedding local

Dữ liệu nguồn: `D:\3-SoulSpace\semantic-search\data-semantic-1000.csv`, gồm các cột `id,content,labels`. Đã kiểm tra: **1.000 bài, 1.000 ID khác nhau, 1.000 nội dung khác nhau**, không có bài trống hoặc dài quá 5.000 ký tự.

Luồng: **tạo database `soulspace` → tạo schema → nạp CSV thành bài công khai → tạo bảng vector → chạy embedding → kiểm tra**. Dùng PowerShell, chạy từ `D:\3-SoulSpace`. Chạy từng bước; gặp lỗi thì dừng ở bước đó.

## 1. Cấu hình `.env.docker`

Giữ các mật khẩu hiện tại của Docker. Kiểm tra các dòng sau và điền Jina key:

```properties
POSTGRES_DB=soulspace
DB_URL=jdbc:postgresql://127.0.0.1:5432/soulspace
DB_USER=soulspace_runtime
FLYWAY_URL=jdbc:postgresql://127.0.0.1:5432/soulspace
FLYWAY_USER=soulspace_migrator
JINA_API_KEY=<key-Jina-cua-ban>
embedding.worker.enabled=false
embedding.worker.delay-ms=5000
```

Giữ worker `false` trong lúc nạp dữ liệu và chạy Python. Python không cần backend đang chạy. Biến môi trường `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JINA_API_KEY` trong terminal có ưu tiên hơn file; dùng terminal chưa nạp cấu hình Neon/cấu hình DB khác.

## 2. Khởi động Docker và tạo database trống

```powershell
cd D:\3-SoulSpace
docker compose --env-file .env.docker up -d postgres
docker compose --env-file .env.docker ps
docker compose --env-file .env.docker exec -T postgres psql -U postgres -d postgres -c "\l"
```

Bạn đã xóa database `soulspace`. Container/volume vẫn còn nên `up -d` không tự tạo lại database. Nếu danh sách chưa có `soulspace`, chạy:

```powershell
docker compose --env-file .env.docker exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE soulspace OWNER soulspace_migrator TEMPLATE template0 ENCODING 'UTF8';"
if ($LASTEXITCODE -ne 0) { throw "Chưa tạo được database; dừng và kiểm tra lỗi." }
```

Các tài khoản PostgreSQL đã có sẵn, không cần tạo lại. Nếu database đã tồn tại, kiểm tra dữ liệu trước khi tiếp tục; không chạy lệnh xóa để ép nạp.

## 3. Tạo schema bằng Flyway

CSV chỉ chứa dữ liệu, nên cần tạo bảng và view trước. Chạy backend với cấu hình local:

```powershell
Push-Location D:\3-SoulSpace\soulspace
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-local,web-local"
```

Chờ Flyway hoàn tất V1–V5 và backend khởi động thành công, sau đó nhấn **Ctrl+C**, rồi quay về thư mục gốc:

```powershell
Pop-Location
```

Nếu DB đã có schema V1–V5 hợp lệ thì bỏ qua bước này. Dừng backend trong lúc nạp CSV.

## 4. Nạp 1.000 bài CSV vào `soulspace`

Lệnh dưới tạo một tác giả thử nghiệm `csv1000` để thỏa khóa ngoại của bảng `blogs`, rồi nạp toàn bộ bài dưới dạng công khai. Tài khoản này không có mật khẩu đăng nhập. ID nguồn dạng `SS_...` được đổi thành UUID ổn định bằng `md5('soulspace.csv.data-semantic-1000.' || id)::uuid`; nạp lại cùng file sẽ giữ nguyên UUID và không thêm bài trùng.

`labels` là nhãn chủ đề nguồn, được giữ trong CSV; bước này chỉ nạp nội dung bài, không chuyển chúng thành nhãn liên quan 0/1/2 hay kết quả phân loại của hệ thống.

Chép CSV vào container:

```powershell
docker compose --env-file .env.docker cp semantic-search/data-semantic-1000.csv postgres:/tmp/data-semantic-1000.csv
if ($LASTEXITCODE -ne 0) { throw "Chưa chép được CSV; dừng nạp." }
```

Chạy nguyên khối sau. Mọi thay đổi nằm trong một transaction; lỗi sẽ rollback:

```powershell
$importSql = @'
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL ROLE soulspace_runtime;
CREATE TEMP TABLE incoming_posts (
    source_id TEXT PRIMARY KEY CHECK (btrim(source_id) <> ''),
    content TEXT NOT NULL CHECK (btrim(content) <> '' AND char_length(content) <= 5000),
    labels TEXT
) ON COMMIT DROP;
\copy incoming_posts (source_id, content, labels) FROM '/tmp/data-semantic-1000.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')

DO $$
BEGIN
    IF (SELECT count(*) FROM incoming_posts) <> 1000 THEN
        RAISE EXCEPTION 'Expected 1000 CSV rows; import cancelled';
    END IF;
    IF EXISTS (SELECT 1 FROM incoming_posts GROUP BY content HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Duplicate content in CSV; import cancelled';
    END IF;
    IF EXISTS (
        SELECT 1 FROM incoming_posts p
        JOIN public.blogs b ON b.id = md5('soulspace.csv.data-semantic-1000.' || p.source_id)::uuid
        WHERE b.content IS DISTINCT FROM p.content
           OR NOT b.published
    ) THEN
        RAISE EXCEPTION 'Existing imported posts changed; import cancelled';
    END IF;
END $$;

INSERT INTO public.users (id, username, fullname)
VALUES (md5('soulspace.csv.data-semantic-1000.author')::uuid, 'csv1000', 'CSV 1000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.blogs (id, author_id, content, published, category_mode)
SELECT md5('soulspace.csv.data-semantic-1000.' || source_id)::uuid,
       md5('soulspace.csv.data-semantic-1000.author')::uuid,
       content, true, 'MANUAL'
FROM incoming_posts
ON CONFLICT (id) DO NOTHING;
COMMIT;
'@
$importSql | docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw "Nạp CSV thất bại; kiểm tra lỗi trước khi tiếp tục." }
```

Kiểm tra số bài, số nội dung khác nhau và số bài công khai của bộ vừa nạp:

```powershell
docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace -c "SELECT count(*) AS imported_posts, count(DISTINCT content) AS distinct_contents, count(*) FILTER (WHERE published) AS public_posts FROM public.blogs WHERE author_id = md5('soulspace.csv.data-semantic-1000.author')::uuid;"
```

Kỳ vọng cả ba cột là **1.000**. Bộ này không sử dụng file dump 10.000 bài cũ.

## 5. Tạo bảng embedding và chuẩn bị Python

```powershell
Get-Content -LiteralPath semantic-search/scripts/setup-embeddings.sql -Raw -Encoding utf8 | docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw "Chưa tạo được bảng embedding; dừng trước khi gọi Jina." }
```

Đây là đường dẫn hiện tại sau khi chuyển file SQL vào `scripts/`. Bước này tạo extension `vector`, bảng `blog_embeddings`, `blog_embedding_jobs` và quyền truy cập; chưa tạo vector.

Nếu chưa có môi trường Python, cài một lần; nếu đã có thì bỏ qua:

```powershell
python -m venv data/demo-seed/.venv
.\data\demo-seed\.venv\Scripts\python.exe -m pip install -r data/demo-seed/requirements.txt
```

## 6. Chạy thử 3 bài rồi tạo vector cho phần còn lại

Chạy thử:

```powershell
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/scripts/embeddings.py --env-file .env.docker --limit 3
```

Trên DB mới chưa có embedding, kỳ vọng lưu **3 vector**. Sau đó chạy tất cả bài công khai còn thiếu vector:

```powershell
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/scripts/embeddings.py --env-file .env.docker
```

Script giữ model `jina-embeddings-v3`, vector 1.024 chiều, task `retrieval.passage`. Mỗi lô mặc định 16 bài, có thể thêm `--batch-size 8`. Ba bài đã xử lý được bỏ qua nếu nội dung không đổi; nếu dừng giữa chừng, chạy lại sẽ tiếp tục các bài thiếu hoặc cũ vector. Script xử lý mọi bài công khai trong DB, nên DB chỉ chứa bộ mới thì tổng kỳ vọng là **1.000 vector**.

Embedding lưu trong PostgreSQL local; việc tạo vector vẫn gọi Jina qua Internet và sử dụng hạn mức API.

## 7. Kiểm tra vector và thử tìm kiếm

```powershell
docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace -c "SELECT count(*) AS imported_posts, count(e.blog_id) AS stored_vectors, count(e.blog_id) FILTER (WHERE e.content_sha256 = encode(sha256(convert_to(b.content, 'UTF8')), 'hex')) AS matching_vectors FROM public.blogs b LEFT JOIN public.blog_embeddings e ON e.blog_id = b.id WHERE b.author_id = md5('soulspace.csv.data-semantic-1000.author')::uuid;"
```

Kỳ vọng cả ba cột là **1.000** khi xử lý xong.

Thử tìm 10 bài gần nghĩa, không cần backend:

```powershell
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/scripts/embeddings.py --env-file .env.docker --query "áp lực học tập và công việc"
```

Tìm kiếm dùng task `retrieval.query` và cũng gọi Jina. Khi muốn dùng giao diện, khởi động lại backend với profile `db-local,web-local`.

Bộ 1.000 bài có ID và nội dung khác corpus 350 bài cũ. Cần xây dựng query/ground truth riêng trước khi đánh giá; không dùng bộ nhãn cũ hoặc bước kiểm corpus 350 để chấm bộ mới.

## 8. Tự tạo embedding cho bài mới qua backend (tùy chọn)

Sau khi nạp và backfill xong, nếu muốn backend tự xử lý bài công khai mới, đổi `.env.docker` rồi khởi động lại backend:

```properties
embedding.worker.enabled=true
embedding.worker.delay-ms=5000
```

Giữ worker `false` nếu chỉ chạy backfill Python thủ công.

## 9. Gán 1.000 bài đã nạp cho 40 user demo (mỗi người 25 bài)

Chạy khi đã có 1.000 bài và bảng embedding. Bước này chỉ nạp riêng `data/demo-seed/users.csv`, không chạy `seed.py` và không nạp 400 bài cũ hay các tương tác của bộ demo. Dừng backend và lệnh backfill đang chạy trước khi thực hiện.

Script gán lần lượt bài theo ID nguồn đã sắp xếp cho 40 user đầu tiên trong `users.csv`, theo thứ tự UUID tăng dần, lặp lại 25 vòng. Tài khoản mở rộng `demo41` được bỏ qua khi gán bài. Username hiện tại được đặt theo tên thật của nhân vật, ví dụ `minhkhoi`, `ngocan`, `hoaiphuong`, …, `vinhan`. Thứ tự UUID chính là thứ tự user cũ `demo01`–`demo40`, nên đổi username không làm chuyển bài sang người khác. Giữ nguyên ID bài, nội dung, trạng thái công khai và vector; không cần tạo lại embedding.

```powershell
cd D:\3-SoulSpace
docker compose --env-file .env.docker cp data/demo-seed/users.csv postgres:/tmp/demo-users.csv
if ($LASTEXITCODE -ne 0) { throw "Chưa chép được users.csv; dừng." }
docker compose --env-file .env.docker cp semantic-search/data-semantic-1000.csv postgres:/tmp/data-semantic-1000.csv
if ($LASTEXITCODE -ne 0) { throw "Chưa chép được CSV bài đăng; dừng." }
Get-Content -LiteralPath semantic-search/scripts/assign-demo-users.sql -Raw -Encoding utf8 | docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw "Chưa gán được tác giả; transaction rollback, kiểm tra lỗi." }
```

Kết quả trước `COMMIT` là 40 dòng với username mới, mỗi dòng có `assigned_posts = 25`. Nếu cả 1.000 bài đã embed xong thì `stored_vectors` và `matching_vectors` cũng đều là 25. Thống kê này chỉ tính đúng bộ bài trong CSV.

User đã tồn tại được nhận diện bằng UUID và chỉ đồng bộ `username` từ CSV khi tên thay đổi; hồ sơ còn lại và mật khẩu giữ nguyên. User chưa có được tạo từ CSV, chưa có mật khẩu đăng nhập; việc gán bài không tự cấu hình đăng nhập. Nếu username mới đang thuộc một UUID khác, thiếu bài hay nội dung bài đã thay đổi so với CSV, script dừng và rollback.

Nếu đã gán 1.000 bài trước khi đổi username, chỉ cần chạy lại khối lệnh ở bước 9 để áp dụng tên mới trong Docker. Không nạp lại bài hay chạy lại embedding. Có thể cần đăng xuất rồi đăng nhập lại để giao diện nhận tên mới.

User tạm `csv1000` vẫn được giữ lại và không còn sở hữu các bài vừa chuyển. Sau bước này, các lệnh kiểm tra ở bước 4 và 7 lọc theo tác giả tạm sẽ không còn phản ánh bộ 1.000 bài; dùng thống kê của bước 9. Không chạy lại bước tạo database hay nạp lại toàn bộ dữ liệu.

Xem tổng số bài của từng user trong DB (bao gồm cả các bài khác, nếu có):

```powershell
$demoUserIds = (Import-Csv -LiteralPath data/demo-seed/users.csv | ForEach-Object { "'" + ([guid]$_.id).ToString() + "'::uuid" }) -join ', '
docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace -c "SELECT u.username, u.fullname, count(b.id) AS total_posts FROM public.users u LEFT JOIN public.blogs b ON b.author_id = u.id WHERE u.id IN ($demoUserIds) GROUP BY u.id, u.username, u.fullname ORDER BY u.username;"
```
