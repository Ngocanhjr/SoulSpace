-- Run as soulspace_runtime on a disposable database migrated through V5.
\set
ON_ERROR_STOP on
BEGIN;
SET LOCAL statement_timeout = '15s';

CREATE FUNCTION pg_temp.assert_categories(ok BOOLEAN, label TEXT) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    IF
ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'Category assertion failed: %', label;
END IF;
END;
$$;
CREATE FUNCTION pg_temp.expect_category_error(statement TEXT, expected_state TEXT) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
caught BOOLEAN := false;
BEGIN
BEGIN
EXECUTE statement;
EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE <> expected_state THEN RAISE;
END IF;
        caught
:= true;
END;
    IF
NOT caught THEN RAISE EXCEPTION 'Expected SQLSTATE % for %', expected_state, statement;
END IF;
END;
$$;

DO
$$
DECLARE
author UUID := gen_random_uuid();
    post_a
UUID := gen_random_uuid();
    post_b
UUID := gen_random_uuid();
    study
UUID := gen_random_uuid();
    tech
UUID := gen_random_uuid();
BEGIN
INSERT INTO users(id, username, password_hash, fullname)
VALUES (author, 'categorytestauthor', 'TEST_ONLY_NOT_A_LOGIN_HASH', 'Author');
INSERT INTO blogs(id, author_id, content)
VALUES (post_a, author, 'Study and technology'),
       (post_b, author, 'Study');
PERFORM
pg_temp.assert_categories(
        (SELECT category_mode = 'AUTO' FROM blogs WHERE id = post_a), 'source defaults to AUTO');
INSERT INTO categories(id, name, slug)
VALUES (study, 'Học tập', 'hoc-tap'),
       (tech, 'Công nghệ', 'cong-nghe');
INSERT INTO blog_categories(blog_id, category_id, category_source, confidence)
VALUES (post_a, study, 'AUTO', 0.92),
       (post_a, tech, 'AUTO', 0.76),
       (post_b, study, 'AUTO', 0.85);
PERFORM
pg_temp.assert_categories(
        (SELECT count(*) = 2 FROM blog_categories WHERE blog_id = post_a), 'one blog has multiple categories');
    PERFORM
pg_temp.assert_categories(
        (SELECT count(*) = 2 FROM blog_categories WHERE category_id = study), 'one category has multiple blogs');
    PERFORM
pg_temp.expect_category_error(format(
        'INSERT INTO blog_categories(blog_id,category_id,category_source,confidence) VALUES(%L,%L,''AUTO'',0.9)', post_a, study), '23505');
    PERFORM
pg_temp.expect_category_error(format(
        'INSERT INTO blog_categories(blog_id,category_id,category_source,confidence) VALUES(%L,%L,''AUTO'',0.9)', post_a, gen_random_uuid()), '23503');
    PERFORM
pg_temp.expect_category_error(format(
        'INSERT INTO blog_categories(blog_id,category_id,category_source,confidence) VALUES(%L,%L,''AUTO'',0.9)', gen_random_uuid(), study), '23503');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE blog_categories SET confidence=-0.01 WHERE blog_id=%L', post_a), '23514');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE blog_categories SET confidence=1.01 WHERE blog_id=%L', post_a), '23514');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE blog_categories SET confidence=''NaN''::double precision WHERE blog_id=%L', post_a), '23514');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE blog_categories SET confidence=''Infinity''::double precision WHERE blog_id=%L', post_a), '23514');
UPDATE blog_categories
SET confidence=0
WHERE blog_id = post_b;
UPDATE blog_categories
SET confidence=1
WHERE blog_id = post_b;
PERFORM
pg_temp.expect_category_error(format(
        'INSERT INTO categories(id,name,slug) VALUES(%L,''Duplicate'',''hoc-tap'')', gen_random_uuid()), '23505');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE categories SET slug=''Hoc Tap'' WHERE id=%L', study), '23514');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE categories SET name=''   '' WHERE id=%L', study), '23514');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE categories SET slug=NULL WHERE id=%L', study), '23502');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE blogs SET category_mode=''OTHER'' WHERE id=%L', post_a), '23514');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE blogs SET category_mode=NULL WHERE id=%L', post_a), '23502');
    PERFORM
pg_temp.expect_category_error(format('DELETE FROM categories WHERE id=%L', study), '23503');
INSERT INTO blog_categories(blog_id, category_id, category_source)
VALUES (post_a, study, 'MANUAL');
PERFORM
pg_temp.assert_categories(
        (SELECT count(*) = 2 FROM blog_categories WHERE blog_id=post_a AND category_id=study), 'AUTO and MANUAL coexist for same category');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE blog_categories SET confidence=NULL WHERE blog_id=%L AND category_source=''AUTO''', post_a), '23514');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE blog_categories SET confidence=0.9 WHERE blog_id=%L AND category_source=''MANUAL''', post_a), '23514');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE blog_categories SET category_source=''OTHER'' WHERE blog_id=%L', post_b), '23514');
    PERFORM
pg_temp.expect_category_error(format(
        'UPDATE blog_categories SET category_source=NULL WHERE blog_id=%L', post_b), '23502');
DELETE
FROM blog_categories
WHERE blog_id = post_a
  AND category_source = 'MANUAL';
UPDATE blogs
SET category_mode = 'MANUAL'
WHERE id = post_a;
INSERT INTO blog_categories(blog_id, category_id, category_source)
VALUES (post_a, tech, 'MANUAL');
PERFORM
pg_temp.assert_categories(
        (SELECT confidence IS NULL FROM blog_categories WHERE blog_id=post_a AND category_id=tech AND category_source='MANUAL'), 'manual label has no model confidence');
UPDATE blog_categories
SET confidence=0.88
WHERE blog_id = post_a
  AND category_source = 'AUTO';
PERFORM
pg_temp.assert_categories(
        EXISTS(SELECT FROM blog_categories WHERE blog_id=post_a AND category_source='MANUAL' AND category_id=tech)
        AND (SELECT category_mode='MANUAL' FROM blogs WHERE id=post_a), 'AUTO update preserves MANUAL and display mode');
DELETE
FROM blog_categories
WHERE blog_id = post_a
  AND category_source = 'MANUAL';
PERFORM
pg_temp.assert_categories(
        (SELECT category_mode = 'MANUAL' FROM blogs WHERE id = post_a)
        AND NOT EXISTS(SELECT FROM blog_categories WHERE blog_id = post_a AND category_source='MANUAL')
        AND EXISTS(SELECT FROM blog_categories WHERE blog_id = post_a AND category_source='AUTO'), 'manual empty category set preserves AUTO comparison data');
    PERFORM
pg_temp.assert_categories(
        NOT EXISTS(SELECT FROM blog_categories bc JOIN blogs b ON b.id=bc.blog_id
                   WHERE b.id=post_a AND bc.category_source=b.category_mode), 'manual empty display does not fall back to AUTO');
UPDATE blogs
SET category_mode = 'AUTO'
WHERE id = post_a;
UPDATE blog_categories
SET confidence=0.8
WHERE blog_id = post_a
  AND category_id = tech
  AND category_source = 'AUTO';
DELETE
FROM blogs
WHERE id = post_a;
PERFORM
pg_temp.assert_categories(
        NOT EXISTS(SELECT FROM blog_categories WHERE blog_id = post_a)
        AND EXISTS(SELECT FROM categories WHERE id = tech), 'blog delete cascades links and preserves catalog');
    PERFORM
pg_temp.assert_categories(
        (SELECT count(*) = 1 FROM v_public_blog_feed WHERE author_id = author), 'view still one row per public blog');
DELETE
FROM categories
WHERE id = tech;
SET CONSTRAINTS ALL IMMEDIATE;
RAISE
NOTICE 'Category schema checks passed; fixtures will roll back.';
END;
$$;
ROLLBACK;
