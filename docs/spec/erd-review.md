# Rà soát và chốt thiết kế database SoulSpace

Ghi chú 31/10/2026: báo cáo dưới đây giữ nguyên phạm vi core phiên bản 1.1 (9 bảng/57 cột, 35 endpoint). Spec hiện tại
phiên bản 1.4 có [thiết kế phân loại chủ đề](07-phan-loai-chu-de.md), [ERD đầy đủ](erd.md), tổng 11 bảng/65 cột và 37
endpoint theo thiết kế. Phần chủ đề thêm 2 bảng/8 cột so với core lịch sử, gồm category_mode, category_source và
confidence; các kết luận rà soát cũ không thay việc review phần bổ sung. Quyết định V1/V2 và view được tổng hợp
tại [báo cáo thiết kế SQL](08-bao-cao-tong-ket-sql.md).

Quy tắc mô tả comments bên dưới đã cập nhật theo quyết định ngày 31/10/2026: bỏ giới hạn cứng độ sâu,
UI thụt lề tối đa 3 tầng nhưng vẫn gửi đúng parent. Các kết quả kiểm tra phiên bản 1.1 được giữ như lịch sử,
không chứng minh V3/test SQL hiện có đã đáp ứng quyết định mới; xem [phần cần đồng bộ](03-postgresql.md#cây-reply-và-cách-hiển-thị).

Ngày rà soát/chốt: 27/10/2026. Phiên bản spec: 1.1. Phạm vi: toàn bộ bộ spec, thiết kế dữ liệu trong [03-postgresql.md](03-postgresql.md), bốn sơ đồ nguồn, SVG và [erd.md](erd.md). Người dùng đã chọn chính sách comment, timestamp, attach/cleanup và lease. Đây là rà soát tài liệu, không phải xác nhận schema hoặc trigger đã chạy trên PostgreSQL.

## Kết luận

**Có thể chốt thiết kế database để bắt đầu triển khai.** Mô hình có 9 bảng/57 cột, hợp đồng 35 endpoint (29 legacy + 6 bổ sung), không cần thêm bảng root/reply. Không còn quyết định nghiệp vụ đang chờ trả lời trong phạm vi database này. Toàn bộ schema không được chứng nhận BCNF: blog_id trên reply là phần dư thừa được giữ có chủ đích để truy vấn theo bài.

Các mục R01–R06 đã được chốt và đồng bộ ở mức tài liệu; tiêu chí nghiệm thu tương ứng đã được ghi để kiểm chứng implementation sau này. Chưa có Flyway SQL/entities nghiệp vụ trong target, chưa kết nối database thực tế và chưa triển khai API/frontend trong lượt cập nhật spec này. Trạng thái "chốt thiết kế" không có nghĩa database đã chạy hoặc nghiệm thu thành công.

## Thay đổi ERD đã áp dụng

1. Comments không lưu depth; giữ blog_id và parent_id, không giới hạn cứng độ sâu. Parent phải tồn tại/cùng bài/chưa xóa, không tự trỏ; cấm đổi ID/blog/parent sau tạo để giữ cây không vòng.
2. Comments thêm updated_at, deleted_at và version. Tổng 10 cột thay cho 8 cột cũ.
3. Comments.author_id nullable, FK users ON DELETE SET NULL; cạnh ERD đổi thành tác giả tùy chọn. Comment không có tác giả phải đã xóa nội dung.
4. CHECK trạng thái yêu cầu comment hoạt động có content hợp lệ, comment đã xóa có content=NULL. Xóa tài khoản phải xóa nội dung/gỡ tác giả trong cùng transaction; không chỉ dựa vào FK SET NULL.
5. Comments_count chỉ tính node hoạt động. Trigger xử lý INSERT, lần chuyển deleted_at đầu tiên và DELETE vật lý, tránh giảm hai lần. Like không làm thay đổi version/updated_at của nội dung.
6. Media_assets bỏ mime_type, còn 10 cột; backend suy ra MIME từ format jpeg/png đã kiểm tra. CHECK trạng thái metadata phải kiểm NOT NULL rõ vì CHECK có thể chấp nhận kết quả NULL.
7. Blog_images dùng asset_id làm PK/FK; blog_id và position NOT NULL, UNIQUE(blog_id,position), position trong 0–9.
8. Đồng bộ đủ cột và quan hệ ở cả bốn sơ đồ; render SVG nền sáng với nhãn SVG thuần. Chưa mở thử trực tiếp trong VS Code/IntelliJ của người dùng.
9. Chốt không tạo reply trực tiếp vào placeholder; khóa parent và kiểm trạng thái trước khi insert. Replies cũ và trả lời comment con đang hoạt động vẫn được giữ.
10. Timestamp like/share NOT NULL DEFAULT now(); attach/cleanup khóa asset và kiểm owner/purpose/state. Claim worker và bước khóa asset tách transaction để không giữ job lock chờ asset.
11. RUNNING bắt buộc có lease, PENDING/DEAD không giữ lease; attempts từ 0–5. Worker dùng cặp attempts/lease_until từ RETURNING và kiểm hạn bằng clock_timestamp() trước ghi kết quả; kết quả cũ mất quyền bị bỏ.

## Rà soát từng bảng

| Bảng | Cột | Khóa/quan hệ đã kiểm | Kết quả và lưu ý |
| --- | --- | --- | --- |
| users | 8 | PK id; username UNIQUE NOT NULL; avatar FK nullable/unique | Phù hợp; avatar nullable không là khóa ứng viên của toàn bảng. FK vòng với media được thêm sau khi tạo hai bảng. |
| blogs | 9 | PK id; author FK CASCADE; content/ảnh kiểm cuối transaction | Phù hợp; xóa bài vẫn xóa toàn bộ cây. Counters là số lưu sẵn, phải đối soát quan hệ. |
| comments | 10 | PK id; UNIQUE(id,blog_id) phục vụ FK parent ghép; author SET NULL; parent cùng bài | Phù hợp hướng đã chốt; UNIQUE ghép tuy dư theo id nhưng cần làm đích FK PostgreSQL. Trạng thái xóa, version và cây cần trigger/transaction đúng, không chỉ dựa vào hình. |
| blog_likes | 3 | PK(blog_id,user_id); hai FK CASCADE | Chống like trùng; created_at đã chốt NOT NULL DEFAULT now(). |
| comment_likes | 3 | PK(comment_id,user_id); hai FK CASCADE | Cần chặn like khi comment đã xóa qua mọi đường ghi tương tác; soft delete không tự kích hoạt FK cascade của likes. |
| blog_shares | 3 | PK(user_id,blog_id); hai FK CASCADE | Không copy nội dung; share bị ẩn khi bài private, bị xóa khi bài gốc bị xóa. |
| media_assets | 10 | PK id; provider_public_id UNIQUE NOT NULL; owner SET NULL | Metadata đủ để phục vụ/cleanup; provider_asset_id nullable không là khóa ứng viên vô điều kiện. Purpose/owner/liên kết cần kiểm như R04. |
| blog_images | 3 | PK asset_id; blog FK CASCADE, asset FK RESTRICT; UNIQUE(blog_id,position) | Một asset có tối đa một liên kết trong bảng; vị trí NOT NULL và giới hạn 0–9 bảo đảm tối đa 10 ảnh/bài. |
| media_cleanup_jobs | 8 | PK id; asset_id UNIQUE NOT NULL, FK CASCADE; CHECK status/lease/attempts | Một job/asset; queue không mất theo blog/user; có xác nhận lượt claim và giới hạn 5 lượt tự động. |
| **Tổng** | **57** | **9 bảng** | Phân biệt thiết kế trên giấy với ràng buộc thực sự được tạo bởi Flyway. |

## Các phát hiện đã được xử lý trong thiết kế

### R01 — Đã đồng bộ: Chính sách xóa

Đã cập nhật [01-nghiep-vu.md](01-nghiep-vu.md), CMT01–CMT05/DEL02; [README.md](README.md), D09–D14; [05-trien-khai.md](05-trien-khai.md), M4. Xóa comment giữ node/replies; xóa tài khoản xóa nội dung/gỡ tác giả ở bài còn sống. Bài của tài khoản vẫn bị xóa vĩnh viễn cùng cây thuộc bài.

Comments_count chỉ đếm node hoạt động. Xóa account phải chuẩn bị nội dung trước SET NULL, enqueue assets trước cascade và có khóa actor để không bỏ sót ghi mới. Chưa xác nhận implementation thực hiện đúng; phải đạt DEL01/DEL02, DB07 và CON11.

### R02 — Đã đồng bộ: API/DTO và tiêu chí nghiệm thu

Đã cập nhật [02-api.md](02-api.md): B15/B16 là PATCH/DELETE comment, chỉ tác giả được thao tác; expectedVersion bắt buộc, bản cũ 409, xóa lặp 200. CommentDTO có updatedAt/isDeleted/version, author/content nullable và replies giữ nguyên. B07/B08 chặn node đã xóa; tổng thiết kế 35 routes. Client phải gửi version đang hiển thị; chỉ đọc version tại server không bảo vệ bản cũ ở client.

Đã bổ sung [nghiệm thu](06-nghiem-thu.md) CMT02–CMT08, cập nhật DEL01/DEL02, thêm CON09–CON12 và DB01–DB07. Đã ghi mapping JPA counter read-only để tránh merge/native SQL ghi đè số đếm. Đây là danh sách ca phải chạy khi triển khai, chưa phải test report.

Phải kiểm hai tab cùng version, sửa đồng thời xóa, xóa lặp, không tăng version khi like, LEFT JOIN tác giả đã xóa và không giảm counter placeholder lần hai khi hard delete bài.

### R03 — Đã chốt: Timestamp tương tác

blog_likes.created_at, comment_likes.created_at và blog_shares.shared_at đã ghi NOT NULL DEFAULT now(). Runtime không nhận timestamp từ body. Seed có thể dùng mốc cố định không NULL để tái lập. DB02 kiểm bỏ qua cột được database cấp mặc định và explicit NULL bị từ chối.

### R04 — Đã chốt: Attach/cleanup có khóa asset

Đã mô tả luồng attach dùng chung trong 03/04: khóa user/blog đích rồi asset theo thứ tự ID, đọc lại state/owner/purpose và chỉ attach PENDING đã xác nhận đủ metadata. Scanner/cleanup khóa asset trước kiểm tra, DELETE_PENDING không được attach lại. Cloudinary ngoài transaction; enqueue không reset claim của job có sẵn. Kiểm MED11 và CON12.

FK đơn vẫn chỉ kiểm tồn tại, không tự kiểm ownership/visibility. Thiết kế đã chọn service/procedure có khóa cho các quy tắc này; SQL quản trị trực tiếp không được coi là đường ghi API hợp lệ hoặc bằng chứng đã vượt qua kiểm quyền.

### R05 — Đã chốt: Reply vào placeholder; tìm kiếm ngoài phạm vi

Người dùng đã chọn không reply mới trực tiếp vào node đã xóa. Parent được khóa/kiểm chưa xóa; thao tác xóa và reply tuần tự hóa. Comment con chưa xóa vẫn được trả lời không phụ thuộc độ sâu; kiểm CMT06 và CON10. CMT01/DB08 yêu cầu chuỗi ít nhất 10 cấp được ghi, CMT09 kiểm thụt lề và parent thật trên UI; đây là tiêu chí mới chưa được xác nhận PASS.

Người dùng đưa tìm kiếm nâng cao theo comment ra khỏi phạm vi chốt database lần này. Không thêm bảng/cột/index search hoặc giữ câu hỏi đó làm blocker thiết kế. Tính năng tìm kiếm sẽ có hợp đồng và truy vấn riêng khi được yêu cầu.

### R06 — Đã chốt: Lease và xác nhận lượt xử lý cleanup

Đã bổ sung CHECK status/lease và attempts 0–5 vào schema, cùng quy trình worker trong 04: claim riêng chỉ khóa job rồi commit; chuẩn bị/ghi kết quả khóa asset -> job và kiểm cặp attempts/lease_until từ RETURNING, RUNNING và hạn lease theo clock_timestamp(). Worker cũ mất claim không ghi receipt/job. Retry/thu hồi lease luôn xóa lease khi chuyển PENDING/DEAD; không claim tự động lần thứ 6. Requeue DEAD là hành động vận hành riêng có khóa/kiểm state/namespace.

Phải đạt DB03, MED12–MED14 và CON12. Late success sau mất lease có thể để receipt chưa được dọn cho đến lần đối soát hợp lệ hoặc xử lý DEAD; không hứa DB và provider có chung transaction.

## BCNF và phần dữ liệu lưu sẵn

- Giữ comments.blog_id phục vụ truy vấn, chấp nhận phụ thuộc có điều kiện parent_id -> blog_id trên reply; bỏ depth giảm một thuộc tính suy ra cần duy trì.
- Bỏ media_assets.mime_type tránh lưu lặp ánh xạ format -> MIME. Không cần thêm bảng định dạng chỉ cho jpeg/png.
- Counters là tổng hợp liên bảng; sự hiện diện của counters không tự chứng minh vi phạm BCNF. Cần kiểm nhất quán và một nơi cập nhật.
- Chưa có bộ phụ thuộc hàm đầy đủ cho mọi trạng thái/NULL để chứng minh BCNF toàn bộ. Không dùng việc có UUID PK làm bằng chứng đủ.

## Kiểm tra đã thực hiện và giới hạn

Rà soát tĩnh tên bảng/cột/khóa, cardinality, nullability, FK và chính sách xóa; so sánh định nghĩa bảng lặp trong bốn sơ đồ, đối chiếu SVG với mã nguồn và xem ảnh render để phát hiện cắt dòng/ô thừa. Kiểm liên kết tài liệu và SVG thuần là kiểm tra artifact; không xác nhận thực thi SQL.

| Kiểm tra artifact | Kết quả |
| --- | --- |
| Số bảng/cột trong sơ đồ tổng quan | PASS: 9 bảng, 57 cột |
| Định nghĩa bảng lặp ở các nhóm | PASS: cùng field/type/key/ghi chú với sơ đồ tổng quan |
| Render Mermaid và đối chiếu cột theo từng node SVG | PASS: 4 sơ đồ; lần lượt 9, 3, 6, 5 bảng; không thiếu cột |
| SVG độc lập | PASS: 4 file XML hợp lệ; không có foreignObject, script hoặc tài nguyên href/src bên ngoài |
| Liên kết cục bộ trong bộ spec | PASS: 52 tham chiếu tồn tại, gồm một anchor hợp lệ; đã kiểm cả 9 file Markdown |
| Xem ảnh render | Không thấy cắt dòng, node direction/LR thừa hoặc bảng mất cột; hình tổng quan cần phóng to khi khung preview hẹp |

Chưa tạo/chạy Flyway, PostgreSQL Docker/Neon, trigger/procedure, kiểm thử đồng thời, frontend hoặc benchmark. Không sửa/xóa code ứng dụng hay dữ liệu trong lượt cập nhật tài liệu. R01–R06 đã đóng ở mức thiết kế; chỉ đánh dấu implementation đạt khi có bằng chứng các ca nghiệm thu.

## Kết quả kiểm tra cuối phiên bản 1.1

| Nội dung | Kết quả |
| --- | --- |
| 9 mục mô tả bảng và cột so với nguồn Mermaid | PASS: đủ 57 cột, không còn cột depth hoặc mime_type |
| Bốn nhóm sơ đồ và SVG | PASS: định nghĩa bảng đồng nhất; từng cột có trong đúng node; XML hợp lệ, không có foreignObject/script/tài nguyên ngoài |
| Hợp đồng API | PASS: 35 cặp method/route và 35 mã route duy nhất; có B15/B16 và expectedVersion |
| JSON ví dụ và cấu trúc Markdown | PASS: 3 ví dụ JSON parse được, code fence cân bằng, đường dẫn file trong tài liệu là tương đối |
| Truy vết quyết định và nghiệm thu | PASS ở mức tài liệu: R01–R06 đã đồng bộ; 70 mã ca nghiệm thu không trùng, bao phủ comment/version/timestamp/lease/attach và xóa tài khoản; các ca chưa được chạy |
| Rà quy tắc cũ mâu thuẫn | Không còn yêu cầu xóa toàn nhánh reply của người khác khi xóa account, inventory 33 route hoặc thiếu endpoint sửa/xóa comment |
| Khả năng chốt thiết kế | **Chốt được trong phạm vi đã thống nhất; không còn câu hỏi hoặc điểm chặn thiết kế bắt buộc đang mở** |
| Khả năng xác nhận database vận hành | Đã tạo V1–V4 và kiểm thử bằng PostgreSQL WASM; **chưa xác nhận Docker/Neon/JDBC/Flyway runtime**. Chưa có entity nghiệp vụ. Xem [kết quả và giới hạn](../../infra/postgres/README.md). |

Bước kế tiếp là triển khai Flyway/schema và các luồng service/procedure theo spec, rồi chạy bằng chứng nghiệm thu riêng trên Docker và Neon. Không dùng kết quả kiểm tra tài liệu này thay migration success, SQL integration test, benchmark hay chứng nhận BCNF toàn bộ.

## Đồng bộ sơ đồ ngày 31/10/2026

Cả bốn sơ đồ Mermaid trong 03-postgresql.md, sơ đồ chủ đề trong 07 và năm SVG trong assets/erd đồng nhất với V1: tổng
quan 11 bảng/65 cột, blogs có 10 cột, categories có 3 cột và blog_categories có 4 cột. PK của blog_categories là (
blog_id,category_id,category_source), AUTO có confidence, MANUAL để NULL; category_mode trên blogs chọn nguồn hiển thị.
Các sơ đồ nhóm cùng hiển thị quan hệ N–N blogs–categories. Số 9 bảng/57 cột ở báo cáo lịch sử bên trên chỉ core trước
khi bổ sung chủ đề. V1–V5 và ba bộ smoke SQL kiểm tra được trên PostgreSQL WASM; Docker/Neon/JDBC và model cần bằng
chứng riêng.
