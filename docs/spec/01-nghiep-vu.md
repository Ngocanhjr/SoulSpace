# Nghiệp vụ migration backend

## Phạm vi và vai trò

Giữ đăng ký, đăng nhập, xem/sửa/xóa hồ sơ, avatar, danh sách bài viết, bài công khai/riêng tư, chỉnh sửa bài và ảnh, comments/replies, like bài/bình luận, share/unshare và tìm kiếm hiện tại của frontend. Bổ sung sửa/xóa comment của chính tác giả theo quyết định ngày 27/10/2026. Tìm kiếm nâng cao hoặc tìm theo comment thuộc thiết kế tính năng riêng; không là điều kiện chốt database lần này. Chưa bổ sung search backend, theo dõi, lưu bài, chat, admin hay AI.

| Tài nguyên hoặc thao tác | Guest | Người đăng nhập | Chủ tài nguyên |
| --- | --- | --- | --- |
| Feed và bài công khai, comments, ảnh của bài công khai | Được xem | Được xem | Được xem |
| Hồ sơ công khai, avatar, danh sách share công khai | Được xem | Được xem | Được xem |
| Ngày sinh | Không thấy | Chỉ thấy của mình | Được xem của mình |
| Bài riêng tư, comments và ảnh đi kèm | Không thấy | Không thấy của người khác | Được xem |
| `/blogs/{userId}/all`, hồ sơ phục vụ chỉnh sửa | 401 | 403 nếu khác user | Được xem |
| Đăng bài, comment, reply, like, share | 401 | Được nếu tài nguyên được phép xem | Được; share chỉ áp dụng bài công khai |
| Sửa/xóa bài, đổi quyền công khai | 401 | 403 nếu khác chủ | Được |
| Sửa/xóa comment | 401 | 403 nếu khác tác giả | Chỉ tác giả, đồng thời có quyền xem bài; chủ bài không được sửa/xóa comment của người khác |
| Sửa/xóa hồ sơ và avatar, unshare | 401 | 403 nếu khác chủ | Được |
| Upload độc lập và xóa ảnh chưa gắn vào bài | 401 | Chỉ tài sản của mình | Được theo trạng thái asset |

Tài nguyên private được che bằng 404 ở các route đọc/ảnh/tương tác để không xác nhận sự tồn tại. Route chỉ rõ chủ tài khoản như `/users/{id}` khi sửa, hoặc `/blogs/{userId}/all`, trả 403 khi người đăng nhập dùng ID của người khác. Guest gọi thao tác cần đăng nhập nhận 401 trước khi xử lý nghiệp vụ. Server luôn lấy actor từ session; `userId` do client gửi chỉ để đối chiếu tương thích.

## Tài khoản và hồ sơ

**USR01 đăng ký.** Nhận username, fullname, ngày sinh và mật khẩu; canonical hóa username bằng trim và lowercase, kiểm trùng ở constraint; băm mật khẩu rồi tạo user. Lỗi trùng trả 409. Không tự đăng nhập sau đăng ký, phù hợp luồng frontend hiện tại. Avatar mặc định là tài nguyên tĩnh của dự án, không gắn với tài khoản Cloudinary legacy.

**USR02 đăng nhập.** Xác thực bằng Spring Security; tạo/đổi session ID và trả JSON tương thích. Sai username hoặc mật khẩu dùng cùng thông báo, 401. Reload frontend dùng `/auth/me` để lấy trạng thái thật. Đăng xuất gọi backend để hủy session rồi mới xóa trạng thái UI.

**USR03 cập nhật.** PATCH chỉ thay field hiện diện; field vắng mặt giữ nguyên. Username không đổi vẫn hợp lệ. Fullname và username có giới hạn; ngày sinh nếu có phải trước ngày hiện tại ở `Asia/Ho_Chi_Minh`. PUT giữ route cũ, cập nhật các field được cung cấp; đổi mật khẩu cần `currentPassword`, kiểm chính sách mới và hủy các session đang đăng nhập. Frontend chuyển về đăng nhập sau khi đổi mật khẩu. Hash/verify thực hiện ngoài transaction dài; khi khóa user để commit thao tác nhạy cảm phải kiểm password_hash vẫn là bản đã xác thực, nếu bị đổi đồng thời thì trả 409 và yêu cầu xác nhận lại.

`userAvatarUrl` là dữ liệu đọc. Nếu form chỉnh sửa gửi lại đúng URL hiện tại thì bỏ qua; yêu cầu thay URL tùy ý bị từ chối. Đổi avatar bằng endpoint upload dành riêng. Không mass assign entity từ JSON. Mật khẩu/hash không xuất hiện trong response, log hay localStorage.

**USR04 hiển thị tác giả.** Username/avatar của bài, comment và share được đọc từ user hiện tại bằng quan hệ; cập nhật hồ sơ hiển thị ở các nơi này mà không cần copy dữ liệu sang từng bài.

## Bài viết và ảnh

**BLOG01 tạo.** Người đăng nhập tạo bài từ content, published và files. Content tối đa 5.000 ký tự; bài cần có content sau trim hoặc ít nhất một ảnh upload thành công. Thu thập ảnh ngoài transaction; tạo blog và liên kết các ảnh đã upload trong một transaction. Giữ response `successUrls`, `failedFiles` cho trường hợp thành công một phần.

Nếu không còn content và tất cả ảnh đều upload lỗi thì trả 502, không tạo bài rỗng. Nếu có content hợp lệ, bài vẫn được tạo với ảnh thành công và danh sách ảnh lỗi. File sai định dạng/kích thước bị chặn trước upload, không biến lỗi validation thành thành công một phần.

**BLOG02 xem.** Feed chỉ có bài `published=true`, sắp theo `createdAt DESC, id DESC`. Trang công khai của một user cũng chỉ trả bài public. Trang `/all` của chính chủ gồm public/private. Các danh sách hiện tại giữ JSON array; không tự cắt dữ liệu bằng limit ngầm. Đây là giới hạn quy mô của demo; pagination thuộc thay đổi API riêng khi mở rộng.

**BLOG03 sửa.** Route multipart giữ tên legacy. Content/published chỉ đổi khi có field. `removeImagesUrl` phải trỏ đến ảnh thuộc bài này, URL sai hoặc của bài khác trả 400 và không thay database. Số ảnh sau thêm/xóa không quá 10. Upload ảnh mới ngoài transaction, sau đó khóa bài, đọc lại trạng thái hiện tại, validate và commit metadata/quan hệ/job dọn ảnh cũ cùng nhau.

Nếu thao tác sửa làm bài không còn content và ảnh, trả 400. Có lỗi upload một phần thì vẫn cập nhật phần hợp lệ; các lỗi mạng được trả bằng `failedUploadFiles`. Hết quyền hoặc bài đã xóa trước khi commit thì hủy cập nhật, đưa ảnh đã upload vào cleanup. `updatedAt` phản ánh lần sửa nội dung, ảnh hoặc published; tương tác không thay thời gian này.

**BLOG04 riêng tư.** `published=false` chỉ chủ bài xem được. Thay public sang private có hiệu lực tại truy vấn quyền tiếp theo, gồm feed, chi tiết, comment, share và API ảnh. Like/comment/share đã có được giữ trong database; share bị ẩn và hiện trở lại nếu bài được public lại. Người đã tải nội dung trước đó vẫn có thể giữ bản sao ngoài hệ thống.

## Bình luận và phản hồi

**CMT01** Content sau trim dài 1–2.000 ký tự. Tạo comment/reply chỉ khi actor được xem bài. Chủ bài có thể comment trên bài private của mình. Reply phải có parent thuộc đúng bài và không vượt 5 cấp; vi phạm trả 400. Không lưu depth; trigger đếm chuỗi cha, kể cả placeholder, và chặn đổi blog_id/parent_id sau tạo. Khóa blog rồi parent và kiểm parent chưa xóa; reply trực tiếp vào placeholder trả 409 COMMENT_DELETED. Có thể reply vào comment con chưa xóa. Client không được chọn author, ID hay bộ đếm.

**CMT02** Danh sách trả cây `replies`, root/replies tăng dần theo `createdAt, id`. `commentsCount` chỉ tính node có deleted_at IS NULL, gồm cả replies còn hoạt động. Placeholder vẫn được trả để giữ cây nhưng không tính vào counter. Đọc comments của bài private dùng cùng kiểm quyền như đọc bài. Like chỉ áp dụng cho node chưa xóa; node đã xóa trả 409 COMMENT_DELETED.

**CMT03 sửa.** Chỉ tác giả được sửa nội dung comment của mình khi còn quyền xem bài. Gửi content và expectedVersion; version không khớp trả 409 CONCURRENT_MODIFICATION, không tự ghi đè/retry bằng version mới. Thành công chỉ cập nhật node đó, updated_at và tăng version; parent/blog, created_at và các replies được giữ.

**CMT04 xóa.** Chỉ tác giả được xóa riêng comment, lần đầu phải khớp expectedVersion. Trong cùng transaction xóa các likes của node, đặt content=NULL/deleted_at, cập nhật updated_at/version và giữ replies. Gọi xóa lặp với body hợp lệ, đúng tác giả và quyền xem bài trả 200 dù expectedVersion đã cũ; không đổi thêm version/time/count. UI hiện "Bình luận đã xóa" từ isDeleted, không lưu câu này vào content. Không có chức năng khôi phục nội dung.

**CMT05 tác giả đã xóa.** Khi xóa tài khoản, comment ở bài người khác được xóa nội dung và gỡ author_id trong cùng transaction. Response userId/username/userAvatarUrl của node đó là null; frontend hiển thị tác giả đã xóa và giữ replies. Backend dùng LEFT JOIN users để không làm mất node. Xóa riêng comment khi tài khoản vẫn tồn tại giữ liên kết tác giả; chỉ content bị xóa.

## Like có trạng thái mong muốn

**LIKE01** Body bắt buộc có boolean `liked`. `true` bảo đảm có một quan hệ like; `false` bảo đảm không còn quan hệ. Tối đa một like cho mỗi user/bài hoặc user/comment bằng khóa database. Response trả trạng thái và số đếm đã xác nhận.

**LIKE02** Gửi `true` nhiều lần không tăng thêm; gửi `false` nhiều lần không âm. Các request đồng thời được tuần tự hóa tại tài nguyên cần sửa. Hai giá trị trái nhau không được frontend gửi song song: khóa nút trong lúc request đang chạy, chỉ cập nhật UI từ response. Tính idempotent của từng giá trị không bảo đảm thứ tự của hai request trái nhau.

`likedUserIds` giữ array để tương thích UI, nhưng chỉ chứa ID người đang xem khi người đó đã like; Guest nhận `[]`. `likesCount` là tổng số like. UI không suy ra tổng từ độ dài array này.

## Share và unshare

**SHR01** Share chỉ nhận bài công khai, gồm cả bài của chính mình. Lưu quan hệ `(user, blog)` và thời gian share, không lưu snapshot nội dung/tác giả/ảnh. API lắp response từ bài gốc hiện tại. Sửa bài hoặc hồ sơ tác giả thể hiện ngay khi đọc lại share.

**SHR02** Share trùng trả 409 để giữ hành vi thông báo hiện có của frontend. Unique constraint chống tăng số đếm hai lần khi request đồng thời. Unshare chỉ chủ danh sách được làm; nếu quan hệ đã mất, trả 200 với chuỗi thành công để thao tác xóa có thể lặp an toàn.

**SHR03** Danh sách share sắp `sharedAt DESC, blogId DESC` và lọc bài public. `sharesCount` của blog đếm mọi quan hệ còn lưu, kể cả đang ẩn khi bài private. `totalSharedBlogs` sau một lần share đếm các share hiện hiển thị của actor.

## Xóa bài, tài khoản và dữ liệu liên quan

**DEL01 xóa bài.** Trong một transaction, xác nhận chủ sở hữu, khóa bài, lập job cho các ảnh liên quan rồi xóa blog. FK xóa images links, comments/replies, likes và shares liên quan. Job đã commit tiếp tục tồn tại để xóa ảnh Cloudinary. Không gọi Cloudinary trong transaction này.

**DEL02 xóa tài khoản.** Yêu cầu session đúng user và xác nhận lại mật khẩu qua currentPassword. Transaction khóa user trước, thu thập/khóa các bài bị ảnh hưởng theo thứ tự ID. Comment của user ở bài người khác được chuyển thành placeholder và gỡ tác giả; xóa likes của các node đó, giữ replies của người khác. Thu thập assets và lập job ảnh trước khi xóa các bài của user rồi xóa user; like/share do user tạo được xóa theo FK. Toàn bộ cây thuộc bài của user vẫn bị xóa cùng bài. Bộ đếm của tài nguyên còn sống phải khớp dữ liệu hoạt động; không giảm hai lần cho placeholder. Quy tắc khóa actor để không phát sinh ghi mới trong khi xóa tài khoản nằm trong thiết kế PostgreSQL.

**DEL03** Hủy mọi session của tài khoản sau commit; từng request vẫn kiểm tra tài khoản còn tồn tại nên session cũ không tiếp tục được dùng. Không có chức năng khôi phục. Lặp xóa một blog đã không còn trả 404; lặp xóa tài khoản bằng session đã hủy trả 401. UI cần xác nhận hành động xóa tài khoản/bài trước khi gửi.

**DEL04** Thất bại dọn ảnh không phục hồi bài đã xóa. Job retry có giới hạn, giữ trạng thái lỗi để xử lý lại. Ảnh của user khác không bao giờ được xóa chỉ vì một share bị xóa.

## Giới hạn sản phẩm

Một backend giữ session và rate limit trong bộ nhớ; restart cần đăng nhập lại. Danh sách chưa có pagination, cây comment có giới hạn độ sâu và media đi qua backend. Khi mở rộng ngoài demo, cần đo tải trước khi thiết kế session store chung, pagination hoặc cache; không đưa các thành phần đó vào giai đoạn này.
