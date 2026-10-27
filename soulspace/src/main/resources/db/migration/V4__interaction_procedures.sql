-- Actor comes from the authenticated server session, never directly from JSON.
-- SECURITY INVOKER deliberately does not turn these into a browser-facing API.
CREATE PROCEDURE sp_set_blog_like(p_actor UUID, p_blog UUID, p_desired BOOLEAN)
LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $$
DECLARE
    target blogs%ROWTYPE;
BEGIN
    IF p_desired IS NULL THEN
        RAISE EXCEPTION 'LIKED_REQUIRED' USING ERRCODE = '22004';
    END IF;
    PERFORM 1 FROM users WHERE id = p_actor FOR KEY SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'ACTOR_NOT_FOUND' USING ERRCODE = 'P0002'; END IF;
    SELECT * INTO target FROM blogs WHERE id = p_blog FOR UPDATE;
    IF NOT FOUND OR NOT (target.published OR target.author_id = p_actor) THEN
        RAISE EXCEPTION 'BLOG_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    IF p_desired THEN
        INSERT INTO blog_likes(blog_id, user_id) VALUES (p_blog, p_actor) ON CONFLICT DO NOTHING;
    ELSE
        DELETE FROM blog_likes WHERE blog_id = p_blog AND user_id = p_actor;
    END IF;
END;
$$;

CREATE PROCEDURE sp_set_comment_like(p_actor UUID, p_blog UUID, p_comment UUID, p_desired BOOLEAN)
LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $$
DECLARE
    target blogs%ROWTYPE;
    target_deleted TIMESTAMPTZ;
BEGIN
    IF p_desired IS NULL THEN RAISE EXCEPTION 'LIKED_REQUIRED' USING ERRCODE = '22004'; END IF;
    PERFORM 1 FROM users WHERE id = p_actor FOR KEY SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'ACTOR_NOT_FOUND' USING ERRCODE = 'P0002'; END IF;
    SELECT * INTO target FROM blogs WHERE id = p_blog FOR UPDATE;
    IF NOT FOUND OR NOT (target.published OR target.author_id = p_actor) THEN
        RAISE EXCEPTION 'BLOG_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    SELECT deleted_at INTO target_deleted FROM comments WHERE id = p_comment AND blog_id = p_blog FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'COMMENT_NOT_FOUND' USING ERRCODE = 'P0002'; END IF;
    IF target_deleted IS NOT NULL THEN RAISE EXCEPTION 'COMMENT_DELETED' USING ERRCODE = '23514'; END IF;
    IF p_desired THEN
        INSERT INTO comment_likes(comment_id, user_id) VALUES (p_comment, p_actor) ON CONFLICT DO NOTHING;
    ELSE
        DELETE FROM comment_likes WHERE comment_id = p_comment AND user_id = p_actor;
    END IF;
END;
$$;

CREATE PROCEDURE sp_share_blog(p_actor UUID, p_blog UUID)
LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $$
DECLARE
    is_public BOOLEAN;
BEGIN
    PERFORM 1 FROM users WHERE id = p_actor FOR KEY SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'ACTOR_NOT_FOUND' USING ERRCODE = 'P0002'; END IF;
    SELECT published INTO is_public FROM blogs WHERE id = p_blog FOR UPDATE;
    IF NOT FOUND OR NOT is_public THEN RAISE EXCEPTION 'BLOG_NOT_FOUND' USING ERRCODE = 'P0002'; END IF;
    INSERT INTO blog_shares(user_id, blog_id) VALUES (p_actor, p_blog) ON CONFLICT DO NOTHING;
    IF NOT FOUND THEN RAISE EXCEPTION 'ALREADY_SHARED' USING ERRCODE = '23505'; END IF;
END;
$$;

CREATE PROCEDURE sp_unshare_blog(p_actor UUID, p_blog UUID)
LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $$
BEGIN
    PERFORM 1 FROM users WHERE id = p_actor FOR KEY SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'ACTOR_NOT_FOUND' USING ERRCODE = 'P0002'; END IF;
    -- Removal remains possible after a source blog becomes private.
    PERFORM 1 FROM blogs WHERE id = p_blog FOR UPDATE;
    DELETE FROM blog_shares WHERE user_id = p_actor AND blog_id = p_blog;
END;
$$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL PROCEDURES IN SCHEMA public FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO soulspace_runtime;
GRANT EXECUTE ON PROCEDURE sp_set_blog_like(UUID, UUID, BOOLEAN),
    sp_set_comment_like(UUID, UUID, UUID, BOOLEAN), sp_share_blog(UUID, UUID),
    sp_unshare_blog(UUID, UUID) TO soulspace_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON users, blogs, comments, blog_likes,
    comment_likes, blog_shares, media_assets, blog_images, media_cleanup_jobs TO soulspace_runtime;
DO $$
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        REVOKE ALL ON TABLE public.flyway_schema_history FROM soulspace_runtime;
    END IF;
END;
$$;
