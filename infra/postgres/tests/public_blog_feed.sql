-- Run as soulspace_runtime on a disposable database migrated through V5.
\set
ON_ERROR_STOP on
BEGIN;
SET LOCAL statement_timeout = '15s';

CREATE FUNCTION pg_temp.assert_feed(ok BOOLEAN, label TEXT) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    IF
ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'Feed assertion failed: %', label;
END IF;
END;
$$;

DO
$$
DECLARE
author UUID := gen_random_uuid();
    viewer
UUID := gen_random_uuid();
    public_post
UUID := gen_random_uuid();
    private_post
UUID := gen_random_uuid();
    avatar
UUID := gen_random_uuid();
    denied
BOOLEAN;
BEGIN
INSERT INTO users(id, username, password_hash, fullname)
VALUES (author, 'viewtestauthor', 'TEST_ONLY_NOT_A_LOGIN_HASH', 'Original Name'),
       (viewer, 'viewtestviewer', 'TEST_ONLY_NOT_A_LOGIN_HASH', 'Viewer');
INSERT INTO blogs(id, author_id, content, published)
VALUES (public_post, author, 'Public original', true),
       (private_post, author, 'Private original', false);

PERFORM
pg_temp.assert_feed(
        EXISTS(SELECT FROM v_public_blog_feed WHERE blog_id = public_post), 'new public post visible');
    PERFORM
pg_temp.assert_feed(
        NOT EXISTS(SELECT FROM v_public_blog_feed WHERE blog_id = private_post), 'new private post hidden');
    PERFORM
pg_temp.assert_feed(
        (SELECT count(*) = 1 FROM v_public_blog_feed WHERE author_id = author), 'public profile author filter');

UPDATE blogs
SET content = 'Edited original'
WHERE id = public_post;
PERFORM
pg_temp.assert_feed(
        (SELECT content = 'Edited original' FROM v_public_blog_feed WHERE blog_id = public_post), 'content edit reflected');
INSERT INTO media_assets(id, owner_id, purpose, state, provider_public_id, provider_asset_id, format, size_bytes)
VALUES (avatar, author, 'AVATAR', 'ATTACHED', 'test/' || avatar, 'provider/' || avatar, 'png', 100);
UPDATE users
SET fullname        = 'Current Name',
    avatar_media_id = avatar
WHERE id = author;
PERFORM
pg_temp.assert_feed(
        (SELECT fullname = 'Current Name' AND avatar_media_id = avatar
         FROM v_public_blog_feed WHERE blog_id = public_post), 'current author name and avatar');

CALL sp_share_blog(viewer, public_post);
CALL sp_set_blog_like(viewer, public_post, true);
PERFORM
pg_temp.assert_feed(
        (SELECT count(*) = 1 AND max(shares_count) = 1 AND max(likes_count) = 1
         FROM v_public_blog_feed WHERE blog_id = public_post), 'share keeps one original row and updates counters');
    PERFORM
pg_temp.assert_feed(
        NOT EXISTS(SELECT FROM v_public_blog_feed WHERE author_id = viewer), 'share does not make viewer the author');
UPDATE blogs
SET published = false
WHERE id = public_post;
PERFORM
pg_temp.assert_feed(
        NOT EXISTS(SELECT FROM v_public_blog_feed WHERE blog_id = public_post), 'private transition hides row');
    PERFORM
pg_temp.assert_feed(
        EXISTS(SELECT FROM blogs WHERE id = public_post)
        AND EXISTS(SELECT FROM blog_shares WHERE blog_id = public_post AND user_id = viewer), 'privacy does not delete original or share');
UPDATE blogs
SET published = true
WHERE id = public_post;
PERFORM
pg_temp.assert_feed(
        EXISTS(SELECT FROM v_public_blog_feed WHERE blog_id = public_post), 'public transition restores visibility');
CALL sp_unshare_blog(viewer, public_post);
PERFORM
pg_temp.assert_feed(
        (SELECT shares_count = 0 FROM v_public_blog_feed WHERE blog_id = public_post), 'unshare preserves original row');

    PERFORM
pg_temp.assert_feed(
        NOT has_table_privilege(current_user, 'public.v_public_blog_feed', 'INSERT')
        AND NOT has_table_privilege(current_user, 'public.v_public_blog_feed', 'UPDATE')
        AND NOT has_table_privilege(current_user, 'public.v_public_blog_feed', 'DELETE'), 'runtime SELECT only');
    denied
:= false;
BEGIN
UPDATE v_public_blog_feed
SET content = 'Forbidden'
WHERE blog_id = public_post;
EXCEPTION WHEN SQLSTATE '42501' OR SQLSTATE '55000' THEN denied := true;
END;
    PERFORM
pg_temp.assert_feed(denied, 'direct view write rejected');
    PERFORM
pg_temp.assert_feed(
        NOT EXISTS(SELECT FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'v_public_blog_feed'
                   AND column_name IN ('password_hash', 'dob', 'provider_public_id', 'provider_asset_id')), 'no sensitive fields');
DELETE
FROM blogs
WHERE id = public_post;
PERFORM
pg_temp.assert_feed(
        NOT EXISTS(SELECT FROM v_public_blog_feed WHERE blog_id = public_post), 'deleted post absent');
SET CONSTRAINTS ALL IMMEDIATE;
RAISE
NOTICE 'Public blog feed checks passed; fixtures will roll back.';
END;
$$;
ROLLBACK;
