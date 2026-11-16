-- PK/UNIQUE indexes already cover username and the leading relation IDs.
-- New feed: published blogs, newest first.
CREATE INDEX blogs_public_feed_idx ON blogs (created_at DESC, id DESC) WHERE published;

-- Own profile: all blogs by the author, newest first.
CREATE INDEX blogs_author_idx ON blogs (author_id, created_at DESC, id DESC);

-- Other users' profiles: published blogs by the author, newest first.
CREATE INDEX blogs_public_author_idx ON blogs (author_id, created_at DESC, id DESC) WHERE published;

-- Blog comments: support newest-first queries with ORDER BY created_at DESC, id DESC.
CREATE INDEX comments_blog_idx ON comments (blog_id, created_at DESC, id DESC);

-- Quickly find replies to a comment.
CREATE INDEX comments_parent_idx ON comments (parent_id, blog_id) WHERE parent_id IS NOT NULL;

-- Comment authors: find a user's comments, including during account deletion. (tính năng spam cmt để thống kê số lượng cmt của user)
CREATE INDEX comments_author_idx ON comments (author_id);

-- Find a user's most recent blog likes for personalized recommendations.
CREATE INDEX blog_likes_user_idx ON blog_likes (user_id, created_at DESC, blog_id DESC);

-- Find a user's most recent comment likes for personalized recommendations.
CREATE INDEX comment_likes_user_idx ON comment_likes (user_id, created_at DESC, comment_id DESC);

-- Find or count shares of a blog.
CREATE INDEX blog_shares_blog_idx ON blog_shares (blog_id);

-- Find a user's most recent shares for personalized recommendations.
CREATE INDEX blog_shares_user_time_idx ON blog_shares (user_id, shared_at DESC, blog_id DESC);

-- User media: find media assets owned by a user.
CREATE INDEX media_assets_owner_idx ON media_assets (owner_id);

-- Stale media lookup: filter assets by state and creation time.
CREATE INDEX media_assets_stale_idx ON media_assets (state, created_at);

-- Cleanup queue: find pending jobs that are due, earliest first.
CREATE INDEX cleanup_ready_idx ON media_cleanup_jobs (available_at, id) WHERE status = 'PENDING';

-- Cleanup recovery: find running jobs whose leases have expired.
CREATE INDEX cleanup_expired_idx ON media_cleanup_jobs (lease_until) WHERE status = 'RUNNING';

-- Lấy thông báo của user, mới nhất trước, và đếm số chưa đọc.
CREATE INDEX notifications_recipient_time_idx ON notifications (recipient_id, created_at DESC, id DESC);
CREATE INDEX notifications_unread_idx ON notifications (recipient_id) WHERE read_at IS NULL;

-- Hỗ trợ xóa thông báo khi xóa bài; UNIQUE(actor_id, blog_id) đã hỗ trợ phía actor.
CREATE INDEX notifications_blog_idx ON notifications (blog_id);

-- Find notifications linked to a comment when that comment is permanently deleted.
CREATE INDEX notifications_comment_idx ON notifications (comment_id, blog_id)
WHERE comment_id IS NOT NULL;

-- Find notifications created by an actor when their account is deleted.
CREATE INDEX notifications_actor_idx ON notifications (actor_id);
