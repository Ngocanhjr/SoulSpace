# Chạy embedding từ PostgreSQL local

Luồng: **bài công khai trong DB local → Jina API → vector 1024 chiều → blog_embeddings trong DB local**. Giữ model `jina-embeddings-v3`, bài viết dùng `retrieval.passage`, query dùng `retrieval.query`.

Script đã có `--env-file`; không truyền thì vẫn đọc `.env.neon`. Hướng dẫn dưới dùng Docker PostgreSQL local của repository. Nếu PostgreSQL cài trực tiếp trên Windows, cài pgvector theo [hướng dẫn chính thức](https://github.com/pgvector/pgvector#windows), bỏ qua lệnh Docker và chạy SQL bằng công cụ quản trị DB local.

## 1. Điền cấu hình local

Chạy các lệnh từ gốc repository:

```powershell
cd D:\3-SoulSpace
if (-not (Test-Path -LiteralPath .env.docker)) {
    Copy-Item -LiteralPath .env.docker.example -Destination .env.docker
}
```

Điền `.env.docker` với ba mật khẩu khác nhau và Jina key:

```properties
POSTGRES_DB=soulspace
POSTGRES_PASSWORD=<mat-khau-postgres>
DB_URL=jdbc:postgresql://127.0.0.1:5432/soulspace
DB_USER=soulspace_runtime
DB_PASSWORD=<mat-khau-runtime>
FLYWAY_URL=jdbc:postgresql://127.0.0.1:5432/soulspace
FLYWAY_USER=soulspace_migrator
FLYWAY_PASSWORD=<mat-khau-migrator>
JINA_API_KEY=<key-Jina>
embedding.worker.enabled=false
embedding.worker.delay-ms=5000
```

Giữ worker `false` tới khi tạo xong bảng ở bước 3. Script Python không cần worker/backend đang chạy để backfill.

Quy tắc cấu hình cũ được giữ: các biến môi trường `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JINA_API_KEY` của terminal có ưu tiên hơn file. Nếu terminal đã nạp cấu hình Neon, đổi chúng về local hoặc dùng terminal chưa nạp chúng trước khi chạy; `--env-file` không vô hiệu hóa các biến này.

## 2. Khởi động DB có pgvector và chuẩn bị schema lõi

`compose.yaml` đã dùng image `pgvector/pgvector:0.8.7-pg17-bookworm`, giữ PostgreSQL major 17, cổng loopback 5432 và named volume hiện có. Image được liệt kê trong [tài liệu Docker của pgvector](https://github.com/pgvector/pgvector#docker).

```powershell
docker compose --env-file .env.docker up -d postgres
docker compose --env-file .env.docker ps
```

Nếu DB chưa có schema lõi, khởi động backend một lần để Flyway chạy V1–V5. Worker vẫn để `false`, Flyway cần bật (source mặc định là `true`):

```powershell
cd D:\3-SoulSpace\soulspace
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-local,web-local"
```

Sau khi backend khởi động và migration hoàn tất, có thể Ctrl+C để tiếp tục backfill riêng. Nếu DB đã có `public.blogs` và `public.v_public_blog_feed`, bỏ qua bước khởi tạo schema.

Không cần xóa volume để thêm pgvector. Với volume đã có, đổi mật khẩu trong env không tự cập nhật mật khẩu các role; dùng đúng mật khẩu hiện tại của DB.

## 3. Tạo bảng vector và hàng đợi job

File `semantic-search/sql/setup-embeddings.sql` tạo extension `vector`, bảng `blog_embeddings`, `blog_embedding_jobs`, index nhận job và quyền runtime. Bảng được sở hữu bởi `soulspace_migrator` như schema ứng dụng.

```powershell
cd D:\3-SoulSpace
Get-Content -LiteralPath semantic-search/sql/setup-embeddings.sql -Raw -Encoding utf8 | docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace -v ON_ERROR_STOP=1
```

Nếu `POSTGRES_DB` khác `soulspace`, thay tên sau `-d`. Cần schema lõi và các role từ bước 2. SQL không xóa bảng/dữ liệu; bảng đã tồn tại không bị tự thay cấu trúc.

## 4. Chuẩn bị dữ liệu và Python

DB local phải có bài công khai có nội dung. Tạo bảng vector không tự import bài từ Neon/corpus CSV. Nếu dùng bộ ground truth hiện tại, phải giữ đúng **ID, nội dung và trạng thái công khai của 350 bài**; seed bằng ID mới sẽ không khớp bộ đánh giá.

Các lệnh dưới dùng venv đã có ở `data/demo-seed/.venv`. Nếu máy mới chưa có, tạo/cài một lần:

```powershell
python -m venv data/demo-seed/.venv
.\data\demo-seed\.venv\Scripts\python.exe -m pip install -r data/demo-seed/requirements.txt
```

## 5. Nạp thử 3 bài rồi nạp phần còn lại

```powershell
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/scripts/embeddings.py --env-file .env.docker --limit 3
```

Khi thử đạt, nạp tất cả bài còn thiếu/cũ vector:

```powershell
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/scripts/embeddings.py --env-file .env.docker
```

Mỗi lô mặc định 16 bài; có thể đổi bằng `--batch-size 8`. Script bỏ qua bài trống và bài có vector khớp SHA-256 nội dung. Ba bài đã nạp thử sẽ được bỏ qua ở lượt sau nếu không đổi nội dung. Backfill gọi Jina thật và ghi vào DB local, vẫn cần Internet và hạn mức API.

## 6. Thử tìm kiếm và kiểm tra thực nghiệm

Không cần backend cho phép thử Python này:

```powershell
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/scripts/embeddings.py --env-file .env.docker --query "tự đặt câu hỏi sau mỗi buổi học"
```

Lệnh gọi Jina cho query và trả tối đa 10 bài gần nghĩa trên DB local. Nếu dùng tiếp bộ thực nghiệm, kiểm tra 350 bài/embedding:

```powershell
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/experiment/runner/01-check-corpus.py --check --env-file .env.docker
```

Khởi động backend bằng `db-local,web-local`, đăng nhập lại rồi chạy bước 02/03 trong [README thực nghiệm](../experiment/README.md). Hai bước đó không cần đổi thuật toán theo loại DB.

## 7. Tự tạo embedding cho bài mới qua backend

Sau khi hai bảng vector/job đã có, đổi `.env.docker` rồi khởi động lại backend local:

```properties
embedding.worker.enabled=true
embedding.worker.delay-ms=5000
```

Bài công khai mới tạo qua backend sẽ có job; worker gọi Jina và lưu vector local. Backfill Python dùng để nạp các bài đã có sẵn.

## Kiểm tra code không gọi DB/Jina

```powershell
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 -m unittest discover -s semantic-search/scripts -p "test_*.py" -v
python -X utf8 -m unittest discover -s semantic-search/experiment -p "test_*.py" -v
```

Neon vẫn dùng lệnh cũ hoặc `--env-file .env.neon`. Kết nối remote vẫn yêu cầu `sslmode=verify-full` và `channelBinding=require`; local loopback không bị bắt buộc TLS như Neon.
