-- Actor comes from the authenticated server session, never directly from JSON.
-- SECURITY INVOKER deliberately does not turn these into a browser-facing API.
-- The caller owns the transaction; procedures and triggers commit or roll back together.
-- Set the requested like state; repeated requests must not change the count twice.
-- xử lý khi người dùng bấm thích hoặc bỏ thích một bài viết
CREATE PROCEDURE sp_set_blog_like(
    p_actor UUID,
    p_blog UUID,
    p_desired BOOLEAN
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
DECLARE
    -- biến chứa dữ liệu bài viết đã khai báo trước đó.
    target blogs%ROWTYPE; --sử dụng để chứa bài viết, rồi kiểm tra target.published và target.author_id
BEGIN
    IF p_desired IS NULL THEN
        -- '22004': mã lỗi PostgreSQL cho trường hợp không chấp nhận giá trị NULL.
        RAISE EXCEPTION 'LIKED_REQUIRED' USING ERRCODE = '22004';
    END IF;
    PERFORM 1 FROM users
    WHERE id = p_actor
    FOR KEY SHARE;
    IF NOT FOUND THEN
        --mã lỗi SQLSTATE gắn vào lỗi được phát sinh. P0002 có tên chuẩn là no_data_found — không tìm thấy dữ liệu.
        RAISE EXCEPTION 'ACTOR_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    SELECT * INTO target FROM blogs
    WHERE id = p_blog
    FOR UPDATE; --khóa hàng được tìm thấy cho đến khi transaction kết thúc
    --bài công khai hoặc người thao tác là tác giả
    IF NOT FOUND OR NOT (target.published OR target.author_id = p_actor) THEN ---cong khai hoặc bài riêng tư thì chỉ có tác giả được thao tác
        RAISE EXCEPTION 'BLOG_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    IF p_desired THEN
        INSERT INTO blog_likes(blog_id, user_id)
        VALUES (p_blog, p_actor)
        ON CONFLICT DO NOTHING;
        -- sau đó trigger thực hiện tăng likes_count, nếu đã thích rồi thì không làm gì cả.
    ELSE
        DELETE FROM blog_likes
        WHERE blog_id = p_blog
          AND user_id = p_actor;
    END IF;
END;
$$;


--thích hoặc bỏ thích một bình luận
-- Set the requested like state only for an active comment in a readable blog.
CREATE PROCEDURE sp_set_comment_like(
    p_actor UUID,
    p_blog UUID,
    p_comment UUID,
    p_desired BOOLEAN
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
DECLARE
    target blogs%ROWTYPE;
    target_deleted TIMESTAMPTZ; --chứa thời điểm xóa của bình luận
BEGIN
    IF p_desired IS NULL THEN
        RAISE EXCEPTION 'LIKED_REQUIRED' USING ERRCODE = '22004';
    END IF;
    PERFORM 1 FROM users
    WHERE id = p_actor
    FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ACTOR_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    SELECT * INTO target -- Lấy toàn bộ cột, lưu vào biến target
    FROM blogs
    WHERE id = p_blog
    FOR UPDATE;
    IF NOT FOUND OR NOT (target.published OR target.author_id = p_actor) THEN
        RAISE EXCEPTION 'BLOG_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    --deleted_at cho biết bình luận đã bị xóa mềm hay chưa
    --không cho thích một bình luận đã bị xóa
    SELECT deleted_at INTO target_deleted
    FROM comments
    WHERE id = p_comment
      AND blog_id = p_blog
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'COMMENT_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    --Không cho tương tác với bình luận đã xóa
    IF target_deleted IS NOT NULL THEN
        RAISE EXCEPTION 'COMMENT_DELETED' USING ERRCODE = '23514';
    END IF;
    IF p_desired THEN
        INSERT INTO comment_likes(comment_id, user_id)
        VALUES (p_comment, p_actor)
        ON CONFLICT DO NOTHING;
        -- trigger cập nhật comment.likes_count, nếu đã thích rồi thì không làm gì cả.
    ELSE
        DELETE FROM comment_likes
        WHERE comment_id = p_comment AND user_id = p_actor;
    END IF;
END;
$$;

--xử lý chia sẻ bài viết
-- Sharing requires a public blog; an existing share is reported as a conflict.
CREATE PROCEDURE sp_share_blog(
    p_actor UUID,
    p_blog UUID
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
DECLARE
    is_public BOOLEAN;
BEGIN
    PERFORM 1 FROM users
    WHERE id = p_actor
    FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ACTOR_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    ---bài có công khai không, nên lấy một cột published là đủ.
    SELECT published INTO is_public FROM blogs
    WHERE id = p_blog
    FOR UPDATE;
    IF NOT FOUND OR NOT is_public THEN
        RAISE EXCEPTION 'BLOG_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    INSERT INTO blog_shares(user_id, blog_id)
    VALUES (p_actor, p_blog)
    ON CONFLICT DO NOTHING;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ALREADY_SHARED' USING ERRCODE = '23505';
    END IF;
END;
$$;

-- Xử lý bỏ chia sẻ bài viết
-- Unsharing is safe to repeat, including after the source blog becomes private.
CREATE PROCEDURE sp_unshare_blog(
    p_actor UUID,
    p_blog UUID
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
BEGIN
    PERFORM 1 FROM users
    WHERE id = p_actor
    FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ACTOR_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    -- Removal remains possible after a source blog becomes private.
    PERFORM 1 FROM blogs
    WHERE id = p_blog
    FOR UPDATE;
    DELETE FROM blog_shares
    WHERE user_id = p_actor
      AND blog_id = p_blog;
END;
$$;

--Phần này phân quyền truy cập database: cho backend thực thi các hàm/thủ tục và đọc/ghi bảng nghiệp vụ, đồng thời hạn chế quyền truy cập không cần thiết.
--có thể khỏi ghi vào báo cáo
-- Remove default execution access; only the backend runtime role should call these routines.
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL PROCEDURES IN SCHEMA public FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO soulspace_runtime;
GRANT EXECUTE ON PROCEDURE sp_set_blog_like(UUID, UUID, BOOLEAN),
    sp_set_comment_like(UUID, UUID, UUID, BOOLEAN), sp_share_blog(UUID, UUID),
    sp_unshare_blog(UUID, UUID) TO soulspace_runtime;

-- Procedures run with the caller's privileges, so the runtime also needs table access.
GRANT SELECT, INSERT, UPDATE, DELETE ON users, blogs, comments, blog_likes,
    comment_likes, blog_shares, media_assets, blog_images, media_cleanup_jobs,
    categories, blog_categories TO soulspace_runtime;

-- The runtime must not modify Flyway migration history.
DO $$
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        REVOKE ALL ON TABLE public.flyway_schema_history FROM soulspace_runtime;
    END IF;
END;
$$;

-- Runtime chỉ tạo thông báo qua luồng backend tin cậy và chỉ được sửa cột đã đọc.
-- Thu hồi cả DML có thể đã được cấp từ default privileges lúc tạo bảng.
REVOKE ALL ON TABLE notifications FROM PUBLIC, soulspace_runtime;
GRANT SELECT, INSERT ON TABLE notifications TO soulspace_runtime;
GRANT UPDATE (read_at) ON TABLE notifications TO soulspace_runtime;
