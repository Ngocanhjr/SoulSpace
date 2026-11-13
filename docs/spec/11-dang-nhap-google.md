# Đăng nhập bằng mật khẩu và Google

## Trạng thái và phạm vi

Đây là thiết kế cần triển khai, chưa phải chức năng đã chạy. Giữ đăng nhập username/password và bổ sung Google OpenID Connect (OIDC). Backend tiếp tục dùng session Spring Security; browser chỉ giữ cookie session HttpOnly, không nhận JWT ứng dụng hoặc token Google. Không thêm nhà cung cấp khác, gỡ liên kết Google, xóa mật khẩu hoặc khôi phục mật khẩu qua email trong phạm vi này.

V1 hiện định nghĩa 13 bảng/76 cột, gồm `external_identities`; `users.password_hash` nullable. View không tính là bảng. Migration và chức năng Google vẫn cần chạy và kiểm chứng; đặc tả không tự triển khai Entity hay API.

## Tài khoản nội bộ và danh tính Google

`users.id` vẫn là UUID nội bộ theo [chính sách UUID](03-postgresql.md#chính-sách-sinh-uuid). Bài viết, bình luận và session dùng ID này, không dùng email hoặc Google subject làm user ID. `username` và `fullname` vẫn bắt buộc; `dob` và avatar có thể để trống. `password_hash=NULL` nghĩa là chưa thiết lập đăng nhập bằng mật khẩu, không phải mật khẩu rỗng.

| Cột thiết kế của external_identities | Kiểu và ràng buộc |
| --- | --- |
| user_id | UUID NOT NULL, FK users(id) ON DELETE CASCADE; UNIQUE(user_id, provider) |
| provider | VARCHAR(20) NOT NULL, CHECK provider IN ('GOOGLE'); thuộc PK |
| subject | VARCHAR(255) NOT NULL; thuộc PK(provider, subject), không trim/lowercase |

PK bảo đảm một Google identity chỉ thuộc một user; UNIQUE bảo đảm một user liên kết tối đa một tài khoản Google. Không cần ID phụ hoặc cột timestamp cho bảng ba cột này. Backend chỉ nhận provider/subject từ kết quả OIDC đã xác minh của cấu hình Google tin cậy; client không được gửi chúng để tạo liên kết.

Google `sub` là định danh ổn định, case-sensitive; email có thể đổi nên không dùng làm khóa đăng nhập hoặc tự động nối tài khoản. `email_verified` chỉ mô tả email, không thay bằng chứng sở hữu tài khoản SoulSpace đang có. Không tự tạo cột email chỉ để đăng nhập; không log hay trả subject trong API quản lý phương thức. [Google ID token claims](https://developers.google.com/identity/openid-connect/openid-connect#an-id-tokens-payload)

User đã commit phải có ít nhất một phương thức đăng nhập: hash mật khẩu hoặc Google identity. Đây là invariant liên bảng do service/transaction bảo vệ; CHECK trên riêng users không kiểm được sự tồn tại của hàng ở bảng khác. Đăng ký local tạo hash; đăng ký Google tạo user và identity cùng transaction. V1 không có thao tác gỡ phương thức cuối cùng. Khi xóa tài khoản, identity được cascade cùng user.

## Luồng OIDC và session

1. Frontend lấy CSRF rồi POST bắt đầu flow; backend lưu authorization request ngắn hạn trong session với intent, actor nếu có, state và nonce. Chỉ một flow Google đang chờ trong một session; bắt đầu flow mới hủy flow cũ. GET mặc định `/oauth2/authorization/google` không được cho phép bỏ qua G01: chặn hoặc dùng resolver chỉ chấp nhận authorization request đã được G01 khởi tạo và bind đúng session/intent.
2. Browser điều hướng tới authorization URL do backend trả, không gọi Google bằng fetch. Dùng Authorization Code flow, scope `openid profile email`; chỉ xin quyền đăng nhập, không xin offline access.
3. Google redirect GET về `/login/oauth2/code/google`, endpoint framework ngoài base `/api/v1`. Backend đổi code qua kết nối server và để Spring kiểm chữ ký, issuer, audience, expiry, state và nonce theo flow. Không tự giải mã JWT rồi tin payload. Redirect URI đăng ký với Google phải khớp môi trường; không nhận redirect/issuer/client ID tùy ý từ JSON. [Google server flow](https://developers.google.com/identity/openid-connect/openid-connect#server-flow), [Spring OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)
4. Callback chỉ xử lý đúng intent đã lưu; không lấy actor từ query. State/code dùng một lần, intent hết hạn hoặc session không khớp bị từ chối. Lỗi không tạo user, identity hoặc proof. Callback kết thúc bằng 302 tới route frontend cố định theo kết quả, không mang token/proof/code/email/subject trong URL ứng dụng.
5. LOGIN với identity đã tồn tại tạo principal mang UUID SoulSpace, thay session ID chống fixation và đăng ký session để thu hồi. Frontend lấy lại CSRF và gọi `/auth/me`; AuthResponse giữ nguyên. Pending onboarding chỉ có session tạm, không phải principal được phép truy cập API nghiệp vụ. Custom success handling phải ngăn Spring lưu OIDC Authentication như user đã hoàn tất trước bước này; không chỉ ẩn nút trên frontend hoặc dựa vào role mặc định.

Cookie giữ HttpOnly, Secure theo môi trường, SameSite=Lax như [bảo mật session](04-bao-mat-media.md). CSRF vẫn bắt buộc cho mọi unsafe API; GET callback dùng tương quan state/nonce, không là lý do tắt CSRF toàn ứng dụng. Session/authorization request mặc định trong bộ nhớ nên restart làm mất flow đang chờ. Logout phải hủy cả session tạm và flow chưa hoàn tất.

## Đăng ký Google lần đầu

LOGIN chưa tìm thấy identity chỉ lưu pending identity phía server tối đa 10 phút; không ghi user thiếu username, không trả `authentication=true` và `/auth/me` vẫn trả 401. Tên Google nếu có chỉ là gợi ý; avatar mặc định của SoulSpace được dùng, không gán URL `picture` vào FK `avatar_media_id`.

Người dùng tự chọn username và xác nhận fullname. POST hoàn tất chỉ nhận username/fullname/dob, không nhận Google token, email, subject, userId hoặc passwordHash. Canonical username bằng trim/lowercase rồi kiểm `^[a-z0-9]{3,32}$`; fullname sau trim dài 1–100; dob nếu có phải trước hôm nay theo Asia/Ho_Chi_Minh. Không dùng nguyên email/name làm username và không tự sửa fullname đã có ở các lần login sau.

Trong một transaction tạo UUID user, user với password_hash=NULL và identity đã xác minh. UNIQUE username/provider-subject là chốt chống race; conflict rollback cả transaction, không để user mồ côi. Lỗi field/username trùng cho phép sửa và gửi lại trong TTL; thành công tiêu thụ pending đúng một lần và tạo session đầy đủ. Hai POST đồng thời chỉ một lần thành công; lần còn lại trả conflict, không tạo tài khoản khác hoặc tự gán identity vào user vừa tìm thấy.

## Liên kết Google vào user đang có

Không tự liên kết do trùng email. User đã có session chủ động chọn liên kết, xác nhận lại quyền sở hữu tài khoản nội bộ, sau đó hoàn tất một flow Google riêng. Proof `LINK_GOOGLE` hợp lệ được tiêu thụ khi khởi tạo và actor/intent được gắn vào authorization request; callback chỉ được liên kết vào actor đó nếu user/session vẫn hợp lệ.

LINK cần flow riêng hoặc handler lưu/khôi phục local Authentication an toàn: không để `oauth2Login` mặc định ghi đè principal SoulSpace bằng tài khoản Google đang chọn hoặc chuyển sang tài khoản nội bộ khác. Không đổi actor chỉ vì identity được tìm thấy. Khi callback, khóa user, kiểm lại user tồn tại/phương thức xác nhận còn hợp lệ, rồi insert identity; Google identity thuộc user khác hoặc user đã liên kết một Google khác trả cùng conflict không lộ ID/email/chủ sở hữu. Identity đã thuộc chính actor là kết quả idempotent. Không chuyển hoặc gộp hai tài khoản. [Spring OIDC customization](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html#oauth2login-advanced-oidc-user-service)

User có password xác nhận bằng currentPassword để lấy proof LINK_GOOGLE; user Google-only đã có một identity nên không có nhu cầu thêm Google thứ hai ở v1. Login Google thông thường bị từ chối nếu đang có principal đầy đủ; người dùng đăng xuất để LOGIN, hoặc chọn LINK/REAUTH rõ ràng. Cách này tránh đổi tài khoản âm thầm và login CSRF.

## Xác nhận lại và mật khẩu

Proof xác nhận lại là giá trị ngẫu nhiên khó đoán, lưu và kiểm phía server, TTL 5 phút, dùng một lần; bind actor, session và một intent `LINK_GOOGLE`, `SET_PASSWORD` hoặc `DELETE_ACCOUNT`. Không phải JWT đăng nhập, không cấp quyền chung, không được ghi log hoặc đưa vào URL/localStorage. Proof thành công được tiêu thụ nguyên tử khi thao tác được chấp nhận; transaction thất bại không được để proof cấp lại quyền cho actor/intent khác. Password hash thay đổi, user bị xóa hoặc session bị thu hồi làm proof/flow cũ vô hiệu. Kiểm TTL tại lần dùng, không chỉ khi tạo.

| Tình trạng user | Đặt/đổi mật khẩu | Xóa tài khoản |
| --- | --- | --- |
| Có password_hash | Giữ U06 với currentPassword; hash vẫn phải là bản đã xác nhận lúc khóa user | Giữ U08 với currentPassword |
| Google-only | Google REAUTH đúng subject đã liên kết, lấy proof SET_PASSWORD rồi đặt mật khẩu lần đầu | Google REAUTH đúng subject đã liên kết, dùng proof DELETE_ACCOUNT ở U08 |

Google REAUTH chỉ dành cho Google-only với intent SET_PASSWORD/DELETE_ACCOUNT trong thiết kế v1. Dùng authorization mới có state/nonce và actor/session/action; chỉ đánh dấu ceremony hoàn tất nếu subject đúng liên kết đang có. `prompt=select_account` yêu cầu chọn account, không bảo đảm Google yêu cầu nhập lại password/MFA; không mô tả đây là xác nhận mật khẩu mới. Google công bố các giá trị prompt none/consent/select_account, không dùng prompt=login. Chính sách v1 chấp nhận ceremony mới có thể tận dụng session SSO Google hiện hữu; nếu cần mức bảo đảm mạnh hơn phải thiết kế thêm trước khi tuyên bố đạt. [Google authorization parameters](https://developers.google.com/identity/openid-connect/openid-connect#authenticationuriparameters)

Sau đặt/đổi mật khẩu, thu hồi mọi session, flow và proof của user rồi yêu cầu đăng nhập lại. Hash mật khẩu mới theo Argon2id hiện tại; không sinh mật khẩu giả cho user Google-only. Login local với hash NULL vẫn kiểm dummy hash và trả cùng 401 chung như username không tồn tại/sai password. Không dùng Google email để reset password hoặc bypass currentPassword của user đã có hash.

## API bổ sung mục tiêu

Các route dưới đây chưa có implementation. Base `/api/v1`; response thành công 200 JSON, riêng callback 302. JSON chỉ nhận các field liệt kê; mọi POST có CSRF. Rate limit bắt đầu/callback/proof/onboarding theo session và IP, tối đa 10 lượt/5 phút mỗi nhóm trước khi gọi provider/hash; vượt trả 429 kèm Retry-After. Không thay đổi response của A01–A04 hoặc U01/U06/U08.

| Mã | Method/route | Input, quyền | Thành công |
| --- | --- | --- | --- |
| G01 | POST `/auth/google/start` | `{intent:"LOGIN"}` cho Guest; `{intent:"LINK",reauthProof}` cho user có password; `{intent:"REAUTH",action:"SET_PASSWORD" hoặc "DELETE_ACCOUNT"}` cho Google-only | `{authorizationUrl,expiresAt}`; flow 10 phút, URL chỉ tới authorization endpoint Google tin cậy |
| G02 | GET `/auth/google/onboarding` | Session pending đã xác minh, chưa hết TTL | `{required:true,suggestedFullname:string hoặc null}`; không có token/subject |
| G03 | POST `/auth/google/onboarding` | `{username,fullname,dob?}`, session pending | AuthResponse hiện tại, authentication=true sau transaction và tạo session đầy đủ |
| G04 | GET `/auth/methods` | Session user đầy đủ | `{passwordEnabled:boolean,googleLinked:boolean}`; không trả email/subject/hash |
| G05 | POST `/auth/reauthenticate` | `{intent:"LINK_GOOGLE",currentPassword}` cho user có hash; hoặc `{intent:"SET_PASSWORD" hoặc "DELETE_ACCOUNT"}` sau Google REAUTH đúng action được stage trong chính session | `{reauthProof,expiresAt}`; tiêu thụ kết quả Google stage đúng một lần; không cấp proof chỉ vì có session |
| G06 | POST `/auth/password` | `{password,reauthProof}`, Google-only; proof SET_PASSWORD | `{message:"Password has been set. Please log in again."}` sau commit, thu hồi mọi session |
| Framework | GET `/login/oauth2/code/google` | code/state từ Google, matching authorization request trong session | 302 tới `/auth/google/result`, `/auth/google/complete` hoặc `/auth/google/error` trên frontend cùng origin; không có dữ liệu xác thực trong URL |

`/auth/google/result` chỉ là trang kết quả frontend: LOGIN gọi me; LINK gọi methods; REAUTH gọi G05 với action đã khởi tạo để lấy proof qua JSON rồi gửi thao tác tương ứng. Stage REAUTH có TTL 5 phút, single-use và không tự động thực hiện xóa/đặt password. Cookie vẫn HttpOnly; lấy CSRF mới nếu session ID/context thay đổi. Frontend lưu proof ngắn trong bộ nhớ và hiển thị xác nhận trước xóa tài khoản.

U08 mở rộng body: user có hash gửi `{currentPassword}` như cũ; Google-only gửi `{reauthProof}` intent DELETE_ACCOUNT. Không nhận cả hai cùng lúc. User có hash không được dùng proof để bỏ qua currentPassword. U06 không đặt password đầu tiên cho Google-only: trả 409 PASSWORD_NOT_ENABLED và hướng dẫn G06; field hồ sơ khác vẫn hoạt động như cũ. G06 khi password đã được thiết lập trả 409 PASSWORD_ALREADY_ENABLED, không ghi đè hash mới của request đồng thời.

| HTTP/code | Nghĩa và xử lý |
| --- | --- |
| 400 VALIDATION_FAILED / OIDC_FLOW_INVALID | Field/intent không đúng hoặc state/nonce/code/token không hợp lệ; không ghi DB |
| 401 AUTH_REQUIRED / ONBOARDING_REQUIRED | Thiếu session đầy đủ ở route yêu cầu user hoặc thiếu/hết pending ở G02/G03; pending không có quyền nghiệp vụ |
| 401 INVALID_CREDENTIALS | Sai password xác nhận; cùng phản hồi chung, không lộ phương thức đăng nhập |
| 403 CSRF_INVALID / FORBIDDEN | CSRF sai hoặc user không được thực hiện intent; không tự bắt đầu provider flow |
| 403 REAUTH_REQUIRED | Proof thiếu/hết TTL/đã dùng/sai intent/actor/session hoặc chưa có Google stage phù hợp; phải xác nhận lại |
| 409 USERNAME_EXISTS / IDENTITY_CONFLICT / ONBOARDING_ALREADY_COMPLETED | Xung đột đăng ký/liên kết; không lộ user đang giữ identity |
| 409 PASSWORD_NOT_ENABLED / PASSWORD_ALREADY_ENABLED / CONCURRENT_MODIFICATION | Luồng password không phù hợp hoặc credential đã đổi khi chuẩn bị commit |
| 429 RATE_LIMITED / 503 IDENTITY_PROVIDER_UNAVAILABLE | Chạm quota hoặc Google không sẵn sàng; không tự tạo account/session đầy đủ |

Callback lỗi vẫn redirect 302 tới trang lỗi cố định với trạng thái lỗi chung được lưu trong session; frontend chỉ hiển thị lỗi chung, không phản chiếu tham số Google vào HTML. HTTP/code trong bảng áp dụng các API JSON. Không log authorization code, query callback, token, proof, body mật khẩu hay client secret.

## Cấu hình và kiểm chứng

Boot 4 dùng `spring-boot-starter-security-oauth2-client`, version do BOM dự án quản lý; tên starter oauth2-client cũ đã deprecated. Client ID/secret được cấp qua môi trường backend, không đặt trong Vite hoặc commit. Dùng Spring oauth2Login/OidcUserService theo version Boot 4.1.1; cần custom intent handling như trên trước khi coi LINK/REAUTH an toàn. Không cần OAuth2 resource server hoặc JWT ứng dụng chỉ để thêm Google login. [Boot starters](https://docs.spring.io/spring-boot/reference/using/build-systems.html#using.build-systems.starters)

Không lưu bền Google access/refresh tokens cho tính năng hiện tại; transient token/code xử lý phía server theo vòng đời flow, không trả frontend. Default authorized-client storage cần được cấu hình/kiểm tra để không giữ credential provider không cần thiết trong principal/session ứng dụng sau khi hoàn tất. API principal nội bộ chỉ giữ user ID và dữ liệu xác thực tối thiểu; session registry, revocation và kiểm user tồn tại áp dụng giống login local.

Kiểm thử cần đi qua security filters, session, callback và PostgreSQL thật; mock claims hoặc unit test service không chứng minh redirect/cookie/Google Console đúng. Xem [các ca GGL](06-nghiem-thu.md#đăng-nhập-google-và-liên-kết-tài-khoản); smoke thật dùng account Google kiểm thử được chủ sở hữu cho phép, credentials/redirect URI riêng môi trường, không ghi token vào bằng chứng.
