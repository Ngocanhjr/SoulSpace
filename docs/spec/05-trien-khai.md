# Kiến trúc triển khai và lộ trình migration

## Hai môi trường database

| Hạng mục              | Local Docker                                           | Neon dùng chung/online                                   |
|-----------------------|--------------------------------------------------------|----------------------------------------------------------|
| PostgreSQL            | Container chính thức, volume riêng, cổng bind loopback | Project/database/branch Neon do người dùng quản lý       |
| Backend/frontend      | Chạy trên máy                                          | Tiếp tục chạy trên máy, kết nối Neon qua mạng            |
| Schema                | Flyway V1–V5                                           | Cùng Flyway V1–V5                                        |
| Dữ liệu               | Seed demo riêng                                        | Seed demo riêng trong database/branch sạch               |
| Credentials           | Admin bootstrap, migrator và runtime riêng             | Owner provision, migrator và runtime riêng               |
| TLS database          | Có thể tắt trên loopback local                         | Bắt buộc xác minh certificate và hostname                |
| Media namespace       | soulspace-local                                        | soulspace-neon-demo; branch kiểm thử dùng namespace khác |
| Khi mạng Internet mất | Core text vẫn hoạt động; media Cloudinary có thể lỗi   | Không coi backend là sẵn sàng khi không truy cập Neon    |

Mỗi lần backend chạy chỉ kết nối một database. Đổi môi trường là đổi profile/cấu hình và restart, cần đăng nhập lại. Không tự chuyển ghi sang Docker khi Neon mất mạng, không duy trì dual-write. Neon chỉ thay nơi chạy PostgreSQL, không thay session Spring Security bằng Neon Auth hoặc cho browser truy cập database trực tiếp.

Major PostgreSQL Docker phải khớp Neon. Với project mới, chọn 17; nếu Neon đã có major khác, xác minh và cập nhật Docker trước triển khai, không tự đổi major hay nội dung project Neon. Dùng schema sạch, không import MongoDB. Chưa tạo project/branch, credential hay kết nối Neon trong bước lập tài liệu này.

## Nền backend và reuse

Giữ package gốc `ctu.soulspace` với cấu trúc controller/service/repository/dto/config/exception tương tự legacy; thêm entities, mapper và phần media trong các lớp tương ứng. Chưa tách microservice hoặc tạo interface/implementation cho từng lớp khi chỉ có một cách triển khai.

Đối chiếu từng hàm legacy và phần logic cần thay tại [ánh xạ nghiệp vụ sang SQL](03-postgresql.md). Trong nhóm tương tác ở M4, hoàn thiện like bài làm luồng mẫu cho procedure + trigger + transaction, rồi mở rộng comments/shares. Các luồng CRUD/media/xóa triển khai theo mốc bên dưới. Dùng feed/trang tác giả để đo index; mỗi luồng phải cập nhật cả service, repository, DTO và caller liên quan.

| Thành phần cũ | Cách chuyển |
| --- | --- |
| Controller và DTO | Giữ route/field, bổ sung validation và principal; bỏ phần khai báo/comment cũ không còn dùng khi port. |
| Blog/User MongoRepository | Viết JpaRepository và query projection/batch phù hợp; không copy MongoTemplate/ObjectId. |
| BlogSyncService | JOIN users cho tên/avatar; bỏ nhu cầu đồng bộ snapshot. Phần xóa ảnh đưa vào cleanup dùng chung. |
| MapStruct | DTO ít field có thể map trực tiếp theo pattern hiện có; chỉ đưa MapStruct trở lại nếu thực sự dùng đủ và cấu hình processor tương thích. Không mang dependency chỉ vì legacy có. |
| CloudinaryService | Giữ điểm tích hợp dùng chung; thay việc parse URL/overwrite avatar bằng receipt, public_id và authenticated media. |
| SecurityBeans/AuthenticationService | Một PasswordEncoder chung; login tích hợp Spring Security/session thật. |
| Exception handler | Mapping lỗi thống nhất, JSON 401/403, không trả exception message nội bộ. |
| Frontend config/api | Giữ API URL helper, thêm client cookie/CSRF dùng chung và dùng lại tại mọi caller. |

Dependency bổ sung được yêu cầu là Cloudinary SDK tương thích, BouncyCastle cho Argon2, và công cụ integration test PostgreSQL khi triển khai. Giữ dependency do Spring Boot quản lý nếu phù hợp; kiểm effective versions và advisory trước khi pin. Không copy nguyên POM legacy hoặc gắn version Spring Security khác BOM Boot. POM đang khai Boot 4.1.1/Java 21 nhưng chưa có bằng chứng build; xác minh mốc đầu.

## Generator UUID khi triển khai Entity

Theo [D17 và chính sách UUID](03-postgresql.md#chính-sách-sinh-uuid), tất cả ID được sinh mới phải là v7; notifications.id vẫn do database sinh khi trigger tạo thông báo. Trước khi chọn annotation, kiểm tra Hibernate thực tế do Spring Boot BOM resolve và generator hỗ trợ v7. Không nâng PostgreSQL hoặc thêm dependency chỉ vì mặc định GenerationType.UUID chưa chỉ rõ v7. Nếu runtime chưa hỗ trợ phương án đã chọn, ghi nhận và chốt cách triển khai thay vì âm thầm đổi chính sách sang v4.

Với PostgreSQL 17, V1 đã định nghĩa hàm `uuid_v7()` trước bảng notifications và dùng làm DEFAULT. Cần kiểm chứng hàm theo RFC 9562; trigger vẫn INSERT không truyền id.

Khi triển khai, đồng bộ entity, các đường ghi native/procedure cần ID Java cấp, seed sinh ID mới và bài Entity/JPA trong learning với chính sách này. Giữ fixture ID cố định, khóa ghép, FK và kiểu UUID hiện có. Chạy các ca UUID01–UUID04 trong tài liệu 06; chỉ ghi đã hoàn tất khi có bằng chứng chạy. Tác vụ hiện tại chỉ cập nhật spec, chưa thay generator hoặc bài học.

## File dự kiến khi triển khai

Nền database/config đã được tạo: Compose, env examples, provision roles, các profile và Flyway V1–V5.
Xem [hướng dẫn và giới hạn kiểm chứng](../../infra/postgres/README.md). Các thành phần Java nghiệp vụ, seed, media
worker và tích hợp frontend bên dưới vẫn thuộc phạm vi triển khai tiếp theo:

```text
compose.yaml
.env.docker.example
.env.neon.example
infra/postgres/init/
certs/                             # tài nguyên TLS local, secret phải ignore
soulspace/src/main/resources/
  application.properties
  application-db-local.properties
  application-db-neon.properties
  application-web-local.properties
  application-web-lan.properties
  db/migration/V1__core_schema.sql
  db/migration/V2__query_indexes.sql
  db/migration/V3__counters_and_invariants.sql
  db/migration/V4__interaction_procedures.sql
  db/migration/V5__read_views.sql
soulspace/src/main/java/ctu/soulspace/
  config/ controller/ dto/ entities/ exception/ mapper/ repository/ service/
soulspace/src/test/java/ctu/soulspace/
frontend/src/config/api.js
frontend/src/config/http.js
frontend/src/App.jsx
frontend/src/Components/ và frontend/src/page/     # caller nêu trong hợp đồng API
frontend/public/default-avatar.svg
frontend/vite.config.js
docs/spec/                         # bộ tài liệu hiện tại
```

Đường dẫn trong code/config/script dùng tương đối hoặc biến môi trường, không hardcode ổ đĩa/tên user. Không di chuyển/xóa `old/Blog_api` khi triển khai backend mới. Tài liệu spec không yêu cầu commit/stage file hay thay đổi branch tự động.

## Cấu hình và secret

| Biến cấu hình | Ý nghĩa |
| --- | --- |
| DB_URL, DB_USER, DB_PASSWORD | JDBC và runtime role; không đặt username/password trong URL. |
| FLYWAY_URL, FLYWAY_USER, FLYWAY_PASSWORD | Direct connection và role migrator đúng cùng database với runtime. |
| POSTGRES_DB, POSTGRES_PASSWORD | Chỉ Docker bootstrap; admin không được backend dùng. |
| CLOUD_NAME, CLOUD_API_KEY, CLOUD_API_SECRET | Credentials Cloudinary do backend nhận; đây là tên cấu hình target, không mặc định giống mọi tên legacy. |
| MEDIA_NAMESPACE | Namespace riêng local, neon-demo, neon-test; server kiểm public_id trước cleanup. |
| DEMO_USER_PASSWORD | Mật khẩu seed do người vận hành cấp, đạt policy; không có giá trị mặc định trong source. |
| VITE_API_BASE_URL | `/` cho cùng origin qua proxy; không chứa secret. |
| TLS_CERT_FILE, TLS_KEY_FILE | Đường dẫn tương đối cho Vite HTTPS LAN; private key không được phục vụ như static file. |
| CORS_ALLOWED_ORIGINS | Chỉ cần khi gọi khác origin; allowlist đầy đủ, không wildcard credentials. |

Tạo root `.env.docker` và `.env.neon` từ example tương ứng khi triển khai; cả hai phải ignore Git. Local profile import rõ `optional:file:../.env.docker[.properties]`; Neon profile import `optional:file:../.env.neon[.properties]`, với working directory `soulspace`. Dùng tập con `KEY=value` tương thích Java properties/Compose: không quote kiểu shell, không tham chiếu biến lồng, không dùng backslash/newline trong secret file. Với secret có ký tự cần escape/định dạng khác, cấp bằng process environment thay vì mong hai parser tự xử lý giống nhau. Process environment được ưu tiên; không thêm thư viện dotenv chỉ để nạp file.

Frontend có `frontend/.env.local` chỉ chứa các biến VITE công khai; không đọc chung secret file vào bundle. Compose được truyền `--env-file .env.docker` và chỉ chuyển các biến bootstrap cần thiết vào container, không đưa Cloudinary secrets cho database. Placeholder/example không được dùng làm credential thật. Thực thi phải fail sớm và chỉ nêu tên biến thiếu, không in giá trị.

## Kết nối Neon

Mặc định một Spring Boot instance dùng direct endpoint với Hikari tối đa 5 connections, minimum idle 0, idle timeout 60 giây, max lifetime 10 phút và connection timeout 30 giây. Đây là cấu hình khởi đầu cần đo; tránh giữ nhiều idle connections. Kết nối Flyway luôn direct. Nếu sau này runtime đổi pooled endpoint, vẫn giữ Flyway direct và kiểm lại transaction/session settings. Neon pooler dùng transaction pooling với các giới hạn về session-level features. [Neon connection pooling](https://neon.com/docs/connect/connection-pooling).

Mẫu JDBC không có credential:

```properties
DB_URL=jdbc:postgresql://<neon-direct-host>/<database>?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require
FLYWAY_URL=jdbc:postgresql://<neon-direct-host>/<database>?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require
```

Dùng hostname Neon thật từ Console, không đổi thành IP. Verify-full cùng trust store Java phải xác minh chain và hostname; channelBinding=require dùng đúng driver hỗ trợ và endpoint SCRAM-SHA-256-PLUS. Nếu trust store máy thiếu CA hợp lệ thì sửa trust/certificate, không dùng NonValidatingFactory hoặc hạ xuống sslmode=require như cách chữa lỗi. [pgJDBC TLS](https://jdbc.postgresql.org/documentation/ssl/), [Neon secure connections](https://neon.com/docs/connect/connect-securely).

Không copy version driver cũ trong ví dụ hướng dẫn. Kiểm driver được BOM resolve và advisory hiện hành trước build; endpoint/URL không thay thế được việc sửa lỗ hổng driver. [pgJDBC security](https://jdbc.postgresql.org/security/).

Provision database/role/schema bằng owner trên project Neon, cấp runtime DML/EXECUTE và default privileges cần thiết. Không cấp runtime membership quyền quản trị chỉ để vượt lỗi permission. Chạy Flyway trên branch/database sạch trước, kiểm migration history rồi mới seed. Không chạy Flyway clean, benchmark phá dữ liệu hoặc seed reset trên database dùng chung. [Neon Flyway](https://neon.com/docs/guides/flyway).

Neon có thể có thời gian mở compute/kết nối lại tùy cấu hình project. Ghi riêng latency lần đầu và latency sau warmup; khi mất kết nối giữa COMMIT và response, không tự replay một request tạo bài/xóa/upload chưa biết kết quả. Client đọc lại trạng thái hoặc báo chưa xác nhận. Queue nằm trong database đã chọn và được xử lý tiếp sau reconnect.

## Mốc triển khai và điều kiện chuyển mốc

Sau các mốc migration core, triển khai model/API
theo [thứ tự phân loại chủ đề](07-phan-loai-chu-de.md#triển-khai-và-nghiệm-thu-riêng). Schema chủ đề/confidence đã có
trong V1, tổng 13 bảng/76 cột; không tạo thêm migration lặp các bảng này. V1 được bổ sung trước lần chạy Flyway đầu tiên
theo xác nhận của người dùng; migration đã áp dụng phải được giữ nguyên.

| Mốc | Công việc                                                                                                                                         | Điều kiện hoàn tất                                                                                                                  |
|-----|---------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| M0  | Xác minh Boot/JDK/dependency; inventory 29 legacy routes, 6 route bổ sung, frontend callers và baseline Git.                                      | Build nền được ghi nhận, hợp đồng 35 routes được dùng làm checklist; chưa kết nối nhầm Mongo hoặc Neon dùng chung.                  |
| M1  | Docker PostgreSQL, Neon profile, roles, Flyway/schema, index/view/trigger/procedures, seed text.                                                  | Cùng migration chạy trên local và Neon sạch; counters và rollback đúng; runtime không có DDL, view chỉ SELECT.                      |
| M2  | Spring Security, session/CSRF, users, validation và HTTP client frontend.                                                                         | Signup/login/reload/logout/ownership/password change chạy; cookie/token/lỗi đúng hợp đồng.                                          |
| M3  | Media receipts/worker/proxy, avatar, blog CRUD/public-private, khóa attach/cleanup và xác nhận lượt claim.                                        | Cleanup sau commit; asset DELETE_PENDING không được attach, worker cũ không ghi kết quả sau mất lease; ảnh private không bị bypass. |
| M4  | Comments/replies không giới hạn cứng độ sâu, không lưu depth; UI thụt lề tối đa 3 tầng; PATCH/DELETE với version, placeholder giữ replies, desired-state likes, share reference và xóa tài khoản. | Chỉ tác giả sửa/xóa; version cũ 409; reply từ cấp 6 trở đi hợp lệ, gửi đúng parent; chặn reply trực tiếp vào node đã xóa; xóa tài khoản giữ replies ở bài còn sống; counters đúng. |
| M5  | Nghiệm thu, đo SQL, local/LAN và Neon smoke, runbook.                                                                                             | Từng tiêu chí có PASS/FAIL/chưa kiểm và bằng chứng; chưa đạt media/live Neon được ghi riêng.                                        |

Không ấn định số tuần khi người dùng chưa đưa deadline. Mỗi mốc phải bàn giao phần hoàn chỉnh gồm backend, caller frontend và fixture liên quan. Không chỉ port controller rồi để frontend bị lỗi auth hoặc schema khác DTO.

## Runbook sau khi triển khai

Compose/profile/Flyway đã có để bắt đầu dựng database; các lệnh chạy ứng dụng đầy đủ vẫn phụ thuộc entity/service/API, seed và tích hợp frontend ở các mốc tiếp theo. Cấp credentials và provision role trước khi khởi động Spring Boot; không coi startup thành công là đã hoàn tất migration nghiệp vụ.

Từ thư mục gốc, tạo secret files và provision roles theo tài liệu đi kèm implementation. Docker local:

```powershell
docker compose --env-file .env.docker -f compose.yaml up -d postgres
Push-Location soulspace
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-local,web-local"
Pop-Location
```

Backend dùng Neon, vẫn chạy trên máy:

```powershell
Push-Location soulspace
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-neon,web-local"
Pop-Location
```

Terminal frontend riêng:

```powershell
Push-Location frontend
npm ci
npm run dev
Pop-Location
```

Frontend cần proxy `/api` tới backend loopback và VITE_API_BASE_URL=/; điện thoại LAN không dùng localhost làm địa chỉ backend. Với LAN, chọn `web-lan`, bật Vite HTTPS/certificate tương ứng và cookie Secure; kiểm browser tin certificate. Vite chỉ expose frontend/proxy cần thiết, chặn phục vụ `.env*`, cert/private key, `.git`, `old` và source backend qua file server.

Compose bind cổng database vào `127.0.0.1`, có healthcheck, volume lưu dữ liệu và image pin version cụ thể. Backend loopback có health tối thiểu; frontend proxy không expose toàn bộ actuator. Dừng một tiến trình không chứng minh database/media đã được dọn; không hướng dẫn xóa volume hoặc filesystem như bước chạy lại thông thường.

## Chuyển frontend sang backend mới

1. Ghi nhận frontend đang trỏ backend nào bằng cấu hình, không suy từ tên thư mục. Cấp profile db-local hoặc db-neon rõ ràng, kiểm database/branch và migration history mà không in credential.
2. Chạy seed demo trên target sạch; không dùng dữ liệu hoặc credential Mongo cũ.
3. Chạy bộ nghiệm thu trên local, sau đó smoke tương đương trên Neon sạch và demo LAN HTTPS.
4. Chuyển frontend qua backend mới bằng API base/proxy. Clear cache đăng nhập cũ, yêu cầu login bằng user PostgreSQL mới.
5. Giữ legacy nguyên trạng làm nguồn đối chiếu. Nếu quay lại legacy phải đổi cấu hình và đăng nhập lại tài khoản legacy; dữ liệu tạo ở PostgreSQL không tự xuất hiện trong Mongo.

## Bàn giao

Cần source, secret examples, Flyway, seed runner, ERD/API cập nhật, bộ test và báo cáo kết quả riêng cho Docker, Neon, media thật và LAN. Runbook phải nêu đúng cách nạp env, xem job DEAD và requeue ID cụ thể, đổi database, phục hồi kết nối và giới hạn session sau restart. Không đưa credential, bản dump dữ liệu cá nhân hoặc URL ký media vào báo cáo.

V1 định nghĩa 13 bảng/76 cột, gồm `external_identities` (3 cột); cần chạy migration và nghiệm thu theo [tài liệu 11](11-dang-nhap-google.md). Flyway phải tạo timestamp likes/shares NOT NULL DEFAULT now(), ràng
buộc lease/status và các invariant comment/media đã chốt. Chốt thiết kế không thay thế bằng chứng chạy migration.
Requeue DEAD cần theo transaction và cặp claim trong tài liệu bảo mật/media; không reset một job RUNNING hoặc bỏ điều
kiện xác nhận lượt xử lý.

## Bổ sung thông báo vào V1–V4 trước khi chạy Flyway

Theo xác nhận chưa chạy migration, gộp schema/index/trigger/quyền thông báo vào V1–V4; không tạo V6 trùng chức năng. V5 giữ nguyên. Sau migration thực tế đầu tiên phải tạo migration mới cho các thay đổi tiếp theo.

Sau các mốc tương tác, triển khai entity/repository notifications, GET danh sách + unreadCount, PATCH đã đọc và chuông thông báo của Navigation theo [10](10-thong-bao.md). SQL hiện có chưa đồng nghĩa các API/UI này hoạt động. Seed like/comment/share sẽ sinh thông báo tự động qua trigger; không seed lại cùng thông báo bằng tay. Chạy SQL suite notifications.sql và sau đó kiểm JDBC/Flyway, API/UI, concurrency trên Docker/Neon.
