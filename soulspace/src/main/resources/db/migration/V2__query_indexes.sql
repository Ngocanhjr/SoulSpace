-- PK/UNIQUE indexes already cover username and the leading relation IDs.
CREATE INDEX blogs_public_feed_idx ON blogs (created_at DESC, id DESC) WHERE published;
CREATE INDEX blogs_author_idx ON blogs (author_id, created_at DESC, id DESC);
CREATE INDEX blogs_public_author_idx ON blogs (author_id, created_at DESC, id DESC) WHERE published;
CREATE INDEX comments_blog_idx ON comments (blog_id, created_at, id);
CREATE INDEX comments_parent_idx ON comments (parent_id, blog_id) WHERE parent_id IS NOT NULL;
CREATE INDEX comments_author_idx ON comments (author_id);
CREATE INDEX blog_likes_user_idx ON blog_likes (user_id, blog_id);
CREATE INDEX comment_likes_user_idx ON comment_likes (user_id, comment_id);
CREATE INDEX blog_shares_blog_idx ON blog_shares (blog_id);
CREATE INDEX blog_shares_user_time_idx ON blog_shares (user_id, shared_at DESC, blog_id DESC);
CREATE INDEX media_assets_owner_idx ON media_assets (owner_id);
CREATE INDEX media_assets_stale_idx ON media_assets (state, created_at);
CREATE INDEX cleanup_ready_idx ON media_cleanup_jobs (available_at, id) WHERE status = 'PENDING';
CREATE INDEX cleanup_expired_idx ON media_cleanup_jobs (lease_until) WHERE status = 'RUNNING';
