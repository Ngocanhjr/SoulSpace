# Giải thích từng đoạn V3

Đối chiếu code ngày 01/11/2026: [V3__counters_and_invariants.sql](../../soulspace/src/main/resources/db/migration/V3__counters_and_invariants.sql), 9 function và 14 trigger. Mỗi khối SQL dưới đây chép đúng đoạn hiện tại; các khối nối theo thứ tự sẽ khôi phục toàn bộ V3.

SQL đã đồng bộ với [spec cây reply](03-postgresql.md#cây-reply-và-cách-hiển-thị): không giới hạn cứng độ sâu, không lưu depth và không duyệt toàn bộ tổ tiên khi thêm reply. Parent phải tồn tại trong cùng bài, chưa xóa; ID/blog/parent bất biến sau tạo giữ cây không vòng. Hai tối ưu phạm vi blogs_nonempty và deleted_comment_no_likes tiếp tục được giữ.

## Cách đọc cú pháp chung

| Code | Ý nghĩa trong file |
| --- | --- |
| CREATE FUNCTION ... RETURNS trigger | Tạo hàm do trigger gọi; không phải procedure CALL của V4. |
| LANGUAGE plpgsql | Thân hàm dùng ngôn ngữ thủ tục PostgreSQL. |
| SET search_path = public, pg_temp | Phân giải tên bảng/hàm không ghi schema theo thứ tự public rồi pg_temp; không phải mở transaction. |
| AS $$ ... $$ | Dấu phân cách chuỗi chứa thân hàm, tránh phải escape dấu nháy đơn bên trong. |
| DECLARE | Biến cục bộ cho lần gọi hàm hiện tại. |
| BEGIN ... END | Khối xử lý PL/pgSQL; BEGIN trong thân hàm không tự mở transaction mới. |
| TG_OP / TG_TABLE_NAME | Thao tác INSERT/UPDATE/DELETE và bảng đã kích hoạt trigger. |
| NEW / OLD | Hàng sau/trước thay đổi; INSERT dùng NEW, DELETE dùng OLD, UPDATE có cả hai. |
| := | Gán biến hoặc trường của NEW trong hàm. |
| PERFORM | Chạy SELECT nhưng không giữ dữ liệu trả về; vẫn có thể lấy khóa. |
| SELECT ... INTO | Đưa kết quả vào biến cục bộ; FOUND cho biết có tìm được hàng hay không. |
| FOR UPDATE | Khóa các hàng tìm thấy; giữ đến khi transaction kết thúc, có thể chờ writer khác. Không khóa toàn bảng. |
| IS DISTINCT FROM | So sánh thay đổi có xử lý NULL rõ ràng. |
| RAISE EXCEPTION ... ERRCODE | Phát sinh lỗi SQL; 23503 dùng cho liên kết không tồn tại, 23514 cho vi phạm quy tắc dữ liệu trong các hàm này. Không phải HTTP status. |
| BEFORE ... FOR EACH ROW | Chạy trước khi ghi từng hàng; RETURN NEW/OLD cho phép ghi tiếp, exception chặn thao tác. |
| AFTER ... FOR EACH ROW | Chạy sau thay đổi hàng trong cùng transaction; không phải chạy sau commit. |
| DEFERRABLE INITIALLY DEFERRED | Mặc định kiểm cuối transaction, có thể ép sớm bằng SET CONSTRAINTS. |

Transaction nghiệp vụ do caller/service quản lý. Hàm, procedure và trigger cùng commit/rollback khi dùng cùng transaction/connection; V3 không có COMMIT riêng. Nếu có exception chưa được xử lý, không được báo thành công như thể transaction đã commit. SAVEPOINT/exception handler là cơ chế phục hồi riêng, không được thêm vào hành vi V3 khi giải thích.

## 1. validate_comment — Kiểm comment trước khi ghi

```sql
CREATE FUNCTION validate_comment() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    parent_deleted TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.blog_id IS DISTINCT FROM OLD.blog_id
           OR NEW.parent_id IS DISTINCT FROM OLD.parent_id THEN
            RAISE EXCEPTION 'COMMENT_STRUCTURE_IMMUTABLE' USING ERRCODE = '23514';
        END IF;
        IF NEW.author_id IS DISTINCT FROM OLD.author_id AND NEW.author_id IS NOT NULL THEN
            RAISE EXCEPTION 'COMMENT_AUTHOR_IMMUTABLE' USING ERRCODE = '23514';
        END IF;
        IF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
            RAISE EXCEPTION 'COMMENT_DELETION_IMMUTABLE' USING ERRCODE = '23514';
        END IF;
        IF NEW.content IS DISTINCT FROM OLD.content OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
           OR NEW.author_id IS DISTINCT FROM OLD.author_id THEN
            NEW.version := OLD.version + 1;
            NEW.updated_at := clock_timestamp();
        ELSE
            -- Counter updates and repeated deletion must not invalidate an editor's version.
            NEW.version := OLD.version;
            NEW.updated_at := OLD.updated_at;
        END IF;
        RETURN NEW;
    END IF;

    PERFORM 1 FROM blogs WHERE id = NEW.blog_id FOR UPDATE;
    IF NEW.parent_id IS NOT NULL THEN
        SELECT deleted_at INTO parent_deleted FROM comments
        WHERE id = NEW.parent_id AND blog_id = NEW.blog_id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'COMMENT_PARENT_NOT_FOUND' USING ERRCODE = '23503';
        END IF;
        IF parent_deleted IS NOT NULL THEN
            RAISE EXCEPTION 'COMMENT_DELETED' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER comments_validate BEFORE INSERT OR UPDATE ON comments
FOR EACH ROW EXECUTE FUNCTION validate_comment();
```

Ví dụ: Bình sửa comment phiên bản 3. Nếu nội dung đổi, phiên bản thành 4; một lượt like chỉ đổi counter và vẫn giữ phiên bản 3.

1. `parent_deleted` giữ thời điểm xóa của parent khi thêm reply; đây là biến cục bộ, không phải cột được thêm vào comments.
2. `TG_OP = 'UPDATE'` tách sửa hàng có sẵn khỏi INSERT. Nhánh UPDATE kết thúc bằng `RETURN NEW`, nên sửa nội dung/counter không chạy lại bước khóa và kiểm parent của INSERT.
3. Ba phép `IS DISTINCT FROM` đầu chặn đổi `id`, `blog_id`, `parent_id`: không chuyển comment sang bài hoặc cha khác. Phép so sánh này xử lý cả NULL, ví dụ đổi parent từ NULL sang UUID vẫn bị phát hiện.
4. Điều kiện author chặn đổi sang một user khác và chặn khôi phục tác giả từ NULL. Chuyển author sang NULL được phép ở nhánh này; CHECK trạng thái trong V1 vẫn bắt comment còn hoạt động phải có tác giả. Service phải xóa nội dung trước khi gỡ tác giả khi xóa tài khoản.
5. Khi OLD.deleted_at đã có giá trị, không được đổi timestamp hay đặt lại NULL. Đây là chặn khôi phục comment và ghi lại thời điểm xóa khi request xóa lặp.
6. Nếu content/deleted_at/author_id thật sự đổi, gán `NEW.version := OLD.version + 1` và thời gian thực `clock_timestamp()`. Đây là sửa cùng một record, không tạo lịch sử. Nếu ba trường không đổi, giữ nguyên version/updated_at, kể cả Java gửi giá trị khác hoặc trigger like cập nhật counter.
7. `RETURN NEW` trong BEFORE UPDATE trả hàng đã được điều chỉnh để PostgreSQL tiếp tục ghi. Nó không phải COMMIT hay response API. Trigger không tự phát hiện bản client cũ: câu UPDATE vẫn cần `WHERE version = :expectedVersion`, kiểm số hàng và trả 409 khi xung đột.
8. Nhánh INSERT dùng `PERFORM 1 ... FOR UPDATE` để khóa blog mà không cần giữ kết quả SELECT. Nếu blog không tồn tại, lệnh này không khóa được hàng; FK của V1 mới là lớp từ chối liên kết không hợp lệ.
9. `parent_id IS NOT NULL` nghĩa là reply. SELECT cha phải khớp cả id và blog_id, đồng thời khóa cha. `NOT FOUND` trả lỗi 23503; cha đã xóa trả 23514. Thứ tự blog → comment được dùng để phối hợp các luồng ghi.
10. Không còn phép đếm cấp hoặc CTE tổ tiên. Parent ở cấp 5, 99 hay sâu hơn đều có thể nhận reply nếu các điều kiện còn lại hợp lệ. Không có cột depth phải cập nhật khi sửa nội dung/xóa comment.
11. Cây không vòng được giữ từ database sạch: PK yêu cầu ID mới, BEFORE INSERT yêu cầu parent đã tồn tại, BEFORE UPDATE cấm đổi ID/blog/parent. Node mới chỉ nối vào cây đã có nên không thể là ancestor của chính mình. Tự trỏ hoặc cặp node trỏ vòng trong cùng INSERT bị chặn ngay vì parent chưa tồn tại; đổi parent của root sang descendant cũng bị chặn. Không cần duyệt ancestor mỗi lần ghi. Dữ liệu nhập bằng quyền quản trị tắt trigger cần được kiểm riêng.
12. Chỉ cha trực tiếp phải chưa xóa. Ví dụ comment cấp 1 thành placeholder, comment cấp 100 vẫn đang hoạt động thì được trả lời để tạo cấp 101; các replies cũ vẫn tồn tại và counter chỉ đếm node chưa xóa.
13. `comments_validate BEFORE INSERT OR UPDATE ... FOR EACH ROW` gọi hàm cho mỗi hàng thật sự được ghi. Một UPDATE 10 hàng có thể gọi 10 lần, UPDATE không khớp hàng nào không gọi row trigger.

Dùng trigger giúp các đường ghi cùng chịu quy tắc cấu trúc/version, giảm nguy cơ một repository quên tăng phiên bản. Nếu chỉ dùng Java, phải bảo đảm mọi caller và tác vụ quản trị đều làm đúng. Bỏ CTE giảm công việc duyệt tổ tiên khi thêm reply; INSERT vẫn khóa blog và parent, UPDATE vẫn kiểm bất biến. Không giới hạn độ sâu nghiệp vụ không có nghĩa API/JSON/trình duyệt có tài nguyên vô hạn: khi triển khai đọc cây phải tránh đệ quy phụ thuộc call stack và chốt phân trang/tải từng nhóm cho thảo luận lớn.

## 2. update_relation_counter — Đếm likes và shares

```sql
CREATE FUNCTION update_relation_counter() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    delta INTEGER := CASE WHEN TG_OP = 'INSERT' THEN 1 ELSE -1 END;
BEGIN
    CASE TG_TABLE_NAME
        WHEN 'blog_likes' THEN
            UPDATE blogs SET likes_count = likes_count + delta
            WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.blog_id ELSE OLD.blog_id END;
        WHEN 'blog_shares' THEN
            UPDATE blogs SET shares_count = shares_count + delta
            WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.blog_id ELSE OLD.blog_id END;
        WHEN 'comment_likes' THEN
            UPDATE comments SET likes_count = likes_count + delta
            WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.comment_id ELSE OLD.comment_id END;
    END CASE;
    RETURN NULL;
END;
$$;
CREATE TRIGGER blog_likes_counter AFTER INSERT OR DELETE ON blog_likes
FOR EACH ROW EXECUTE FUNCTION update_relation_counter();
CREATE TRIGGER comment_likes_counter AFTER INSERT OR DELETE ON comment_likes
FOR EACH ROW EXECUTE FUNCTION update_relation_counter();
CREATE TRIGGER blog_shares_counter AFTER INSERT OR DELETE ON blog_shares
FOR EACH ROW EXECUTE FUNCTION update_relation_counter();
```

Ví dụ: bài có 20 likes; thêm một quan hệ blog_likes làm tổng thành 21. Gỡ đúng quan hệ đó làm tổng về 20. Nếu INSERT bị ON CONFLICT DO NOTHING và không thêm hàng, AFTER INSERT không tăng tổng.

- `delta` khởi tạo +1 cho INSERT, -1 cho DELETE. Hàm này chỉ được ba trigger cuối đoạn gắn vào INSERT/DELETE.
- `TG_TABLE_NAME` chọn counter: blog_likes → blogs.likes_count; blog_shares → blogs.shares_count; comment_likes → comments.likes_count.
- INSERT lấy ID từ NEW; DELETE lấy từ OLD vì hàng mới không tồn tại trong thao tác xóa.
- `SET ... = ... + delta` cập nhật giá trị ngay trong PostgreSQL. Các writer vào cùng hàng được tuần tự hóa bằng row lock; không có bước Java đọc số cũ rồi ghi đè số mới. Nhiều người like cùng bài vẫn có thể phải chờ khóa hàng blog; tối ưu constraint không loại bỏ điểm tranh chấp này.
- UPDATE counter của comment sẽ gọi comments_validate, nhưng nhánh giữ version ở hàm trước giúp lượt like không làm bản sửa nội dung bị cũ.
- `RETURN NULL` là hợp lệ trong AFTER trigger vì giá trị trả về bị bỏ qua; các UPDATE trong thân hàm vẫn có tác dụng. Nếu transaction rollback, cả quan hệ và counter đều rollback.
- Ba CREATE TRIGGER cuối đoạn gắn cùng một hàm cho ba bảng, chạy mỗi hàng sau INSERT/DELETE. Xóa cascade cũng có thể kích hoạt; nếu hàng cha đã mất, UPDATE counter có thể ảnh hưởng 0 hàng và không tạo lại cha.

Counter lưu sẵn giúp feed đọc trực tiếp số tổng. Nếu không lưu counter, có thể COUNT quan hệ khi đọc để giảm logic ghi, đổi lại truy vấn danh sách phải tổng hợp thêm. Thiết kế hiện chọn counter; Java không được tăng/trừ thêm lần nữa. Cần đối soát counter vì quyền DML trực tiếp có thể bị dùng sai.

## 3. reject_relation_update — Không sửa trực tiếp quan hệ like/share

```sql
CREATE FUNCTION reject_relation_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'RELATION_IMMUTABLE_USE_INSERT_DELETE' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER blog_likes_immutable BEFORE UPDATE ON blog_likes
FOR EACH ROW EXECUTE FUNCTION reject_relation_update();
CREATE TRIGGER comment_likes_immutable BEFORE UPDATE ON comment_likes
FOR EACH ROW EXECUTE FUNCTION reject_relation_update();
CREATE TRIGGER blog_shares_immutable BEFORE UPDATE ON blog_shares
FOR EACH ROW EXECUTE FUNCTION reject_relation_update();
```

Ví dụ: Bình đã like bài A. Không được UPDATE bản ghi đó để biến thành like bài B; phải DELETE quan hệ ở A và INSERT quan hệ ở B.

- Thân hàm chỉ RAISE `RELATION_IMMUTABLE_USE_INSERT_DELETE`, SQLSTATE 23514, không có đường thành công.
- Ba BEFORE UPDATE trigger áp dụng cho blog_likes, comment_likes, blog_shares. Gán lại cùng giá trị hay chỉ đổi timestamp vẫn là UPDATE và bị từ chối nếu có hàng khớp.
- Không cần RETURN ở cuối vì luôn phát sinh exception.

Quy tắc giúp counter chỉ phải xử lý INSERT/DELETE, không phải tính lại OLD/NEW cho trường hợp chuyển quan hệ. Bỏ trigger này thì phải cho phép/chặn UPDATE ở nơi khác hoặc bổ sung counter cho UPDATE; chỉ giữ hàm counter hiện tại rồi cho UPDATE quan hệ sẽ làm sai tổng.

## 4. update_comment_counter — Chỉ đếm bình luận còn hoạt động

```sql
CREATE FUNCTION update_comment_counter() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    delta INTEGER;
    target UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        delta := CASE WHEN NEW.deleted_at IS NULL THEN 1 ELSE 0 END;
        target := NEW.blog_id;
    ELSIF TG_OP = 'DELETE' THEN
        delta := CASE WHEN OLD.deleted_at IS NULL THEN -1 ELSE 0 END;
        target := OLD.blog_id;
    ELSE
        delta := (CASE WHEN NEW.deleted_at IS NULL THEN 1 ELSE 0 END)
               - (CASE WHEN OLD.deleted_at IS NULL THEN 1 ELSE 0 END);
        target := NEW.blog_id;
    END IF;
    IF delta <> 0 THEN
        UPDATE blogs SET comments_count = comments_count + delta WHERE id = target;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER comments_counter AFTER INSERT OR DELETE OR UPDATE OF deleted_at ON comments
FOR EACH ROW EXECUTE FUNCTION update_comment_counter();
```

Ví dụ độc lập với cùng mức ban đầu 3 comment còn hoạt động: thêm comment → 4; xóa một comment → 2; sửa nội dung hoặc xóa lặp → vẫn 3 nếu trước thao tác có 3.

- `delta` là phần tăng/giảm, `target` là blog cần cập nhật.
- INSERT: NEW.deleted_at NULL thì +1, placeholder mới thì 0; lấy NEW.blog_id.
- DELETE vật lý: OLD.deleted_at NULL thì -1, node đã xóa trước đó thì 0; lấy OLD.blog_id.
- UPDATE: tính `live(NEW) - live(OLD)` với live=1 khi chưa xóa, ngược lại 0. Xóa mềm lần đầu: 0-1=-1; xóa lặp: 0-0=0. Công thức có trường hợp khôi phục +1, nhưng validate_comment đã chặn khôi phục deleted_at.
- Chỉ UPDATE blogs khi delta khác 0 để tránh ghi/khóa thêm không cần thiết.
- `comments_counter AFTER INSERT OR DELETE OR UPDATE OF deleted_at` bắt mọi thêm/xóa hàng và UPDATE có deleted_at trong SET. UPDATE chỉ content hoặc likes_count không gọi counter này. `SET deleted_at=deleted_at` vẫn kích hoạt, nhưng delta=0 nên không trừ tiếp.
- `RETURN NULL` không hủy thay đổi của AFTER trigger.

Nếu không dùng trigger, mỗi đường thêm, xóa mềm, cascade và xóa tài khoản phải tự duy trì tổng đúng. Trigger giúp giữ một nơi tính; đánh đổi là thêm ghi vào hàng blog và phải phân biệt xóa mềm với DELETE vật lý. API xóa comment dùng placeholder, không DELETE cả nhánh.

## 5. guard_comment_like — Kiểm bình luận trước khi thêm like

```sql
CREATE FUNCTION guard_comment_like() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    target_blog UUID;
    target_deleted TIMESTAMPTZ;
BEGIN
    SELECT blog_id INTO target_blog FROM comments WHERE id = NEW.comment_id;
    PERFORM 1 FROM blogs WHERE id = target_blog FOR UPDATE;
    SELECT deleted_at INTO target_deleted FROM comments WHERE id = NEW.comment_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'COMMENT_NOT_FOUND' USING ERRCODE = '23503';
    END IF;
    IF target_deleted IS NOT NULL THEN
        RAISE EXCEPTION 'COMMENT_DELETED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER comment_like_active BEFORE INSERT ON comment_likes
FOR EACH ROW EXECUTE FUNCTION guard_comment_like();
```

Ví dụ: Bình like comment đã bị xóa trong lúc trang vẫn đang mở; database từ chối thay vì tạo lượt like trên placeholder.

1. SELECT blog_id để biết bài cần khóa. Biến target_blog nhận kết quả, chưa phải kiểm quyền.
2. `PERFORM ... FOR UPDATE` khóa blog trước.
3. SELECT lại deleted_at của comment với FOR UPDATE để kiểm trạng thái của hàng sau khi lấy khóa.
4. `NOT FOUND` trả COMMENT_NOT_FOUND/23503; deleted_at khác NULL trả COMMENT_DELETED/23514.
5. `RETURN NEW` cho BEFORE INSERT tiếp tục ghi quan hệ khi đạt hai điều kiện.
6. `comment_like_active BEFORE INSERT ON comment_likes` không chạy khi gỡ like bằng DELETE.

Lấy khóa phối hợp thao tác like và xóa cùng comment; comment đã biến mất giữa hai SELECT sẽ bị lần đọc sau từ chối. Hàm không xác thực actor, không kiểm published hay comment thuộc blog trong URL; V4/service còn phải làm các việc đó. Nếu chỉ đọc trạng thái ở frontend hoặc SELECT không khóa rồi mới INSERT, khoảng cách giữa kiểm và ghi có thể gây race. Lợi ích bảo vệ ở DB đi cùng chi phí lấy khóa.

## 6. check_deleted_comment_likes — Comment đã xóa không được còn likes

```sql
CREATE FUNCTION check_deleted_comment_likes() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT FROM comments c WHERE c.id = NEW.id AND c.deleted_at IS NOT NULL
               AND (c.likes_count <> 0 OR EXISTS (SELECT FROM comment_likes l WHERE l.comment_id = c.id))) THEN
        RAISE EXCEPTION 'DELETED_COMMENT_HAS_LIKES' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER deleted_comment_no_likes
AFTER INSERT OR UPDATE ON comments DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW WHEN (NEW.deleted_at IS NOT NULL) EXECUTE FUNCTION check_deleted_comment_likes();
```

Ví dụ: comment đang có 2 likes. Transaction có thể đặt content=NULL/deleted_at trước, sau đó DELETE đủ 2 likes. Cuối cùng phải không còn quan hệ và likes_count=0; nếu thiếu bước dọn likes thì không commit được.

- EXISTS ngoài đọc lại comments theo NEW.id. NEW.id chỉ xác định hàng cần kiểm; nội dung/trạng thái dùng dữ liệu hiện tại trong bảng.
- Nếu hàng không còn tồn tại hoặc hiện chưa xóa, hàm không báo lỗi.
- Với node đã xóa, chỉ cần likes_count khác 0 HOẶC còn bất kỳ hàng comment_likes là vi phạm. Kiểm cả hai để không bỏ qua trường hợp counter và quan hệ lệch nhau.
- RAISE DELETED_COMMENT_HAS_LIKES/23514 từ chối trạng thái cuối; hàm không tự DELETE likes hoặc tự sửa counter.
- `AFTER INSERT OR UPDATE ... FOR EACH ROW` vẫn bao phủ các lần ghi vào comment.
- **Thay đổi đã áp dụng:** `WHEN (NEW.deleted_at IS NOT NULL)` xét ngay sau thao tác ghi. Comment còn hoạt động không được xếp lịch chạy hàm deferred. WHEN không tự chờ tới COMMIT.
- `DEFERRABLE INITIALLY DEFERRED` mặc định chạy kiểm ở cuối transaction; `SET CONSTRAINTS deleted_comment_no_likes IMMEDIATE` có thể ép kiểm sớm. Một node đã xóa được UPDATE counter khi gỡ likes vẫn có thể xếp thêm lần kiểm; đây không phải cơ chế gộp mỗi ID thành một lần.

Dùng deferred cho phép trạng thái trung gian còn likes trong cùng transaction. Nếu kiểm tức thời ngay sau đặt placeholder, thứ tự đặt placeholder rồi gỡ likes sẽ thất bại dù trạng thái cuối hợp lệ. WHEN giảm số lần xếp lịch của comment hoạt động nhưng giữ kiểm toàn vẹn cho node đã xóa. Thời điểm WHEN và UPDATE OF theo [PostgreSQL 17 CREATE TRIGGER](https://www.postgresql.org/docs/17/sql-createtrigger.html).

## 7. lock_image_blog — Khóa bài khi thay đổi liên kết ảnh

```sql
CREATE FUNCTION lock_image_blog() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.blog_id IS DISTINCT FROM OLD.blog_id OR NEW.asset_id IS DISTINCT FROM OLD.asset_id) THEN
        RAISE EXCEPTION 'IMAGE_LINK_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        PERFORM 1 FROM blogs WHERE id = OLD.blog_id FOR UPDATE;
        RETURN OLD;
    END IF;
    PERFORM 1 FROM blogs WHERE id = NEW.blog_id FOR UPDATE;
    RETURN NEW;
END;
$$;
CREATE TRIGGER image_blog_lock BEFORE INSERT OR UPDATE OR DELETE ON blog_images
FOR EACH ROW EXECUTE FUNCTION lock_image_blog();
```

Ví dụ: ảnh M của bài A được đổi position từ 0 sang 1 nếu vị trí hợp lệ. Không được UPDATE liên kết để M thuộc bài B hoặc thay M bằng asset khác.

- Điều kiện đầu chỉ áp dụng UPDATE: đổi blog_id hoặc asset_id thì RAISE IMAGE_LINK_IMMUTABLE/23514. IS DISTINCT FROM vẫn phát hiện thay đổi liên quan NULL; NOT NULL/FK của V1 tiếp tục áp dụng.
- DELETE dùng OLD.blog_id để khóa blog và RETURN OLD. Không có NEW khi xóa hàng.
- INSERT hoặc UPDATE hợp lệ dùng NEW.blog_id để khóa blog, rồi RETURN NEW. Position không nằm trong danh sách cấm nên có thể đổi; UNIQUE/range của V1 vẫn kiểm thứ tự.
- `image_blog_lock BEFORE INSERT OR UPDATE OR DELETE` lấy khóa trước khi ghi liên kết, giúp phối hợp các thay đổi ảnh trên cùng bài với kiểm bài không rỗng.

Nếu không khóa thống nhất, hai transaction có thể kiểm điều kiện ảnh theo các trạng thái không phối hợp với nhau. Trigger này chỉ giải quyết khóa và định danh liên kết; không xác nhận owner/purpose/state của media, không upload hoặc xóa Cloudinary. Service vẫn cần khóa asset và kiểm các quy tắc attach/cleanup.

## 8. check_nonempty_blog — Bài còn tồn tại phải có chữ hoặc ảnh

```sql
CREATE FUNCTION check_nonempty_blog() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    target UUID;
    body TEXT;
BEGIN
    IF TG_TABLE_NAME = 'blogs' THEN
        target := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    ELSE
        target := CASE WHEN TG_OP = 'DELETE' THEN OLD.blog_id ELSE NEW.blog_id END;
    END IF;
    SELECT content INTO body FROM blogs WHERE id = target FOR UPDATE;
    IF FOUND AND length(btrim(body)) = 0 AND NOT EXISTS (SELECT FROM blog_images WHERE blog_id = target) THEN
        RAISE EXCEPTION 'BLOG_REQUIRES_TEXT_OR_IMAGE' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER blogs_nonempty AFTER INSERT OR UPDATE OF content ON blogs
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_nonempty_blog();
CREATE CONSTRAINT TRIGGER blog_images_nonempty AFTER INSERT OR UPDATE OR DELETE ON blog_images
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_nonempty_blog();
```

Ví dụ: An tạo bài chỉ có ảnh. INSERT blogs với content rỗng rồi INSERT blog_images trong cùng transaction được phép; xóa ảnh cuối mà không thêm chữ/ảnh thay thế thì bị từ chối.

1. `target UUID` giữ ID bài; `body TEXT` giữ content đọc lại.
2. TG_TABLE_NAME='blogs' thì lấy ID từ hàng blogs; nhánh blog_images lấy blog_id. DELETE phải đọc OLD, các trường hợp khác đọc NEW. Hàm có nhánh OLD.id cho blogs DELETE nhưng V3 không gắn trigger blogs DELETE.
3. SELECT content FOR UPDATE vừa đọc trạng thái hiện tại vừa khóa hàng blog. Không dùng content từ một sự kiện cũ để kết luận trạng thái cuối.
4. `FOUND` false khi blog đã bị xóa: bỏ qua kiểm vì không còn bài cần bảo vệ.
5. Khi FOUND true, content sau btrim rỗng và không tồn tại blog_images thì RAISE BLOG_REQUIRES_TEXT_OR_IMAGE/23514. Cả hai điều kiện rỗng phải cùng đúng. Content NOT NULL đã do V1 bảo đảm. btrim mặc định xử lý dấu cách ở đầu/cuối, không phải công cụ lọc HTML.
6. `RETURN NULL` hoàn tất AFTER constraint trigger.
7. **Thay đổi đã áp dụng:** `blogs_nonempty AFTER INSERT OR UPDATE OF content ON blogs` chỉ xếp lịch cho tạo bài hoặc UPDATE nhắm tới content. Like/share, sửa counters hoặc published không xếp lịch. `SET content=content` vẫn kích hoạt vì UPDATE OF xét cột trong SET, không so sánh giá trị.
8. `blog_images_nonempty AFTER INSERT OR UPDATE OR DELETE` giữ nguyên. Kể cả UPDATE position hiện vẫn xếp lịch kiểm; lần tối ưu này chưa loại riêng thao tác đó.
9. Cả hai DEFERRABLE INITIALLY DEFERRED nên cho phép blog rỗng tạm thời, nhưng trạng thái cuối phải hợp lệ. Ép SET CONSTRAINTS IMMEDIATE có thể làm kiểm diễn ra sớm hơn.

Một CHECK đơn giản trên blogs không SELECT ảnh ở bảng khác để bảo đảm quy tắc này theo thiết kế hiện tại. Kiểm chỉ trong service dễ bị bỏ sót ở đường ghi khác. Constraint deferred gom các bước của một nghiệp vụ vào cùng điều kiện cuối; chi phí là đọc/khóa và có thể kiểm nhiều lần nếu cùng bài đổi nhiều hàng.

## 9. guard_media_identity — Giữ định danh và vòng đời media

```sql
CREATE FUNCTION guard_media_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.purpose IS DISTINCT FROM OLD.purpose
       OR NEW.provider_public_id IS DISTINCT FROM OLD.provider_public_id
       OR (OLD.provider_asset_id IS NOT NULL AND NEW.provider_asset_id IS DISTINCT FROM OLD.provider_asset_id) THEN
        RAISE EXCEPTION 'MEDIA_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'DELETE_PENDING' AND NEW.state <> 'DELETE_PENDING' THEN
        RAISE EXCEPTION 'MEDIA_PENDING_DELETION' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ATTACHED' AND NEW.state NOT IN ('ATTACHED', 'DELETE_PENDING') THEN
        RAISE EXCEPTION 'ATTACHED_MEDIA_STATE_CANNOT_REVERT' USING ERRCODE = '23514';
    END IF;
    IF OLD.provider_asset_id IS NOT NULL
       AND (NEW.format IS DISTINCT FROM OLD.format OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes) THEN
        RAISE EXCEPTION 'CONFIRMED_MEDIA_METADATA_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER media_identity_immutable BEFORE UPDATE ON media_assets
FOR EACH ROW EXECUTE FUNCTION guard_media_identity();
```

Ví dụ: ảnh đã xác nhận và ATTACHED không được sửa public ID để trỏ sang file khác; muốn đổi ảnh phải dùng asset mới. Ảnh chờ xóa không được quay lại sử dụng.

- Nhóm 1 chặn đổi id, purpose, provider_public_id. Với provider_asset_id, OLD NULL được phép xác nhận lần đầu; OLD đã có giá trị thì không được đổi hay xóa. Lỗi MEDIA_IDENTITY_IMMUTABLE/23514.
- Nhóm 2: OLD.state=DELETE_PENDING thì NEW.state phải tiếp tục DELETE_PENDING. Lỗi MEDIA_PENDING_DELETION nếu quay lui. DELETE vật lý sau khi dọn provider thành công là thao tác khác, không phải UPDATE đổi state.
- Nhóm 3: OLD.state=ATTACHED thì chỉ giữ ATTACHED hoặc chuyển DELETE_PENDING; không quay về PENDING. Lỗi ATTACHED_MEDIA_STATE_CANNOT_REVERT.
- Nhóm 4: khi OLD.provider_asset_id đã có, format và size_bytes không được đổi; lỗi CONFIRMED_MEDIA_METADATA_IMMUTABLE. Lúc xác nhận upload lần đầu, bộ metadata đầy đủ còn phải qua CHECK V1.
- `RETURN NEW` cho BEFORE UPDATE tiếp tục khi không vi phạm. `media_identity_immutable BEFORE UPDATE ON media_assets` không chạy cho INSERT hay DELETE; CHECK/UNIQUE/FK của V1 vẫn bảo vệ các thao tác tương ứng.

Trigger giúp một asset luôn trỏ đến cùng tài sản đã xác nhận và tránh tái sử dụng media đang dọn. Nếu bỏ nó, mọi caller phải tự giữ vòng đời và định danh nhất quán. Nó không kiểm toàn bộ quy trình gắn ảnh, không gọi Cloudinary, không phải worker dọn media và không thay transaction/lease của service.

## Kiểm chứng sau thay đổi

- PostgreSQL WASM 17.5/PGlite 0.3.14, database mới trong bộ nhớ: chạy V1–V5 theo thứ tự bằng role migrator.
- [invariants.sql](../../infra/postgres/tests/invariants.sql), [public_blog_feed.sql](../../infra/postgres/tests/public_blog_feed.sql), [blog_categories.sql](../../infra/postgres/tests/blog_categories.sql) chạy đạt với runtime role và rollback fixture.
- Bổ sung ca đặt placeholder trước rồi gỡ likes; ép kiểm khi còn likes thất bại, gỡ đủ thì đạt. Bổ sung ca UPDATE content rỗng bị chặn và giữ nội dung trước lỗi.
- Chuỗi reply qua cấp 6 đến cấp 100 được tạo và đếm đúng; reply cấp 101 vẫn hợp lệ khi ancestor là placeholder. Parent sai bài/không tồn tại, tự trỏ, cặp node trỏ vòng trong một INSERT và UPDATE đổi ID/blog/parent bị chặn. Bộ test mới thất bại với V3 cũ vì giới hạn độ sâu, chạy đạt với V3 đã sửa.
- 100/101 là kích thước fixture để kiểm vượt giới hạn cũ, không phải giới hạn mới trong nghiệp vụ hoặc SQL.
- [verify-v3.cjs](../../infra/postgres/tests/verify-v3.cjs) instrument hai hàm kiểm trong transaction thử nghiệm rồi rollback: like bài/comment, đổi published và sửa comment hoạt động không gọi hai hàm deferred; sửa content vẫn gọi nonempty; xóa comment vẫn gọi kiểm likes.
- Đây không phải đo tốc độ, tải 100 user, Docker/JDBC/Flyway/Neon hoặc API. Docker daemon không khả dụng trong lần kiểm này. Không dùng số PASS SQL để khẳng định các môi trường đó đã chạy.

[Sơ đồ A/B của V3](../diagram/README.md) dùng ví dụ trước rồi tới luồng SQL; [báo cáo SQL](08-bao-cao-tong-ket-sql.md) tổng hợp mục đích cho báo cáo cuối kỳ.
