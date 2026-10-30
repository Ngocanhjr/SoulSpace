# PostgreSQL foundation

Phạm vi: cấu hình Docker/Neon và Flyway V1–V4. Chưa có entity/service/API, seed runner hoặc media worker; tạo schema không đồng nghĩa các luồng ứng dụng đã hoạt động. Không dùng dữ liệu Mongo.

## Docker local

1. Sao chép `.env.docker.example` thành `.env.docker` ở root. Điền ba mật khẩu khác nhau cho bootstrap, migrator và runtime. Không commit secret file; example cố ý để trống mật khẩu.
2. Khởi động Docker Desktop (Linux containers), rồi từ root:

```powershell
docker compose --env-file .env.docker up -d postgres
docker compose --env-file .env.docker ps
Push-Location soulspace
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-local,web-local"
Pop-Location
```

PostgreSQL chỉ bind loopback; dữ liệu ở named volume. Script init tạo role `soulspace_migrator` (schema owner) và `soulspace_runtime` (DML), dùng mật khẩu riêng. Backend/Flyway chạy trên máy, Flyway dùng migrator; container không nhận Cloudinary credentials. Image pin `postgres:17.11-bookworm` theo [official image](https://hub.docker.com/_/postgres).

Init chỉ chạy khi volume còn trống. Đổi env không tự đổi mật khẩu của volume đã có; cập nhật role bằng thao tác quản trị riêng. Nếu init lỗi giữa chừng, sửa nguyên nhân rồi chạy lại provision bằng owner trên đúng database, không xóa volume. Không chạy `down -v` để chữa lỗi thông thường.

## Neon

1. Dùng database/branch sạch dành riêng cho ứng dụng, PostgreSQL major 17 để khớp Docker. Nếu project có major khác thì điều chỉnh Docker và kiểm thử trước; không tự nâng major Neon.
2. Provision role bằng database owner với `psql` 17. Cấp `DB_PASSWORD` và `FLYWAY_PASSWORD` qua process environment (không đưa vào command line hoặc history); script lấy bằng `\getenv`. Hai mật khẩu phải khác nhau. Chạy từ root:

```powershell
psql "host=<neon-direct-host> dbname=<database> user=<owner-role> sslmode=verify-full channel_binding=require" -W -f infra/postgres/provision.sql
```

3. Sao chép `.env.neon.example` thành `.env.neon`, thay host/database và mật khẩu bằng đúng role vừa tạo. Cả hai JDBC URL phải trỏ cùng database; dùng direct endpoint và Java trust store hợp lệ. Không đặt username/password vào URL; không hạ TLS khi kết nối lỗi.
4. Chạy từ `soulspace` với `db-neon,web-local`. Chỉ chọn một profile database; đổi profile cần restart. Flyway không baseline một schema cũ hoặc tự clean.

Provision chỉ dành cho database sạch, không tiếp quản schema đang có ứng dụng khác. Script có thể chạy lại để cập nhật mật khẩu/default privileges nhưng không thu hồi membership/quyền thừa của role có sẵn; kiểm tra role cũ trước khi tái sử dụng.

## Schema và sử dụng

- V1: 9 bảng/57 cột, FK avatar bổ sung sau tạo media, CHECK nội dung/metadata/lease, vị trí ảnh 0–9. Media có ba trạng thái PENDING/ATTACHED/DELETE_PENDING; PENDING dùng cả trước xác nhận upload và sau upload khi chưa gắn, phân biệt bằng bộ metadata provider_asset_id/format/size_bytes.
- V2: 14 index bổ sung cho feed, cây comment, cascade và queue.
- V3: counters, cây tối đa 5 cấp, cấu trúc bất biến, version comment, bài không rỗng ở cuối transaction; metadata media đã xác nhận bất biến và state ATTACHED không quay về PENDING. `blog_image_position` có thể được trì hoãn bằng `SET CONSTRAINTS blog_image_position DEFERRED` khi hoán đổi thứ tự ảnh; mặc định kiểm ngay.
- V4: bốn procedure SECURITY INVOKER, quyền runtime và chặn truy cập Flyway history. Chúng không tự COMMIT; service phải mở transaction, lấy actor từ session và đọc response sau cập nhật.

Content edit/delete tự tăng `comments.version` và `updated_at` trong trigger. Service vẫn bắt buộc `WHERE version=:expectedVersion`, kiểm affected rows; JPA không merge toàn entity để tránh ghi đè counters. Counter updates không tăng version. Xóa mềm phải xóa likes của node trong cùng transaction; constraint deferred chặn placeholder còn likes.

Attach ảnh, enqueue/claim/lease guard, quyền sở hữu và xóa tài khoản đầy đủ vẫn là trách nhiệm service theo [spec](../../docs/spec/03-postgresql.md). Không gọi Cloudinary trong SQL. Runtime không có DDL hoặc quyền TRUNCATE; credentials runtime vẫn là dữ liệu tin cậy chỉ backend giữ, không là bảo vệ khỏi một backend bị chiếm quyền.

`ddl-auto=validate`, Flyway clean bị tắt. Không sửa nội dung migration sau khi đã áp dụng; thay đổi tiếp theo tạo V5. Role runtime có lock timeout 2 giây và statement timeout 5 giây; migration dùng role riêng.

## Smoke test SQL

Trên database kiểm thử riêng đã migrate, dùng psql với role runtime:

```powershell
psql "host=127.0.0.1 dbname=<test-database> user=soulspace_runtime" -W -f infra/postgres/tests/invariants.sql
```

Fixture nằm trong transaction và luôn ROLLBACK khi thành công; ON_ERROR_STOP làm process thoát khi có lỗi. Không chạy trên database dùng chung. Test kiểm like lặp, privacy, share, counters, 5 cấp, version, xóa mềm/tài khoản, metadata, lease và constraint bài rỗng. Chưa kiểm deadlock/nhiều connection, JDBC/Flyway, Neon, media hoặc API; phải nghiệm thu riêng theo [06](../../docs/spec/06-nghiem-thu.md).

## Kết quả kiểm tra khi tạo file — 27/10/2026

- `docker compose config --quiet`: PASS với biến giả chỉ dùng để kiểm cấu trúc; không dùng credential thật.
- PostgreSQL 17.5 WASM qua PGlite 0.3.14 cài trong thư mục tạm: PASS provision SQL với role owner không phải superuser; V1–V4 chạy lần lượt trong transaction; 9 bảng/57 cột; smoke suite và kiểm quyền runtime PASS. Đây là thực thi SQL bằng engine PostgreSQL, chưa phải chạy qua Flyway/JDBC hay server Docker 17.11.
- Runtime bị chặn CREATE trong public, đọc Flyway history và TRUNCATE.
- Docker daemon chưa chạy nên chưa khởi động container, chưa xác minh shell entrypoint/psql end-to-end, persistent volume hoặc healthcheck. Chưa kết nối Neon, chạy Maven hoặc nghiệm thu concurrency nhiều connection.
