-- Run once as migrator on the WITHOUT database; keep backends stopped.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'soulspace_benchmark_without_index' THEN
        RAISE EXCEPTION 'Wrong benchmark database: %', current_database();
    END IF;
    DROP INDEX
        public.blogs_public_feed_idx,
        public.blogs_author_idx,
        public.blogs_public_author_idx,
        public.comments_blog_idx,
        public.comments_parent_idx,
        public.comments_author_idx,
        public.blog_likes_user_idx,
        public.comment_likes_user_idx,
        public.blog_shares_blog_idx,
        public.blog_shares_user_time_idx,
        public.media_assets_owner_idx,
        public.media_assets_stale_idx,
        public.cleanup_ready_idx,
        public.cleanup_expired_idx,
        public.notifications_recipient_time_idx,
        public.notifications_unread_idx,
        public.notifications_blog_idx,
        public.notifications_comment_idx,
        public.notifications_actor_idx;
END;
$$;
COMMIT;
