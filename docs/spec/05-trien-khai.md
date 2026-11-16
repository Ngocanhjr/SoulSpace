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
frontend/src/config/assets.js                     # URL avatar mặc định công khai
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

### Triển khai xác thực local

UI đăng ký/đăng nhập dùng Axios client chung có cookie và CSRF. Vite dev/preview proxy `/api` sang backend `127.0.0.1:8080`; VITE_API_BASE_URL để trống khi dùng proxy. Tải lại trang gọi `/auth/me` để xác nhận session, localStorage chỉ giữ dữ liệu tương thích cho những trang chưa migrate. Sidebar hiển thị username và nút logout gọi backend. Tạo/đọc bài, bình luận/like/chia sẻ và quản lý profile được triển khai theo các mốc ngày 15/11/2026 bên dưới.

Log backend có các event user_registered/login_success/authentication_rejected/logout; event thành công có UUID nội bộ, không ghi mật khẩu/hash/token/session ID. Profile db-local bật DEBUG riêng JpaTransactionManager để xem ranh giới transaction, không bật log SQL bind parameters. Các form xác thực không log toàn bộ lỗi Axios. SQL chỉ đọc để xem user mới và metadata nằm tại `infra/postgres/check-auth.sql` (chạy bằng psql, truyền `-v username=testuser01`).

Source đã có `UserRepository`, DTO request/response, `UserService`, `UserDetailsService`, `AuthenticationService`, cấu hình encoder Argon2id và security chain. API hiện triển khai U01 đăng ký và A01–A04 (đăng nhập, CSRF, user hiện tại và đăng xuất). Đăng ký chỉ ghi một hàng users, dùng transaction của repository; không bọc thêm TransactionTemplate. Băm mật khẩu trước thao tác ghi, UNIQUE username là chốt chống trùng đồng thời. Không dùng blocklist theo quyết định hiện tại.

Controller đăng nhập gọi SessionAuthenticationStrategy, đổi session ID, xóa CSRF cũ, đăng ký session rồi lưu SecurityContext qua repository. SessionGuardFilter kiểm trạng thái thu hồi và user còn tồn tại; idle timeout 1 giờ do servlet container quản lý. Không giới hạn cứng tuổi session tính từ lúc đăng nhập; request tiếp tục gia hạn idle timeout. Runtime lỗi database trả 503, không coi là user đã bị xóa. Cần lấy lại CSRF sau login/logout. Trạng thái session/rate limit lưu trong bộ nhớ của một backend.

Rate limit local dùng chung cho đăng nhập/đăng ký: 10 request trong 5 phút trên mỗi remote IP, tối đa 4096 IP đang theo dõi; request vượt hạn mức trả 429 và Retry-After. Các client sau cùng NAT/proxy chia sẻ hạn mức; chưa nhận IP từ X-Forwarded-For. Exception nghiệp vụ và thông báo dùng ErrorCode/constant; filter bảo mật có handler riêng dùng cùng ErrorResponse. Mật khẩu/hash không nằm trong DTO response, không được đưa vào log lỗi. JSON ngoài danh sách field được phép bị từ chối.

`LocalAuthenticationTests` chạy toàn bộ security filter cùng encoder Argon2id thật và repository giả lập, không kết nối database hoặc chạy Flyway. Chạy từ thư mục `soulspace`: `mvn -Dtest=LocalAuthenticationTests test` (hoặc Maven Wrapper đã hoạt động). Bộ test này không thay thế kiểm thử PostgreSQL, constraint race, idle timeout của container, cookie trên browser hay tích hợp frontend. Frontend cần gửi cookie/CSRF theo hợp đồng trước khi coi luồng giao diện hoạt động. Google OIDC và vòng đời tài khoản có kiểm thử riêng ở các mốc triển khai bên dưới.

Kiểm tra ngày 14/11/2026: Maven trên Java 21 biên dịch thành công; 12 test trong LocalAuthenticationTests đạt. Bao gồm đăng ký/hash/DTO an toàn, login và context giữa request, session ID mới, CSRF, logout, thu hồi/giới hạn tuổi session, tài khoản đã xóa, lỗi database 503, lỗi đăng nhập chung, validation/field lạ, chuyển lỗi unique giả lập thành 409 và rate limit. Lỗi unique ở đây là exception do mock tạo, chưa chứng minh constraint hoặc race trên PostgreSQL thật.

Cần source, secret examples, Flyway, seed runner, ERD/API cập nhật, bộ test và báo cáo kết quả riêng cho Docker, Neon, media thật và LAN. Runbook phải nêu đúng cách nạp env, xem job DEAD và requeue ID cụ thể, đổi database, phục hồi kết nối và giới hạn session sau restart. Không đưa credential, bản dump dữ liệu cá nhân hoặc URL ký media vào báo cáo.

V1 định nghĩa 13 bảng/76 cột, gồm `external_identities` (3 cột); cần chạy migration và nghiệm thu theo [tài liệu 11](11-dang-nhap-google.md). Flyway phải tạo timestamp likes/shares NOT NULL DEFAULT now(), ràng
buộc lease/status và các invariant comment/media đã chốt. Chốt thiết kế không thay thế bằng chứng chạy migration.
Requeue DEAD cần theo transaction và cặp claim trong tài liệu bảo mật/media; không reset một job RUNNING hoặc bỏ điều
kiện xác nhận lượt xử lý.

## Bổ sung thông báo vào V1–V4 trước khi chạy Flyway

Theo xác nhận chưa chạy migration, gộp schema/index/trigger/quyền thông báo vào V1–V4; không tạo V6 trùng chức năng. V5 giữ nguyên. Sau migration thực tế đầu tiên phải tạo migration mới cho các thay đổi tiếp theo.

Sau các mốc tương tác, triển khai entity/repository notifications, GET danh sách + unreadCount, PATCH đã đọc và chuông thông báo của Navigation theo [10](10-thong-bao.md). SQL hiện có chưa đồng nghĩa các API/UI này hoạt động. Seed like/comment/share sẽ sinh thông báo tự động qua trigger; không seed lại cùng thông báo bằng tay. Chạy SQL suite notifications.sql và sau đó kiểm JDBC/Flyway, API/UI, concurrency trên Docker/Neon.

## Triển khai tạo bài và đọc feed ngày 15/11/2026

Backend bổ sung POST `/api/v1/blogs`, GET `/api/v1/soulspaces`, GET `/api/v1/blogs/{userId}/all`, GET `/api/v1/blogs/{userId}/blogs`, GET `/api/v1/blogs/{blogId}`, GET `/api/v1/users/{userId}` và GET `/api/v1/media/{assetId}`. Giữ nguyên JSON array, không thêm giới hạn số bài ngầm. Danh sách đọc bằng các truy vấn tách riêng public/author để dùng các index V2; luôn có `ORDER BY created_at DESC, id DESC`. Tác giả, ảnh và like của viewer được đọc theo nhóm, không truy vấn lại từng bài. Hồ sơ người khác có `dob=null`.

Tạo bài lấy actor từ session, kiểm CSRF; không nhận author/counter do client tự đặt. `userId` compatibility phải trùng actor; `username` compatibility được bỏ qua. Content tối đa 5.000 Unicode code points, không có NUL. File được kiểm trước khi gọi provider: tối đa 10 ảnh JPEG/PNG tĩnh, mỗi ảnh 10 MiB và 25 triệu pixel; chặn PNG động và file giả định dạng. Upload có giới hạn 30 request/giờ/user, tối đa hai request upload đồng thời/user, danh sách theo dõi có dung lượng giới hạn.

Cloudinary dùng SDK `cloudinary-http5`, delivery type `authenticated`, `overwrite=false`; `asset_folder` và tiền tố `public_id` đều là `${MEDIA_NAMESPACE}/blogs`. Ví dụ local: `MEDIA_NAMESPACE=soulspace/local`, demo: `soulspace/demo`, test: `soulspace/test`. Đây là thư mục trong Folders; Collection `soulspace` chỉ là nhóm tham chiếu ảnh và không thay thế thư mục hoặc kiểm tra quyền. Không thay namespace giữa các lần chạy khi còn dữ liệu của môi trường cũ cần dọn.

Receipt PENDING được commit trước upload. Sau upload, tạo blog, xác nhận metadata và gắn ảnh trong một transaction ngắn; mọi lời gọi provider nằm ngoài transaction. Bài có text vẫn được tạo nếu ảnh upload lỗi, response báo `failedFiles` theo vị trí `image-1`, `image-2`, …; không trả lại đường dẫn hoặc tên file tùy ý của client. Bài chỉ có ảnh mà mọi upload đều thất bại trả 502 và không tạo blog rỗng. Khi chưa cấu hình Cloudinary, bài text vẫn dùng được; ảnh không được giả lập thành upload thành công.

Upload lỗi hoặc transaction tạo bài rollback sẽ đưa receipt còn PENDING vào queue bù trừ; ảnh đã gắn thành công không bị thu hồi. Receipt đã có xác nhận provider được giữ metadata để dọn ngay; receipt chưa xác nhận vẫn chờ 1 giờ. Nếu database không nhận được thao tác enqueue, bộ quét stale là bước phục hồi, không làm mất receipt.

Endpoint ảnh kiểm tra trạng thái asset và quyền hiện tại của blog tại mỗi request. Chỉ backend dùng signed download URL; UI nhận bytes qua `/api/v1/media/{assetId}`, không nhận URL Cloudinary hoặc redirect. Bài private chỉ chủ bài xem ảnh, còn guest/người khác nhận 404, kể cả đã biết assetId. Response có `Cache-Control:no-store` và `nosniff`.

MediaCleanupService quét receipt stale và xử lý queue mỗi phút. Receipt upload chưa xác nhận sau 10 phút được đưa vào queue với khoảng chờ 1 giờ; receipt đã xác nhận chưa gắn sau 24 giờ được thu hồi. Worker claim bằng SKIP LOCKED, dùng lease 120 giây và cặp attempts/lease từ database; kiểm thời hạn sau khi lấy khóa, gọi provider ngoài transaction, không xóa public_id ngoài namespace hiện tại. Tối đa 5 lượt thử rồi DEAD; khoảng chờ retry là 1 phút, 5 phút, 30 phút và 2 giờ. Worker không chạy khi thiếu cấu hình Cloudinary.

Frontend tạo bài gửi multipart bằng Axios client chung, không gửi actor lấy từ localStorage. Đăng thành công bài public mở feed; bài private mở trang của mình. Feed và hồ sơ không đảo ngược thứ tự API; bấm avatar/username mở `/user/{userId}` và chỉ tải bài public. URL preview cục bộ được thu hồi khi đổi ảnh/rời trang; lỗi đăng bài mở lại nút gửi để thử lại.

### Format và kiểm thử

Java dùng Google Java Format style AOSP (4 dấu cách) qua Spotless đã ghim phiên bản trong `soulspace/pom.xml`, cùng `.editorconfig`. Từ `soulspace/`, dùng `mvn spotless:apply` để format hoặc `mvn spotless:check` để kiểm tra. Với tác vụ nhỏ, giới hạn file bằng `-DspotlessFiles=.*BlogService.java,.*BlogController.java` để tránh thay đổi các file không liên quan. Không tự chạy formatter trong lúc người dùng đang sửa file khác.

`BlogApiTests` chỉ chạy khi `SOULSPACE_BLOG_TEST_DB=true`; cấu hình datasource/Flyway lấy từ các biến BLOG_TEST_* và profile test, không lấy DB local/Neon. Từ root chạy `./infra/postgres/tests/run-blog-api.ps1` bằng PowerShell với Java 21 và Docker đang chạy. Script tạo PostgreSQL 17 trên cổng ngẫu nhiên, cấp hai role, chạy V1–V5 cùng bộ test local authentication/bài viết rồi dọn đúng container và volume kiểm thử do nó tạo. Cloudinary được mock trong bộ test này; không coi kết quả là bằng chứng upload/download/destroy đã chạy với tài khoản Cloudinary thật.

Kết quả ngày 15/11/2026: 19/19 test đạt (12 local authentication, 7 bài viết/media). V1–V5 chạy bằng Flyway trên PostgreSQL 17.11 riêng; JPA validate và các API được kiểm thử qua MockMvc với security filter thật. Các ca gồm bài text/ảnh, thứ tự mới nhất trước, feed chỉ public, `/all` chỉ chính chủ, DOB không công khai, chặn giả actor/field lạ/thiếu CSRF, ảnh private trả 404 cho guest/người khác, đổi public/private có hiệu lực trên link ảnh cũ, provider lỗi, rollback khi actor mất trong lúc upload và queue dừng sau 5 lượt lỗi. Không chạy migration hoặc ghi fixture vào database ứng dụng.

Kiểm tra Cloudinary thật riêng: upload PNG do agent sinh vào `soulspace/test/blogs` bằng delivery type authenticated; signed download bên trong backend thành công; link unsigned trả HTTP 401. Ảnh thử đã được destroy sau kiểm tra. Không lưu hoặc công khai URL ký/credential. Đây là kiểm tra SDK/provider, tách với bộ test API dùng provider mock.

Frontend build, ESLint trên các màn hình được sửa và test session/CSRF phía frontend đều đạt. Vite vẫn cảnh báo bundle lớn hơn 500 kB; chưa tối ưu bundle trong mốc này. Các kiểm tra này không thay thế kiểm thử toàn bộ luồng trên trình duyệt hoặc LAN. Phần bình luận/like/chia sẻ và sửa/xóa bài, hồ sơ/avatar được bổ sung ở các mốc tiếp theo bên dưới.

## Bình luận, lượt thích và chia sẻ ngày 15/11/2026

InteractionController và InteractionService bổ sung B05–B09, B12, B15–B16, U03 và U11 theo hợp đồng API. Bao gồm đọc/thêm bình luận, trả lời, sửa và xóa mềm bình luận, thích/bỏ thích bài hoặc bình luận, chia sẻ, đọc danh sách chia sẻ và gỡ chia sẻ. Request ghi dùng session/CSRF và UUID actor ở principal. Field lạ bị từ chối; userId compatibility khác actor trả 403. liked phải là JSON boolean; expectedVersion phải là số nguyên không âm, không nhận chuỗi hoặc số thập phân.

| Luồng | SQL thực hiện | Index và trigger liên quan | Transaction |
| --- | --- | --- | --- |
| Đọc bình luận | Lấy flat comments theo blog, ORDER BY created_at DESC, id DESC; đọc tác giả và like viewer theo nhóm, giữ node có author_id NULL. Dựng cây bằng hai vòng lặp. | comments_blog_idx; PK comment_likes hỗ trợ lookup theo comment. comments_parent_idx vẫn hỗ trợ tìm replies/FK, không thay index sắp xếp của toàn blog. | Read-only, kiểm quyền bài trước khi đọc cây. |
| Tạo bình luận/reply | FOR KEY SHARE trên actor, khóa blog, khóa parent nếu có rồi INSERT comments. Parent khác bài/không tồn tại trả 400, parent đã xóa trả 409. | PK comments và comments_blog_idx; comments_validate/validate_comment, comments_counter/update_comment_counter và trigger thông báo tương tác. | Một transaction cho comment, count và thông báo; không tự cộng count trong Java. |
| Sửa/xóa comment | Khóa actor → blog → comment, kiểm tác giả và expectedVersion. Xóa các comment_likes trước khi đặt content=NULL/deleted_at; không xóa replies. | comments_validate giữ cấu trúc và cập nhật version/updated_at; comment_likes_counter, comments_counter và kiểm tra deferred về likes của node đã xóa. | Lần xóa đầu phải khớp version; xóa lặp với body hợp lệ giữ nguyên version/time/count. |
| Thích/bỏ thích | CALL sp_set_blog_like hoặc sp_set_comment_like với liked mong muốn. | PK chống trùng; comment_like_active chặn placeholder; các relation-counter trigger cập nhật count. | Procedure và trigger cùng transaction service; đọc/refresh counter từ DB trước khi trả response. |
| Share/unshare | CALL sp_share_blog hoặc sp_unshare_blog. Share chỉ public, trùng trả 409; unshare được lặp cả khi bài private/đã mất. | PK blog_shares, blog_shares_blog_idx; blog_shares_counter và trigger thông báo share. | Quan hệ, count và thông báo commit/rollback cùng nhau; unshare kiểm owner từ path. |
| Danh sách chia sẻ | JOIN blog_shares với blogs hiện tại, chỉ published=true; ORDER BY shared_at DESC, blog_id DESC. Batch lấy tác giả/ảnh. | blog_shares_user_time_idx hỗ trợ lọc user và thứ tự chia sẻ. | Read-only; không tạo snapshot, bài private bị ẩn nhưng quan hệ/counter còn lưu. |

Các lệnh CALL dùng native query có bind parameters. Tầng API không mở transaction bao ngoài InteractionService; TransactionTemplate mở transaction ở từng lần thử và chỉ trả kết quả sau commit. SQLSTATE 40P01/40001 được retry toàn transaction tối đa hai lần sau rollback; lần thử thứ ba còn lỗi trả 409 CONCURRENT_MODIFICATION. Lock timeout 55P03 trả 409, không replay; xung đột nghiệp vụ như ALREADY_SHARED không retry. Không tự retry HTTP tạo comment hoặc upload khi mất response. Log chỉ ghi event và UUID, không ghi nội dung bình luận, SQL hoặc token.

SessionGuardFilter giới hạn các request ghi bài/tương tác ở 60 lần/phút/user, bảng theo dõi có TTL và tối đa 4096 user. Trả 429 kèm Retry-After, chặn trước controller/procedure. Giới hạn này dùng chung giữa các session của cùng user trong một backend; login/signup vẫn dùng giới hạn IP riêng, upload vẫn có quota riêng. Backend restart sẽ reset các bảng đếm trong bộ nhớ.

CommentPopup đọc cây rồi chuyển sang danh sách phẳng bằng vòng lặp, giữ thứ tự mới nhất trước, placeholder và ID parent thật. UI thụt lề tối đa 3 tầng, hiển thị đang trả lời ai; không đặt trần độ sâu dữ liệu. Tác giả được sửa/xóa; khi gặp 409 UI đọc lại dữ liệu nhưng không tự gửi lại bản sửa với version mới. Các ô nhập còn mở giữ draft để người dùng đối chiếu và quyết định gửi lại. Popup hỗ trợ Escape, vòng focus Tab và trả focus khi đóng.

ListPost, PostDetailPage và CommentPopup dùng chung useLikes: hiển thị ý định ngay, tối đa một request đang chạy trên mỗi mục, gộp các lần nhấn thành ý định cuối. Counter chuẩn lấy từ response; lỗi không có ý định mới hơn thì khôi phục trạng thái đã xác nhận. Phản hồi GET bắt đầu trước thay đổi like không ghi đè ý định hoặc response mới hơn. UserPage đọc danh sách share thực, gỡ share qua API có CSRF và không coi lỗi tải là danh sách rỗng.

Avatar mặc định dùng URL công khai đã được chủ dự án chỉ định ở USR01. MediaConstants và config/assets.js là hai nơi khai báo cho Java/JavaScript; response đăng ký, đăng nhập, hồ sơ, bài, bình luận và share dùng cùng ảnh. avatar_media_id vẫn NULL cho user chưa có avatar riêng; ảnh dùng chung không tham gia queue cleanup. Không thay schema, index, procedure, trigger hoặc ERD cho quyết định này.

Kiểm chứng: 32/32 test backend đạt (13 local authentication/filter, 16 API/JPA trên PostgreSQL 17.11 tách biệt, 3 unit test retry/rollback). Flyway V1–V5 chạy bằng role migrator; API dùng role runtime. Các ca kiểm tra version cũ, placeholder/replies, parent sai bài, dữ liệu không hợp lệ, quyền private, share trùng, unshare lặp, counter/notification đúng, avatar thống nhất, share đồng thời và like chạy cùng xóa comment. Các test retry dùng lỗi SQL mô phỏng và transaction manager mock; không coi đó là bằng chứng đã gây deadlock PostgreSQL thật.

Test EXPLAIN dùng SET LOCAL enable_seqscan=off và enable_bitmapscan=off trong transaction kiểm thử để xác nhận comments_blog_idx và blog_shares_user_time_idx có thể phục vụ ORDER BY mà không cần Sort riêng khi dùng ordered index scan. Đây là kiểm tra khả năng của index, không phải benchmark hay cam kết planner luôn chọn index scan trên fixture ít dữ liệu. Cấu hình này không được áp dụng lên datasource runtime.

Frontend có 7 test Node đạt cho session/CSRF, thứ tự/placeholder, cây 5.000 cấp không dùng call stack đệ quy, like optimistic/gộp lần bấm/lỗi và phản hồi GET cũ. Các file Java được format/check theo AOSP; frontend lint/build được kiểm tra riêng. Chưa thực hiện kiểm thử thủ công đầy đủ trên browser/LAN hoặc nghiệm thu Neon. Thông báo đã được trigger ghi vào DB; API/chuông thông báo vẫn là mốc riêng. Google OIDC được mô tả tại tài liệu 11. Sửa/xóa bài được triển khai trong mốc profile bên dưới.

## Hoàn thiện trang cá nhân local ngày 15/11/2026

UserPage dùng session và HTTP client chung để đọc hồ sơ riêng, danh sách bài public/private của chính chủ và các bài chia sẻ còn công khai. Có form sửa username/họ tên/ngày sinh, đổi hoặc gỡ avatar, đổi mật khẩu, đăng xuất và xóa tài khoản có xác nhận mật khẩu. DOB chỉ hiển thị trong hồ sơ của chủ tài khoản. Liên kết xem công khai dùng trang public profile hiện có; thẻ bài mở trang chi tiết để thích, bình luận hoặc chia sẻ.

UserController và ProfileService triển khai U05–U10. PATCH hồ sơ chỉ cập nhật field được cho phép; field vắng mặt giữ nguyên, dob:null xóa ngày sinh. UNIQUE username vẫn quyết định xung đột cuối cùng. Mật khẩu hiện tại được kiểm và mật khẩu mới được băm trước transaction, sau đó đối chiếu lại bản hash đã xác thực khi khóa user. Đổi mật khẩu hoặc xóa tài khoản chỉ thu hồi session sau khi commit; client chỉ xóa auth khi server xác nhận thành công. Xác nhận mật khẩu được giới hạn 10 lần/5 phút/user, tính cả lần thành công; API ghi profile cũng dùng giới hạn chung 60 request/phút/user.

Avatar mới dùng authenticated delivery với public_id trong MEDIA_NAMESPACE/avatars; ảnh bài viết vẫn trong MEDIA_NAMESPACE/blogs. Với môi trường local hiện tại, hai thư mục tương ứng là soulspace/local/avatars và soulspace/local/blogs. Backend giữ signed URL và phục vụ ảnh qua MediaService. Thay/gỡ avatar chuyển asset cũ sang DELETE_PENDING và tạo job trong cùng transaction với cập nhật user; xóa ảnh thật do worker làm sau commit. Avatar mặc định dùng chung không có receipt riêng và không bị đưa vào queue.

BlogController/BlogService triển khai B11, B13 và B14. UserPage gửi một multipart duy nhất cho nội dung, published, ảnh mới và ảnh gỡ bỏ. Service kiểm quyền trước upload, khóa actor rồi blog, kiểm lại ảnh thuộc bài, khóa asset theo UUID, sắp lại position liên tục và lưu queue cùng dữ liệu bài. Xóa bài cascade tương tác trong DB và enqueue ảnh. Nếu không thể tạo job cleanup, toàn bộ sửa/xóa rollback; ảnh mới đã upload nhưng chưa gắn được đưa vào cleanup riêng.

Xóa tài khoản khóa user, mọi blog liên quan và comment theo thứ tự UUID trước khi ghi. Likes của các comment do user viết ở bài người khác được gỡ trước khi chuyển comment thành placeholder/gỡ tác giả; replies được giữ. Bài riêng của user bị cascade. Toàn bộ asset do user sở hữu được enqueue trước khi xóa user; FK owner_id SET NULL cho phép job tồn tại để worker xử lý tiếp. Các index, procedure, trigger, constraint và ERD V1–V5 hiện có tiếp tục áp dụng; không thay migration hoặc quan hệ dữ liệu cho phần profile này. Các sơ đồ V3/V4 vẫn mô tả trigger/procedure đã có, không phải sơ đồ đầy đủ của ProfileService.

WriteTransaction dùng chung cho BlogService, ProfileService và InteractionService: retry tối đa 2 lần bằng transaction mới khi SQLSTATE là 40P01/40001; lock timeout hoặc hết retry trả 409 CONCURRENT_MODIFICATION. Không retry lỗi nghiệp vụ, password confirmation hoặc HTTP upload. Role runtime giữ lock_timeout=2s và statement_timeout=5s như provision.sql. Log chỉ ghi loại thao tác, UUID và cờ thay đổi mật khẩu, không ghi mật khẩu/hash/session cookie.

Kiểm chứng tự động: 46/46 test backend đạt (13 local authentication, 16 BlogApiTests, 3 retry/rollback, 14 ProfileApiTests). Bộ chạy run-blog-api.ps1 dùng PostgreSQL 17.11 riêng, Flyway thật V1–V5 và role runtime. ProfileApiTests bao phủ field absent/null/không hợp lệ, giả owner, username trùng và rollback, đổi password/thu hồi nhiều session, hash bị thay sau xác thực, rate limit, avatar/queue/provider lỗi, ảnh sai bài, chỉnh nội dung/quyền/thứ tự ảnh, rollback khi enqueue lỗi, cascade xóa bài và giữ replies khi xóa tài khoản. Cloudinary được mock, kiểm lời gọi upload diễn ra ngoài transaction DB. Retry/deadlock được kiểm bằng SQLSTATE mô phỏng; chưa gây deadlock DB thật. Frontend đạt 10/10 test Node (session/CSRF, comment traversal, like queue và timestamp); ESLint các file sửa, Vite build và Spotless check 15 file Java đều đạt. Vite vẫn cảnh báo bundle hơn 500 kB.

Google OIDC và reauth proof đã được bổ sung theo tài liệu 11: tài khoản chưa có local hash không được tự đặt password ở U06; U08 không chấp nhận proof giả để bỏ qua xác nhận mật khẩu. Chưa nghiệm thu trên Neon/LAN hoặc chạy upload/destroy với Cloudinary thật. Chưa kiểm tra render/thao tác UI trực tiếp: trình duyệt tích hợp không vào được localhost; Chrome riêng không khả dụng và quyền đọc cửa sổ Chrome đang mở ứng dụng khác bị duyệt tự động chặn.

## Nút chia sẻ lại và trạng thái viewer

BlogResponse bổ sung sharedByMe:boolean, lấy theo viewer trong cùng nhóm ID bài đang đọc bằng một query blog_shares, guest=false. Query tận dụng khóa chính (user_id, blog_id); không thêm cột, index hoặc sửa procedure/trigger. Các route feed, hồ sơ và chi tiết dùng chung mapper nên trả trạng thái nhất quán sau reload và không lấy trạng thái của user khác.

Feed và chi tiết dùng icon Repeat2 từ lucide-react, giữ bố cục icon kèm tổng lượt. Icon màu thường khi chưa chia sẻ, màu xanh khi đã chia sẻ; bấm lại gỡ share, không cần xác nhận. Nút khóa trong lúc request chạy và có chốt chống gửi hai write song song. Các nút thích, bình luận, chia sẻ đều có tooltip title và aria-label; share có aria-pressed phản ánh sharedByMe.

Frontend tiếp tục gọi POST share và DELETE unshare hiện có, không đổi response text của U11. Sau DELETE thành công, cập nhật sharedByMe=false rồi GET chi tiết để lấy count chuẩn. Lỗi GET sau commit không hoàn tác trạng thái thành công hoặc tự gửi lại write; UI báo chưa tải lại được count. Nếu nguồn bài không còn được xem, bỏ bài khỏi màn hình. ALREADY_SHARED do dữ liệu cũ ở tab khác chỉ làm GET đồng bộ, không tự unshare. Phản hồi từ tài khoản cũ không ghi đè trạng thái sau khi đổi tài khoản. Tab Chia sẻ của UserPage giữ đường gỡ share hiện có.

Kiểm chứng backend: 47/47 test đạt trên bộ chạy PostgreSQL tách biệt, gồm ca mới kiểm sharedByMe trước/sau share, viewer khác/guest, feed/hồ sơ/chi tiết, unshare/reload và gỡ khi bài của chủ đã private. Frontend đạt 16/16 test, gồm sáu ca mới cho share/unshare, CSRF, lỗi refresh sau commit, bài mất quyền xem, ALREADY_SHARED và lỗi DELETE. Build, ESLint các file frontend sửa và Spotless check đều đạt; Vite còn cảnh báo bundle lớn. Chưa kiểm tra trực tiếp giao diện trên browser.


## Google OIDC — ngày 16/11/2026

Đã bổ sung G01–G06, callback Spring Security và giao diện tiếng Anh theo [thiết kế Google](11-dang-nhap-google.md). `GOOGLE_ENABLED=false` là mặc định; credentials chỉ ở backend. Session ứng dụng chỉ lưu principal UUID nội bộ; OAuth authorized client dùng repository không lưu token. LINK/REAUTH không lưu principal Google đè lên tài khoản local. Luồng mới dùng lại schema hiện tại và transaction ghi của dự án; không sửa migration/ERD.

Hướng dẫn cấu hình hiện hành và giới hạn kiểm chứng nằm tại [cấu hình chạy local](11-dang-nhap-google.md#cấu-hình-chạy-local).


Kết quả kiểm tra ngày 16/11/2026: bộ hồi quy đầy đủ 66 ca backend đạt trên PostgreSQL riêng; sau đó ca bổ sung kiểm chính sách password có khoảng trắng đạt trong nhóm 6 GoogleSecurityTests (tổng 67 ca khác nhau trong các báo cáo hiện tại). Frontend có 17 ca Node đạt, lint các file sửa và Vite build đạt; Spotless check các file Java sửa đạt. UI giả lập kiểm nút Google khi chưa cấu hình, onboarding về trang cá nhân và menu tài khoản Google-only. Container PostgreSQL và server UI kiểm thử đã được dừng/dọn; không thay dữ liệu ứng dụng hoặc cấu hình Google Console thật.


Cập nhật policy mật khẩu ngày 16/11/2026: validator chung từ chối mật khẩu mới rỗng sau `trim()` ở đăng ký, đổi password và đặt password Google-only. UI kiểm tra tương ứng và giữ nguyên chuỗi hợp lệ khi gửi. Login và xác nhận mật khẩu hiện tại không tự trim hoặc áp lại chính sách tạo mật khẩu, tránh khóa tài khoản cũ; kiểm thử bảo đảm dấu cách đầu/cuối vẫn được băm và so sánh nguyên bản.

Kiểm chứng policy cập nhật: toàn bộ 69 ca backend đạt trên PostgreSQL riêng, gồm chặn password toàn dấu cách ở signup/đổi/đặt password, giữ nguyên dấu cách đầu/cuối và login tài khoản cũ. Spotless, lint các file frontend sửa và Vite build đạt.

Callback Google hiện hành là `GET /api/v1/auth/google/callback`, do Spring Security xử lý và trả 302; không tính vào các API JSON. Cấu hình Google Console, GOOGLE_REDIRECT_URI và proxy `/api` phải dùng cùng địa chỉ. Không giữ alias `/login/oauth2/code/google`.

Đổi callback sang `/api/v1/auth/google/callback`: 20 ca GoogleSecurityTests/LocalAuthenticationTests đạt, Spotless và frontend build đạt. Các ca Google dùng provider giả lập; chưa xác minh cấu hình Google Console thực tế.
