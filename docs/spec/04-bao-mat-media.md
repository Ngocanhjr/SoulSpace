# Bảo mật session và quản lý ảnh

## Mục tiêu và giới hạn

Giai đoạn AI bổ sung quyền chỉnh chủ đề chỉ dành cho tác giả, session/CSRF cho thao tác ghi và kiểm soát nội dung gửi vào model theo [đặc tả chủ đề](07-phan-loai-chu-de.md#xử-lý-nền-model-và-bảo-mật). Phân loại nội bộ không mở quyền đọc bài private và không gửi nội dung private đến dịch vụ bên thứ ba.

Thiết kế giảm các nguy cơ giả danh qua userId, đọc tài nguyên riêng tư, CSRF, đánh cắp session qua JavaScript, brute force đăng nhập, SQL injection và upload nguy hiểm. Phạm vi là một backend, một frontend web, Neon PostgreSQL cho dùng chung/online và PostgreSQL Docker cho local. Tài liệu không phải chứng nhận an toàn hoặc kết quả pentest.

## Session và vòng đời đăng nhập

Sử dụng cơ chế session của Spring Security/Servlet; chỉ lưu session ID vô nghĩa ở browser và giữ identity trên server. Với mô hình web đã chốt, session giúp hủy đăng nhập trực tiếp và quản lý vòng đời trong một process. [Spring Security session management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html).

| Thuộc tính thiết kế | Localhost HTTP | LAN HTTPS |
| --- | --- | --- |
| Tên cookie | SOULSPACESESSION | __Host-SOULSPACESESSION |
| HttpOnly | true | true |
| Secure | false, chỉ cho loopback phát triển | true |
| SameSite | Lax | Lax |
| Path / Domain | `/`, không đặt Domain | `/`, không đặt Domain |
| Cookie bền qua đóng browser | Không đặt Max-Age | Không đặt Max-Age |
| Idle / tuổi tối đa phía server | 30 phút / 8 giờ | 30 phút / 8 giờ |

HTTPS bảo vệ mật khẩu/session trên mạng; HttpOnly hạn chế JavaScript đọc cookie; SameSite hỗ trợ phòng vệ CSRF nhưng vẫn cần token. Người dùng tấn công được bằng XSS có thể thao tác trong browser nên vẫn phải xử lý nội dung an toàn. [OWASP session management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html).

Luồng bắt buộc:

1. Frontend gọi GET `/auth/csrf`, giữ token trong bộ nhớ.
2. POST `/auth/log-in` gửi JSON password, cookie và CSRF header.
3. Backend dùng AuthenticationManager/PasswordEncoder, thực hiện SessionAuthenticationStrategy để chống session fixation, lưu SecurityContext bằng cơ chế Spring Security và đăng ký session để có thể thu hồi. Với JSON login tự viết, chỉ set context vào thread là chưa đủ để đăng nhập tồn tại ở request tiếp theo.
4. Response JSON đúng AuthResponse; frontend gọi lại `/auth/csrf`, sau đó `/auth/me` khi cần bootstrap.
5. Logout/đặt hoặc đổi password/account deletion làm hết hiệu lực session ở server, không chỉ xóa cookie hoặc localStorage; hủy cả pending Google flow và proof xác nhận lại liên quan.
6. Mỗi request kiểm user còn tồn tại và session chưa hết tuổi tối đa. Session bị đánh dấu thu hồi phải bị chặn ở request tiếp theo. Dùng session registry và cơ chế filter/listener của Spring, kiểm thử cả trường hợp custom JSON login.

Server chỉ chấp nhận session qua cookie; tắt URL rewriting/session ID trên URL. Endpoint API lỗi xác thực trả JSON 401/403, không redirect HTML sang form login. Không bật remember-me trong giai đoạn này. Restart backend làm hết session; chưa có Redis hoặc Spring Session JDBC.

## CSRF và origin

Giữ CSRF cho mọi unsafe method và cho login/logout/signup. Token lấy từ endpoint JSON và gửi header `X-CSRF-TOKEN`; session cookie vẫn HttpOnly. Không tắt CSRF chỉ để giải quyết lỗi 403 hoặc multipart. Token mới cần được cấp sau login/logout. [Spring Security CSRF và SPA](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

Phát triển/demo ưu tiên Vite proxy `/api` tới backend loopback để browser dùng cùng origin. Frontend đặt API base thành `/`, dùng lại buildApiUrl, gửi cookie bằng client chung. Khi cố tình gọi khác origin, CORS phải allowlist origin đầy đủ từ cấu hình, `allowCredentials=true`, cho phép header CSRF và các method cần thiết; không dùng wildcard origin. Preflight được xử lý đúng trước authentication.

Trong LAN, React/Vite phục vụ HTTPS bằng certificate được thiết bị demo tin cậy; backend có thể chạy HTTP loopback sau proxy. Backend và cổng Docker PostgreSQL không mở trực tiếp ra LAN. Kết nối backend tới Neon luôn dùng TLS xác minh certificate/hostname; không hạ cấu hình TLS để chữa lỗi kết nối. Nếu dùng forwarded headers để nhận biết HTTPS, chỉ tin proxy do dự án kiểm soát; browser không được tùy ý giả X-Forwarded-* qua một backend đang public.

## Mật khẩu

Do database mới không import tài khoản cũ, dùng Argon2id cho toàn bộ password mới. Tham số ban đầu: memory 19.456 KiB, iterations 2, parallelism 1, salt 16 bytes, hash 32 bytes; lưu chuỗi hash tự mô tả đầy đủ. Đây là điểm khởi đầu cần đo trên máy demo. [OWASP password storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

Spring Argon2PasswordEncoder cần BouncyCastle; thêm đúng dependency crypto cần thiết, xác minh tương thích với Boot đang dùng. Dùng một PasswordEncoder bean chung cho signup/login/reauth/seed, không khởi tạo encoder khác nhau trong service. [Spring Security password storage](https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html).

Policy thiết kế cho user mới: 15–128 Unicode code points và tối đa 512 UTF-8 bytes, cho phép passphrase/dấu cách/Unicode, không trim hoặc cắt password; từ chối control character/NUL. Không bắt buộc quy tắc ký tự đặc biệt của frontend cũ. Cập nhật hướng dẫn UI tương ứng. Dùng blocklist password phổ biến đã version trong repository, không gọi dịch vụ ngoài cho mỗi lần login. Chiều dài tối thiểu 15 phù hợp khuyến nghị cho tài khoản chưa có MFA. [OWASP authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html).

Rate limit phải chạy trước hash; nếu username không tồn tại hoặc password_hash=NULL (Google-only) vẫn kiểm một dummy hash để giảm chênh lệch thời gian. Thông báo lỗi chung. User có hash đổi password và xóa account cần password hiện tại; Google-only đặt password đầu tiên hoặc xóa account cần Google REAUTH và proof đúng intent theo [tài liệu 11](11-dang-nhap-google.md#xác-nhận-lại-và-mật-khẩu). Không tạo password giả; không cho Google proof bỏ qua currentPassword của user đã có hash. Không gửi password/proof qua query string, không ghi body vào access log. Không xuất passwordHash trong JSON/entity serialization, không log form signup hoặc toàn bộ response login. Không giữ password ở localStorage.

## Google OIDC, liên kết và xác nhận lại

Thiết kế [đăng nhập Google](11-dang-nhap-google.md) bổ sung vào session hiện tại, chưa có code thực thi. Dùng Authorization Code flow với state/nonce gắn server session; Spring xác minh chữ ký/issuer/audience/expiry và tương quan flow. Provider/subject lấy từ kết quả đã xác minh, không lấy từ JSON frontend. Google sub là khóa định danh ổn định; email/email_verified không phải bằng chứng cho phép tự gộp tài khoản SoulSpace. [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect), [Spring OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)

Pending Google onboarding có TTL 10 phút, không có principal đầy đủ và không được gọi API nghiệp vụ; tạo users+identity trong một transaction khi POST hoàn tất có CSRF. Username/fullname vẫn bắt buộc. Session tạm có thể dùng GET CSRF, GET/POST onboarding và logout đúng chính sách; không coi provider authentication là user SoulSpace đã đăng ký xong.

LINK chỉ bắt đầu bằng POST có session và CSRF sau xác nhận lại tài khoản nội bộ. Authorization request giữ actor/intent; callback phải giữ local principal, không để oauth2Login mặc định thay actor bằng tài khoản Google khác. Identity đã liên kết tài khoản khác trả conflict chung, không tiết lộ chủ sở hữu, không chuyển liên kết. Không auto-link theo email, không mở endpoint gỡ phương thức cuối cùng. Custom handler/flow phải được kiểm qua security filter thật trước khi coi liên kết an toàn. [Spring OIDC user service](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html#oauth2login-advanced-oidc-user-service)

Proof xác nhận lại có TTL 5 phút, khó đoán, lưu/kiểm server, single-use và bind actor/session/một intent LINK_GOOGLE, SET_PASSWORD hoặc DELETE_ACCOUNT. Google REAUTH là authorization mới đúng linked sub, state/nonce và action; ceremony có thể dùng Google SSO hiện hữu. prompt=select_account không chứng minh Google đã bắt nhập lại password/MFA; không giả định prompt=login hoặc max_age được hỗ trợ. Chính sách v1 chấp nhận mức bảo đảm này; không gọi đây là xác minh mật khẩu mới. [Google prompt](https://developers.google.com/identity/openid-connect/openid-connect#authenticationuriparameters)

Callback GET `/login/oauth2/code/google` nằm ngoài API JSON và dùng state/nonce; không yêu cầu CSRF header trên redirect từ Google, không tắt CSRF của unsafe API. Callback chỉ redirect về route frontend cố định, không chứa token/proof/email/subject; log phải lọc code và query callback. Đăng nhập thành công thay session ID, lấy CSRF mới và dùng lại session registry/revocation; LINK/REAUTH không đổi actor. Session hết hạn hoặc user bị xóa làm flow/proof cũ vô hiệu. Không nhận return URL tùy ý, không tạo open redirect.

Client secret chỉ ở môi trường backend; không đưa vào frontend, repository hoặc log. Scope chỉ openid/profile/email, không xin offline access và không lưu bền Google access/refresh tokens cho tính năng đăng nhập. Không trả token provider hoặc JWT ứng dụng cho browser; kiểm cấu hình authorized-client storage để credential tạm không tồn tại ngoài vòng đời cần thiết. Redirect URI phải đúng Google Console và môi trường, production dùng HTTPS. Boot 4 dùng starter security-oauth2-client do BOM quản lý; các hướng dẫn triển khai ở [tài liệu 11](11-dang-nhap-google.md#cấu-hình-và-kiểm-chứng) là thiết kế, không là bằng chứng đã cấu hình Google Console.

## Kiểm quyền và dữ liệu đầu vào

- Đối chiếu principal với owner của từng tài nguyên, gồm blog access/details, avatar, comments/replies, share và media. Không chỉ kiểm tra người dùng đã login.
- `userId` client là thông tin compatibility; khác principal bị chặn. Author/counters/createdAt không được lấy từ body. Route UUID hợp lệ về format vẫn cần kiểm quyền.
- Query có parameter binding; tên cột/sort không ghép từ input. Procedure nhận tham số, không tạo dynamic SQL từ content.
- Content/comment hiển thị dưới dạng text React; không dùng dangerouslySetInnerHTML cho dữ liệu người dùng. Profile/ảnh dùng URL server cấp, chặn URL JavaScript hoặc remote tùy ý.
- DOB không trả công khai. Response private, auth và media có `Cache-Control:no-store`; lỗi không lộ SQL/stack trace hay cấu hình database.
- Sửa/xóa comment chỉ cho tác giả còn quyền xem bài; kiểm expectedVersion từ client. Không cho like hoặc reply trực tiếp vào node đã xóa; khóa parent trước kiểm tra để phối hợp với thao tác xóa. Placeholder không trả lại content đã xóa và dùng LEFT JOIN tác giả để giữ replies sau xóa tài khoản.
- Actuator chỉ cho thông tin health tối thiểu ở loopback; không proxy actuator/env/configprops ra LAN. Runtime database role không có DDL hoặc quyền superuser.

## Giới hạn lạm dụng

Các giá trị sau là mặc định dự án cho demo, phải kiểm thử 429; không phải thông số do chuẩn bên ngoài quy định.

| Hoạt động | Giới hạn ban đầu |
| --- | --- |
| Login, xác nhận password nhạy cảm | 10 lần thất bại/5 phút trên cặp IP và username; thêm 60 lần thất bại/15 phút trên IP. |
| Signup | 10 request/giờ/IP. |
| Ghi nghiệp vụ thông thường | 60 request/phút/user. |
| Upload | 30 request/giờ/user, tối đa 2 request upload đồng thời/user. |
| GET cấp CSRF | 120 request/phút/IP. |

Trả 429 và Retry-After; không khóa tài khoản vĩnh viễn vì request sai từ mạng. Bộ đếm có TTL và dung lượng giới hạn; một instance không cần hạ tầng Redis. Khi restart, limiter được reset; nhiều instance hoặc chống DDoS phân tán cần thiết kế riêng. Chỉ lấy IP từ proxy tin cậy; không tin header tùy ý. Áp dụng limit dung lượng JSON/body trước hash, decode hoặc upload.

## Kiểm tra file và quyền truy cập ảnh

Cho phép JPEG và PNG tĩnh, dùng decoder JDK; chưa thêm WebP decoder. Kiểm extension, MIME và nội dung giải mã; không chỉ tin tên file hoặc Content-Type. File tối đa 10 MiB, một bài tối đa 10 ảnh, toàn multipart tối đa 100 MiB gồm overhead; avatar dùng cùng giới hạn mỗi file. Giới hạn ảnh giải mã 25 triệu pixel, một frame; chặn SVG/HTML/GIF/video/ảnh động và ảnh lỗi. Kiểm header/kích thước trước khi giải mã toàn ảnh, áp dụng giới hạn bộ nhớ; từ chối PNG có APNG chunks để không nhận nhầm ảnh động. Tên tài nguyên do server sinh, không ghép đường dẫn từ original filename. UI crop không thay kiểm tra server.

Upload tất cả media với Cloudinary delivery type `authenticated` để original và derived assets không bị truy cập bằng URL unsigned. Giữ public_id nguyên vẹn lấy từ receipt, không cắt URL để đoán public_id như legacy. SDK quản lý signing; không tự dựng crypto. [Cloudinary access control](https://cloudinary.com/documentation/control_access_to_media).

GET `/api/v1/media/{assetId}` giải quyết quyền bằng database hiện tại:

| Liên kết/trạng thái | Ai được xem |
| --- | --- |
| Avatar đang được user còn tồn tại dùng | Công khai. |
| Ảnh gắn với blog public | Công khai. |
| Ảnh gắn với blog private | Chỉ chủ bài. |
| PENDING đã xác nhận upload nhưng chưa gắn | Chỉ owner. |
| PENDING chưa xác nhận, DELETE_PENDING, asset mất owner/đã xóa | Không phục vụ, trả 404. |

Backend tạo signed download URL nội bộ, tải từ Cloudinary với timeout/giới hạn bytes, rồi stream bytes sau kiểm quyền; không trả URL này, không redirect và không log chữ ký. Endpoint chỉ tải asset theo metadata đáng tin, không nhận upstream URL client gửi. Trả Content-Type đã xác minh, inline filename do server sinh, nosniff và no-store. Chi phí/băng thông media đi qua backend cần được đo; không thêm cache làm bỏ qua kiểm quyền.

Database chỉ giữ format canonical jpeg/png, không lưu mime_type; một hàm backend ánh xạ định dạng đã kiểm tra thành image/jpeg hoặc image/png. Không dùng Content-Type do client gửi làm nguồn tin cậy.

Nếu provider không sẵn sàng, media trả 502; UI hiển thị placeholder và thông báo đúng trạng thái. Bài text và dữ liệu đã có vẫn sử dụng được. Không cho phép fallback sang public Cloudinary URL để vượt qua lỗi riêng tư. Phiên bản SDK và upload/download/destroy authenticated phải được kiểm tra bằng tài khoản thực trước nghiệm thu media; cấu hình credential không chứng minh tích hợp đã chạy.

## Upload và commit dữ liệu

1. Validate file và quyền cơ bản. Sinh UUID/public_id mới theo namespace riêng từng môi trường, lưu receipt tối thiểu state=PENDING trong transaction ngắn; provider_asset_id, format và size_bytes đều NULL. Receipt giúp dọn ảnh nếu tiến trình dừng giữa lúc gọi Cloudinary và ghi nghiệp vụ; public_id không ghi đè hoặc dùng lại cho ảnh khác.
2. Upload ngoài transaction, đặt timeout kết nối 5 giây, đọc 30 giây. Sau khi provider xác nhận, giữ metadata trả về để ghi cùng transaction nghiệp vụ; kiểm user/receipt vẫn còn hợp lệ.
3. Transaction nghiệp vụ giữ khóa actor, khóa user/blog đích rồi các asset FOR UPDATE theo UUID tăng dần. Sau khi lấy khóa, kiểm lại ownership/visibility và asset thuộc actor, purpose đúng AVATAR/BLOG, state=PENDING. Ghi đồng thời provider_asset_id/format/size_bytes; chỉ asset có đủ metadata mới được chuyển ATTACHED và gắn vào avatar/bài trong cùng commit. Upload độc lập ghi đủ metadata nhưng giữ PENDING. Ảnh ATTACHED đang giữ nguyên không phải attach mới; DELETE_PENDING không được gắn lại.
4. Với ảnh gỡ bỏ, khóa asset, bỏ liên kết, chuyển DELETE_PENDING và enqueue job trong cùng transaction. INSERT job ON CONFLICT(asset_id) DO NOTHING; không reset lượt claim/lease của job có sẵn. Thay avatar dùng public_id mới để không ghi đè ảnh trước commit.
5. Nếu transaction nghiệp vụ thất bại, enqueue cleanup cho ảnh mới đã upload; nếu process crash trước bước này, bộ quét PENDING đã xác nhận sau 24 giờ sẽ thu hồi.

Upload bị timeout có thể đã thành công ở provider nhưng backend chưa kịp commit metadata. Không retry upload mù bằng public_id mới. PENDING chưa xác nhận (ba trường metadata còn NULL) quá 10 phút được đưa vào đối soát/cleanup, có khoảng chờ 1 giờ trước lần destroy đầu để giảm cuộc đua với upload chưa xác nhận. PENDING đã xác nhận nhưng chưa gắn được bộ quét thu hồi sau 24 giờ. Nếu receipt đến muộn sau khi user/blog đã xóa hoặc job đã được lập, chỉ enqueue compensation, không gắn ảnh trở lại.

Scanner khóa asset trước khi đọc lại state/age/liên kết và enqueue. Nếu asset đã được gắn thì bỏ qua; nếu scanner đã chuyển DELETE_PENDING thì attach phải từ chối. Purpose của asset bất biến. Thứ tự khóa chung theo [thiết kế PostgreSQL](03-postgresql.md), không giữ transaction trong bất kỳ lời gọi upload/download/destroy nào.

Vì database và provider không có chung transaction, vẫn cần đối soát namespace public_id của dự án với receipts khi có upload không xác định hoặc crash. Không hứa xóa tức thì mọi bản sao bên ngoài; các trường hợp provider trả kết quả quá muộn phải được ghi nhận và xử lý qua cùng queue bằng public_id đã cấp.

## Worker dọn ảnh

Worker là Spring scheduled task trong cùng backend, dùng bảng media_cleanup_jobs. Không cần broker hoặc service riêng. Mỗi môi trường có MEDIA_NAMESPACE riêng; worker từ chối destroy public_id ngoài namespace đó. Không chạy worker trên Neon branch clone có media/jobs của môi trường khác; nghiệm thu dùng database/branch sạch. Quy tắc này ngăn queue được copy theo branch xóa ảnh đang được môi trường gốc dùng.

1. Transaction claim chỉ khóa job PENDING đến hạn có attempts < 5 bằng FOR UPDATE SKIP LOCKED; tăng attempts, đặt RUNNING và lease_until = clock_timestamp() + interval '120 seconds'. Lấy attempts/lease_until chính xác từ RETURNING làm cặp xác nhận lượt claim rồi commit, trước khi chờ khóa asset.
2. Transaction chuẩn bị khóa asset rồi job, kiểm RUNNING, cặp attempts/lease_until còn khớp và lease chưa hết theo clock_timestamp() sau lấy khóa. Kiểm asset DELETE_PENDING, namespace đúng và không có liên kết avatar/blog sống. Sai điều kiện thì không destroy; chỉ worker còn quyền claim được ghi lỗi/chuyển trạng thái. Commit trước lời gọi provider.
3. Gọi Cloudinary destroy với public_id đầy đủ, resource type image, delivery type authenticated và yêu cầu invalidation; không giữ DB transaction hoặc row lock khi gọi mạng.
4. Ghi kết quả bằng transaction mới theo thứ tự asset -> job, kiểm lại cặp claim/trạng thái/hạn lease trước mọi thay đổi. Thành công hoặc not found sau đối soát receipt được xác nhận rồi xóa metadata asset/job cùng commit. Mất lượt claim hoặc quá lease thì bỏ kết quả; không xóa receipt, không ghi lỗi hoặc retry thay worker mới. Timeout không là thành công; destroy cùng public_id có thể được xử lý lại vì ID không tái sử dụng.
5. Tối đa 5 lượt claim tự động, kể cả lượt crash/hết lease. Sau lỗi ở lượt 1–4: chuyển PENDING, xóa lease và đặt available_at theo các khoảng chờ 1 phút, 5 phút, 30 phút, 2 giờ. Lượt 5 thất bại: DEAD, lease=NULL, giữ lỗi đã lọc và metadata. Thu hồi lease hết hạn: nếu attempts < 5 thì PENDING với available_at phù hợp, ngược lại DEAD; luôn xóa lease và không phát sinh lượt thứ 6.

Schema bắt buộc RUNNING có lease_until, PENDING/DEAD có lease_until=NULL; attempts nằm trong 0–5. Cặp claim là dữ liệu được database cấp, không dựng lại từ đồng hồ Java. Điều kiện ghi kết quả khớp id/status/attempts/lease_until và lease chưa hết; tác động 0 hàng nghĩa là mất lượt xử lý, phải bỏ/rollback phần ghi của worker cũ. Không giữ khóa job từ bước claim để chờ asset, vì luồng attach/enqueue dùng thứ tự asset -> job.

Lượt đầu thất bại không rollback nghiệp vụ đã commit. Các job DEAD có truy vấn/runbook để xem và requeue; chưa mở API admin public. Khi sửa thủ công database để requeue phải dùng tham số/ID cụ thể, không xóa bảng hoặc volume.

Requeue DEAD là thao tác vận hành rõ ràng: khóa asset rồi job, xác nhận vẫn DELETE_PENDING/không liên kết và namespace đúng, chuyển PENDING, attempts=0, lease=NULL, available_at mới. Đây là một đợt thử mới do người vận hành yêu cầu, không phải retry tự động vượt giới hạn. Kết quả cũ mang lease khác không được ghi đè đợt mới. Nếu provider đã xóa ảnh nhưng worker cũ hết lease trước khi ghi kết quả, lượt còn quyền tiếp theo đối soát not found hoặc người vận hành xử lý job DEAD; không coi receipt đã được dọn khi chưa có commit hợp lệ.

Cloudinary invalidation và việc người dùng đã lưu ảnh không tạo bảo đảm thu hồi mọi bản sao. Bản ứng dụng mới không phục vụ ảnh của bài đã xóa/private qua API ở request tiếp theo, kể cả khi cleanup còn pending. [Cloudinary Upload API](https://cloudinary.com/documentation/image_upload_api_reference).

## Quyền truy cập thông báo

GET/PATCH thông báo bắt buộc đăng nhập; recipient_id phải lấy từ session và được dùng trong WHERE của cả đọc lẫn cập nhật. PATCH cần CSRF. Không nhận actor_id/recipient_id từ JSON và không xuất entity trực tiếp. List/count phải cùng lọc quyền xem bài hiện tại và loại comment đã xóa để không lộ nội dung bài private hoặc placeholder. Role runtime dùng chung cho backend, không phải RLS theo user; quyền sở hữu được thực thi tại service. Xem [10 thông báo](10-thong-bao.md).
