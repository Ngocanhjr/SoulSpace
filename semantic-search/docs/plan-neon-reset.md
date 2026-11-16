# Kế hoạch tạo Neon và làm Semantic Search cho SoulSpace

## Cấu trúc thư mục cần tạo

```text
3-SoulSpace/
├── .env.neon                                        # Cập nhật cấu hình kết nối
├── semantic-search/
│   ├── scripts/
│   │   └── embeddings.py                            # Tạo mới · nạp vector và thử tìm kiếm
│   └── sql/
│       ├── blog_embeddings.sql                      # Tạo mới · bảng vector và quyền
│       └── blog_embedding_jobs.sql                  # Tạo mới · hàng đợi đồng bộ
├── soulspace/src/main/java/ctu/soulspace/
│   ├── controller/SearchController.java             # API tìm kiếm
│   ├── service/SearchEmbeddingService.java          # Gọi Jina
│   ├── repositories/SearchRepository.java           # Truy vấn Neon
│   └── workers/BlogEmbeddingWorker.java             # Dự kiến ở bước 10 · cập nhật vector
└── frontend/src/
    ├── config/searchHistory.js                      # Tạo mới · lịch sử localStorage, bước 11
    └── page/SearchPage/
        ├── SearchPage.jsx                           # Sửa · tìm kiếm, gợi ý và lịch sử
        └── SearchPage.module.css                    # Bổ sung · giao diện lịch sử
```

## Bước 1 — Tạo project Neon mới

1. Mở Neon Console → **New Project** → chọn **PostgreSQL 17** → tạo project demo.
2. Mở **SQL Editor**, chọn đúng branch và database của project mới, dùng role owner. Giữ project cũ đến bước 13.

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
   python .\semantic-search\scripts\embeddings.py --limit 3
   python .\semantic-search\scripts\embeddings.py
   ```

3. **Chạy tìm kiếm.** `--query` tạo vector cho câu tìm kiếm bằng `retrieval.query` và trả tối đa 10 bài công khai có vector khớp nội dung hiện tại, xếp theo độ tương đồng cosine. Có thể chạy khi backend tắt.

   ```powershell
   python .\semantic-search\scripts\embeddings.py --query "Áp lực ôn thi"
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

   Kết quả mong đợi là HTTP `200` và tối đa 50 bài công khai, có `id`, `content`, `username`, `similarity`. Endpoint nhận thêm `limit` từ 1 đến 50, mặc định 50. Đổi câu trong dấu nháy rồi chạy lại khối lệnh để thử câu khác.

6. **Kiểm tra lỗi.** `401` cần nhập lại mật khẩu của lần khởi động backend hiện tại. Câu tìm kiếm rỗng trả `400`. Thiếu key hoặc Jina không khả dụng trả `503`. Danh sách `[]` cần kiểm tra database đích và vector đã nạp ở Bước 7. Để dừng backend, nhấn **Ctrl+C** trong terminal thứ nhất.

## Bước 9 — Nối API vào SearchPage

1. **Các phần đã xây dựng.** Lấy source có đủ các file sau trên máy mới.

   | File | Công việc |
   |---|---|
   | `soulspace/src/main/java/ctu/soulspace/controller/SearchController.java` | Nhận `GET /api/v1/search?q=...&limit=...`, mặc định/tối đa 50 bài |
   | `soulspace/src/main/java/ctu/soulspace/service/SearchEmbeddingService.java` | Gọi Jina v3 tạo vector cho câu tìm kiếm |
   | `soulspace/src/main/java/ctu/soulspace/repositories/SearchRepository.java` | Dùng tham số SQL cho giới hạn số bài công khai; trả `id`, `content`, `username`, `similarity` |
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

   Nhập “Áp lực ôn thi” và ngừng gõ khoảng 600 ms: frontend gọi `/api/v1/search?q=...&limit=8` để hiện tối đa 8 bài gợi ý gần nghĩa. Khi nhấn Enter, frontend gọi lại với `limit=50` để hiện nhiều kết quả hơn, tối đa 50 bài. API tìm bài vẫn yêu cầu đăng nhập. Trong **F12 → Network**, kiểm tra `q`, `limit`, response `200` và danh sách JSON; request cũ bị hủy khi đổi từ khóa, nhấn Enter, xóa ô hoặc rời trang. Gợi ý dùng cùng Jina như tìm kiếm sau Enter, nên mỗi request được backend xử lý có thể sử dụng quota; thời gian chờ 600 ms giảm số lần gọi khi gõ liên tục.

6. **Các tình huống đã gặp.**

   - `ECONNREFUSED` là proxy chưa kết nối được backend; kiểm tra backend đã khởi động xong trước khi tải lại trang.
   - `401` cần đăng nhập lại bằng mật khẩu Spring của lần chạy hiện tại. Đăng nhập qua `/login` được proxy tới Spring và dùng cookie phiên cho API.
   - Sau đăng nhập, nếu gặp `404` tại `http://localhost:8080/`, mở lại `http://localhost:5173/#/search`; backend hiện chưa có trang chủ tại `/`.
   - Nhóm People báo chưa tải được thì kiểm tra `GET /api/v1/users` với người phụ trách backend. Kết quả semantic của Posts vẫn xử lý độc lập.
   - Khung Sign in or Sign up của frontend dựa vào trạng thái `localStorage`, tách với phiên Spring dùng để thử API. Có thể bấm **Continue as guest** để đóng khung trong lúc thử tìm kiếm.
   - Bấm kết quả sẽ mở trang chi tiết gọi `GET /api/v1/blogs/{id}`. Phần hiển thị chi tiết cần API bài viết tương ứng từ người phụ trách backend.

   Bản thử trước đã tìm kiếm từ giao diện và hiển thị 10 kết quả. Bản cập nhật gợi ý 8 bài/kết quả Enter tối đa 50 bài đã đạt 33 test backend liên quan, ESLint SearchPage và build frontend; test dùng mock Jina/database. Chưa kiểm tra trực tiếp giao diện hoặc gọi Jina thật cho luồng gợi ý mới. Khi dùng máy khác, kiểm tra lại mục 5 để xác nhận cấu hình và kết nối.

## Bước 10 — Tự động embedding khi tạo bài mới

Đã hoàn thành phần code và tích hợp ghi job vào API tạo bài ngày 15/11/2026. Luồng hiện tại:

```text
Đăng bài → Lưu bài + ghi job → Commit
    → Worker nhận job → Gọi Jina → Lưu vector → DONE
```

Phạm vi hiện tại là tự động xử lý bài mới được tạo qua API. Chức năng sửa bài, đổi quyền sau khi đăng và xóa bài được để lại cho giai đoạn sau. Kết quả chạy thực tế trên Neon/Jina vẫn cần kiểm chứng riêng theo mục 6.

1. **Các file đã có và phần code đã bổ sung.** Cấu trúc đã thống nhất dùng `controller/`, `service/` và tên `SearchEmbeddingService.java`:

   ```text
   soulspace/src/
   ├── main/java/ctu/soulspace/
   │   ├── controller/BlogController.java
   │   ├── service/
   │   │   ├── BlogService.java
   │   │   └── SearchEmbeddingService.java
   │   ├── repositories/
   │   │   ├── BlogRepository.java
   │   │   ├── EmbeddingJobRepository.java
   │   │   └── SearchRepository.java
   │   ├── workers/BlogEmbeddingWorker.java
   │   └── SoulspaceApplication.java
   └── test/java/ctu/soulspace/
       ├── blog/BlogCreationTransactionTests.java
       ├── controllers/SearchControllerTests.java
       └── workers/BlogEmbeddingWorkerTests.java
   ```

   | File | Vai trò và nội dung đã thực hiện |
   | --- | --- |
   | [BlogController.java](../../soulspace/src/main/java/ctu/soulspace/controller/BlogController.java) | Dùng lại API `POST /api/v1/blogs` của backend để nhận bài mới và gọi `BlogService.create()`. FE không cần gọi thêm API embedding. |
   | [BlogService.java](../../soulspace/src/main/java/ctu/soulspace/service/BlogService.java) | **Bổ sung lần này:** inject `EmbeddingJobRepository` qua constructor; gọi `embeddingJobs.enqueue(blog.getId())` sau khi lưu bài, gắn ảnh và flush, ngay trong transaction tạo bài. |
   | [BlogRepository.java](../../soulspace/src/main/java/ctu/soulspace/repositories/BlogRepository.java) | Dùng lại repository JPA và `saveAndFlush()` để ghi bài trước khi JDBC ghi job. |
   | [EmbeddingJobRepository.java](../../soulspace/src/main/java/ctu/soulspace/repositories/EmbeddingJobRepository.java) | Đã có `enqueue()`, `claim()`, `lockOwned()`, `complete()` và `fail()`; quản lý generation, lượt thử và thời hạn giữ job. |
   | [SearchEmbeddingService.java](../../soulspace/src/main/java/ctu/soulspace/service/SearchEmbeddingService.java) | Đã có `embedPost()` dùng Jina v3, adapter `retrieval.passage`, 1024 chiều; đã cập nhật package/class/constructor theo tên file mới. |
   | [SearchRepository.java](../../soulspace/src/main/java/ctu/soulspace/repositories/SearchRepository.java) | Đã có đọc/khóa bài, lưu hoặc xóa vector, lưu SHA-256 nội dung UTF-8; tìm kiếm chỉ dùng vector có hash khớp nội dung hiện tại. |
   | [BlogEmbeddingWorker.java](../../soulspace/src/main/java/ctu/soulspace/workers/BlogEmbeddingWorker.java) | Đã có worker định kỳ nhận job, gọi Jina, kiểm tra lại bài và lượt xử lý rồi ghi kết quả; đã đổi import sang `SearchEmbeddingService`. |
   | [SoulspaceApplication.java](../../soulspace/src/main/java/ctu/soulspace/SoulspaceApplication.java) | Đã bật `@EnableScheduling` để chạy worker. |
   | [BlogCreationTransactionTests.java](../../soulspace/src/test/java/ctu/soulspace/blog/BlogCreationTransactionTests.java) | **Tạo mới lần này:** 2 test kiểm tra enqueue trước commit và ghi job lỗi thì yêu cầu rollback, gọi dọn ảnh đã upload. Transaction manager và các phụ thuộc được mock. |
   | [BlogEmbeddingWorkerTests.java](../../soulspace/src/test/java/ctu/soulspace/workers/BlogEmbeddingWorkerTests.java) | Đã có 7 test worker dùng mock; cập nhật import và kiểu dữ liệu theo tên service mới. |
   | [SearchControllerTests.java](../../soulspace/src/test/java/ctu/soulspace/controllers/SearchControllerTests.java) | Cập nhật import controller/service sau khi sắp xếp thư mục; 3 test search hiện có đã chạy đạt. |

2. **Transaction lưu bài và ghi job đã được nối.** `BlogService.create()` dùng `TransactionTemplate`, mở transaction bằng `transactions.execute(...)`. Sau khi kiểm tra người đăng, service lưu bài, gắn ảnh rồi ghi job trước khi commit và trả kết quả cho FE.

   ```text
   FE → POST /api/v1/blogs → BlogController → BlogService.create()
       → Upload ảnh nếu có, trước transaction lưu bài
       → transactions.execute(...)
           ├── Kiểm tra/khóa người đăng
           ├── BlogRepository.saveAndFlush(blog)
           ├── MediaService.attach(...) + EntityManager.flush()
           └── EmbeddingJobRepository.enqueue(blogId)
       → Commit → Trả kết quả tạo bài cho FE
   ```

   Phần code đã bổ sung nằm ở cuối transaction hiện có:

   ```java
   blogs.saveAndFlush(blog);
   List<String> urls = media.attach(actor, blog.getId(), uploaded);
   em.flush();
   embeddingJobs.enqueue(blog.getId());
   return new BlogCreateResponse(
       blog.getId(), urls, List.copyOf(failedFiles));
   ```

   `enqueue()` dùng JDBC trên cùng datasource, với `Propagation.MANDATORY` để yêu cầu transaction đang tồn tại. Khi ghi job lỗi, exception được truyền ra để rollback transaction lưu bài; nhánh xử lý lỗi của `BlogService` gọi `media.discard(uploaded)` để dọn ảnh. Jina được gọi sau commit bởi worker, nên FE không phải chờ tạo vector và lỗi Jina được xử lý bằng retry job.

3. **Worker xử lý và lưu vector đã có trong source.** `BlogEmbeddingWorker.runNext()` nhận tối đa một job mỗi lượt bằng `FOR UPDATE SKIP LOCKED`, chuyển `RUNNING`, tăng số lần thử và đặt lease 2 phút. Worker đọc bài rồi gọi Jina ngoài transaction database.

   ```text
   Job PENDING đã commit
     → claim(): RUNNING + lease
     → Đọc bài → Jina retrieval.passage trả vector 1024 chiều
     → Transaction hoàn tất: khóa lại bài và kiểm tra generation/lượt thử/lease
     → Lưu blog_embeddings + SHA-256 nội dung → Job DONE → Commit
   ```

   Nếu bài riêng tư hoặc nội dung rỗng, worker xóa vector và hoàn tất job mà không cần tạo embedding mới. Nếu nội dung thay đổi trong lúc gọi Jina, worker ghi lại job thay vì lưu vector cũ. Job mất quyền xử lý không được ghi kết quả. Đây là các nhánh bảo vệ đã có trong worker; API sửa bài/đổi quyền/xóa bài chưa nằm trong phạm vi hiện tại.

   Jina lỗi thì lưu tên loại lỗi, đặt thời điểm thử lại và xóa lease. Tối đa 5 lượt xử lý; hết lượt chuyển `DEAD`. Job `RUNNING` hết lease có thể được nhận lại nếu chưa hết lượt thử. Giữ `embeddings.py` cho nạp vector ban đầu hoặc xử lý theo lô khi cần.

4. **Điều kiện database và bật worker.** Database chạy ứng dụng cần có `blog_embeddings`, `blog_embedding_jobs` và quyền cần thiết cho `soulspace_runtime`. Bảng job cần khóa chính `blog_id`, FK tới `blogs.id` với `ON DELETE CASCADE`, các trường `generation`, `status` (`PENDING`, `RUNNING`, `DONE`, `DEAD`), `attempts`, `available_at`, `lease_until`, `last_error`, cùng index nhận job đến hạn. Mỗi bài có một dòng job; enqueue lại tăng generation và đặt về trạng thái chờ.

   Chưa kiểm tra trực tiếp schema/quyền trên Neon trong lần bổ sung code này. File SQL dự kiến `semantic-search/sql/blog_embedding_jobs.sql` hiện chưa có trong repo, và Flyway V1–V5 chưa chứa bảng job; cần lưu lại SQL đã chạy trên Neon để tái tạo môi trường. Nếu thiếu bảng hoặc quyền ghi job, transaction tạo bài sẽ rollback.

   `.env.neon` trên máy đã có cấu hình sau và có `JINA_API_KEY`; không ghi giá trị secret vào tài liệu:

   ```properties
   embedding.worker.enabled=true
   embedding.worker.delay-ms=5000
   ```

   Khởi động backend với profile Neon để đọc file cấu hình đó:

   ```powershell
   Set-Location D:\3-SoulSpace\soulspace
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-neon,web-local"
   ```

   Backend mặc định dùng `db-local,web-local` nếu không chọn profile. Worker chỉ được tạo khi `embedding.worker.enabled=true`. Backend cần tiếp tục chạy; sau mỗi lượt xử lý, worker chờ 5 giây rồi kiểm tra tiếp. Đây là thời gian giữa các lượt, không phải cam kết bài sẽ có vector trong 5 giây. Để tạm dừng, đặt `embedding.worker.enabled=false` rồi khởi động lại.

5. **Kiểm chứng code đã thực hiện ngày 15/11/2026.** Hai test mới đã thất bại trước khi thêm enqueue, rồi chạy đạt sau khi tích hợp. Maven compile source chính và test thành công; nhóm 28 test liên quan chạy đạt, không có failure/error/skipped:

   | Bộ test | Số test đạt | Phạm vi |
   | --- | --- | --- |
   | `BlogCreationTransactionTests` | 2 | Enqueue sau lưu bài, trước commit; lỗi enqueue truyền ra và yêu cầu rollback/dọn ảnh. |
   | `BlogEmbeddingWorkerTests` | 7 | Lưu vector trước hoàn tất job; bỏ kết quả mất quyền, requeue nội dung đổi, xử lý private và lỗi. |
   | `SearchControllerTests` | 3 | Kiểm tra từ khóa, gọi tìm kiếm và lỗi thiếu API key. |
   | `InteractionTransactionTests` | 3 | Các test retry/rollback nghiệp vụ hiện có. |
   | `LocalAuthenticationTests` | 13 | Các test xác thực/session/CSRF hiện có. |

   Chạy lại nhóm test từ thư mục `soulspace`:

   ```powershell
   .\mvnw.cmd -o -B "-Dtest=BlogCreationTransactionTests,InteractionTransactionTests,SearchControllerTests,BlogEmbeddingWorkerTests,LocalAuthenticationTests" test
   ```

   Chế độ `-o` cần dependency đã có trong cache Maven. Các test này dùng mock cho database/Jina hoặc các phụ thuộc tương ứng; chưa chứng minh commit/rollback trên PostgreSQL thật hoặc luồng Neon/Jina chạy thành công. Chưa chạy toàn bộ suite hoặc `BlogApiTests` trên database riêng trong lần này.

6. **Kiểm chứng thực tế còn lại.** Với backend và database đã cấu hình như mục 4, đăng một bài **công khai có nội dung chữ** qua giao diện/API tạo bài. Không cần chèn job bằng tay: kiểm tra job tự xuất hiện và chuyển `PENDING → RUNNING → DONE` (các trạng thái trung gian có thể chạy qua trước khi truy vấn).

   Thay `<ID bài vừa đăng>` bằng ID trả về khi tạo bài, rồi đọc kết quả trong Neon SQL Editor:

   ```sql
   SELECT j.blog_id, j.generation, j.status, j.attempts, j.last_error,
          (e.blog_id IS NOT NULL) AS has_embedding,
          e.content_sha256 = encode(sha256(convert_to(b.content, 'UTF8')), 'hex')
              AS content_hash_matches
   FROM public.blog_embedding_jobs AS j
   JOIN public.blogs AS b ON b.id = j.blog_id
   LEFT JOIN public.blog_embeddings AS e ON e.blog_id = j.blog_id
   WHERE j.blog_id = '<ID bài vừa đăng>'::uuid;
   ```

   Với bài public có chữ, kết quả thành công là `status = 'DONE'`, `has_embedding = true`, `content_hash_matches = true`; backend có log `Đã đồng bộ embedding cho bài ...`. Bài private hoặc bài chỉ có ảnh có thể `DONE` mà không có vector theo thiết kế. Lượt gọi Jina thật sử dụng quota API. Sau đó kiểm tra bài mới có thể được trả về trong tìm kiếm theo nội dung phù hợp ở bước 12.

## Bước 11 — Thêm lịch sử tìm kiếm bằng localStorage

Đã bổ sung phần code frontend ngày 15/11/2026. Lưu **từ khóa người dùng thực sự chọn tìm**, gồm nhấn Enter hoặc chọn người/bài trong gợi ý. Lịch sử nằm trong trình duyệt; bước này không thêm bảng Neon/local, API backend hay dependency.

1. **Các file đã thêm/sửa.**

   | File | Nội dung đã bổ sung |
   | --- | --- |
   | [frontend/src/config/searchHistory.js](../../frontend/src/config/searchHistory.js) | File mới: tạo key theo tài khoản, đọc/ghi localStorage, lọc dữ liệu không hợp lệ, loại trùng và giới hạn 20 mục. Export `readSearchHistory(userId)` và `writeSearchHistory(userId, items)`. |
   | [frontend/src/page/SearchPage/SearchPage.jsx](../../frontend/src/page/SearchPage/SearchPage.jsx) | Import helper và `useAuth`; thêm `historyOwner`, `historyByUser`, `searchHistory`, `updateSearchHistory()`. Tách `runSearch(rawQuery)` dùng chung cho Enter và bấm từ khóa lịch sử; thêm giao diện xem/xóa lịch sử. Bổ sung lưu từ khóa khi chọn người/bài gợi ý trước khi điều hướng. |
   | [frontend/src/page/SearchPage/SearchPage.module.css](../../frontend/src/page/SearchPage/SearchPage.module.css) | Thêm các class `historyPanel`, `historyHeader`, `historyList`, `historyRow`, `historyQuery`, `historyRemove` cùng trạng thái hover/focus để trình bày danh sách và nút xóa. |

   `frontend/src/config/auth.js` đã có `useAuth()` nên chỉ dùng lại, không cần sửa. Backend và các bảng database giữ nguyên trong bước này.

2. **Key và dữ liệu lưu.** Mỗi tài khoản dùng ID ổn định từ session, không dùng username để tạo key. Khách có key riêng:

   ```text
   soulspace:search-history:<userId>
   soulspace:search-history:guest
   ```

   Giá trị là JSON array từ khóa, mới nhất đứng đầu:

   ```json
   ["Áp lực ôn thi", "Chạy bộ", "Nguyễn Thị Minh"]
   ```

   Helper giữ tối đa 20 mục, trim từ khóa, bỏ giá trị không phải chuỗi, chuỗi rỗng, dài hơn 2000 ký tự hoặc chứa NUL. Khi kiểm tra trùng, chuẩn hóa Unicode NFC, gộp khoảng trắng và chuyển chữ thường; khi hiển thị vẫn giữ từ khóa sau trim. Tìm lại từ khóa cũ sẽ đưa nó lên đầu thay vì thêm dòng trùng.

3. **Thời điểm ghi lịch sử.**

   | Hành động | Xử lý |
   | --- | --- |
   | Chỉ gõ từ khóa hoặc tải gợi ý People/Posts | Chưa ghi lịch sử. |
   | Nhấn Enter | `handleSubmit()` gọi `runSearch(query)`; từ khóa không rỗng được đưa vào lịch sử trước khi gọi API tìm kiếm. |
   | Chọn người trong gợi ý | Gọi `handleOpenProfile(user.id, true)`; lưu từ khóa đang nhập rồi mở hồ sơ. |
   | Chọn bài trong gợi ý | Gọi `handleOpenPost(postId, true)`; lưu từ khóa đang nhập rồi mở bài. Không gọi thêm API tìm kiếm trước khi mở. |
   | Chọn người/bài trong kết quả sau Enter | Các handler dùng mặc định `fromSuggestion=false`; không lưu nhầm từ khóa mới đang gõ dở. |
   | Bấm một từ khóa lịch sử | Điền từ khóa vào ô và gọi `runSearch(term)` để tìm lại; từ khóa đó được đưa lên đầu. |
   | Bấm × trong ô tìm kiếm | Xóa từ khóa/kết quả hiện tại, giữ lịch sử. |
   | Bấm × bên cạnh một dòng lịch sử hoặc Xóa tất cả | Cập nhật riêng danh sách lịch sử của tài khoản/khách hiện tại. |

   Lịch sử lưu ý định tìm kiếm, không phụ thuộc kết quả có bài/người hay không; một lượt đã gửi vẫn có thể nằm trong lịch sử nếu API tìm kiếm sau đó báo lỗi. Chỉ lưu chuỗi từ khóa, không lưu nội dung bài, danh sách kết quả hoặc thông tin đăng nhập.

4. **Hiển thị và tách tài khoản.** Khi ô tìm kiếm trống và có lịch sử, hiện khung **Tìm kiếm gần đây**, nút **Xóa tất cả** và nút × cho từng dòng. Nếu chưa có lịch sử thì giữ thông báo trống hiện tại. Khi gõ thì dùng các gợi ý People/Posts hiện có; khi Enter thì dùng kết quả đã gửi.

   Chờ `useAuth().ready` trước khi đọc/ghi để không nhầm tài khoản đang xác minh với khách. `historyByUser` giữ state theo từng tài khoản trong trang; đổi tài khoản sẽ dùng danh sách/key tương ứng. Đăng xuất không tự xóa lịch sử đã lưu của tài khoản trước.

5. **Khả năng giữ dữ liệu và giới hạn.** Tắt/chạy lại BE hoặc FE không xóa localStorage. Cùng tài khoản, cùng trình duyệt/profile và cùng origin (giao thức + host + cổng) sẽ đọc được lịch sử đã lưu. Nếu backend restart làm mất session thì đăng nhập lại đúng tài khoản để thấy danh sách của mình.

   Đổi từ `localhost:5173` sang `localhost:5174`, sang `127.0.0.1`, trình duyệt/profile hoặc máy khác sẽ dùng nơi lưu khác. Xóa dữ liệu trang hoặc đóng phiên ẩn danh có thể làm mất lịch sử. Chưa đồng bộ thời gian thực giữa các tab đang mở. Khi JSON bị lỗi, helper trả danh sách rỗng; khi trình duyệt chặn ghi/hết dung lượng, tìm kiếm vẫn chạy và danh sách mới chỉ được giữ trong state của trang hiện tại.

6. **Kiểm chứng đã làm và cách thử lại.** Helper đã được thử riêng trong bộ nhớ cho các ca loại trùng, tách tài khoản, giới hạn 20 mục, đọc lại dữ liệu, JSON lỗi, xóa hết và storage chặn ghi. Sau khi bổ sung chọn gợi ý, ESLint hai file JavaScript và build frontend đã chạy đạt; build còn cảnh báo bundle lớn hiện có. Chưa kiểm tra trực tiếp các tương tác trên trình duyệt.

   Chạy từ thư mục `frontend`:

   ```powershell
   npx.cmd eslint src/config/searchHistory.js src/page/SearchPage/SearchPage.jsx
   npm.cmd run build
   ```

   Các ca cần kiểm tra trên giao diện:

   - Enter một từ khóa, xóa ô rồi kiểm tra từ khóa xuất hiện trong lịch sử.
   - Chỉ gõ rồi bỏ đi: chưa phát sinh lịch sử mới.
   - Gõ và chọn người/bài gợi ý mà không Enter: lưu từ khóa rồi mở đúng kết quả.
   - Tìm lại cùng từ khóa với hoa/thường hoặc khoảng trắng khác nhau: còn một dòng, ở đầu danh sách.
   - Enter từ khóa A, gõ dở B rồi chọn kết quả đã gửi của A: không ghi thêm B.
   - Bấm lịch sử để tìm lại; thử xóa một dòng và Xóa tất cả.
   - Tải lại trang, khởi động lại BE/FE rồi đăng nhập cùng tài khoản: lịch sử vẫn còn trên cùng origin.
   - Đổi tài khoản/khách: không hiện lẫn lịch sử của tài khoản trước.

## Bước 12 — Kiểm tra toàn bộ luồng

1. Mở SearchPage, nhập một câu tiếng Việt, kiểm tra kết quả và bấm mở bài. Kiểm tra trạng thái rỗng khi API trả `[]` và trạng thái lỗi khi API trả `503`.
2. Dùng cùng một bộ câu hỏi để so sánh với `ILIKE`. Ghi tỷ lệ bài liên quan trong 5 kết quả đầu và thời gian từ lúc gửi câu đến lúc có kết quả.
