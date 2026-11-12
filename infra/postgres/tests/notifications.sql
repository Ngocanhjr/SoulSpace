-- Run as soulspace_runtime on a migrated test database; fixtures always roll back.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '15s';

CREATE FUNCTION pg_temp.assert_notification(ok BOOLEAN, label TEXT)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF ok IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'Notification assertion failed: %', label;
    END IF;
END;
$$;

CREATE FUNCTION pg_temp.expect_notification_error(statement TEXT, expected_state TEXT)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    caught BOOLEAN := false;
BEGIN
    BEGIN
        EXECUTE statement;
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE <> expected_state THEN RAISE; END IF;
        caught := true;
    END;
    IF NOT caught THEN RAISE EXCEPTION 'Expected SQLSTATE %', expected_state; END IF;
END;
$$;

DO $$
DECLARE
    owner_id UUID := 'd1000000-0000-0000-0000-000000000001';
    liker_id UUID := 'd1000000-0000-0000-0000-000000000002';
    third_id UUID := 'd1000000-0000-0000-0000-000000000003';
    post_id UUID := 'd2000000-0000-0000-0000-000000000001';
    private_id UUID := 'd2000000-0000-0000-0000-000000000002';
    actor_post UUID := 'd2000000-0000-0000-0000-000000000003';
    root_comment UUID := 'd3000000-0000-0000-0000-000000000001';
    read_before TIMESTAMPTZ;
BEGIN
    INSERT INTO users(id, username, password_hash, fullname)
    VALUES (owner_id, 'notifyowner', 'TEST_ONLY', 'Owner'),
           (liker_id, 'notifyactor', 'TEST_ONLY', 'Actor'),
           (third_id, 'notifythird', 'TEST_ONLY', 'Third');
    INSERT INTO blogs(id, author_id, content, published)
    VALUES (post_id, owner_id, 'Public notification fixture', true),
           (private_id, owner_id, 'Private notification fixture', false),
           (actor_post, liker_id, 'Actor post', true);

    CALL sp_set_blog_like(owner_id, post_id, true);
    PERFORM pg_temp.assert_notification(
        (SELECT count(*) = 0 FROM notifications WHERE blog_id = post_id), 'self-like is silent');

    CALL sp_set_blog_like(liker_id, post_id, true);
    CALL sp_set_blog_like(liker_id, post_id, true);
    PERFORM pg_temp.assert_notification(
        (SELECT count(*) = 1 FROM notifications WHERE blog_id = post_id), 'retry creates one notification');
    PERFORM pg_temp.assert_notification(
        (SELECT recipient_id = owner_id AND read_at IS NULL AND created_at IS NOT NULL
         FROM notifications WHERE blog_id = post_id), 'recipient and initial unread state');
    PERFORM pg_temp.assert_notification(
        (SELECT likes_count = 2 FROM blogs WHERE id = post_id), 'notification does not double the like counter');

    UPDATE notifications
    SET read_at = COALESCE(read_at, clock_timestamp())
    WHERE recipient_id = owner_id AND blog_id = post_id;
    SELECT read_at INTO read_before FROM notifications WHERE blog_id = post_id;
    CALL sp_set_blog_like(liker_id, post_id, false);
    CALL sp_set_blog_like(liker_id, post_id, true);
    PERFORM pg_temp.assert_notification(
        (SELECT count(*) = 1 AND bool_and(read_at IS NOT DISTINCT FROM read_before)
         FROM notifications WHERE blog_id = post_id), 'unlike/relike preserves history and read state');
    CALL sp_set_blog_like(third_id, post_id, true);
    PERFORM pg_temp.assert_notification(
        (SELECT count(*) = 1 FROM notifications WHERE recipient_id = owner_id AND read_at IS NULL),
        'new actor adds one unread notification');

    PERFORM pg_temp.expect_notification_error(
        format('CALL sp_set_blog_like(%L,%L,true)', liker_id, private_id), 'P0002');
    PERFORM pg_temp.expect_notification_error(
        format('CALL sp_set_blog_like(NULL,%L,true)', post_id), 'P0002');
    PERFORM pg_temp.expect_notification_error(
        format('CALL sp_set_blog_like(%L,%L,true)', liker_id, gen_random_uuid()), 'P0002');
    PERFORM pg_temp.expect_notification_error(
        format('CALL sp_set_blog_like(%L,%L,NULL)', liker_id, post_id), '22004');
    CALL sp_set_blog_like(owner_id, private_id, true);
    PERFORM pg_temp.assert_notification(
        (SELECT count(*) = 0 FROM notifications WHERE blog_id = private_id), 'private self-like is silent');

    INSERT INTO comments(id, blog_id, author_id, content)
    VALUES (root_comment, post_id, owner_id, 'Comment fixture');
    CALL sp_set_comment_like(liker_id, post_id, root_comment, true);
    CALL sp_share_blog(liker_id, post_id);
    PERFORM pg_temp.assert_notification(
        (SELECT count(*) = 4 FROM notifications WHERE blog_id = post_id), 'comment like and share notify the blog/comment author');

    INSERT INTO comments(id, blog_id, author_id, content)
    VALUES ('d3000000-0000-0000-0000-000000000002', post_id, liker_id, 'Root by actor');
    INSERT INTO comments(id, blog_id, parent_id, author_id, content)
    VALUES ('d3000000-0000-0000-0000-000000000003', post_id,
            'd3000000-0000-0000-0000-000000000002', third_id, 'Reply by third');
    PERFORM pg_temp.assert_notification(
        (SELECT count(*) = 1 FROM notifications WHERE type = 'COMMENT_REPLY' AND recipient_id = liker_id)
        AND (SELECT count(*) = 2 FROM notifications WHERE type = 'BLOG_COMMENT' AND recipient_id = owner_id),
        'new comment notifies blog author; reply also notifies a different parent author');
    INSERT INTO comments(id, blog_id, parent_id, author_id, content)
    VALUES ('d3000000-0000-0000-0000-000000000004', post_id, root_comment, third_id, 'Reply to owner');
    PERFORM pg_temp.assert_notification(
        (SELECT count(*) = 1 FROM notifications WHERE comment_id = 'd3000000-0000-0000-0000-000000000004'),
        'same blog/parent author gets one notification');
    CALL sp_set_comment_like(liker_id, post_id, root_comment, false);
    CALL sp_set_comment_like(liker_id, post_id, root_comment, true);
    CALL sp_unshare_blog(liker_id, post_id);
    CALL sp_share_blog(liker_id, post_id);
    PERFORM pg_temp.assert_notification(
        (SELECT count(*) = 1 FROM notifications WHERE type = 'COMMENT_LIKE')
        AND (SELECT count(*) = 1 FROM notifications WHERE type = 'BLOG_SHARE'),
        'relike and reshare preserve one historical notification');

    CALL sp_set_comment_like(owner_id, post_id, root_comment, true);
    CALL sp_share_blog(owner_id, post_id);
    PERFORM pg_temp.assert_notification(
        NOT EXISTS (SELECT FROM notifications WHERE recipient_id = actor_id),
        'self comment likes and self shares are silent');
    INSERT INTO comments(id, blog_id, author_id, content, deleted_at)
    VALUES ('d3000000-0000-0000-0000-000000000005', post_id, NULL, NULL, clock_timestamp());
    PERFORM pg_temp.assert_notification(
        NOT EXISTS (SELECT FROM notifications WHERE comment_id = 'd3000000-0000-0000-0000-000000000005'),
        'deleted placeholder does not notify');
    PERFORM pg_temp.expect_notification_error(
        format('INSERT INTO notifications(recipient_id,actor_id,blog_id,type) VALUES(%L,%L,%L,''COMMENT_LIKE'')', owner_id, liker_id, post_id),
        '23514');
    PERFORM pg_temp.expect_notification_error(
        format('INSERT INTO notifications(recipient_id,actor_id,blog_id,comment_id,type) VALUES(%L,%L,%L,%L,''COMMENT_LIKE'')', owner_id, liker_id, actor_post, root_comment),
        '23503');

    PERFORM pg_temp.expect_notification_error(
        format('UPDATE notifications SET recipient_id=%L WHERE blog_id=%L', third_id, post_id), '42501');
    PERFORM pg_temp.expect_notification_error(
        'UPDATE notifications SET created_at=created_at', '42501');
    PERFORM pg_temp.expect_notification_error('DELETE FROM notifications', '42501');
    PERFORM pg_temp.expect_notification_error(
        format('INSERT INTO notifications(recipient_id,actor_id,blog_id,type) VALUES(%L,%L,%L,''BLOG_LIKE'')', owner_id, owner_id, post_id),
        '23514');

    BEGIN
        CALL sp_set_blog_like(third_id, actor_post, true);
        RAISE EXCEPTION 'NOTIFICATION_ROLLBACK_TEST';
    EXCEPTION WHEN SQLSTATE 'P0001' THEN
        IF SQLERRM <> 'NOTIFICATION_ROLLBACK_TEST' THEN RAISE; END IF;
    END;
    PERFORM pg_temp.assert_notification(
        NOT EXISTS (SELECT FROM blog_likes WHERE user_id = third_id AND blog_id = actor_post)
        AND NOT EXISTS (SELECT FROM notifications WHERE actor_id = third_id AND blog_id = actor_post)
        AND (SELECT likes_count = 0 FROM blogs WHERE id = actor_post), 'rollback keeps relation/notification/counter atomic');

    CALL sp_set_blog_like(owner_id, actor_post, true);
    -- Xóa tài khoản theo contract: chuyển comments ở bài khác thành placeholder trước.
    DELETE FROM comment_likes
    WHERE comment_id IN (SELECT id FROM comments WHERE author_id = liker_id);
    UPDATE comments
    SET content = NULL, author_id = NULL, deleted_at = clock_timestamp()
    WHERE author_id = liker_id;
    DELETE FROM users WHERE id = liker_id;
    PERFORM pg_temp.assert_notification(
        NOT EXISTS (SELECT FROM notifications WHERE actor_id = liker_id OR recipient_id = liker_id),
        'account deletion removes actor/recipient notifications');
    DELETE FROM blogs WHERE id = post_id;
    PERFORM pg_temp.assert_notification(
        NOT EXISTS (SELECT FROM notifications WHERE blog_id = post_id), 'blog deletion removes notifications');
END;
$$;

ROLLBACK;
