-- kiểm tra bình luận trước khi thêm hoặc cập nhật.
-- Kiểm tra để tránh trường hợp thêm 1 bình luận nhưng sau đó bình luận bị xoá mà chưa bấm gửi
CREATE FUNCTION validate_comment()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
DECLARE
-- chứa thời điểm xóa của bình luận cha:
-- NULL: cha chưa bị xóa.
-- Có thời gian: cha đã bị xóa mềm.
    parent_deleted TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE' THEN
    -- Không cho đổi cấu trúc bình luận
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.blog_id IS DISTINCT FROM OLD.blog_id
           OR NEW.parent_id IS DISTINCT FROM OLD.parent_id THEN
            RAISE EXCEPTION 'COMMENT_STRUCTURE_IMMUTABLE' USING ERRCODE = '23514';
        END IF;
        --Không cho chuyển bình luận sang tác giả khác
        IF NEW.author_id IS DISTINCT FROM OLD.author_id AND NEW.author_id IS NOT NULL THEN
            RAISE EXCEPTION 'COMMENT_AUTHOR_IMMUTABLE' USING ERRCODE = '23514';
        END IF;
        --Bình luận đã xóa thì không được đổi thời điểm xóa
        IF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
            RAISE EXCEPTION 'COMMENT_DELETION_IMMUTABLE' USING ERRCODE = '23514';
        END IF;
        --Chỉ tăng version khi thay đổi nội dung hoặc trạng thái quan trọng
        IF NEW.content IS DISTINCT FROM OLD.content OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
           OR NEW.author_id IS DISTINCT FROM OLD.author_id THEN
            -- Update the version and updated_at fields when the comment is modified.
            NEW.version := OLD.version + 1;
            NEW.updated_at := clock_timestamp();
        ELSE
        --Giữ nguyên phiên bản và thời điểm sửa.
            -- Counter updates and repeated deletion must not invalidate an editor's version.
            NEW.version := OLD.version;
            NEW.updated_at := OLD.updated_at;
        END IF;
        RETURN NEW;
    END IF;
    -- Lock the blog before its parent comment to match the write lock order.
    --Với INSERT, khóa bài trước
    PERFORM 1 FROM blogs
    WHERE id = NEW.blog_id
    FOR UPDATE;
    --Chỉ kiểm tra cha khi đang thêm phản hồi
    IF NEW.parent_id IS NOT NULL THEN
        SELECT deleted_at INTO parent_deleted FROM comments
        WHERE id = NEW.parent_id
          AND blog_id = NEW.blog_id
        FOR UPDATE;
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

CREATE TRIGGER comments_validate
BEFORE INSERT OR UPDATE ON comments
FOR EACH ROW
EXECUTE FUNCTION validate_comment();


-- Dùng cập nhật số lượt thích, cmt, chia sẻ
-- Counters change only when a like/share row is inserted or deleted.
CREATE FUNCTION update_relation_counter()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
DECLARE
    delta INTEGER := CASE WHEN TG_OP = 'INSERT' THEN 1 ELSE -1 END;
BEGIN
    CASE TG_TABLE_NAME
        WHEN 'blog_likes' THEN
            UPDATE blogs
            SET likes_count = likes_count + delta
            WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.blog_id ELSE OLD.blog_id END;
        WHEN 'blog_shares' THEN
            UPDATE blogs
            SET shares_count = shares_count + delta
            WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.blog_id ELSE OLD.blog_id END;
        WHEN 'comment_likes' THEN
            UPDATE comments
            SET likes_count = likes_count + delta
            WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.comment_id ELSE OLD.comment_id END;
    END CASE;
    RETURN NULL;
    --Hàm này dùng với AFTER trigger. PostgreSQL bỏ qua giá trị trả về của AFTER trigger, nên dùng RETURN NULL là phù hợp.
END;
$$;

-- Thực hiện cập nhật số lượt thích, chia sẻ, bình luận khi có INSERT hoặc DELETE trên các bảng liên quan.
CREATE TRIGGER blog_likes_counter
AFTER INSERT OR DELETE ON blog_likes --Chạy sau khi thêm hoặc xóa hàng trong blog_likes
FOR EACH ROW
EXECUTE FUNCTION update_relation_counter();

/*
Thêm một hàng vào blog_likes
→ blog_likes_counter chạy
→ update_relation_counter() nhận:
    TG_TABLE_NAME = 'blog_likes'
    TG_OP = 'INSERT'
→ Tăng blogs.likes_count của bài A thêm 1
*/
CREATE TRIGGER comment_likes_counter
AFTER INSERT OR DELETE ON comment_likes
FOR EACH ROW
EXECUTE FUNCTION update_relation_counter();

CREATE TRIGGER blog_shares_counter
AFTER INSERT OR DELETE ON blog_shares
FOR EACH ROW
EXECUTE FUNCTION update_relation_counter();

--chặn thao tác UPDATE trên các quan hệ thích/chia sẻ
-- vì cập nhật số lượt thích/chia sẻ chỉ xảy ra khi INSERT hoặc DELETE, không phải UPDATE.
-- Moving a like/share with UPDATE would bypass the INSERT/DELETE counters.
CREATE FUNCTION reject_relation_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'RELATION_IMMUTABLE_USE_INSERT_DELETE' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER blog_likes_immutable
BEFORE UPDATE ON blog_likes
FOR EACH ROW
EXECUTE FUNCTION reject_relation_update();

CREATE TRIGGER comment_likes_immutable
BEFORE UPDATE ON comment_likes
FOR EACH ROW
EXECUTE FUNCTION reject_relation_update();

CREATE TRIGGER blog_shares_immutable
BEFORE UPDATE ON blog_shares
FOR EACH ROW
EXECUTE FUNCTION reject_relation_update();


--Đếm đúng số bình luận còn hoạt động.
--Chặn thêm lượt thích vào bình luận đã xóa.
--Kiểm tra bình luận đã xóa không còn lượt thích.
-- Count active comments only; deleted placeholders remain in the tree.
CREATE FUNCTION update_comment_counter()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
DECLARE
    -- Số cần cộng/trừ và ID bài viết cần cập nhật bộ đếm.
    delta INTEGER;
    target UUID;
BEGIN
    -- Thêm bình luận đang hoạt động thì cộng 1, placeholder không được tính.
    IF TG_OP = 'INSERT' THEN
        delta := CASE WHEN NEW.deleted_at IS NULL THEN 1 ELSE 0 END; -- A new active comment adds one.
        target := NEW.blog_id;
    -- Xóa vật lý placeholder không giảm lần nữa vì đã bị loại khi xóa mềm.
    ELSIF TG_OP = 'DELETE' THEN
        delta := CASE WHEN OLD.deleted_at IS NULL THEN -1 ELSE 0 END; -- A placeholder was already excluded.
        target := OLD.blog_id;
    ELSE
        -- UPDATE: lấy trạng thái còn hoạt động sau thay đổi trừ trạng thái trước đó.
        -- Xóa mềm lần đầu giảm 1, giữ nguyên trạng thái xóa thì không giảm thêm.
        delta := (CASE WHEN NEW.deleted_at IS NULL THEN 1 ELSE 0 END)
               - (CASE WHEN OLD.deleted_at IS NULL THEN 1 ELSE 0 END); -- First soft delete: -1; repeated delete: 0.
        target := NEW.blog_id;
    END IF;
    -- Chỉ ghi lại bài khi số bình luận thực sự thay đổi.
    IF delta <> 0 THEN
        UPDATE blogs
        SET comments_count = comments_count + delta
        WHERE id = target;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER comments_counter
AFTER INSERT OR DELETE OR UPDATE OF deleted_at ON comments
FOR EACH ROW
EXECUTE FUNCTION update_comment_counter();


-- Direct INSERTs must also reject likes on deleted comments.
-- Kiểm tra bình luận còn tồn tại và chưa xóa trước khi thêm lượt thích.
CREATE FUNCTION guard_comment_like()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
DECLARE
    -- Bài chứa bình luận đang được thích.
    target_blog UUID;
    -- NULL nghĩa là bình luận chưa bị xóa mềm.
    target_deleted TIMESTAMPTZ;
BEGIN
    -- Tìm ID bài trước để giữ đúng thứ tự khóa blog rồi comment.
    SELECT blog_id INTO target_blog FROM comments
    WHERE id = NEW.comment_id;
    PERFORM 1 FROM blogs
    WHERE id = target_blog
    FOR UPDATE;
    -- Sau khi khóa bài, đọc lại trạng thái xóa và khóa bình luận.
    SELECT deleted_at INTO target_deleted FROM comments
    WHERE id = NEW.comment_id
    FOR UPDATE;
    -- Bình luận không còn tồn tại thì không được tạo quan hệ like.
    IF NOT FOUND THEN
        RAISE EXCEPTION 'COMMENT_NOT_FOUND' USING ERRCODE = '23503';
    END IF;
    -- Chặn cả INSERT trực tiếp vào bảng, không chỉ các lời gọi procedure.
    IF target_deleted IS NOT NULL THEN
        RAISE EXCEPTION 'COMMENT_DELETED' USING ERRCODE = '23514';
    END IF;
    -- BEFORE trigger cho phép tiếp tục INSERT khi đã qua các kiểm tra.
    RETURN NEW;
END;
$$;

CREATE TRIGGER comment_like_active
BEFORE INSERT ON comment_likes
FOR EACH ROW
EXECUTE FUNCTION guard_comment_like();

-- Check at transaction end so deletion and like removal can happen in either order.
-- Bình luận đã xóa phải hết lượt thích thật và có likes_count bằng 0.
CREATE FUNCTION check_deleted_comment_likes()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
BEGIN
    -- Đọc lại trạng thái hiện tại khi constraint chạy, không dùng bộ đếm cũ trong NEW.
    IF EXISTS (
        SELECT
        FROM comments c
        WHERE c.id = NEW.id
          AND c.deleted_at IS NOT NULL
          -- Chỉ cần bộ đếm khác 0 hoặc còn hàng like là dữ liệu chưa được dọn hết.
          AND (
              c.likes_count <> 0
              OR EXISTS (
                  SELECT
                  FROM comment_likes l
                  WHERE l.comment_id = c.id
              )
          )
    ) THEN
        -- Chỉ báo lỗi, hàm không tự xóa like hoặc đặt lại bộ đếm.
        RAISE EXCEPTION 'DELETED_COMMENT_HAS_LIKES' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

-- Mặc định kiểm cuối transaction để thao tác xóa mềm và dọn likes có thể thực hiện trước/sau nhau.
-- WHEN chỉ xếp lịch cho hàng đã xóa, hàm vẫn đọc lại dữ liệu cuối khi thực sự chạy.
CREATE CONSTRAINT TRIGGER deleted_comment_no_likes
AFTER INSERT OR UPDATE ON comments
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
WHEN (NEW.deleted_at IS NOT NULL)
EXECUTE FUNCTION check_deleted_comment_likes();

-- Serialize image changes on the blog before checking its final content.
-- Khóa bài khi thay đổi liên kết ảnh và không cho chuyển ảnh sang liên kết khác.
CREATE FUNCTION lock_image_blog()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
BEGIN
    -- Đổi vị trí ảnh vẫn được, nhưng blog_id và asset_id của liên kết phải giữ nguyên.
    IF TG_OP = 'UPDATE'
       AND (
           NEW.blog_id IS DISTINCT FROM OLD.blog_id
           OR NEW.asset_id IS DISTINCT FROM OLD.asset_id
       ) THEN
        RAISE EXCEPTION 'IMAGE_LINK_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    -- DELETE dùng hàng OLD vì không có hàng NEW sau khi xóa.
    IF TG_OP = 'DELETE' THEN
        PERFORM 1 FROM blogs
        WHERE id = OLD.blog_id
        FOR UPDATE;
        RETURN OLD;
    END IF;
    -- INSERT/UPDATE khóa bài từ NEW để phối hợp với kiểm tra bài không rỗng.
    PERFORM 1 FROM blogs
    WHERE id = NEW.blog_id
    FOR UPDATE;
    RETURN NEW;
END;
$$;

CREATE TRIGGER image_blog_lock
BEFORE INSERT OR UPDATE OR DELETE ON blog_images
FOR EACH ROW
EXECUTE FUNCTION lock_image_blog();

-- Check at transaction end so an image-only blog can be inserted before its images.
-- Bài còn tồn tại phải có nội dung chữ hoặc ảnh khi ràng buộc được kiểm tra.
CREATE FUNCTION check_nonempty_blog()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
DECLARE
    -- ID bài cần kiểm tra và nội dung hiện tại của bài.
    target UUID;
    body TEXT;
BEGIN
    -- Trigger có thể đến từ blogs hoặc blog_images, DELETE phải lấy ID từ OLD.
    IF TG_TABLE_NAME = 'blogs' THEN
        target := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    ELSE
        target := CASE WHEN TG_OP = 'DELETE' THEN OLD.blog_id ELSE NEW.blog_id END;
    END IF;
    -- Đọc lại và khóa bài để kiểm tra trạng thái hiện tại, không chỉ hàng lúc phát sinh trigger.
    SELECT content INTO body FROM blogs
    WHERE id = target
    FOR UPDATE;
    -- Bỏ qua bài đã xóa, chỉ chặn bài còn tồn tại mà cả chữ lẫn ảnh đều rỗng.
    IF FOUND
       AND length(btrim(body)) = 0
       AND NOT EXISTS (
           SELECT
           FROM blog_images
           WHERE blog_id = target
       ) THEN
        RAISE EXCEPTION 'BLOG_REQUIRES_TEXT_OR_IMAGE' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;


-- Kiểm cuối transaction để cho phép tạo bài chỉ có ảnh rồi mới thêm liên kết ảnh.
CREATE CONSTRAINT TRIGGER blogs_nonempty
AFTER INSERT OR UPDATE OF content ON blogs
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_nonempty_blog();
CREATE CONSTRAINT TRIGGER blog_images_nonempty
AFTER INSERT OR UPDATE OR DELETE ON blog_images
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_nonempty_blog();

-- Keep confirmed media identity stable and prevent reuse of media pending deletion.
-- Bảo vệ định danh media và chặn chuyển ngược các trạng thái đã gắn/chờ xóa.
CREATE FUNCTION guard_media_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- id/purpose/public_id không đổi, provider_asset_id chỉ bất biến sau khi đã xác nhận.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.purpose IS DISTINCT FROM OLD.purpose
       OR NEW.provider_public_id IS DISTINCT FROM OLD.provider_public_id
       OR (OLD.provider_asset_id IS NOT NULL AND NEW.provider_asset_id IS DISTINCT FROM OLD.provider_asset_id) THEN
        RAISE EXCEPTION 'MEDIA_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    -- Media đang chờ xóa không được chuyển trở lại trạng thái khác.
    IF OLD.state = 'DELETE_PENDING' AND NEW.state <> 'DELETE_PENDING' THEN
        RAISE EXCEPTION 'MEDIA_PENDING_DELETION' USING ERRCODE = '23514';
    END IF;
    -- Media đã gắn chỉ được giữ ATTACHED hoặc chuyển sang DELETE_PENDING.
    IF OLD.state = 'ATTACHED' AND NEW.state NOT IN ('ATTACHED', 'DELETE_PENDING') THEN
        RAISE EXCEPTION 'ATTACHED_MEDIA_STATE_CANNOT_REVERT' USING ERRCODE = '23514';
    END IF;
    -- Đã có xác nhận từ provider thì format và kích thước file phải giữ nguyên.
    IF OLD.provider_asset_id IS NOT NULL
       AND (NEW.format IS DISTINCT FROM OLD.format OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes) THEN
        RAISE EXCEPTION 'CONFIRMED_MEDIA_METADATA_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER media_identity_immutable
BEFORE UPDATE ON media_assets
FOR EACH ROW
EXECUTE FUNCTION guard_media_identity();

-- CHÚ GIẢI MÃ LỖI (ERRCODE / SQLSTATE) TRONG V3
-- ERRCODE là mã lỗi SQLSTATE gồm 5 ký tự, không phải mã HTTP.
-- Ví dụ: COMMENT_PARENT_NOT_FOUND là thông báo cụ thể; 23503 là loại lỗi.
-- Nhiều thông báo có thể dùng chung một ERRCODE nếu cùng loại lỗi.
-- Các RAISE EXCEPTION ở trên tự phát sinh mã này để bên gọi nhận biết lỗi.
-- Nguồn tên chuẩn: https://www.postgresql.org/docs/17/errcodes-appendix.html

-- 23503 = foreign_key_violation: lỗi liên kết tới dữ liệu không tồn tại/hợp lệ.
-- Trong V3, mã này được dùng khi không tìm thấy bình luận được tham chiếu.
-- COMMENT_PARENT_NOT_FOUND:
--   Không tìm thấy bình luận cha trong đúng bài đang thêm phản hồi.
-- COMMENT_NOT_FOUND:
--   Không tìm thấy bình luận được tham chiếu khi thêm lượt thích.

-- 23514 = check_violation: dữ liệu hoặc thay đổi vi phạm quy tắc kiểm tra.
-- Trong V3, mã này được dùng cho các quy tắc bảo vệ dữ liệu dưới đây.
-- COMMENT_STRUCTURE_IMMUTABLE:
--   Không được đổi id, blog_id hoặc parent_id của bình luận đã tạo.
-- COMMENT_AUTHOR_IMMUTABLE:
--   Không được chuyển tác giả sang user khác; vẫn cho phép gỡ về NULL.
-- COMMENT_DELETION_IMMUTABLE:
--   Bình luận đã xóa không được đổi hoặc xóa thời điểm deleted_at.
-- COMMENT_DELETED:
--   Không được trả lời trực tiếp hoặc thêm lượt thích vào bình luận đã xóa mềm.
-- RELATION_IMMUTABLE_USE_INSERT_DELETE:
--   Quan hệ like/share không được UPDATE; dùng INSERT hoặc DELETE.
-- DELETED_COMMENT_HAS_LIKES:
--   Bình luận đã xóa phải có likes_count = 0 và không còn hàng comment_likes.
-- IMAGE_LINK_IMMUTABLE:
--   Không được đổi blog_id hoặc asset_id của liên kết ảnh đã tạo.
-- BLOG_REQUIRES_TEXT_OR_IMAGE:
--   Bài còn tồn tại phải có chữ hoặc ít nhất một ảnh khi kiểm tra ràng buộc.
-- MEDIA_IDENTITY_IMMUTABLE:
--   Không được đổi id, purpose, provider_public_id hoặc provider_asset_id đã xác nhận.
-- MEDIA_PENDING_DELETION:
--   Media đang DELETE_PENDING không được chuyển sang trạng thái khác.
-- ATTACHED_MEDIA_STATE_CANNOT_REVERT:
--   Media ATTACHED chỉ được giữ ATTACHED hoặc chuyển DELETE_PENDING.
-- CONFIRMED_MEDIA_METADATA_IMMUTABLE:
--   Media đã xác nhận không được đổi format hoặc size_bytes.

-- Ghi thông báo khi một hàng tương tác thực sự được thêm, cùng transaction nghiệp vụ.
CREATE FUNCTION create_interaction_notification()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
DECLARE
    actor UUID;
    recipient UUID;
    blog_author UUID;
    target_blog UUID;
    target_comment UUID;
    notification_type VARCHAR(20);
BEGIN
    CASE TG_TABLE_NAME
        WHEN 'blog_likes', 'blog_shares' THEN
            actor := NEW.user_id;
            target_blog := NEW.blog_id;
            notification_type := CASE WHEN TG_TABLE_NAME = 'blog_likes'
                THEN 'BLOG_LIKE' ELSE 'BLOG_SHARE' END;
            SELECT author_id INTO recipient
            FROM blogs
            WHERE id = target_blog;
        WHEN 'comment_likes' THEN
            actor := NEW.user_id;
            target_comment := NEW.comment_id;
            notification_type := 'COMMENT_LIKE';
            SELECT blog_id, author_id INTO target_blog, recipient
            FROM comments
            WHERE id = target_comment;
        WHEN 'comments' THEN
            -- Không thông báo cho placeholder hoặc dữ liệu đã gỡ tác giả.
            IF NEW.deleted_at IS NOT NULL OR NEW.author_id IS NULL THEN
                RETURN NULL;
            END IF;
            actor := NEW.author_id;
            target_blog := NEW.blog_id;
            target_comment := NEW.id;
            notification_type := 'BLOG_COMMENT';
            SELECT author_id INTO recipient
            FROM blogs
            WHERE id = target_blog;
    END CASE;

    -- Không thông báo cho chính người thao tác hoặc tác giả đã bị gỡ.
    IF recipient IS NOT NULL AND recipient <> actor THEN
        INSERT INTO notifications (recipient_id, actor_id, blog_id, comment_id, type)
        VALUES (recipient, actor, target_blog, target_comment, notification_type)
        ON CONFLICT ON CONSTRAINT notifications_interaction_unique DO NOTHING;
    END IF;

    IF TG_TABLE_NAME = 'comments' THEN
        IF NEW.parent_id IS NOT NULL THEN
            blog_author := recipient;
            SELECT author_id INTO recipient
            FROM comments
            WHERE id = NEW.parent_id
              AND blog_id = target_blog;
            -- Nếu chủ comment cha cũng là chủ bài, một BLOG_COMMENT đã đủ.
            IF recipient IS NOT NULL AND recipient <> actor AND recipient <> blog_author THEN
                INSERT INTO notifications (recipient_id, actor_id, blog_id, comment_id, type)
                VALUES (recipient, actor, target_blog, target_comment, 'COMMENT_REPLY')
                ON CONFLICT ON CONSTRAINT notifications_interaction_unique DO NOTHING;
            END IF;
        END IF;
    END IF;

    -- AFTER trigger không gửi push/email hoặc tự COMMIT.
    RETURN NULL;
END;
$$;

CREATE TRIGGER blog_likes_notify
AFTER INSERT ON blog_likes
FOR EACH ROW
EXECUTE FUNCTION create_interaction_notification();

CREATE TRIGGER comment_likes_notify
AFTER INSERT ON comment_likes
FOR EACH ROW
EXECUTE FUNCTION create_interaction_notification();

CREATE TRIGGER blog_shares_notify
AFTER INSERT ON blog_shares
FOR EACH ROW
EXECUTE FUNCTION create_interaction_notification();

CREATE TRIGGER comments_notify
AFTER INSERT ON comments
FOR EACH ROW
EXECUTE FUNCTION create_interaction_notification();
