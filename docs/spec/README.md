# Đặc tả migration backend SoulSpace

Ngày lập: 24/10/2026. Cập nhật: 31/10/2026. Phiên bản: 1.4. Trạng thái: thiết kế core và bổ sung đặc tả phân loại chủ đề
giai đoạn AI; chưa phải bằng chứng ứng dụng hoặc PostgreSQL đã chạy. Kết quả rà soát core và giới hạn nằm
trong [báo cáo ERD](erd-review.md).

Chuyển các chức năng đang có trong `old/Blog_api` sang backend `soulspace`, sử dụng PostgreSQL mới và tích hợp với frontend React hiện tại. Bộ tài liệu này chốt hành vi sản phẩm, hợp đồng API, dữ liệu, bảo mật và tiêu chí nghiệm thu để có thể triển khai theo từng mốc.

## Quyết định đã thống nhất với người dùng

| Mã  | Quyết định                                                                                                                                                                                                                                                                                                                               |
|-----|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| D01 | Migration backend hiện có trước; AI thuộc giai đoạn riêng.                                                                                                                                                                                                                                                                               |
| D02 | PostgreSQL mới với seed demo; không import MongoDB và không làm ETL dữ liệu legacy.                                                                                                                                                                                                                                                      |
| D03 | Giữ các route `/api/v1` và cấu trúc response mà frontend đang dùng; sửa sai lệch và bổ sung bảo vệ cần thiết.                                                                                                                                                                                                                            |
| D04 | Ưu tiên cơ chế xác thực hạn chế tấn công; thiết kế chọn Spring Security, session server và cookie HttpOnly.                                                                                                                                                                                                                              |
| D05 | Áp dụng Index, View, Trigger, Stored Procedure và Transaction vào nghiệp vụ phù hợp. v_public_blog_feed dùng chung truy vấn bài public với thông tin tác giả hiện tại. AI in SQL để giai đoạn AI.                                                                                                                                        |
| D06 | Neon PostgreSQL cho môi trường dùng chung/online; PostgreSQL Docker cho local. Spring Boot và React tiếp tục chạy trên máy, phục vụ phát triển/demo local và LAN.                                                                                                                                                                        |
| D07 | Like bài và bình luận nhận `liked=true/false`; request lặp cùng giá trị cho cùng kết quả. Frontend cập nhật lạc quan, gộp các lần bấm nhanh theo từng mục và chỉ giữ một request đang chạy cho mỗi mục.                                                                                                                                  |
| D08 | Share tham chiếu bài gốc hiện tại; ẩn khi bài gốc riêng tư, xóa share khi bài gốc bị xóa.                                                                                                                                                                                                                                                |
| D09 | Xóa bài vĩnh viễn cùng dữ liệu thuộc bài; xóa riêng comment giữ node/replies, xóa nội dung. Xóa tài khoản xóa bài của user, còn comment ở bài người khác được xóa nội dung/gỡ tác giả và giữ replies. Dọn ảnh sau DB commit.                                                                                                             |
| D10 | Comments giữ blog_id/parent_id, không lưu depth và không giới hạn cứng độ sâu. UI chỉ thụt lề tối đa 3 tầng, vẫn trả lời đúng parent; không tạo reply mới trực tiếp vào comment đã xóa. |
| D11 | Chỉ tác giả sửa/xóa comment qua hai endpoint bổ sung; expectedVersion chống ghi đè, bản cũ trả 409, xóa lặp trả thành công.                                                                                                                                                                                                              |
| D12 | Timestamp like/share dùng NOT NULL DEFAULT now(). Media giữ format jpeg/png, không lưu mime_type.                                                                                                                                                                                                                                        |
| D13 | Media có ba trạng thái PENDING, ATTACHED, DELETE_PENDING. Chỉ PENDING đã xác nhận metadata và đúng owner/purpose mới được gắn; DELETE_PENDING không được gắn lại. Cloudinary ngoài transaction.                                                                                                                                          |
| D14 | Cleanup RUNNING bắt buộc có lease, PENDING/DEAD không giữ lease; kết quả worker phải khớp lượt claim. Tối đa 5 lượt claim tự động, kể cả lượt bị crash/hết lease.                                                                                                                                                                        |
| D15 | Model phân loại đa nhãn (0..n chủ đề) ở nền, không bắt xác nhận; tác giả được đổi/bỏ nhãn hoặc bật lại AUTO. V1 có categories, blog_categories(category_source,confidence), PK ghép ba cột giữ riêng AUTO/MANUAL để so sánh; blogs.category_mode chọn nguồn hiển thị, kể cả MANUAL rỗng. Chưa triển khai gợi ý cá nhân hóa hay xu hướng. |

## Cách đọc

| Tài liệu                                              | Nội dung                                                                                                                                      |
|-------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| [01 nghiệp vụ](01-nghiep-vu.md)                       | Phạm vi, luồng sử dụng, quyền xem và quy tắc dữ liệu.                                                                                         |
| [02 hợp đồng API](02-api.md)                          | Toàn bộ endpoint legacy, endpoint bổ sung, DTO, validation và thay đổi frontend.                                                              |
| [03 PostgreSQL](03-postgresql.md)                     | ERD, bảng, constraint, index, view public feed, trigger, procedure, locking và Flyway.                                                        |
| [04 bảo mật và media](04-bao-mat-media.md)            | Session, CSRF, mật khẩu, chống lạm dụng, upload, kiểm quyền ảnh và cleanup.                                                                   |
| [05 triển khai migration](05-trien-khai.md)           | Phạm vi file, thứ tự công việc, cấu hình và vận hành local/LAN.                                                                               |
| [06 nghiệm thu](06-nghiem-thu.md)                     | Seed, kiểm thử chức năng, concurrency, SQL, bảo mật và bằng chứng bàn giao.                                                                   |
| [07 phân loại chủ đề](07-phan-loai-chu-de.md)         | Nghiệp vụ AI, dữ liệu/API bổ sung, xử lý nền, quyền sửa nhãn và nghiệm thu riêng.                                                             |
| [08 báo cáo tổng kết SQL](08-bao-cao-tong-ket-sql.md) | ERD và cơ sở thiết kế V1/V2, view, khung báo cáo procedure/trigger/transaction/AI in SQL; lưu quyết định đã chốt để dùng cho báo cáo cuối kỳ. |
| [09 giải thích V3](09-giai-thich-v3.md) | Toàn bộ code V3 theo 9 nhóm, giải thích từng nhánh, ví dụ, lý do dùng và đánh đổi; hai tối ưu constraint trigger và kết quả kiểm SQL. |
| [ERD dạng SVG](erd.md)                                | Năm hình SVG đã đồng bộ: tổng quan 11 bảng/65 cột, ba nhóm nghiệp vụ và chủ đề riêng; chưa triển khai phần AI.                                |
| [Rà soát và chốt database](erd-review.md)             | Các quyết định đã đóng, kiểm tra tài liệu và những bước thực thi còn phải kiểm chứng.                                                         |

Các thuật ngữ `blog` và `bài viết` chỉ cùng một thực thể. Database dùng tên `blogs` để khớp API hiện tại. ID mới là UUID; JSON vẫn biểu diễn ID bằng string. Đường dẫn code/config trong nội dung tính từ gốc repository; link Markdown tính từ file tài liệu. Không hardcode đường dẫn filesystem tuyệt đối.

## Mặc định do thiết kế chọn

Các giá trị dưới đây là lựa chọn thiết kế cho demo, có thể hiệu chỉnh khi có số đo. Chúng không phải kết quả benchmark hoặc những yêu cầu đã được người dùng chỉ định riêng.

| Hạng mục | Mặc định |
| --- | --- |
| Nền backend | Giữ Java 21 và Spring Boot 4.1.1 theo POM hiện có; xác minh dependency khi triển khai. |
| Database | Cùng major PostgreSQL giữa Neon và Docker; mặc định 17 cho project mới. Pin bản vá Docker khi triển khai; xác nhận major của project Neon nếu đã có. |
| Hash mật khẩu mới | Argon2id; tham số và giới hạn trong tài liệu bảo mật. |
| Quyền | Guest, người đăng nhập và chủ tài nguyên; chưa thêm nghiệp vụ admin. |
| Riêng tư | Giữ `published` boolean; chưa thêm quyền FOLLOWERS. |
| Bình luận | Không giới hạn cứng độ sâu; UI thụt lề tối đa 3 tầng và chỉ rõ người được trả lời. Counter tính comment/reply chưa xóa, không tính placeholder. |
| Ảnh | JPEG/PNG; tối đa 10 ảnh/bài, 10 MiB/file, 100 MiB/request. Chọn định dạng giải mã được bằng JDK để không thêm decoder chưa cần. |
| Session | Idle 30 phút, tuổi tối đa 8 giờ, lưu trong bộ nhớ của một backend. |
| Seed chức năng | 8 users, 24 blogs, 48 comments gồm replies, 40 blog likes, 24 comment likes, 12 shares. |

Quyết định ngày 31/10/2026 bỏ giới hạn độ sâu không đổi bảng/cột hoặc các hình ERD. Đã đồng bộ V3,
`infra/postgres/tests/invariants.sql` và sơ đồ ngày 01/11/2026 theo
[thiết kế cây reply](03-postgresql.md#cây-reply-và-cách-hiển-thị): bỏ CTE đếm cấp, giữ parent hợp lệ và cấu trúc bất biến.
Kiểm SQL trên PostgreSQL WASM đạt với chuỗi 100 cấp và reply cấp 101 dưới ancestor đã xóa;
đây chưa phải xác nhận toàn bộ backend/frontend hoặc Flyway trên Docker/Neon đạt đặc tả 1.4.

## Hiện trạng đã đọc từ checkout

- [POM legacy](../../old/Blog_api/pom.xml): Java 17, Spring Boot 3.5.6, MongoDB, MapStruct, Cloudinary và BCrypt trong source.
- [POM target](../../soulspace/pom.xml): Java 21, Spring Boot 4.1.1, JPA, PostgreSQL, Flyway, Security, Validation, Actuator; target hiện chỉ có application class và context test.
- [Cấu hình target](../../soulspace/src/main/resources/application.properties) đã có datasource/Flyway, validate schema,
  giới hạn upload và cấu hình nền. Đã tạo Compose, profile Docker/Neon, provision roles và
  V1–V5; [kết quả kiểm tra SQL và giới hạn](../../infra/postgres/README.md). Chưa có entity/service/API nghiệp vụ trong
  target.
- Bốn controller legacy có 29 endpoint đang được khai báo hoạt động; hợp đồng target giữ đủ 29 và bổ sung 6 endpoint: session/CSRF/media và sửa/xóa comment, tổng 35. Đây là số route thiết kế, chưa triển khai trong target.
- [README legacy](../../old/Blog_api/README.md) ghi `pre-v1`, có route và HTTP method lệch controller; nguồn hợp đồng là controller, DTO và caller frontend đã đối chiếu.
- `docs/Ke_hoach.docx` là kế hoạch tổng thể SoulSpace AI. Trong bộ spec này, chỉ đưa phần backend core và các kỹ thuật SQL phù hợp vào giai đoạn đầu.
- Lúc lập spec, Git báo `old/` chưa được track. Giữ nguyên mã nguồn legacy làm tài liệu đối chiếu; việc tạo bộ spec không thay đổi trạng thái này.

## Ranh giới bàn giao

Giai đoạn này hoàn tất khi frontend chạy các luồng hiện có với PostgreSQL mới, session thật, quyền truy cập được thực thi ở server, dữ liệu không lệch khi chạy đồng thời, và ảnh có quy trình phục vụ/xóa đúng quyền. Các phép đo và tiêu chí đạt nằm trong [nghiệm thu](06-nghiem-thu.md).

Giai đoạn AI tiếp theo được đặc tả tại [07 phân loại chủ đề](07-phan-loai-chu-de.md): tự gắn chủ đề và cho tác giả sửa
tùy chọn. Schema categories, blog_categories.confidence và blogs.category_mode đã đưa vào V1 trước lần migrate đầu;
model/API triển khai ở giai đoạn AI. Recommendation, xu hướng, semantic search và pgvector/pgai vẫn ngoài phạm vi. Chưa
thêm dependency hoặc worker AI; bảng chủ đề được tạo sẵn trong V1.
