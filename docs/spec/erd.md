# ERD PostgreSQL — SoulSpace

Cập nhật 30/10/2026: cả năm hình đã đồng bộ với **thiết kế đầy đủ: 11 bảng/63 cột**, gồm core và chủ đề giai đoạn AI. [Mục 5](#5-mở-rộng-phân-loại-chủ-đề-giai-đoạn-ai) bổ sung ERD chủ đề; sau giai đoạn AI, toàn schema có **11 bảng/63 cột**. Đây là thiết kế, chưa phải schema đã triển khai.

Bản SVG cố định của bốn sơ đồ trong [đặc tả PostgreSQL](03-postgresql.md), xuất lại ngày 30/10/2026. Mở file này bằng Markdown Preview trên VS Code hoặc IntelliJ IDEA để xem ảnh; không cần plugin Mermaid để dựng lại sơ đồ.

Ảnh dùng nền sáng, nhãn SVG thuần và bố cục được lưu sẵn trong file. Khi thay đổi mô hình ở tài liệu nguồn, cần render lại các ảnh SVG tương ứng để bản này phản ánh thiết kế mới.

## Ký hiệu

| Ký hiệu | Ý nghĩa |
| --- | --- |
| PK | Khóa chính; nhiều cột cùng đánh dấu PK tạo khóa chính ghép. Riêng blog_images dùng asset_id làm PK. |
| FK | Khóa ngoại; cột giữ FK xác định phía tham chiếu. |
| UK | Ràng buộc duy nhất trên một cột. |
| Hai vạch ở đầu quan hệ | Đúng một. |
| Vòng tròn và một vạch | Không hoặc một. |
| Vòng tròn và chân quạ | Không hoặc nhiều. |

Các hình hiển thị đầy đủ cột của từng bảng theo [đặc tả PostgreSQL](03-postgresql.md), gồm kiểu dữ liệu, khóa và ghi chú nullable. Sơ đồ tổng quan có **11 bảng, 63 cột**; các sơ đồ theo nhóm cũng giữ đầy đủ cột của các bảng xuất hiện trong hình. Giá trị mặc định, CHECK và ràng buộc nhiều cột xem tại tài liệu nguồn. Xem [báo cáo chốt thiết kế](erd-review.md) để phân biệt kết quả rà soát với phần PostgreSQL còn phải triển khai/kiểm chứng.

| Bảng | Số cột |
| --- | --- |
| users | 8 |
| blogs | 10 |
| categories | 3 |
| blog_categories | 2 |
| comments | 10 |
| blog_likes | 3 |
| comment_likes | 3 |
| blog_shares | 3 |
| media_assets | 10 |
| blog_images | 3 |
| media_cleanup_jobs | 8 |
| **Tổng** | **63** |

Hình tổng quan chứa nhiều thông tin; dùng liên kết mở SVG bên dưới mỗi ảnh và phóng to để đọc rõ từng cột.

## 1. Tổng quan mười một bảng

![ERD tổng quan mười một bảng của SoulSpace](assets/erd/tong-quan.svg)

[Mở SVG tổng quan ở kích thước đầy đủ](assets/erd/tong-quan.svg).

Gồm `users`, `categories`, `blog_categories`, `blogs`, `comments`, `blog_likes`, `comment_likes`, `blog_shares`, `media_assets`, `blog_images` và `media_cleanup_jobs`. Quan hệ sở hữu media và quan hệ avatar là hai liên kết riêng.

## 2. Bài viết và bình luận

![ERD bài viết, người dùng và cây bình luận](assets/erd/bai-viet-binh-luan.svg)

[Mở SVG bài viết và bình luận](assets/erd/bai-viet-binh-luan.svg).

- categories, bảng nối blog_categories và blogs.category_source được hiển thị đầy đủ trong cả bốn sơ đồ có blogs. Mỗi bài có 0..n chủ đề; mỗi chủ đề có 0..n bài. PK ghép (blog_id, category_id) ngăn trùng liên kết.
- Một user có nhiều bài viết và bình luận; mỗi bài có nhiều bình luận, bao gồm replies.
- Bình luận gốc có `parent_id = NULL`. FK ghép `(parent_id, blog_id)` tham chiếu `comments(id, blog_id)` giữ reply trong cùng bài; độ sâu tối đa là 5.
- Không lưu depth; trigger đếm chuỗi cha khi thêm reply. blog_id và parent_id cố định sau tạo; placeholder cũng tính một cấp trong cây.
- Không tạo reply mới trực tiếp vào placeholder; parent được khóa và kiểm chưa xóa. Replies cũ và thao tác trả lời comment con chưa xóa vẫn được giữ theo giới hạn 5 cấp.
- Xóa riêng comment đặt content=NULL/deleted_at, gỡ likes và giữ replies. comments_count chỉ đếm các node chưa xóa; UI dựng dòng "Bình luận đã xóa" từ trạng thái.
- author_id nullable phục vụ xóa tài khoản: xóa nội dung và gỡ tác giả của các comment ở bài người khác trong cùng transaction, giữ replies. Bài của tài khoản bị xóa vẫn bị xóa vĩnh viễn cùng toàn bộ cây.
- version dùng cho sửa/xóa comment, không tạo record lịch sử: client gửi expectedVersion, bản cũ bị từ chối 409. updated_at/version không thay đổi khi chỉ like.

## 3. Lượt thích và chia sẻ

![ERD các quan hệ like bài, like bình luận và share](assets/erd/luot-thich-chia-se.svg)

[Mở SVG lượt thích và chia sẻ](assets/erd/luot-thich-chia-se.svg).

- Khóa chính ghép ngăn một user like hoặc share trùng cùng tài nguyên; timestamp thuộc quan hệ tương ứng.
- Timestamp like/share NOT NULL DEFAULT now(), do database đặt cho thao tác runtime.
- Comment đã xóa không nhận like mới. Xóa các like của node đó và chuyển nó thành placeholder trong cùng transaction; không xóa likes/replies của các comment con còn sống.
- `blog_shares` tham chiếu bài gốc hiện tại; không lưu snapshot nội dung. Share bị ẩn khi bài gốc riêng tư và bị cascade xóa khi bài gốc bị xóa.

## 4. Media và avatar

![ERD tài sản media, avatar, ảnh bài viết và hàng đợi dọn ảnh](assets/erd/media-avatar.svg)

[Mở SVG media và avatar](assets/erd/media-avatar.svg).

- `users.avatar_media_id` nullable và unique: một user có tối đa một avatar; một asset không làm avatar của hai user.
- `media_assets.owner_id` có thể null sau khi xóa user để giữ thông tin phục vụ cleanup.
- `blog_images.asset_id` là PK và FK; blog_id và position NOT NULL, `UNIQUE(blog_id, position)` giữ thứ tự ảnh không trùng trong một bài. position từ 0 đến 9, không bắt buộc liên tục.
- Không lưu mime_type; format được xác minh/canonical hóa thành jpeg/png, backend dùng một hàm chung suy ra Content-Type.
- Cleanup job được ghi cùng transaction nghiệp vụ; worker gọi Cloudinary sau commit. Chi tiết trạng thái asset, quyền xem ảnh và xử lý lỗi nằm trong [bảo mật và media](04-bao-mat-media.md).
- Media có ba trạng thái: PENDING (receipt chưa xác nhận hoặc upload đã xác nhận nhưng chưa gắn), ATTACHED và DELETE_PENDING. Attach/scanner/cleanup khóa asset trước kiểm tra; chỉ PENDING đã xác nhận đủ metadata và đúng owner/purpose mới được gắn. Job RUNNING có lease; job PENDING/DEAD không giữ lease. Worker chỉ ghi kết quả khi cặp attempts/lease của lượt claim còn khớp và chưa hết hạn. Tối đa 5 lượt claim tự động.

## 5. Mở rộng phân loại chủ đề (giai đoạn AI)

![ERD mở rộng categories và blogs](assets/erd/phan-loai-chu-de.svg)

[Mở SVG chủ đề ở kích thước đầy đủ](assets/erd/phan-loai-chu-de.svg). Nguồn Mermaid, kiểu dữ liệu, CHECK, API và nghiệm thu nằm trong [07 phân loại chủ đề](07-phan-loai-chu-de.md).

Thêm categories(id, name, slug), blog_categories(blog_id, category_id) với PK ghép và blogs.category_source=AUTO/MANUAL. Không đặt category_id trên blogs. Mỗi bài có 0..n chủ đề và mỗi chủ đề có 0..n bài qua bảng nối; MANUAL kể cả tập rỗng giữ quyền chọn của tác giả. Model đa nhãn chỉ ghi khi vẫn AUTO và nội dung chưa đổi; thay cả tập trong một transaction. Slug là tên URL không dấu, duy nhất và ổn định. FK blog_categories.blog_id dùng ON DELETE CASCADE; FK category_id dùng ON DELETE RESTRICT. Tổng sau mở rộng: categories 3 cột, blog_categories 2 cột, blogs 10 cột, các bảng còn lại 48 cột, thành 11 bảng/63 cột.

## Nguồn chỉnh sửa

Mã Mermaid của bốn hình chính nằm trong phần **Mô hình và kiểu dữ liệu** của [03-postgresql.md](03-postgresql.md). Sơ đồ chủ đề riêng lấy Mermaid từ [07-phan-loai-chu-de.md](07-phan-loai-chu-de.md). File này nhúng các SVG tại `assets/erd/`; khi sao chép tài liệu cần giữ cả thư mục ảnh và cấu trúc đường dẫn tương đối.

## Phạm vi chuẩn hóa

Giữ blog_id trên comments để truy vấn theo bài và kiểm quyền trực tiếp, dù trên các reply nó được suy ra từ parent. Thiết kế chấp nhận phần dư thừa này, không chứng nhận toàn bộ schema đạt BCNF. Counters là dữ liệu tổng hợp do trigger quản lý; quan hệ like/comment/share là nguồn đối soát. UNIQUE nullable chỉ bảo đảm duy nhất khi có giá trị, không mặc nhiên là khóa ứng viên của toàn bảng.
