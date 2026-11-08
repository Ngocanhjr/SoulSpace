# Seed demo và tiêu chí nghiệm thu

Đây là kế hoạch kiểm chứng cho implementation tương lai. Không có test, benchmark, kết nối Neon, Docker hoặc Cloudinary thực tế nào được xác nhận PASS bởi việc tạo bộ spec.

## Dữ liệu seed chức năng

Seed runner chạy khi được gọi rõ bằng `app.seed-demo=true`, sau Flyway và trước khi backend nhận request nghiệp vụ. Không tự seed mọi lần khởi động. DEMO_USER_PASSWORD lấy từ môi trường và đạt policy; salt được sinh độc lập cho từng user, không nhúng hash/password demo cố định vào repository.

| Đối tượng | Số lượng | Phân bố yêu cầu |
| --- | --- | --- |
| users | 8 | demo01–demo08; UUID cố định của fixture, profile khác nhau, avatar mặc định. |
| blogs | 24 | 18 public, 6 private; phân bố nhiều tác giả, mỗi bài có text. |
| comments | 48 | 24 roots, 24 replies; có chuỗi ít nhất 10 cấp để kiểm bỏ giới hạn cũ, nhiều người tham gia. |
| blog_likes | 40 | Cặp user/blog khác nhau; private chỉ có like của chủ bài. |
| comment_likes | 24 | Có root và reply; chỉ actor hợp lệ đối với bài. |
| blog_shares | 12 | Cặp user/blog khác nhau, chỉ bài public lúc seed. |
| media_assets, blog_images, media_cleanup_jobs | 0 | Seed cơ bản không phụ thuộc Internet/Cloudinary; media được tạo bằng fixture riêng khi kiểm tra. |

Tổng ban đầu phải khớp: SUM blogs.likes_count=40, SUM blogs.comments_count=48, SUM blogs.shares_count=12, SUM comments.likes_count=24. Không set counters trực tiếp; seed dùng insert/procedure để trigger được chạy như luồng thật. Content/ID/quan hệ/thời gian fixture phải tái lập, ví dụ mốc UTC cố định 24/10/2026; không dựa vào random không seed. Hash password có salt ngẫu nhiên nên không yêu cầu bytes hash giống nhau.

Seed tạo toàn bộ dữ liệu nghiệp vụ trong một transaction và rollback toàn bộ nếu lỗi. Chỉ cho target sạch; nếu bộ fixture đã hoàn tất nguyên trạng thì báo đã seed và không ghi đè. Nếu database có dữ liệu khác hoặc fixture dở dang thì dừng với thông báo rõ. Không xóa user/bài/job để làm chỗ cho seed. Neon phải dùng branch/database dành riêng cho demo hoặc kiểm thử, với MEDIA_NAMESPACE riêng.

## Kiểm thử tự động khi triển khai

Chạy integration với PostgreSQL thật cùng major, không dùng H2 để thay thế trigger/procedure/locking. Có thể dùng Testcontainers cho local test; dependency test chỉ bổ sung khi triển khai test suite. Mock provider ở ranh giới Cloudinary cho phần lớn ca lỗi, và có một bộ smoke media thật riêng.

Kiểm thử API qua đầy đủ security filter/session/CSRF, không chỉ gọi trực tiếp service. Các ca kiểm lỗi nghiệp vụ cần token hợp lệ để không bị 403 che mất kết quả. Các test thay dữ liệu dùng database riêng, không trỏ Neon dùng chung mặc định.

| Mã | Kịch bản | Kết quả bắt buộc |
| --- | --- | --- |
| AUTH01 | Signup với dob; signup với birthday; cả hai trùng/khác nhau | Alias hoạt động; khác nhau 400; response không có password/hash. |
| AUTH02 | Username trùng/case khác nhau và hai signup đồng thời | Canonical nhất quán; một account duy nhất, request còn lại 409. |
| AUTH03 | Đúng/sai password, username không tồn tại, vượt rate limit | 200 AuthResponse hoặc 401 chung; 429 và Retry-After; không lộ trạng thái tài khoản. |
| AUTH04 | Login rồi gọi me ở request mới/reload browser | Đăng nhập thật được giữ qua cookie; không phụ thuộc localStorage. |
| AUTH05 | Cookie trước/sau login; CSRF trước/sau login/logout | Session ID thay đổi; token mới dùng được; token cũ không làm phát sinh ghi trái phép. |
| AUTH06 | POST/PATCH/multipart không có/sai CSRF | 403, không đổi DB và không bắt đầu upload. |
| AUTH07 | Idle timeout, tuổi tối đa, logout, restart backend | Session không còn được chấp nhận theo policy; UI clear trạng thái đúng mã lỗi. |
| AUTH08 | Đổi password và xóa account khi đang có nhiều session | Thu hồi tất cả session; password mới hoạt động, password cũ không hoạt động. |
| ACL01 | Đổi userId body/path thành người khác | 403 ở route owner/actor; không giả tác giả, không chỉnh profile hoặc xóa tài sản của người khác. |
| ACL02 | Guest/user khác đọc chi tiết/comments/ảnh của bài private | 404 tại tài nguyên private; không lộ qua feed/profile/shared list. |
| ACL03 | Public chuyển private rồi public trở lại | Share bị ẩn rồi hiện theo nguồn; nội dung cập nhật hiện đúng; ảnh dùng cùng rule. |
| USR01 | PATCH chỉ fullname, chỉ dob, username không đổi, clear dob | Field absent không mất; null/blank xử lý đúng hợp đồng. |
| USR02 | Gửi role/counter/passwordHash hoặc URL avatar tùy ý | 400, không mass assign; profile công khai có dob=null. |
| BLOG01 | Text-only, image-only, text+ảnh, rỗng hoàn toàn | Luồng hợp lệ tạo được; empty bị chặn ở service và constraint cuối transaction. |
| BLOG02 | B14 sửa text/ảnh/published cùng một request | Cùng commit hoặc cùng rollback; published false không bị nhầm với absent. |
| BLOG03 | removeImagesUrl của bài khác/URL lạ | 400, DB và media bài khác không đổi. |
| BLOG04 | Upload thành công một phần/toàn bộ lỗi | Response arrays đúng; không tạo bài rỗng; receipt không bị thất lạc. |
| CMT01 | Root/reply tạo thành chuỗi ít nhất 10 cấp; parent khác blog hoặc không tồn tại | Reply cấp 6 trở đi hợp lệ, tree/count đúng; parent sai bài hoặc không tồn tại bị chặn. |
| CMT02 | Hai bản sửa cùng comment với cùng expectedVersion | Một bản thành công, bản cũ 409 CONCURRENT_MODIFICATION; không ghi đè nội dung mới. |
| CMT03 | Xóa riêng comment có replies và likes | Node giữ nguyên ID/parent; content=NULL/isDeleted=true, likes của node bị xóa, replies và likes của con còn sống được giữ; count giảm đúng 1. |
| CMT04 | Xóa lặp với body hợp lệ và expectedVersion cũ | 200 sau kiểm tác giả/quyền xem bài; version/updatedAt/count không đổi lần nữa. |
| CMT05 | Người khác hoặc chủ bài sửa/xóa comment không do mình viết trên bài public | 403; chỉ tác giả được thao tác. |
| CMT06 | Reply/like vào node đã xóa; reply vào con chưa xóa của node đó ở nhánh sâu | Node đã xóa trả 409 COMMENT_DELETED; con còn sống được reply không phụ thuộc độ sâu, kể cả có ancestor là placeholder. |
| CMT07 | Thiếu/sai kiểu/âm expectedVersion, content không hợp lệ, gửi field cấu trúc | 400, không thay dữ liệu; PATCH node đã xóa với body hợp lệ trả 409 COMMENT_DELETED. |
| CMT08 | Like rồi đọc lại comment; sửa content rồi đọc lại | Like không đổi version/updatedAt; sửa content tăng version, giữ createdAt/parent và replies. |
| CMT09 | Hiển thị chuỗi ít nhất 10 cấp trên desktop/mobile và trả lời node sâu | UI thụt lề tối đa 3 tầng, chỉ rõ người được trả lời, không cắt/mất node; request vẫn gửi ID của parent thật, không ép về root/ancestor. |
| LIKE01 | true hai lần, false hai lần cho bài/comment | Tối đa một row; counter không tăng thừa hoặc âm; response phản ánh trạng thái cuối. |
| SHR01 | Share một lần, share trùng, unshare lặp | Row/count đúng; duplicate 409; unshare lặp 200. |
| DEL01 | Xóa blog có comments nhiều cấp, cả placeholder, likes, shares, ảnh | Cascade toàn bộ dữ liệu thuộc bài; placeholder không làm giảm counter lần hai; enqueue ảnh trong cùng commit. |
| DEL02 | Xóa user có bài riêng và comment ở bài người khác có replies | Bài của user/cây thuộc bài bị xóa; comment ở bài còn sống thành placeholder, author_id=NULL, không còn content/likes của node; giữ replies của người khác; counters đúng, sessions thu hồi. |
| DEL03 | Password xác nhận sai hoặc đổi password đồng thời với xóa | Không xóa dữ liệu; password đã kiểm tra phải còn tương ứng bản hash khi khóa account. |

## Media và chịu lỗi

| Mã | Kịch bản | Kết quả bắt buộc |
| --- | --- | --- |
| MED01 | MIME giả, HTML/SVG đổi extension, ảnh lỗi, file/ảnh quá lớn | Chặn trước provider/DB attachment, 413/415/400 đúng tình huống. |
| MED02 | Truy cập unsigned Cloudinary asset mới | Không đọc được asset authenticated; API đúng quyền vẫn phục vụ được. |
| MED03 | Lấy API URL ảnh rồi đổi bài sang private/xóa | Guest/user khác bị 404 ở lần đọc quyền tiếp theo; không dựa vào việc UI đã giấu ảnh. |
| MED04 | Đổi avatar rồi DB commit thất bại | Avatar cũ vẫn dùng được; asset mới được enqueue/quét thu hồi. |
| MED05 | Cleanup provider lỗi/timeout/backend crash sau claim | Nghiệp vụ đã commit vẫn giữ; lease/retry phục hồi; thất bại hoặc hết lease ở lượt thứ 5 thành DEAD, không có lượt tự động thứ 6. |
| MED06 | Receipt PENDING bị bỏ dở, có/không có metadata xác nhận | Bộ quét phân biệt hai trường hợp theo metadata, đối soát/enqueue đúng; timeout upload không bị báo thành công. |
| MED07 | Xóa user rồi cleanup worker chạy | Receipt/job vẫn có đủ public_id; không FK mất queue theo user. |
| MED08 | Hai worker claim cùng job, destroy not found, receipt đến muộn | Không ghi đè asset; xử lý lặp an toàn, không gắn lại tài nguyên đã bị xóa. |
| MED09 | Queue clone sang môi trường có MEDIA_NAMESPACE khác | Worker từ chối destroy; không xóa ảnh của môi trường gốc. |
| MED10 | Mất Cloudinary/Internet nhưng local Docker vẫn chạy | Core text đọc/ghi được; ảnh lỗi có placeholder, upload không được giả báo thành công. |
| MED11 | Attach asset sai owner/purpose, đã ATTACHED hoặc DELETE_PENDING | Service có khóa từ chối attach mới; không sinh liên kết thứ hai, không gọi provider trong transaction. |
| MED12 | Hai lượt claim nối tiếp sau hết lease, worker cũ trả thành công hoặc lỗi muộn | Chỉ cặp claim hiện tại ghi được; worker cũ không xóa receipt/job, không ghi đè status/attempts/last_error hoặc retry thay worker mới. |
| MED13 | Crash/hết lease đến lượt thứ 5 và provider lỗi đủ 5 lượt | DEAD, lease=NULL; không có claim tự động thứ 6. Retry thành PENDING ở lượt 1–4 phải xóa lease. |
| MED14 | Enqueue trùng job RUNNING; requeue DEAD rõ ràng rồi kết quả cũ đến | Enqueue không reset claim; requeue có khóa/namespace/state hợp lệ, attempts=0 và lease=NULL; kết quả mang lease cũ không ghi được. |

Smoke Cloudinary thật chỉ tạo/xóa asset mới trong namespace kiểm thử; không dùng URL/account data legacy làm mục tiêu xóa. Giữ kết quả tích hợp thật riêng với kết quả mock. Bản sao ảnh đã được người dùng tải trước đó hoặc cache bên ngoài không thể được bảo đảm thu hồi bởi test này.

## Concurrency và transaction

Các ca đồng thời chạy bằng nhiều connection/transaction thực, dùng barrier bắt đầu cùng thời điểm. Mỗi test có fixture riêng để expected count không phụ thuộc seed đã bị các test khác sửa.

| Mã | Tải kiểm tra | Kỳ vọng |
| --- | --- | --- |
| CON01 | 20 request cùng user/blog, desired=true | Một quan hệ, counter tăng đúng 1. |
| CON02 | 20 user khác nhau like cùng bài; sau đó unlike | Tăng đúng 20 rồi về baseline. |
| CON03 | Cùng desired=false nhiều lần trên bài/comment | Không còn quan hệ, counter không âm. |
| CON04 | true/false trộn đồng thời | Không ép kết quả cuối không xác định; quan hệ và count phải khớp sau quiescence. |
| CON05 | 20 request tạo comment/reply cùng bài, gồm parent ở cấp 5 hoặc sâu hơn | Không mất node, count tăng đủ; parent/cây hợp lệ, không vòng và không từ chối do độ sâu. |
| CON06 | Share đồng thời cùng một cặp | Một 200, các request còn lại 409; shares_count tăng 1. |
| CON07 | Ghi tương tác đồng thời xóa user/blog hoặc đổi private | Không có orphan, không ghi trái quyền sau điểm kiểm soát; counter đúng; lỗi được map rõ. |
| CON08 | Deadlock/serialization failure được gây có kiểm soát | Retry chỉ transaction đã rollback, tối đa 2 lần; không lặp upload và không áp dụng tác dụng DB hai lần. |
| CON09 | Sửa và xóa cùng comment với cùng expectedVersion | Chỉ thao tác còn khớp version được ghi; không phục hồi content sau xóa; bản sửa cũ nhận 409. |
| CON10 | Xóa parent đồng thời thêm reply hoặc like | Tuần tự hóa theo khóa; không có tương tác mới được chấp nhận sau khi node đã bị xóa tại điểm kiểm tra. Reply hợp lệ đã commit trước xóa vẫn được giữ. |
| CON11 | Xóa user đồng thời tài khoản đó tạo comment/like hoặc finalize upload | Khóa actor tuần tự hóa thu thập dữ liệu; không có comment mới bị SET NULL nhưng còn content, orphan hoặc receipt bị attach sau xóa. |
| CON12 | Attach asset đồng thời scanner enqueue hoặc worker cleanup | Một phía thắng theo khóa/state; ATTACHED không bị destroy, DELETE_PENDING không được attach; không có vòng khóa job -> asset xuyên transaction claim. |

Chạy query đối soát cả bốn loại counter sau các ca trên khi không còn request đang chạy. Bắt buộc 0 sai lệch và không có duplicate cặp khóa, parent khác blog/không tồn tại, vòng trong cây hoặc ảnh ATTACHED không còn liên kết. Không dùng độ sâu làm điều kiện loại node. Log mã SQLSTATE khi cần điều tra, không log nội dung riêng tư.

Đối soát comments_count chỉ COUNT comments.deleted_at IS NULL. Comment placeholder có thể còn trong cây nhưng phải có content=NULL, không còn like, không bị INNER JOIN users làm mất node. Các ca trên kiểm cả version cũ từ client và SQL trigger/native query, tránh chỉ thử hai transaction cùng đọc bản mới trên server.

## Ràng buộc schema bắt buộc khi triển khai

| Mã | Kiểm tra trên PostgreSQL thật | Kết quả bắt buộc |
| --- | --- | --- |
| DB01 | Comment hoạt động content=NULL/blank; placeholder còn content; author_id=NULL nhưng chưa xóa | CHECK từ chối, rollback; trạng thái hợp lệ được ghi. |
| DB02 | Bỏ qua timestamp khi insert like/share; explicit NULL | Database đặt now() khi thiếu; NULL bị NOT NULL từ chối. |
| DB03 | RUNNING không lease; PENDING/DEAD có lease; attempts âm hoặc >5 | CHECK từ chối; claim và chuyển trạng thái hợp lệ được ghi. |
| DB04 | Trùng vị trí ảnh, position=NULL/ngoài 0–9, gắn cùng asset hai bài | NOT NULL/PK/UNIQUE/CHECK từ chối; không gỡ index/constraint để chạy demo. |
| DB05 | Sửa ID/blog_id/parent_id comment, đổi author sang người khác, tự tham chiếu hoặc cố tạo vòng | Trigger/FK/CHECK từ chối; đổi author sang NULL chỉ hợp lệ khi nội dung đã xóa. |
| DB06 | Metadata PENDING thiếu một phần; ATTACHED thiếu xác nhận; format ngoài jpeg/png hoặc size <= 0 | CHECK từ chối metadata một phần/sai; receipt PENDING chưa xác nhận được phép để cả ba trường NULL; ATTACHED phải có đủ metadata. |
| DB07 | Xóa trực tiếp user còn comment hoạt động ở bài người khác, chưa xóa nội dung | Không để tồn tại comment author=NULL nhưng còn nội dung; transaction thiếu bước chuẩn bị thất bại, service đúng quy trình DEL02 thành công. |
| DB08 | INSERT tuần tự chuỗi ít nhất 10 cấp bằng SQL với parent đúng bài và còn hoạt động | Commit thành công, parent_id đúng, comments_count khớp số node hoạt động; không phát sinh lỗi giới hạn độ sâu. |

DB01–DB07 kiểm các invariant có thể thực thi bằng constraint/trigger; ownership/visibility và xác nhận claim còn phải kiểm qua các luồng service/procedure đã khóa. Không coi FK đơn là bằng chứng bảo vệ mọi phép ghi SQL quản trị.

## View bài công khai

| Mã     | Kịch bản                                         | Điều kiện đạt                                                                                                                                                           |
|--------|--------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| VIEW01 | Tạo bài public/private; lọc author_id            | Chỉ bài public xuất hiện, đúng tác giả, một hàng/blog.                                                                                                                  |
| VIEW02 | Sửa content, username/fullname/avatar ở bảng gốc | SELECT lại lấy thông tin mới, không refresh view.                                                                                                                       |
| VIEW03 | Public -> private -> public                      | Ẩn rồi xuất hiện lại; blogs/blog_shares không bị xóa do đổi visibility.                                                                                                 |
| VIEW04 | Share/unshare bài public                         | Không sinh thêm hàng feed hay đổi tác giả; shares_count phản ánh trigger.                                                                                               |
| VIEW05 | Xóa bài                                          | View không còn trả hàng tương ứng.                                                                                                                                      |
| VIEW06 | Runtime đọc/ghi view, kiểm cột                   | SELECT được; không có INSERT/UPDATE/DELETE, không có password_hash/dob/provider IDs.                                                                                    |
| VIEW07 | Tích hợp API feed/trang công khai với repository | Repository thật đọc v_public_blog_feed, giữ contract BlogDTO, sort/phân trang và batch ảnh/trạng thái viewer; trang riêng tư của chính mình dùng query kiểm quyền khác. |

VIEW01–VIEW06 có smoke SQL tại [public_blog_feed.sql](../../infra/postgres/tests/public_blog_feed.sql); VIEW07 chưa
triển khai Java. Bằng chứng view phải có định nghĩa, query sử dụng thật và kết quả trước/sau thay đổi dữ liệu, không chỉ
ảnh chụp CREATE VIEW thành công. Schema V1 có 11 bảng/65 cột và thêm 1 view/11 cột; view không được tính là bảng lưu trữ
thứ 12.

## Năm nhóm bằng chứng SQL

Mỗi bằng chứng phải chỉ ra nghiệp vụ/hàm legacy trong [bảng ánh xạ SQL](03-postgresql.md), service/query mới, migration
V1–V5 liên quan, mã kịch bản kiểm chứng và file kết quả thực tế. Với index, lưu query cùng tham số; với
view/trigger/procedure/transaction, lưu trạng thái trước/sau và kết quả đối soát. Đây là yêu cầu cho báo cáo tương lai,
không phải kết quả đã chạy.

| Nhóm             | Cách chứng minh                                                               | Điều kiện đạt                                                                                                     |
|------------------|-------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| Index            | EXPLAIN (ANALYZE,BUFFERS) trước/sau tạo index bổ sung trên cùng dataset/query | Kết quả logic như nhau; lưu plan và số đo thật, giải thích planner; không tạo index thừa chỉ để có ảnh.           |
| Trigger          | Insert/delete/duplicate/cascade và chuyển deleted_at trên comments            | Counters khớp COUNT hoạt động; không có đoạn Java cùng tăng counter hoặc giảm placeholder lần hai.                |
| Stored Procedure | CALL bốn procedure với desired/actor hợp lệ và không hợp lệ                   | Quyền và trạng thái đúng; idempotency/duplicate/unshare theo contract.                                            |
| Transaction      | Gây lỗi sau bước đầu trong share, edit hoặc delete; chạy ROLLBACK             | Không có thay đổi DB một phần và không có job cleanup được commit nhầm.                                           |
| View             | Chạy VIEW01–VIEW07 với V5, đọc public feed và bài công khai theo tác giả      | Cùng định nghĩa JOIN/filter, phản ánh dữ liệu hiện tại, không nhân bản bài khi share, repository thực sự sử dụng. |

Benchmark index dùng database local riêng, tạo dữ liệu 1.000 users, 10.000 blogs, 10.000 comments và 50.000 blog likes, có seed tái lập và độ chọn lọc public/author rõ. Đây là kích thước thiết kế, không phải số liệu đã chạy. Giữ PK/unique/constraint ở baseline; đo trước/sau thêm index nghiệp vụ trên bản dữ liệu benchmark, không gỡ index ở database đang demo/dùng chung. Chạy ANALYZE, warmup và nhiều lượt đo; ghi CPU/RAM/Postgres/image/commit/query/parameters/row count.

Đo index local và latency kết nối/query Neon thành hai báo cáo để không quy lợi ích/chi phí mạng cho index. Không ép planner dùng index với dữ liệu nhỏ. Không đặt mục tiêu p95 chưa được đo thành kết quả đã đạt; ghi p50/p95/query plan và hạn chế của phép đo.

## Neon và local phải được nghiệm thu riêng

| Mã | Kiểm tra | Kết quả cần có |
| --- | --- | --- |
| ENV01 | Flyway mới hoàn toàn trên Docker và Neon sạch | Cùng version/checksum/schema/trigger/procedure, Hibernate validate thành công. |
| ENV02 | Runtime role thử DDL, đọc Flyway secrets/history ngoài quyền | Không có quyền DDL/sửa history; migrator chạy được migration. |
| ENV03 | Neon verify-full/channel binding, certificate/hostname không hợp lệ | Kết nối thật hợp lệ thành công; kiểm chứng negative TLS bằng fixture/proxy kiểm thử an toàn, không hạ validation. |
| ENV04 | Neon reconnect/timeout hoặc mất response khi commit | Báo trạng thái chính xác, không replay write chưa biết kết quả, không tự chuyển sang Docker. |
| ENV05 | Đổi db-local sang db-neon | Dữ liệu/namespace riêng, session cũ hết hiệu lực sau restart; không tự import/sync dữ liệu. |
| ENV06 | LAN HTTPS từ thiết bị khác | Cookie Secure, CSRF hoạt động, ảnh/private đúng quyền; browser không kết nối database/localhost của thiết bị. |
| ENV07 | Kiểm file serving/bundle và cấu hình mạng | Secret/cert/private key không tải được qua frontend; Docker DB/backend bind loopback. |

Không chạy negative TLS bằng cách sửa endpoint hoặc credential của database dùng chung. Ghi host/database/branch đã kiểm ở mức không chứa secret, major version và trạng thái cold/warm; cấu hình URL thành công không được dùng thay bằng chứng kết nối.

## Regression frontend

Thực hiện lần lượt signup, login, reload, xem feed/profile/search hiện tại, tạo text và ảnh, sửa bài và quyền xem, avatar, like/unlike, comment/reply/comment like, sửa/xóa comment, share/unshare, logout, hết session và xóa tài khoản. Kiểm Guest, tác giả comment, chủ bài khác tác giả và user khác bằng browser/session riêng. Danh sách vẫn là array; placeholder/tác giả null được dựng đúng, không mất replies; ảnh relative URL hoạt động qua proxy. Tìm kiếm nâng cao theo comment nằm ngoài mốc chốt database này.

Đặc biệt kiểm SignUp không còn log password, SignIn xử lý 401/429, UI không coi localStorage là auth; khi bấm like/unlike liên tục, biểu tượng đổi tức thì, request được gộp theo từng mục, tối đa một request chạy cùng lúc và kết quả cuối khớp ý định cuối cùng. Response cũ không ghi đè giao diện mới; lỗi khôi phục trạng thái đã xác nhận hoặc cho retry desired-state an toàn. Sửa bài không cần hai commit và dialog xóa account không làm lộ password.

## Bằng chứng và điều kiện bàn giao

Giai đoạn AI có các ca TOP-T01–TOP-T13
tại [nghiệm thu phân loại chủ đề](07-phan-loai-chu-de.md#triển-khai-và-nghiệm-thu-riêng), gồm model lỗi không chặn đăng
bài, MANUAL không bị ghi đè, kết quả cũ bị loại và quyền bài private. Khi triển khai AI, kiểm riêng schema 11 bảng/65
cột và tổng 37 endpoint; Definition of Done bên dưới vẫn dành cho migration core.

Lưu báo cáo tương lai dưới `docs/verification/<run-id>/` với commit, thời gian UTC, môi trường, version, lệnh thực chạy, PASS/FAIL/chưa kiểm, test report, query plan và ảnh/video demo khi cần. Các đường dẫn này là đầu ra dự kiến, chưa có báo cáo ở thời điểm lập spec.

Definition of Done:

1. Đủ 35 endpoint theo hợp đồng (29 legacy + 6 bổ sung), DTO/caller đã cập nhật thống nhất.
2. Cả Docker và Neon sạch chạy cùng migration và core smoke; không cần Mongo cũ.
3. Session/CSRF/owner/privacy/upload/cleanup, schema DB01–DB07 và các ca comment/version/claim đồng thời có kiểm chứng; không dùng chốt thiết kế thay bằng chứng thực thi.
4. Có bằng chứng cho Index, View (VIEW01–VIEW07), Trigger, Procedure, Transaction và đối soát 0 sai lệch.
5. Media thật và LAN HTTPS được ghi nhận riêng, không suy từ mock hoặc localhost.
6. Runbook dùng đường dẫn tương đối, env rõ ràng, không lộ secret; giới hạn session, pagination và media proxy được ghi lại.
7. Source legacy và dữ liệu ngoài môi trường kiểm thử được giữ nguyên; không gộp công việc AI vào mốc nghiệm thu này.

Nếu còn ca bắt buộc chưa chạy hoặc thất bại thì ghi rõ phần đó chưa nghiệm thu, không đánh dấu toàn bộ migration hoàn tất.
