# Kế hoạch tạo lại Neon và làm Semantic Search cho SoulSpace

## Cấu trúc thư mục cần tạo

```text
3-SoulSpace/
├── .env.neon                                        # Cập nhật cấu hình kết nối
├── sematic-search/
│   ├── scripts/
│   │   └── embeddings.py                            # Tạo mới · nạp vector và thử tìm kiếm
│   └── sql/
│       ├── blog_embeddings.sql                      # Tạo mới · bảng vector và quyền
│       └── blog_embedding_jobs.sql                  # Tạo mới · hàng đợi đồng bộ
├── soulspace/src/main/java/ctu/soulspace/
│   ├── controllers/SearchController.java            # API tìm kiếm
│   ├── services/SearchEmbedding.java                # Gọi Jina
│   ├── repositories/SearchRepository.java           # Truy vấn Neon
│   └── workers/BlogEmbeddingWorker.java             # Dự kiến ở bước 10 · cập nhật vector
└── frontend/src/page/SearchPage/
    └── SearchPage.jsx                               # Sửa · gọi API tìm kiếm
```

## Bước 1 — Tạo project Neon mới

1. Mở Neon Console → **New Project** → chọn **PostgreSQL 17** → tạo project demo.
2. Mở **SQL Editor**, chọn đúng branch và database của project mới, dùng role owner. Giữ project cũ đến bước 12.

## Bước 2 — Tạo bảng, index và trigger bằng SQL Editor

1. Tạo role cho backend và script nạp CSV. Thay hai chỗ giữ chỗ bằng mật khẩu mới và tên database thật, ví dụ `neondb`.

   ```sql
   CREATE ROLE soulspace_runtime LOGIN PASSWORD '<mat-khau-moi>';
   GRANT CONNECT ON DATABASE "<ten-database>" TO soulspace_runtime;
   GRANT USAGE ON SCHEMA public TO soulspace_runtime;
   ```

- mật khẩu lấy bằng cách: click chữ Connect, mk là chuỗi đứng trước dấu @
2. Mở [thư mục migration](../../soulspace/src/main/resources/db/migration/) và chạy nội dung từng file trên SQL Editor theo thứ tự **V1 → V2 → V3 → V4 → V5**. V1 tạo bảng, V2 tạo index, V3 tạo trigger, V4 tạo procedure và cấp quyền cho runtime, V5 tạo view bài công khai.
- Để nạp dữ liệu vào neon bằng seed.py (là công cụ nạp dữ liệu từ các file đó vào Neon. Nó đọc đủ 8 CSV, nạp theo thứ tự khóa ngoại, tạo hash mật khẩu cho user demo và kiểm tra dữ liệu sau khi nạp)
=> cần chạy v3 và v5 (seed.py import) => chạy luôn v2 và v4
3. Kiểm tra trên SQL Editor bằng 
`SELECT to_regclass('public.blogs'), to_regclass('public.v_public_blog_feed');`
Cả hai cột phải có kết quả.

## Bước 3 — Điền thông tin kết nối

1. Trên Neon bấm **Connect**, chọn đúng branch/database mới và tắt pooling để lấy **direct host**.
2. Mở `.env.neon` ở gốc repo. Dựa vào [file mẫu](../../.env.neon.example), cập nhật `DB_URL` với host và database mới, giữ cấu hình TLS của mẫu. Điền `DB_USER=soulspace_runtime`, `DB_PASSWORD` từ bước 2 và `DEMO_USER_PASSWORD` dài 15–128 ký tự cho tài khoản demo.
- đổi mk mới trên SQL Editor: ALTER ROLE soulspace_runtime PASSWORD '<mat-khau-moi>';
- demo_user_password là mk cho các tài khoản demo (đặt sao cũng được)
3. Chọn cách quản lý schema trong `.env.neon`.

   - **Cách A — Tiếp tục dùng SQL Editor.** Giữ `spring.flyway.enabled=false`. Các biến `FLYWAY_*` có thể để trống.
   - **Cách B — Chuyển sang Flyway sau này.** Kiểm tra schema trên Neon đã khớp đủ V1–V5. Tạo role `soulspace_migrator` có quyền tạo đối tượng trong schema `public` và quyền sở hữu phù hợp trên các đối tượng mà migration mới sẽ sửa. Điền `FLYWAY_URL` trỏ tới cùng database qua direct endpoint, `FLYWAY_USER=soulspace_migrator` và `FLYWAY_PASSWORD` riêng.
   - Với **Cách B**, chạy `flyway -baselineVersion=5 baseline` bằng Flyway CLI với URL và tài khoản migrator của database mới. Bước này ghi nhận V1–V5 đã được áp dụng. Sau khi baseline thành công, đặt `spring.flyway.enabled=true` và giữ `spring.flyway.baseline-on-migrate=false`. Flyway sẽ chạy các migration mới từ V6 khi backend khởi động. [Tài liệu Flyway baseline](https://documentation.red-gate.com/flyway/reference/commands/baseline)

   Cả hai cách đều dùng `DB_URL`, `DB_USER` và `DB_PASSWORD` để backend kết nối qua JDBC và `seed.py` kết nối qua `psycopg`.

4. **Cài chứng chỉ CA một lần trên mỗi máy Windows** để Python xác minh kết nối Neon. Lưu file vào `LocalAppData` và lưu đường dẫn ở cấp User:

   ```powershell
   $neonCa = Join-Path $env:LOCALAPPDATA 'neon-ca-bundle.pem'
   if (-not (Test-Path -LiteralPath $neonCa -PathType Leaf)) {
       Invoke-WebRequest -UseBasicParsing -Uri 'https://curl.se/ca/cacert.pem' -OutFile $neonCa -ErrorAction Stop
   }
   [Environment]::SetEnvironmentVariable('PGSSLROOTCERT', $neonCa, 'User')
   $env:PGSSLROOTCERT = $neonCa

   //kiểm tra đã có đường dẫn chứng chỉ chưa
   Test-Path -LiteralPath $env:PGSSLROOTCERT -PathType Leaf
   ```

   Nếu trả `True`, tiếp tục Bước 4 trong terminal hiện tại. Để terminal mới tự nhận biến, thoát hẳn VS Code rồi mở lại từ Start Menu. Nếu VS Code vẫn đang dùng môi trường cũ, nạp lại đường dẫn đã lưu bằng `$env:PGSSLROOTCERT = [Environment]::GetEnvironmentVariable('PGSSLROOTCERT', 'User')`.

## Bước 4 — Nạp CSV theo bảng

1. Từ gốc repo, tạo môi trường Python và cài thư viện. Phần chứng chỉ đã được thiết lập ở Bước 3, mục 4.

   ```powershell
   python -m venv data/demo-seed/.venv
   .\data\demo-seed\.venv\Scripts\python.exe -m pip install -r data/demo-seed/requirements.txt
   ```

2. Chạy lệnh dưới đây. Thay `neondb` nếu bạn đặt tên database khác.

   ```powershell
   .\data\demo-seed\.venv\Scripts\python.exe data/demo-seed/seed.py --apply --env-file .env.neon --target-database neondb
   ```

3. Chạy lại cùng lệnh với `--verify` thay cho `--apply`. Kết quả dự kiến có **40 user, 400 bài, 700 comment**. `seed.py` đọc đủ 8 CSV theo thứ tự khóa ngoại và tạo hash mật khẩu demo. SQL Editor không đọc được file CSV trên ổ `D:` qua `\copy`. [Tài liệu import CSV của Neon](https://neon.com/docs/import/import-from-csv)

## Bước 5 — Chạy backend với database mới

1. Từ gốc repo chạy `Set-Location soulspace`, rồi chạy `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-neon,web-local"`.
2. Mở `http://localhost:8080/actuator/health` và kiểm tra `status` là `UP`. Trên SQL Editor, chạy `SELECT count(*) FROM public.v_public_blog_feed;` và kiểm tra có 350 bài công khai.

## Bước 6 — Tạo embedding cho bài viết

1. Sau khi seed xong, lưu SQL dưới đây vào `blog_embeddings.sql` và chạy trên SQL Editor bằng role owner.

   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   CREATE TABLE public.blog_embeddings (
       blog_id UUID PRIMARY KEY REFERENCES public.blogs(id) ON DELETE CASCADE,
       embedding VECTOR(1024) NOT NULL,
       content_sha256 TEXT NOT NULL
   );
   GRANT SELECT, INSERT, UPDATE, DELETE ON public.blog_embeddings TO soulspace_runtime;
   ```

2. Điền `JINA_API_KEY` trong `.env.neon`. Script `embeddings.py` đọc bài công khai có nội dung, gọi Jina v3 với `retrieval.passage` và lưu vector 1024 chiều cùng SHA-256 của nội dung theo `blog_id`. Chạy script theo hướng dẫn ở Bước 7. [Tài liệu API Jina](https://docs.jina.ai/)

## Bước 7 — Thử tìm kiếm trên Neon

1. Mở PowerShell, về gốc repo và kích hoạt môi trường ảo đã tạo ở Bước 4. Khi thấy `(.venv)`, dùng `python` ở đầu lệnh để chạy script.

   ```powershell
   Set-Location D:\3-SoulSpace
   .\data\demo-seed\.venv\Scripts\Activate.ps1
   ```

2. **Nạp vector cho bài viết nếu chưa nạp.** Chạy thử 3 bài trước, sau đó bỏ `--limit 3` để nạp phần còn lại. Script bỏ qua các bài đã có vector khớp nội dung. Nếu đã nạp hết, chuyển sang mục 3.

   ```powershell
   python .\sematic-search\scripts\embeddings.py --limit 3
   python .\sematic-search\scripts\embeddings.py
   ```

3. **Chạy tìm kiếm.** `--query` tạo vector cho câu tìm kiếm bằng `retrieval.query` và trả tối đa 10 bài công khai có vector khớp nội dung hiện tại, xếp theo độ tương đồng cosine. Có thể chạy khi backend tắt.

   ```powershell
   python .\sematic-search\scripts\embeddings.py --query "Áp lực ôn thi"
   ```

   Kết quả hiện tác giả, điểm tương đồng và đoạn trích. Nếu báo không tìm thấy bài công khai có embedding khớp nội dung, kiểm tra kết quả nạp ở mục 2 và đảm bảo `.env.neon` đang trỏ đúng database đã nạp. Xem [luồng chính](flowmain.md).

## Bước 8 — Tạo API tìm kiếm trong Spring Boot

1. **Chuẩn bị trên máy mới.** Cài JDK 21 và lấy source có đủ ba file trong `soulspace/src/main/java/ctu/soulspace/` gồm `controllers/SearchController.java`, `services/SearchEmbedding.java`, `repositories/SearchRepository.java`. Tạo `.env.neon` tại gốc repo với `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JINA_API_KEY` và `spring.flyway.enabled=false` theo các bước trước. Trỏ tới database đã nạp vector ở Bước 7. Endpoint hiện tại là `GET /api/v1/search`; dùng đường dẫn này trong lệnh thử bên dưới.

2. **Lưu JAVA_HOME một lần trên mỗi máy.** Chạy `Get-ChildItem 'C:\Program Files\Java' -Directory` để xem thư mục JDK. Thay đường dẫn bên dưới bằng thư mục JDK 21 thật của máy, rồi chạy trong PowerShell.

   ```powershell
   $jdkPath = 'C:\Program Files\Java\jdk-21.0.10'
   [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkPath, 'User')
   $env:JAVA_HOME = $jdkPath
   ```

   Để terminal mới nhận cấu hình, thoát hẳn VS Code rồi mở lại từ Start Menu. Nếu JDK nằm ngoài `C:\Program Files\Java`, dùng đường dẫn cài đặt của JDK đó.

3. **Chạy backend trong terminal thứ nhất.** Thay đường dẫn repo nếu máy mới lưu ở vị trí khác.

   ```powershell
   Set-Location D:\3-SoulSpace\soulspace
   .\mvnw.cmd -v
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-neon,web-local"
   ```

   Kiểm tra Maven dùng Java 21. Chờ dòng `Started SoulspaceApplication` và giữ terminal này chạy. API gọi Jina bằng `retrieval.query`, truyền vector qua JDBC tới Neon rồi trả `id`, `content`, `username`, `similarity`.

4. **Đăng nhập trong terminal PowerShell thứ hai.** Với cấu hình Spring Security mặc định hiện tại, lấy mật khẩu sau dòng `Using generated security password:` trong log của terminal thứ nhất. Chạy lệnh dưới để hiện hộp đăng nhập, giữ username `user` và dán mật khẩu đó vào ô **Password**, rồi bấm **OK**. Mật khẩu được tạo lại khi backend khởi động lại.

   ```powershell
   $credential = Get-Credential -UserName 'user' -Message 'Dang nhap backend'
   ```

5. **Gọi API và đọc tiếng Việt bằng UTF-8.** Chạy trong terminal thứ hai sau khi đăng nhập.

   ```powershell
   $query = [Uri]::EscapeDataString('Áp lực ôn thi')
   $resultFile = Join-Path $env:TEMP 'semantic-search.json'
   Invoke-WebRequest -UseBasicParsing `
       -Uri "http://localhost:8080/api/v1/search?q=$query" `
       -Credential $credential `
       -OutFile $resultFile
   Get-Content -LiteralPath $resultFile -Raw -Encoding UTF8
   ```

   Kết quả mong đợi là HTTP `200` và tối đa 10 bài công khai, có `id`, `content`, `username`, `similarity`. Đổi câu trong dấu nháy rồi chạy lại khối lệnh để thử câu khác.

6. **Kiểm tra lỗi.** `401` cần nhập lại mật khẩu của lần khởi động backend hiện tại. Câu tìm kiếm rỗng trả `400`. Thiếu key hoặc Jina không khả dụng trả `503`. Danh sách `[]` cần kiểm tra database đích và vector đã nạp ở Bước 7. Để dừng backend, nhấn **Ctrl+C** trong terminal thứ nhất.

## Bước 9 — Nối API vào SearchPage

1. **Các phần đã xây dựng.** Lấy source có đủ các file sau trên máy mới.

   | File | Công việc |
   |---|---|
   | `soulspace/src/main/java/ctu/soulspace/controllers/SearchController.java` | Nhận `GET /api/v1/search?q=...` |
   | `soulspace/src/main/java/ctu/soulspace/services/SearchEmbedding.java` | Gọi Jina v3 tạo vector cho câu tìm kiếm |
   | `soulspace/src/main/java/ctu/soulspace/repositories/SearchRepository.java` | Tìm tối đa 10 bài công khai, trả `id`, `content`, `username`, `similarity` |
   | `frontend/src/page/SearchPage/SearchPage.jsx` | Gửi câu khi nhấn Enter, hiển thị kết quả, trạng thái chờ và lỗi; hủy lượt tìm cũ khi gửi câu mới |
   | `frontend/src/config/api.js` | Ghép URL API; dùng đường dẫn cùng origin khi phát triển qua Vite |
   | `frontend/vite.config.js` | Proxy `/api`, `/login`, `/logout` tới backend cổng 8080 |

   Luồng hoạt động là **SearchPage → API Spring Boot → Jina tạo vector câu hỏi → Neon tìm theo cosine → JSON → SearchPage**. Bài viết được tìm bằng semantic. Nhóm People gọi riêng `GET /api/v1/users` và lọc theo tên/username; API này do phần backend người dùng cung cấp.

2. **Chuẩn bị trên máy khác.** Cài Node.js 22.12 trở lên và JDK 21, cấu hình `JAVA_HOME` theo Bước 8. Tạo `.env.neon` tại gốc repo theo Bước 3, có `JINA_API_KEY` và thông tin kết nối tới database đã nạp bài và vector. Với cùng database đã chuẩn bị, có thể chạy thẳng API và frontend. Cấu hình dưới dùng `spring.flyway.enabled=false` cho schema đã tạo thủ công.

   Tạo `frontend/.env.local` với nội dung sau để request đi qua Vite proxy. Khởi động lại Vite sau khi đổi cấu hình này.

   ```dotenv
   VITE_API_BASE_URL=/
   ```

3. **Terminal thứ nhất — chạy backend.** Thay đường dẫn repo theo máy đang dùng.

   ```powershell
   cd soulspace
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-neon,web-local"
   ```

   Chờ `Started SoulspaceApplication` và `Tomcat started on port 8080`. Giữ terminal chạy và lấy mật khẩu ở dòng `Using generated security password:` cho mục 5.

4. **Terminal thứ hai — chạy frontend.** Cài thư viện lần đầu hoặc khi lockfile thay đổi, rồi chạy Vite.

   ```powershell
   Set-Location D:\3-SoulSpace\frontend
   npm ci
   npm run dev
   ```

   Giữ cả hai terminal chạy. Dùng đúng cổng Vite in ra, thường là `5173`.

5. **Đăng nhập và tìm kiếm trên trình duyệt.** Mở `http://localhost:5173/login` đầy đủ chữ `login`, nhập username `user` và mật khẩu Spring của lần khởi động hiện tại. Sau đó mở `http://localhost:5173/#/search`. Frontend dùng HashRouter nên đường dẫn trang có `/#/search`.

   Nhập “Áp lực ôn thi” rồi nhấn Enter. Kết quả mong đợi là nhóm Posts có tối đa 10 bài và tiếng Việt đúng. Trong **F12 → Network**, kiểm tra request `/api/v1/search?q=...` trả `200` và danh sách JSON.

6. **Các tình huống đã gặp.**

   - `ECONNREFUSED` là proxy chưa kết nối được backend; kiểm tra backend đã khởi động xong trước khi tải lại trang.
   - `401` cần đăng nhập lại bằng mật khẩu Spring của lần chạy hiện tại. Đăng nhập qua `/login` được proxy tới Spring và dùng cookie phiên cho API.
   - Sau đăng nhập, nếu gặp `404` tại `http://localhost:8080/`, mở lại `http://localhost:5173/#/search`; backend hiện chưa có trang chủ tại `/`.
   - Nhóm People báo chưa tải được thì kiểm tra `GET /api/v1/users` với người phụ trách backend. Kết quả semantic của Posts vẫn xử lý độc lập.
   - Khung Sign in or Sign up của frontend dựa vào trạng thái `localStorage`, tách với phiên Spring dùng để thử API. Có thể bấm **Continue as guest** để đóng khung trong lúc thử tìm kiếm.
   - Bấm kết quả sẽ mở trang chi tiết gọi `GET /api/v1/blogs/{id}`. Phần hiển thị chi tiết cần API bài viết tương ứng từ người phụ trách backend.

   Trên máy hiện tại đã thử thành công luồng tìm kiếm bài từ giao diện và hiển thị 10 kết quả. Khi dùng máy khác, kiểm tra lại mục 5 để xác nhận cấu hình và kết nối.

## Bước 10 — Đồng bộ embedding khi bài thay đổi

1. **Cấu trúc cần xây dựng và tích hợp.** Các file dưới đây là dự kiến; đối chiếu source của người phụ trách backend trước khi tạo để dùng lại phần đã có.

   ```text
   3-SoulSpace/
   ├── sematic-search/sql/
   │   └── blog_embedding_jobs.sql             # Tạo mới · bảng job và quyền cho runtime
   └── soulspace/src/main/java/ctu/soulspace/
       ├── controllers/
       │   └── BlogController.java             # Backend bài viết · nhận yêu cầu tạo/sửa/xóa
       ├── services/
       │   ├── BlogService.java                # Backend bài viết · lưu bài và ghi job cùng transaction
       │   └── SearchEmbedding.java            # Bổ sung · embedding bài bằng retrieval.passage
       ├── repositories/
       │   ├── BlogRepository.java             # Backend bài viết · đọc/ghi bảng blogs
       │   ├── EmbeddingJobRepository.java     # Tạo mới · ghi, nhận và cập nhật job
       │   └── SearchRepository.java           # Bổ sung · lưu/xóa vector và hash nội dung
       ├── workers/
       │   └── BlogEmbeddingWorker.java        # Tạo mới · xử lý job định kỳ
       └── SoulspaceApplication.java           # Bổ sung · bật scheduling cho worker
   ```

   `BlogController`, `BlogService`, `BlogRepository` do người phụ trách API bài viết xây dựng. Phần embedding cung cấp `EmbeddingJobRepository.enqueue(blogId)` để tích hợp vào bước lưu bài.

2. **Tạo bảng job trên Neon SQL Editor.** `blog_embedding_jobs.sql` tạo khóa chính `blog_id`, đồng thời là khóa ngoại tới `blogs.id` với `ON DELETE CASCADE`. Các trường gồm `generation`, `status` (`PENDING`, `RUNNING`, `DONE`, `DEAD`), `attempts`, `available_at`, `lease_until`, `last_error`. Cấp quyền cần thiết cho `soulspace_runtime` và tạo index cho job đến hạn. Mỗi bài có một dòng job; `enqueue()` tăng generation, đặt lại trạng thái chờ và số lần thử cho phiên bản mới.

3. **Tích hợp ngay trong bước lưu bài.** FE gửi yêu cầu tới `BlogController`, controller gọi `BlogService`. Service mở transaction, kiểm tra quyền của người đăng, lưu bài và ghi job, rồi commit trước khi trả kết quả cho FE.

   ```text
   FE → BlogController → BlogService (@Transactional)
                            ├── BlogRepository lưu vào blogs
                            └── EmbeddingJobRepository.enqueue(blogId)
                        → Commit → Trả bài đã tạo/sửa cho FE
   ```

   Mã minh họa tại vị trí tích hợp trong service:

   ```java
   @Transactional
   public Blog createBlog(...) {
       Blog blog = blogRepository.saveAndFlush(...);
       embeddingJobs.enqueue(blog.getId());
       return blog;
   }
   ```

   Người phụ trách backend thay phần `...` bằng tham số và cách lưu thật. Khi dùng JPA, gọi `saveAndFlush()` hoặc `flush()` trước khi `enqueue()` dùng JDBC để bản ghi bài đã được ghi vào DB trong transaction. `enqueue()` phải dùng cùng datasource và transaction với lưu bài. Lỗi ghi job phải khiến transaction rollback. Áp dụng cùng cơ chế khi sửa nội dung hoặc đổi quyền công khai/riêng tư; mọi nơi ghi bài cần đi qua luồng này để worker nhận được thay đổi.

4. **Worker chạy nền sau khi bài đã lưu.** Bật scheduling trong Spring Boot, cho `BlogEmbeddingWorker` kiểm tra job đến hạn định kỳ, ví dụ mỗi 5 giây. Worker nhận job bằng transaction ngắn có khóa để nhiều worker không lấy cùng job, đánh dấu `RUNNING` và đặt thời hạn giữ job. Sau đó đọc bài và gọi Jina ngoài transaction dài bằng `retrieval.passage`, cùng model và 1024 chiều như bước nạp ban đầu.

   ```text
   Job PENDING đã commit
     → Worker nhận job → Đọc bài → Jina trả vector
     → Kiểm tra lại generation, nội dung và quyền bài
     → Lưu blog_embeddings và đánh dấu DONE trong cùng transaction
   ```

   Trước khi ghi vector, khóa/kiểm tra lại bài và job trong transaction để tránh kết quả cũ ghi đè khi bài vừa thay đổi. Lưu vector kèm SHA-256 nội dung UTF-8. Nếu bài riêng tư hoặc nội dung rỗng, xóa vector và hoàn tất phiên bản job phù hợp. Khi xóa bài, khóa ngoại xóa vector và job tương ứng.

5. **Xử lý lỗi và thử riêng trước khi nối API bài viết.** Jina lỗi thì ghi lỗi an toàn, đặt thời điểm thử lại và giới hạn số lần thử; hết số lần thì chuyển `DEAD`. Job `RUNNING` hết lease được nhận lại nếu worker dừng giữa chừng. Mọi thao tác lưu kết quả hoặc cập nhật trạng thái phải kiểm tra generation và quyền giữ job hiện tại.

   Trong lúc chờ API bài viết, ghi một job thử cho bài đã có trên Neon rồi chạy Spring Boot. Kiểm tra worker tự nhận job, lưu vector và chuyển `DONE`. Sau khi tích hợp, thử tạo bài, sửa liên tiếp, đổi quyền và xóa bài. Kiểm tra bài riêng tư không xuất hiện và vector cũ không được lưu đè lên nội dung mới.

   Khi worker hoạt động, bài mới được embedding tự động. Giữ `embeddings.py` để nạp vector ban đầu hoặc chạy lại theo lô khi cần.

6. **Bật worker đã viết trong source.** `SoulspaceApplication.java` có `@EnableScheduling`, cho phép Spring chạy hàm `@Scheduled`. `BlogEmbeddingWorker.runNext()` nhận tối đa một job mỗi lượt, chờ 5 giây sau khi lượt trước kết thúc rồi kiểm tra tiếp. Backend cần tiếp tục chạy để xử lý job.

   Sau khi đã tạo bảng job và cấp quyền như mục 2, thêm vào `.env.neon` ở gốc repo:

   ```properties
   embedding.worker.enabled=true
   embedding.worker.delay-ms=5000
   ```

   Worker mặc định tắt khi thiếu dòng `enabled`. Để tạm dừng, đặt `embedding.worker.enabled=false` rồi khởi động lại backend. Worker dùng `JINA_API_KEY` và kết nối DB đã cấu hình trong `.env.neon`.

   Trong PowerShell, khởi động backend bằng lệnh:

   ```powershell
   Set-Location D:\3-SoulSpace\soulspace
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-neon,web-local"
   ```

   Để thử với một bài public có sẵn, chạy SQL sau bằng owner trong Neon SQL Editor. Chọn bài đang có trong `v_public_blog_feed`; lệnh thêm tối đa một job. Lần chạy lại tăng generation và đặt job về PENDING.

   ```sql
   INSERT INTO public.blog_embedding_jobs AS j (blog_id)
   SELECT blog_id FROM public.v_public_blog_feed
   WHERE btrim(content) <> ''
   ORDER BY blog_id
   LIMIT 1
   ON CONFLICT (blog_id) DO UPDATE
   SET generation = j.generation + 1,
       status = 'PENDING', attempts = 0,
       available_at = now(), lease_until = NULL, last_error = NULL;

   SELECT blog_id, generation, status, attempts, last_error
   FROM public.blog_embedding_jobs;
   ```

   Khi xử lý thành công, backend ghi log `Đã đồng bộ embedding cho bài ...` và job chuyển `DONE`. Một lượt gọi Jina thật sẽ sử dụng quota API. Phần tích hợp `enqueue()` vào API tạo/sửa bài vẫn cần phối hợp với người phụ trách backend bài viết.

## Bước 11 — Kiểm tra toàn bộ luồng

1. Mở SearchPage, nhập một câu tiếng Việt, kiểm tra kết quả và bấm mở bài. Kiểm tra trạng thái rỗng khi API trả `[]` và trạng thái lỗi khi API trả `503`.
2. Dùng cùng một bộ câu hỏi để so sánh với `ILIKE`. Ghi tỷ lệ bài liên quan trong 5 kết quả đầu và thời gian từ lúc gửi câu đến lúc có kết quả.

## Bước 12 — Xóa project Neon cũ

1. Khi project mới đã chạy đúng và dữ liệu cần giữ đã được sao lưu, mở **đúng project cũ** → **Settings → General → Delete project**. Thao tác này xóa cả database và branch thuộc project đó. [Tài liệu xóa project của Neon](https://neon.com/docs/manage/projects#delete-a-project)
