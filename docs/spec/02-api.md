# Hợp đồng API và tích hợp frontend

Phần dưới là 35 endpoint và DTO của migration core. Giai đoạn AI bổ sung GET /api/v1/categories, PATCH
/api/v1/blogs/{blogId}/categories và categories/categoryMode trên BlogDTO/SharedBlogDTO
theo [hợp đồng phân loại chủ đề](07-phan-loai-chu-de.md#hợp-đồng-api-bổ-sung). Tổng sau mở rộng là 37 endpoint; chưa
triển khai các bổ sung này.

## Quy ước chung

Base path là `/api/v1`. Giữ JSON trực tiếp hoặc array trực tiếp như frontend hiện tại, không bọc thêm `result` hay `data`. Những endpoint xóa trả chuỗi legacy giữ `text/plain; charset=utf-8`. Tất cả response thành công của 29 endpoint legacy tiếp tục dùng HTTP 200. ID là UUID string; timestamp là ISO 8601 UTC; ngày sinh là `YYYY-MM-DD`; field danh sách không có phần tử trả `[]`, không trả null. Giới hạn ký tự trong text/password tính theo Unicode code points để thống nhất với char_length của PostgreSQL.

Cookie session và CSRF áp dụng theo [bảo mật](04-bao-mat-media.md). GET an toàn không thay dữ liệu. POST/PUT/PATCH/DELETE cần CSRF, gồm đăng nhập và đăng ký. Actor được lấy từ session; `userId` legacy nếu được gửi phải trùng actor, khác trả 403. ID sai định dạng trả 400, tài nguyên không tồn tại hoặc private không có quyền đọc trả 404.

Request chỉ bind vào DTO có danh sách field cho phép. Các field compatibility được bỏ qua phải được liệt kê cụ thể; các field ngoài hợp đồng như role, passwordHash, counters hoặc author không được bind và trả 400. Không trả entity JPA trực tiếp.

## Các kiểu response dùng chung

| Kiểu | Field |
| --- | --- |
| `AuthResponse` | `authentication:boolean`, `user:LoginUser hoặc null`, `message:string` |
| `LoginUser` | `id`, `username`, `avatarUrl` |
| `UserResponse` | `id`, `username`, `fullname`, `dob`, `userAvatarUrl`; không có password |
| `UserResponsePatch` | `username`, `fullname`, `dob`, `userAvatarUrl` |
| `BlogDTO` | `id`, `userId`, `username`, `userAvatarUrl`, `content`, `imageContentUrls:string[]`, `likesCount:long`, `commentsCount:long`, `sharesCount:long`, `likedUserIds:string[]`, `published:boolean`, `createdAt`, `updatedAt` |
| `CommentDTO` | `id`, `userId:string hoặc null`, `username:string hoặc null`, `userAvatarUrl:string hoặc null`, `content:string hoặc null`, `likesCount`, `likedUserIds:string[]`, `replies:CommentDTO[]`, `createdAt`, `updatedAt`, `isDeleted:boolean`, `version:long` |
| `SharedBlogDTO` | `blogId`, `originalUserId`, `username`, `userAvatarUrl`, `content`, `imageContentUrls`, `published`, `originalCreatedAt`, `likesCount`, `commentsCount`, `sharesCount`, `sharedAt` |

`LoginUser.avatarUrl` giữ tên mà SignIn đang đọc; profile dùng `userAvatarUrl`. `dob` chỉ có giá trị trong response xem/sửa chính mình; danh sách users và hồ sơ người khác trả null. `likedUserIds` chứa tối đa ID viewer; tổng like lấy từ `likesCount`.

`imageContentUrls` là URL cùng origin dạng `/api/v1/media/{assetId}`. Avatar upload cũng được phục vụ qua route này; avatar mặc định dùng tài nguyên tĩnh của frontend. Các field vẫn là string URL nhưng không lộ URL có chữ ký hoặc thông tin tài khoản Cloudinary.

CommentDTO có isDeleted=true khi deleted_at khác NULL; content khi đó luôn null, likesCount=0 và likedUserIds=[]. Xóa riêng comment giữ thông tin tác giả còn tồn tại; xóa tài khoản gỡ tác giả nên userId/username/userAvatarUrl đều null. Frontend dựng placeholder, không hiển thị chuỗi "null" hoặc bỏ mất node/replies. updatedAt/version chỉ đổi khi sửa nội dung, xóa hoặc gỡ tác giả, không đổi khi like. Không trả deletedAt vì isDeleted đã thể hiện trạng thái cần dùng.

Ví dụ đăng nhập thành công:

```json
{
  "authentication": true,
  "user": {
    "id": "10000000-0000-4000-8000-000000000001",
    "username": "demo01",
    "avatarUrl": "/default-avatar.svg"
  },
  "message": "Log in successfully"
}
```

Ví dụ `BlogDTO`:

```json
{
  "id": "20000000-0000-4000-8000-000000000001",
  "userId": "10000000-0000-4000-8000-000000000001",
  "username": "demo01",
  "userAvatarUrl": "/default-avatar.svg",
  "content": "Một bài viết demo",
  "imageContentUrls": [],
  "likesCount": 2,
  "commentsCount": 3,
  "sharesCount": 1,
  "likedUserIds": ["10000000-0000-4000-8000-000000000002"],
  "published": true,
  "createdAt": "2026-10-24T01:00:00Z",
  "updatedAt": "2026-10-24T01:00:00Z"
}
```

## Session và đăng nhập

| Mã | Method | Route | Request | Thành công và quyền |
| --- | --- | --- | --- | --- |
| A01 | POST | `/api/v1/auth/log-in` | JSON `{username,password}` | `AuthResponse`, tạo session; Guest được gọi với CSRF. |
| A02 | GET | `/api/v1/auth/csrf` | Không có body | `{token,headerName,parameterName}`, tạo token cho session; công khai. |
| A03 | GET | `/api/v1/auth/me` | Không có body | `AuthResponse` với user hiện tại; cần session còn hiệu lực. |
| A04 | POST | `/api/v1/auth/log-out` | Không có body | `{message:"Logged out successfully"}`, hủy session và cookie. |

A01 giữ route legacy. A02–A04 là endpoint bổ sung. A02 trả `headerName="X-CSRF-TOKEN"`, `parameterName="_csrf"`; client gửi token ở header cho cả JSON và multipart. Token không lưu bền trong localStorage. Lấy lại token sau đăng nhập hoặc đăng xuất vì session/token có thể đã đổi.

Sai thông tin đăng nhập trả 401, gồm `authentication=false`, `user=null`, `message="Invalid username or password"` và các field lỗi chung. Không phân biệt tài khoản không tồn tại, sai password hoặc account đã xóa. Hết session ở A03 trả lỗi `AUTH_REQUIRED` 401. Logout không có session xác thực vẫn có thể kết thúc bằng 200 nếu CSRF hợp lệ; CSRF sai luôn trả 403.

## Người dùng

| Mã | Method | Route | Request | Thành công và quyền |
| --- | --- | --- | --- | --- |
| U01 | POST | `/api/v1/users/sign-up` | JSON `{username,password,fullname,dob? hoặc birthday?}` | `UserResponse`, chưa đăng nhập; Guest có CSRF. |
| U02 | GET | `/api/v1/users` | Không có body | `UserResponse[]`, công khai; dob đều null. |
| U03 | GET | `/api/v1/users/{userId}/shared-blogs` | UUID path | `SharedBlogDTO[]`, chỉ các bài còn public. |
| U04 | GET | `/api/v1/users/{userId}` | UUID path | `UserResponse`; dob chỉ trả cho chính user. |
| U05 | GET | `/api/v1/users/patch/{userId}` | UUID path | `UserResponsePatch`, chính chủ. |
| U06 | PUT | `/api/v1/users/{userId}` | JSON field cập nhật; xem bên dưới | `UserResponse`, chính chủ. |
| U07 | PATCH | `/api/v1/users/{userId}` | JSON `{username?,fullname?,dob?}` | `UserResponsePatch`, chính chủ. |
| U08 | DELETE | `/api/v1/users/{userId}` | JSON `{currentPassword}` | Chuỗi `User has been deleted.`, chính chủ, xác nhận lại password. |
| U09 | DELETE | `/api/v1/users/{id}/avatar` | Không có body | Chuỗi `Avatar has been deleted.`, chính chủ. |
| U10 | PATCH | `/api/v1/users/{id}/avatar` | Multipart part `file` | `UserResponsePatch`, chính chủ. |
| U11 | DELETE | `/api/v1/users/{userId}/shared-blogs/{blogId}` | UUID path | Chuỗi `Shared blog removed successfully`, chính chủ. |

U01 hỗ trợ `birthday` vì frontend cũ đang gửi tên này; response thống nhất `dob`. Nếu gửi cả hai khác nhau thì 400. Ngày sinh có thể vắng mặt/null; nếu có phải là ngày trước hôm nay. `userAvatarUrl` khi tạo user không được dùng để gán asset tùy ý.

Username canonical dài 3–32, chỉ `a-z` và `0-9`; fullname sau trim dài 1–100. Password mới theo [chính sách mật khẩu](04-bao-mat-media.md#mật-khẩu). Trùng username trả 409. Response đăng ký giữ ngày sinh của user vừa tạo, nhưng không trả dữ liệu xác thực.

U06 giữ hành vi cập nhật field có giá trị của legacy thay vì biến thành replace toàn entity. Có thể nhận `username`, `fullname`, `dob` và `password`. Nếu `password` được gửi không rỗng thì bắt buộc `currentPassword`, xác thực lại và áp dụng chính sách password mới; thành công làm hết hiệu lực mọi session của user. Chỉ gửi `currentPassword` mà không có thay đổi nào là request không hợp lệ.

U07 không đổi password; field vắng mặt giữ nguyên. Username/fullname explicit null hoặc rỗng trả 400; `dob:null` được xóa ngày sinh. Cả U06/U07 chấp nhận `id` trùng user và `userAvatarUrl` trùng URL hiện tại do form frontend có thể gửi lại; bỏ qua hai field đọc này. ID lệch trả 400; URL khác trả 400 và hướng dẫn dùng U10. U09 gọi lại khi avatar đã mặc định vẫn trả 200. U08 dùng `axios.delete(url, {data:{currentPassword}})` để gửi body JSON và CSRF header.

## Bài viết và tương tác

| Mã | Method | Route | Request | Thành công và quyền |
| --- | --- | --- | --- | --- |
| B01 | GET | `/api/v1/soulspaces` | Không có body | `BlogDTO[]`, bài public. |
| B02 | GET | `/api/v1/blogs/{userId}/all` | UUID path | `BlogDTO[]` public/private, chính chủ. |
| B03 | GET | `/api/v1/blogs/{userId}/blogs` | UUID path | `BlogDTO[]`, bài public của user. |
| B04 | GET | `/api/v1/blogs/{blogId}` | UUID path | `BlogDTO`, public hoặc chủ bài. |
| B05 | GET | `/api/v1/blogs/{blogId}/comments` | UUID path | `CommentDTO[]`, cùng quyền xem bài. |
| B06 | POST | `/api/v1/blogs/{blogId}/comments` | JSON `{content,userId?}` | `CommentCreateResponse`, đăng nhập và xem được bài. |
| B07 | POST | `/api/v1/blogs/{blogId}/comments/{commentId}/reply` | JSON `{content,userId?}` | `CommentReplyResponse`, parent đúng bài và còn trong giới hạn độ sâu. |
| B08 | POST | `/api/v1/blogs/{blogId}/comments/{commentId}/like` | JSON `{liked:boolean,userId?}` | `CommentLikeResponse`, đăng nhập và xem được bài. |
| B09 | POST | `/api/v1/blogs/{blogId}/like` | JSON `{liked:boolean,userId?}` | `BlogLikeResponse`, đăng nhập và xem được bài. |
| B10 | POST | `/api/v1/blogs` | Multipart tạo bài | `BlogCreateResponse`, đăng nhập. |
| B11 | PATCH | `/api/v1/blogs/access` | JSON `{blogId,published:boolean}` | `BlogAccessResponse`, chủ bài. |
| B12 | POST | `/api/v1/blogs/{blogId}/share` | JSON `{userId?}` | `BlogShareResponse`, đăng nhập, bài public, share trùng 409. |
| B13 | DELETE | `/api/v1/blogs/{blogId}` | UUID path | Chuỗi `Blog deleted successfully`, chủ bài. |
| B14 | PATCH | `/api/v1/blogs-details` | Multipart sửa bài | `BlogUpdateResponse`, chủ bài. |
| B15 | PATCH | `/api/v1/blogs/{blogId}/comments/{commentId}` | JSON `{content,expectedVersion}` | `CommentMutationResponse`, chỉ tác giả và được xem bài; chưa xóa, đúng version. |
| B16 | DELETE | `/api/v1/blogs/{blogId}/comments/{commentId}` | JSON `{expectedVersion}` | `CommentMutationResponse`, chỉ tác giả và được xem bài; xóa lần đầu đúng version, xóa lặp thành công. |

Các tên response dưới đây là tên hợp đồng, không bắt buộc tên Java phải đổi so với legacy:

| Response | Field |
| --- | --- |
| `CommentCreateResponse` | `blogId`, `commentsCount`, `comment:CommentDTO`, `message` |
| `CommentReplyResponse` | `blogId`, `parentCommentId`, `commentsCount`, `reply:CommentDTO`, `message` |
| `CommentLikeResponse` | `blogId`, `commentId`, `userId`, `liked`, `likesCount`, `message` |
| `CommentMutationResponse` | `blogId`, `commentsCount`, `comment:CommentDTO`, `message`; comment gồm các replies hiện tại để UI giữ cây |
| `BlogLikeResponse` | `blogId`, `userId`, `liked`, `likesCount`, `message` |
| `BlogCreateResponse` | `blogId`, `successUrls:string[]`, `failedFiles:string[]` |
| `BlogAccessResponse` | `blogId`, `published`, `updatedAt` |
| `BlogShareResponse` | `blogId`, `userId`, `sharesCount`, `totalSharedBlogs`, `sharedAt`, `message` |
| `BlogUpdateResponse` | `blogId`, `content`, `successUrls:string[]`, `failedDeleteFiles:string[]`, `failedUploadFiles:string[]`, `published`, `updatedAt` |

B08/B09 bắt buộc có boolean thật, không nhận null hay string `"false"` trong JSON. Chỉ gửi `{userId}` như client cũ là 400, không được ngầm quay lại toggle. Các message like phản ánh trạng thái cuối, kể cả request không làm thay đổi row.

Frontend cập nhật biểu tượng và trạng thái mong muốn ngay khi bấm (optimistic UI), rồi gửi `liked=true/false` mà không đợi app đóng hoặc giữ thay đổi 10 giây. Gộp các lần bấm nhanh theo từng blog/comment: mỗi mục chỉ có tối đa một request đang chạy; khi request đó chưa xong, chỉ giữ ý định cuối cùng thay vì gửi mọi lần đổi trạng thái. Khi response về, đối chiếu trạng thái đã lưu với ý định mới nhất; nếu khác thì gửi một request tiếp theo cho trạng thái cuối. Không để response cũ ghi đè giao diện mới hơn. Có thể dùng cửa sổ debounce ngắn tối đa khoảng 250 ms để gộp burst, nhưng không tạo queue lưu theo từng cú bấm.

Response thành công là trạng thái đã commit ở server và cung cấp counter chuẩn. Nếu request lỗi mà không có ý định mới hơn, khôi phục trạng thái đã xác nhận gần nhất; nếu người dùng đã bấm tiếp, giữ ý định mới nhất và báo lỗi/cho retry an toàn. Retry phải gửi lại cùng `liked` mong muốn, không biến thành toggle.

B15/B16 là hai endpoint mới, cùng HTTP 200 khi thành công; tổng hợp đồng là 35 endpoint (29 legacy + 6 bổ sung). expectedVersion bắt buộc là số nguyên không âm, không nhận null/string; thiếu hoặc sai kiểu trả 400. B15 content sau trim dài 1–2000; chỉ bind content/expectedVersion, không nhận trường cấu trúc hoặc author. B16 frontend dùng axios.delete(url,{data:{expectedVersion}}) kèm cookie/CSRF. Version là phiên bản trên cùng một hàng, không là ID record lịch sử.

Thứ tự xử lý B15/B16: xác thực/CSRF, kiểm quyền xem bài và comment thuộc bài, kiểm tác giả, kiểm body hợp lệ; khóa blog rồi comment. B16 nếu node đã xóa thì trả thành công với trạng thái hiện tại sau kiểm quyền/body, không kiểm khớp version nữa. B15 trên node đã xóa trả 409 COMMENT_DELETED; B15 và lần xóa đầu B16 có version cũ trả 409 CONCURRENT_MODIFICATION. Không tự retry HTTP bằng version mới. Xóa tài khoản đã gỡ tác giả làm tài khoản/session đó không còn quyền thực hiện B15/B16.

B07 phải khóa parent, kiểm deleted_at IS NULL rồi mới insert reply; parent đã xóa trả 409 COMMENT_DELETED. B08 cũng từ chối node đã xóa bằng mã này. Các replies cũ vẫn được đọc và comment con chưa xóa vẫn có thể được trả lời. Body sai parent/blog hoặc vượt 5 cấp xử lý như hợp đồng validation, không lưu depth vào database.

B10 nhận các part `content?`, `published?` mặc định true, `files` lặp lại, `userId?` để đối chiếu. Chấp nhận và bỏ qua `username` legacy do PostPage đang append field này; author luôn từ session. B14 nhận `blogId`, `content?`, `published?`, `newImages` lặp lại, `removeImagesUrl` lặp lại. Giữ phân biệt field absent với false/rỗng; `published` không có không được bind thành false.

`successUrls` của B14 là toàn bộ ảnh còn gắn với bài sau commit, theo thứ tự hiển thị. `failedDeleteFiles` không báo ảnh đang chờ dọn là lỗi: job được enqueue thành công thì việc gỡ liên kết đã thành công và array này là `[]`. Nếu enqueue thất bại, transaction rollback và trả 500. Lỗi cleanup xảy ra về sau được theo dõi trong queue, không sửa response đã trả.

UI hiện PATCH nội dung rồi PATCH published thành hai request. Hợp đồng B14 đã nhận `published`; frontend chuyển sang gửi một multipart để nội dung/ảnh/trạng thái commit cùng nhau. B11 vẫn được giữ cho thao tác đổi quyền độc lập.

## Upload độc lập và phục vụ ảnh

| Mã | Method | Route | Request | Thành công và quyền |
| --- | --- | --- | --- | --- |
| M01 | POST | `/api/v1/upload` | Multipart `file` | `{success:true,imageUrl:string}`, người đăng nhập. |
| M02 | POST | `/api/v1/uploadFiles` | Multipart `files` lặp lại | `{success:boolean,uploaded:string[],failed:string[]}`, người đăng nhập. |
| M03 | DELETE | `/api/v1/delete` | Query `imgUrl` | `{success:true,message:string,cleanupPending:true}`, asset chưa gắn và thuộc actor. |
| M04 | GET | `/api/v1/media/{assetId}` | UUID path | Binary image, kiểm quyền theo liên kết hiện tại. |

M01–M03 giữ route và field chính legacy; `cleanupPending` là field bổ sung để mô tả xóa bất đồng bộ. Upload độc lập là asset PENDING đã xác nhận upload nhưng chưa gắn, chỉ chủ asset xem được, tự hết hạn sau 24 giờ nếu không dùng; frontend hiện không dùng hai route này để tạo bài. M03 nhận đúng URL API đã cấp, không nhận URL Cloudinary tùy ý, không tải URL client gửi. Asset đang làm avatar hoặc gắn với bài trả 409; gỡ ảnh qua endpoint nghiệp vụ tương ứng. Asset không tồn tại hoặc thuộc người khác trả 404.

M02 giữ HTTP 200 khi có lỗi upstream từng file; `success` chỉ true nếu tất cả thành công. M01 lỗi upstream trả 502. Không có files là 400; file sai loại là 415, vượt kích thước là 413. M04 có `Content-Type` từ ảnh đã kiểm tra, `X-Content-Type-Options:nosniff` và `Cache-Control:no-store`, không redirect tới provider.

## Lỗi thống nhất

```json
{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Invalid request",
  "path": "/api/v1/users/sign-up",
  "timestamp": "2026-10-24T01:00:00Z",
  "fieldErrors": {"username": "Username must contain 3 to 32 letters or digits"}
}
```

| HTTP | Code tiêu biểu | Tình huống |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED`, `INVALID_ID`, `INVALID_MEDIA_REFERENCE`, `COMMENT_DEPTH_EXCEEDED` | Input hoặc liên kết không hợp lệ. |
| 401 | `AUTH_REQUIRED`, `INVALID_CREDENTIALS` | Hết session, chưa đăng nhập, hoặc sai password xác nhận lại. |
| 403 | `FORBIDDEN`, `ACTOR_MISMATCH`, `CSRF_INVALID` | Không có quyền ghi/owner, giả userId, hoặc CSRF không hợp lệ. |
| 404 | `RESOURCE_NOT_FOUND` | Không tồn tại hoặc tài nguyên bị che do privacy. |
| 409 | `USERNAME_EXISTS`, `ALREADY_SHARED`, `MEDIA_IN_USE`, `COMMENT_DELETED`, `CONCURRENT_MODIFICATION` | Node đã xóa, expectedVersion cũ, hoặc xung đột transaction không hoàn tất sau retry nội bộ; version cũ không được tự retry bằng dữ liệu mới. |
| 413 | `PAYLOAD_TOO_LARGE` | Vượt quota kích thước. |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | MIME/định dạng ảnh không được phép. |
| 429 | `RATE_LIMITED` | Chạm giới hạn; trả thêm `Retry-After`. |
| 500 | `INTERNAL_ERROR` | Lỗi chưa dự kiến; không lộ SQL, stack trace, tên class hoặc secret. |
| 502 | `MEDIA_PROVIDER_ERROR` | Cloudinary không trả được kết quả cần thiết. |
| 503 | `SERVICE_UNAVAILABLE` | Database hoặc dependency thiết yếu chưa sẵn sàng. |

`fieldErrors` chỉ có ở lỗi field. Bắt lỗi riêng phải đứng trước catch-all; tránh biến lỗi 401/403/404 thành 400 như handler RuntimeException legacy. Request CSRF sai có thể bị chặn trước validate body; kiểm thử phải cấp token hợp lệ khi muốn kiểm lỗi nghiệp vụ.

## Những nơi frontend cần cập nhật

| Caller | Thay đổi cần có |
| --- | --- |
| `src/config/api.js` và nơi import axios | Tạo một client HTTP dùng chung, gửi cookie và CSRF, giữ `buildApiUrl`; không nhân bản cấu hình ở từng page. Chỉ clear auth toàn cục khi code AUTH_REQUIRED; INVALID_CREDENTIALS của xác nhận lại password được xử lý tại form, không tự logout. |
| `App.jsx` và luồng kiểm tra đăng nhập | Bootstrap `/auth/me`; localStorage chỉ là cache hiển thị, không là bằng chứng đăng nhập. |
| `SignInPage/SignIn.jsx` | Lấy CSRF trước login, xử lý 401/429, nhận avatarUrl, lấy token mới sau login; bỏ log response xác thực. |
| `SignUpPage/SignUp.jsx` | Gửi `dob` canonical; API vẫn nhận birthday; cập nhật policy password và bỏ log password/form. |
| `Navigation`, `UserPage`, mọi nơi logout | Gọi A04 rồi clear state; không chỉ xóa localStorage. |
| `ListPost`, `PostDetailPage` | Optimistic UI; gửi `liked` mong muốn và gộp lần bấm nhanh theo blog, tối đa một request đang chạy/mục; response không ghi đè ý định mới hơn. Counter chuẩn lấy từ response. |
| `Comment/CommentPopup` và các nơi dựng comment trong `PostDetailPage` | Like comment dùng cùng quy tắc optimistic/coalescing; thêm sửa/xóa cho đúng tác giả, gửi expectedVersion; 409 yêu cầu đọc lại. Giữ placeholder/replies, xử lý tác giả null, khóa reply/like ở node đã xóa; count lấy từ response. |
| `UserPage` sửa bài | Gộp published vào B14; giữ các tên multipart hiện có. |
| `UserPage` xóa tài khoản | Dialog xác nhận lại password và gửi U08 body; lỗi không xóa state trước khi server xác nhận. |
| `EditProfile`, `EditAvatar`, `PostPage`, `SearchPage`, `PublicProfilePage` | Dùng chung HTTP client; UUID là string, ảnh API relative URL; xử lý privacy/expired session. |

Giữ route giao diện hiện tại; bổ sung thao tác sửa/xóa comment và các trạng thái đã chốt trong luồng comment. Tìm kiếm nâng cao thuộc thiết kế tính năng riêng, không thêm endpoint tìm kiếm trong bộ 35 route này. Các route chỉ nằm trong tài liệu pre-v1, như `/auth/login`, `/auth/check-session`, PUT `/blogs/details`, không được tính là endpoint hiện có và không bổ sung alias tự động.
