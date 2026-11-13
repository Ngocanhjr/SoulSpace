# Dữ liệu demo SoulSpace — 3 đợt

Bộ dữ liệu hư cấu, tự biên soạn bằng tiếng Việt, dành cho demo và báo cáo CSDL. Không lấy dữ liệu MongoDB, không có media, avatar, liên kết Google hoặc dự đoán AI. Các tên chỉ là tên nhân vật demo; không đại diện người thật.

| Đợt | Bảng | Số hàng |
|---|---|---:|
| 1 | users | 40 |
| 1 | categories | 16 |
| 1 | blogs | 400 |
| 1 | blog_categories | 640 |
| 2 | comments | 700 |
| 3 | blog_likes | 1.000 |
| 3 | comment_likes | 200 |
| 3 | blog_shares | 150 |

350 bài public (87,5%), 50 bài private. Mỗi tài khoản có 4–18 bài; có bài ngắn, bài nhiều đoạn và hai chuỗi bình luận sâu 12 cấp. Private chỉ có tương tác của chính tác giả; share chỉ trỏ bài public. Lượt tương tác có phân bố lệch, nhiều bài không có like/share.

Đủ 16 nhãn của đặc tả phân loại đa nhãn: mã ML dùng `_`, slug CSDL đổi thành `-`, ví dụ `hoc_tap` → `hoc-tap`. Tất cả liên kết đều `MANUAL`, `confidence = NULL`; `blogs.category_mode = MANUAL`. Không thể hiện đây là kết quả model.

CSV UTF-8, có header, UUID v7 và khóa ngoại cố định. Nội dung nhiều đoạn được CSV quote đúng quy tắc; dùng trình đọc CSV, không tách file theo từng dòng vật lý. Ngày tài khoản từ tháng 5/2026; bài và tương tác trong tháng 7–10/2026. Chỉ dùng dataset từ 01/11/2026 trở đi.

## 1. Chuẩn bị

Windows: Python 3.10+ (các lệnh dưới dùng 3.12), JDK 21+ và Docker Desktop Linux containers. Maven Wrapper có sẵn trong repo; không cần cài Maven riêng.

```powershell
git clone https://github.com/Ngocanhjr/SoulSpace.git
Set-Location SoulSpace
Copy-Item .env.docker.example .env.demo-seed
```

Nếu đã clone hoặc đã có file cấu hình, giữ lại file hiện có. Mở `.env.demo-seed`, tự điền:

- `POSTGRES_DB=soulspace_demo`.
- `DB_URL` và `FLYWAY_URL` cùng trỏ `jdbc:postgresql://127.0.0.1:5432/soulspace_demo`.
- Giữ `DB_USER=soulspace_runtime`, `FLYWAY_USER=soulspace_migrator`.
- Điền ba mật khẩu khác nhau cho `POSTGRES_PASSWORD`, `DB_PASSWORD`, `FLYWAY_PASSWORD`.
- Điền `DEMO_USER_PASSWORD` dài 15–128 ký tự Unicode, dùng riêng cho demo.
- Giữ `MEDIA_NAMESPACE` theo example; các trường Cloudinary để trống.

File dùng `KEY=value`, không thêm dấu nháy hoặc ký tự escape. Không đưa secret vào lệnh, CSV hay Git. `.env.demo-seed` và `.env.neon` đã được ignore. Runner cũng nhận biến môi trường; biến process được ưu tiên hơn giá trị trong `--env-file`, nên tránh để cấu hình DB cũ trong terminal.

## 2. Khởi động PostgreSQL demo riêng

```powershell
docker compose --project-name soulspace-demo-seed --env-file .env.demo-seed up -d postgres
docker compose --project-name soulspace-demo-seed --env-file .env.demo-seed ps
```

Chờ trạng thái healthy. Project có volume riêng, chỉ bind `127.0.0.1:5432`. Nếu cổng đang dùng, dừng tại đây và chọn môi trường demo khác; không dừng container không liên quan. Init chỉ provision role khi volume trống. Đổi mật khẩu trong file không tự đổi mật khẩu của volume cũ.

Không dùng production hoặc database đang có dữ liệu nhóm. Không dùng `down -v`, `TRUNCATE` hoặc `DELETE` để ép seed chạy.

## 3. Chạy Flyway V1–V5

Từ root, dùng backend hiện có ở chế độ không mở web server. Lệnh Java dùng classpath tương đối để tránh lỗi `ClassNotFoundException` của `spring-boot:run` trên một số đường dẫn Windows có dấu.

```powershell
Push-Location soulspace
.\mvnw.cmd -q compile dependency:build-classpath "-Dmdep.outputFile=target/demo-seed-classpath.txt" "-DincludeScope=runtime"
if ($LASTEXITCODE -ne 0) { throw 'Maven thất bại; không chạy seed.' }
$demoClasspath = 'target/classes;' + (Get-Content target/demo-seed-classpath.txt -Raw).Trim()
java "-Dspring.devtools.restart.enabled=false" -cp $demoClasspath ctu.soulspace.SoulspaceApplication "--spring.config.additional-location=file:../.env.demo-seed[.properties]" --spring.main.web-application-type=none
if ($LASTEXITCODE -ne 0) { throw 'Khởi động/Flyway thất bại; không chạy seed.' }
Pop-Location
```

Chỉ tiếp tục khi Flyway báo áp dụng thành công 5 migration hoặc schema đã ở version 5, và process kết thúc thành công. Không sửa V1–V5 hoặc baseline schema cũ. Lệnh này chạy Flyway bằng migrator và Hibernate validate bằng runtime; không triển khai API.

## 4. Cài công cụ và nạp bằng một lệnh

Tại root repo:

```powershell
py -3.12 -m venv data/demo-seed/.venv
.\data\demo-seed\.venv\Scripts\python.exe -m pip install -r data/demo-seed/requirements.txt
.\data\demo-seed\.venv\Scripts\python.exe data/demo-seed/seed.py --check
```

Nạp toàn bộ ba đợt:

```powershell
.\data\demo-seed\.venv\Scripts\python.exe data/demo-seed/seed.py --apply --env-file .env.demo-seed --target-database soulspace_demo
```

Đối soát chỉ đọc:

```powershell
.\data\demo-seed\.venv\Scripts\python.exe data/demo-seed/seed.py --verify --env-file .env.demo-seed --target-database soulspace_demo
```

Mong đợi `seeded`, sau đó `verified`, phase 3 và số hàng như bảng trên. Lần `--apply` kế tiếp trả `already-seeded`, không tạo thêm hàng, không đổi hash hoặc ID thông báo. Mã thoát 0 là thành công; khác 0 thì dừng và đọc thông báo.

Tài khoản `demo01`–`demo40` cùng dùng `DEMO_USER_PASSWORD`. Hash Argon2id định dạng PHC, `m=19456 KiB, t=2, p=1`, salt riêng từng user; hash chỉ sinh khi nạp, không lưu vào CSV/Git. Đây là cơ chế theo đặc tả bảo mật hiện tại. Repo đang có entity JPA nhưng chưa có service/repository/API xác thực và PasswordEncoder được nối vào luồng đăng nhập, nên chưa thể xác nhận đăng nhập website. Khi tích hợp auth, kiểm tra bộ giải mã chấp nhận Argon2id PHC này, không seed lại để giả lập đăng nhập.

## 5. Ranh giới transaction và seed lại

- Mặc định `--phase all`: đợt 1 → 2 → 3, cùng một transaction. Lỗi bất kỳ bước nào rollback toàn bộ lần gọi; các đợt đã commit từ lần gọi trước giữ nguyên.
- Có thể thêm `--phase 1`, rồi `--phase 2`, rồi `--phase 3` vào lệnh `--apply`. Mỗi lần gọi là một transaction. Không được nhảy qua đợt trước; `all` có thể tiếp tục từ đợt đã hoàn tất.
- `--verify` mặc định yêu cầu đủ cả 3 đợt. Dùng `--verify --phase 1` hoặc `2` để xác nhận bộ dữ liệu tối thiểu tương ứng.
- Khóa advisory tuần tự hóa các seed runner; khóa các bảng nguồn chặn ghi trong khi đối soát/nạp. Không tắt trigger hay FK. Giữ ứng dụng ngừng ghi trong lúc seed ban đầu.
- Nạp trực tiếp bằng INSERT có tham số và role runtime theo quyền V4, để giữ timestamp lịch sử. V3 tự cập nhật counters và tự tạo notifications; runner không ghi counters hoặc chèn thông báo.
- Dataset được so chính xác với CSV và nguồn. Nếu có user khác, bài đã chỉnh, hash/mật khẩu khác, media, dữ liệu dở dang hoặc bảng ngoài schema mong đợi, mặc định dừng. Không có chế độ ép ghi đè. Dùng database/branch demo mới; dữ liệu cũ giữ nguyên.
- Nếu mất kết nối lúc COMMIT, trạng thái có thể chưa rõ; chạy `--verify` trước khi thử lại.

Thông báo có ID UUID v7 và thời điểm do PostgreSQL sinh khi import, không phải thời gian lịch sử trong CSV. Với đủ 3 đợt hiện tại, trigger tạo 1.898 thông báo; ID/salt hash/thời điểm thông báo khác giữa các database. Nội dung, ID fixture, timestamp CSV và mọi quan hệ vẫn giống nhau. Không có thông báo tự tương tác hoặc chèn giả.

## 6. Neon dùng chung

Một người phụ trách tạo **branch/database demo sạch, PostgreSQL 17**, provision role theo [hướng dẫn PostgreSQL](../../infra/postgres/README.md#neon), rồi cấu hình `.env.neon` từ example. Dùng direct endpoint, giữ `sslmode=verify-full` và `channelBinding=require`. Flyway dùng Java trust store. Để seed runner xác minh chứng chỉ, đặt `PGSSLROOTCERT` trong terminal trỏ tới file PEM CA tin cậy cho endpoint Neon. Xem [cấu hình TLS của libpq](https://www.postgresql.org/docs/17/libpq-connect.html#LIBPQ-CONNECT-SSLROOTCERT).

Trong bước Flyway, thay `file:../.env.demo-seed[.properties]` bằng `file:../.env.neon[.properties]`. Người phụ trách chạy seed một lần:

```powershell
$env:PGSSLROOTCERT = (Resolve-Path '<file-CA-PEM>').Path
.\data\demo-seed\.venv\Scripts\python.exe data/demo-seed/seed.py --apply --env-file .env.neon --target-database TEN_DATABASE_DEMO
```

Thay `TEN_DATABASE_DEMO` bằng tên database thật đã dành riêng cho demo. Người khác chỉ dùng ứng dụng/kết nối database đó, không cần seed và không tự chạy lại Flyway/seed. Không tự seed khi backend khởi động. Dữ liệu đã thay đổi sau khi nhóm dùng thử sẽ khiến đối soát bộ seed nguyên bản dừng; đây không phải yêu cầu xóa dữ liệu nhóm.

Mỗi người có PostgreSQL riêng thì thực hiện bước 1–4 trên máy mình. Không dùng đường dẫn máy tác giả; mọi đường dẫn tính từ root clone.

## 7. Tạo lại CSV và kiểm tra

### Thử hồ sơ demo01 với 1.300 bài

Thêm `--extend-demo01` khi nạp cả ba đợt. Tùy chọn dùng được trên database demo trống đã migrate hoặc database đang chứa bộ seed nguyên bản. Với bộ seed nguyên bản, runner chỉ thêm 988 bài công khai và 296 bài riêng tư, đưa riêng `demo01` lên đúng 1.000 bài công khai và 300 bài riêng tư. Cả database có 1.684 bài. Các bài bổ sung là dữ liệu mô phỏng từ mẫu câu tiếng Việt để thử phân trang, không có ảnh, nhãn hoặc tương tác bổ sung.

```powershell
.\data\demo-seed\.venv\Scripts\python.exe data/demo-seed/seed.py --apply --extend-demo01 --env-file .env.demo-seed --target-database soulspace_demo
.\data\demo-seed\.venv\Scripts\python.exe data/demo-seed/seed.py --verify --extend-demo01 --env-file .env.demo-seed --target-database soulspace_demo
```

Runner nạp trong cùng transaction và đối soát nội dung trước khi commit. Chạy lại không thêm bản ghi. Nếu dữ liệu demo đã bị sửa hoặc có thêm dữ liệu của nhóm, dùng database demo riêng mới.

### Tái tạo bộ CSV gốc

`content.txt` chứa 400 bài cùng đối thoại được biên soạn; `generate.py` đặt seed ngẫu nhiên cố định, tác giả, thời gian, UUID v7 và quan hệ. Sửa nội dung tại nguồn rồi tạo lại và xem diff trước khi chia sẻ. Không thay CSV thủ công vì runner kiểm khớp nguồn.

```powershell
py -3.12 data/demo-seed/generate.py
.\data\demo-seed\.venv\Scripts\python.exe -m unittest discover -s data/demo-seed -p test_seed.py -v
.\data\demo-seed\.venv\Scripts\python.exe data/demo-seed/test_integration.py
```

Integration test tự tạo container PostgreSQL 17.11 tạm, cổng loopback ngẫu nhiên, dữ liệu tmpfs; không đọc `.env` hay nối database dự án. Container riêng của bài test được xóa khi xong. Cần Docker hoạt động và tải được image `postgres:17.11-bookworm`.

Đã chạy ngày 13/11/2026 trên Windows/Python 3.12/PostgreSQL Docker 17.11:

- PASS kiểm dataset, byte CSV tái lập, UUID, FK, nhãn MANUAL/NULL, chuỗi comment cùng bài/không vòng, thời gian, quyền private/share và UTF-8/NFC.
- PASS 6 integration test: nạp/rerun/verify giữ nguyên ID và hash; bảo vệ nội dung đã sửa và sai mật khẩu; từ chối dữ liệu có sẵn; rollback lỗi đợt cuối; thứ tự/tiếp tục theo đợt và không xác nhận DB rỗng; hai seed runner đồng thời không trùng dữ liệu.
- PASS Flyway thực tế V1–V5, Hibernate validate và nạp lại/verify bằng CLI trên database demo cục bộ riêng. Đối soát tất cả counters với bảng tương tác và đủ 1.898 thông báo đúng loại/người nhận/ngữ cảnh.
- Chưa chạy Neon, API/UI, đăng nhập hoặc kiểm hiệu năng. Không coi kiểm SQL là nghiệm thu website.

Bộ này độc lập với fixture nghiệm thu nhỏ 8 user/24 bài/48 comment trong [đặc tả 06](../../docs/spec/06-nghiem-thu.md). Không dùng số liệu seed làm số đo hiệu năng hoặc chất lượng mô hình.
