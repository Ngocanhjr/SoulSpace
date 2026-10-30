-- Run with psql on a migrated, disposable test database as soulspace_runtime.
-- Every fixture rolls back; do not run against a shared demo/production database.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '15s';

CREATE FUNCTION pg_temp.assert_true(ok BOOLEAN, label TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'Assertion failed: %', label; END IF;
END;
$$;
CREATE FUNCTION pg_temp.expect_error(statement TEXT, expected_state TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE caught BOOLEAN := false;
BEGIN
    BEGIN
        EXECUTE statement;
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE <> expected_state THEN RAISE; END IF;
        caught := true;
    END;
    IF NOT caught THEN RAISE EXCEPTION 'Expected SQLSTATE % for %', expected_state, statement; END IF;
END;
$$;

DO $$
DECLARE
    owner_id UUID := gen_random_uuid();
    viewer_id UUID := gen_random_uuid();
    post_id UUID := gen_random_uuid();
    private_id UUID := gen_random_uuid();
    root_id UUID := gen_random_uuid();
    child_id UUID := gen_random_uuid();
    parent UUID;
    node UUID;
    unconfirmed_asset UUID := gen_random_uuid();
    partial_asset UUID := gen_random_uuid();
    unconfirmed_attached_asset UUID := gen_random_uuid();
    bad_format_asset UUID := gen_random_uuid();
    bad_size_asset UUID := gen_random_uuid();
    asset UUID := gen_random_uuid();
    image_post UUID := gen_random_uuid();
    empty_post UUID := gen_random_uuid();
    affected INTEGER;
BEGIN
    INSERT INTO users(id, username, password_hash, fullname) VALUES
        (owner_id, 'sqltestowner', 'TEST_ONLY_NOT_A_LOGIN_HASH', 'Owner'),
        (viewer_id, 'sqltestviewer', 'TEST_ONLY_NOT_A_LOGIN_HASH', 'Viewer');
    INSERT INTO blogs(id, author_id, content) VALUES(post_id, owner_id, 'Public text');
    INSERT INTO blogs(id, author_id, content, published) VALUES(private_id, owner_id, 'Private text', false);

    CALL sp_set_blog_like(viewer_id, post_id, true);
    CALL sp_set_blog_like(viewer_id, post_id, true);
    PERFORM pg_temp.assert_true((SELECT likes_count = 1 FROM blogs WHERE id = post_id), 'like is idempotent');
    PERFORM pg_temp.assert_true((SELECT created_at IS NOT NULL FROM blog_likes WHERE blog_id = post_id), 'like timestamp default');
    CALL sp_set_blog_like(viewer_id, post_id, false);
    CALL sp_set_blog_like(viewer_id, post_id, false);
    PERFORM pg_temp.assert_true((SELECT likes_count = 0 FROM blogs WHERE id = post_id), 'unlike is idempotent');
    PERFORM pg_temp.expect_error(format('CALL sp_set_blog_like(%L,%L,NULL)', viewer_id, post_id), '22004');
    PERFORM pg_temp.expect_error(format('CALL sp_set_blog_like(%L,%L,true)', viewer_id, private_id), 'P0002');
    PERFORM pg_temp.expect_error(format('CALL sp_set_blog_like(%L,%L,true)', gen_random_uuid(), post_id), 'P0002');

    CALL sp_share_blog(viewer_id, post_id);
    PERFORM pg_temp.expect_error(format('CALL sp_share_blog(%L,%L)', viewer_id, post_id), '23505');
    PERFORM pg_temp.assert_true((SELECT shares_count = 1 FROM blogs WHERE id = post_id), 'duplicate share preserves count');
    UPDATE blogs SET published = false WHERE id = post_id;
    CALL sp_unshare_blog(viewer_id, post_id);
    CALL sp_unshare_blog(viewer_id, post_id);
    PERFORM pg_temp.assert_true((SELECT shares_count = 0 FROM blogs WHERE id = post_id), 'unshare private source');
    UPDATE blogs SET published = true WHERE id = post_id;

    INSERT INTO comments(id, blog_id, author_id, content) VALUES(root_id, post_id, viewer_id, 'Root');
    INSERT INTO comments(id, blog_id, author_id, parent_id, content) VALUES(child_id, post_id, owner_id, root_id, 'Reply');
    parent := child_id;
    FOR level IN 3..5 LOOP
        node := gen_random_uuid();
        INSERT INTO comments(id, blog_id, author_id, parent_id, content) VALUES(node, post_id, owner_id, parent, 'Nested');
        parent := node;
    END LOOP;
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO comments(id,blog_id,author_id,parent_id,content) VALUES(%L,%L,%L,%L,''Sixth'')',
        gen_random_uuid(), post_id, owner_id, parent), '23514');
    PERFORM pg_temp.expect_error(format('UPDATE comments SET parent_id = NULL WHERE id = %L', child_id), '23514');
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO comments(id,blog_id,author_id,parent_id,content) VALUES(%L,%L,%L,%L,''Wrong blog'')',
        gen_random_uuid(), private_id, owner_id, root_id), '23503');

    UPDATE comments SET content = 'Edited' WHERE id = root_id AND version = 0;
    UPDATE comments SET content = 'Stale' WHERE id = root_id AND version = 0;
    GET DIAGNOSTICS affected = ROW_COUNT;
    PERFORM pg_temp.assert_true(affected = 0, 'stale expectedVersion updates no row');
    CALL sp_set_comment_like(owner_id, post_id, root_id, true);
    CALL sp_set_comment_like(owner_id, post_id, root_id, true);
    PERFORM pg_temp.assert_true((SELECT likes_count = 1 AND version = 1 FROM comments WHERE id = root_id), 'like does not change version');
    PERFORM pg_temp.expect_error(format('DELETE FROM users WHERE id = %L', viewer_id), '23514');
    DELETE FROM comment_likes WHERE comment_id = root_id;
    UPDATE comments SET content = NULL, deleted_at = clock_timestamp() WHERE id = root_id;
    UPDATE comments SET content = NULL, deleted_at = deleted_at WHERE id = root_id;
    PERFORM pg_temp.assert_true((SELECT version = 2 AND likes_count = 0 FROM comments WHERE id = root_id), 'repeated soft deletion stable');
    PERFORM pg_temp.assert_true((SELECT comments_count = 4 FROM blogs WHERE id = post_id), 'only active comments counted');
    PERFORM pg_temp.assert_true(EXISTS(SELECT FROM comments WHERE id = child_id), 'reply retained');
    PERFORM pg_temp.expect_error(format('CALL sp_set_comment_like(%L,%L,%L,true)', owner_id, post_id, root_id), '23514');
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO comments(id,blog_id,author_id,parent_id,content) VALUES(%L,%L,%L,%L,''Deleted parent'')',
        gen_random_uuid(), post_id, owner_id, root_id), '23514');
    DELETE FROM users WHERE id = viewer_id;
    PERFORM pg_temp.assert_true((SELECT author_id IS NULL AND version = 3 FROM comments WHERE id = root_id), 'anonymized tombstone');
    PERFORM pg_temp.assert_true(EXISTS(SELECT FROM comments WHERE id = child_id), 'other author reply survives account deletion');

    INSERT INTO media_assets(id,owner_id,purpose,state,provider_public_id)
    VALUES(unconfirmed_asset, owner_id, 'BLOG', 'PENDING', 'test/' || unconfirmed_asset);
    PERFORM pg_temp.assert_true(
        (SELECT provider_asset_id IS NULL AND format IS NULL AND size_bytes IS NULL
         FROM media_assets WHERE id = unconfirmed_asset),
        'pending receipt can exist before upload confirmation');
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO media_assets(id,owner_id,purpose,state,provider_public_id,provider_asset_id,format,size_bytes) VALUES(%L,%L,''BLOG'',''ATTACHED'',%L,%L,''png'',100)',
        gen_random_uuid(), owner_id, 'test/empty-provider-id', ''), '23514');
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO media_assets(id,owner_id,purpose,state,provider_public_id,provider_asset_id,format,size_bytes) VALUES(%L,%L,''BLOG'',''PENDING'',%L,%L,''png'',100)',
        gen_random_uuid(), owner_id, 'test/blank-provider-id', '   '), '23514');
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO media_assets(id,owner_id,purpose,state,provider_public_id,provider_asset_id,format) VALUES(%L,%L,''BLOG'',''PENDING'',%L,%L,''png'')',
        partial_asset, owner_id, 'test/' || partial_asset, 'provider/' || partial_asset), '23514');
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO media_assets(id,owner_id,purpose,state,provider_public_id) VALUES(%L,%L,''BLOG'',''ATTACHED'',%L)',
        unconfirmed_attached_asset, owner_id, 'test/' || unconfirmed_attached_asset), '23514');
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO media_assets(id,owner_id,purpose,state,provider_public_id,provider_asset_id,format,size_bytes) VALUES(%L,%L,''BLOG'',''PENDING'',%L,%L,''gif'',100)',
        bad_format_asset, owner_id, 'test/' || bad_format_asset, 'provider/' || bad_format_asset), '23514');
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO media_assets(id,owner_id,purpose,state,provider_public_id,provider_asset_id,format,size_bytes) VALUES(%L,%L,''BLOG'',''PENDING'',%L,%L,''png'',0)',
        bad_size_asset, owner_id, 'test/' || bad_size_asset, 'provider/' || bad_size_asset), '23514');
    INSERT INTO media_assets(id, owner_id, purpose, state, provider_public_id)
    VALUES(asset, owner_id, 'BLOG', 'PENDING', 'test/' || asset);
    UPDATE media_assets
    SET provider_asset_id = 'provider/' || asset, format = 'png', size_bytes = 100
    WHERE id = asset;
    PERFORM pg_temp.assert_true(
        (SELECT state = 'PENDING' AND provider_asset_id IS NOT NULL FROM media_assets WHERE id = asset),
        'confirmed but unattached upload remains pending');
    UPDATE media_assets SET state = 'ATTACHED' WHERE id = asset;
    PERFORM pg_temp.expect_error(format(
        'UPDATE media_assets SET format=''jpeg'' WHERE id=%L', asset), '23514');
    PERFORM pg_temp.expect_error(format(
        'UPDATE media_assets SET state=''PENDING'' WHERE id=%L', asset), '23514');
    INSERT INTO blogs(id, author_id) VALUES(image_post, owner_id);
    INSERT INTO blog_images(blog_id, asset_id, position) VALUES(image_post, asset, 0);
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
    PERFORM pg_temp.expect_error(format(
        'DELETE FROM blog_images WHERE blog_id=%L; SET CONSTRAINTS ALL IMMEDIATE', image_post), '23514');
    PERFORM pg_temp.assert_true(EXISTS(SELECT FROM blog_images WHERE blog_id = image_post), 'last image removal rolled back');
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO media_cleanup_jobs(id,asset_id,status,available_at) VALUES(%L,%L,''RUNNING'',now())',
        gen_random_uuid(), asset), '23514');
    PERFORM pg_temp.expect_error(format(
        'INSERT INTO media_cleanup_jobs(id,asset_id,attempts,available_at) VALUES(%L,%L,6,now())',
        gen_random_uuid(), asset), '23514');
    INSERT INTO blogs(id, author_id) VALUES(empty_post, owner_id);
    PERFORM pg_temp.expect_error('SET CONSTRAINTS ALL IMMEDIATE', '23514');
    DELETE FROM blogs WHERE id = empty_post;
    DELETE FROM blogs WHERE id = image_post;
    DELETE FROM blogs WHERE id = post_id;
    SET CONSTRAINTS ALL IMMEDIATE;
    PERFORM pg_temp.assert_true(NOT EXISTS(SELECT FROM comments WHERE blog_id = post_id), 'blog cascade removes tree');
    RAISE NOTICE 'Database invariant smoke checks passed; fixtures will roll back.';
END;
$$;
ROLLBACK;
