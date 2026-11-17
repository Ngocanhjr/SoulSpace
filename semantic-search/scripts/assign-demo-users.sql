-- Import only the 40 demo users and assign the 1000 CSV posts, 25 per user.
-- Copy users.csv and data-semantic-1000.csv to /tmp first; commands are in
-- ../docs/plan-local-embedding.md, section 9. Run with psql ON_ERROR_STOP.
-- Existing users are matched by UUID; only their username is synchronized.
-- Passwords, other profile fields, post contents and embeddings are preserved.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL ROLE soulspace_runtime;
SET LOCAL statement_timeout = '30s';

CREATE TEMP TABLE incoming_demo_users (
    id UUID PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    fullname TEXT NOT NULL,
    dob DATE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
) ON COMMIT DROP;
\copy incoming_demo_users FROM '/tmp/demo-users.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')

CREATE TEMP TABLE incoming_semantic_posts (
    source_id TEXT PRIMARY KEY,
    content TEXT NOT NULL,
    labels TEXT
) ON COMMIT DROP;
\copy incoming_semantic_posts FROM '/tmp/data-semantic-1000.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')

DO $$
BEGIN
    IF current_database() <> 'soulspace' THEN
        RAISE EXCEPTION 'Expected database soulspace';
    END IF;
    IF (SELECT count(*) FROM incoming_demo_users) <> 40
       OR EXISTS (
           SELECT 1 FROM incoming_demo_users
           WHERE username !~ '^[a-z0-9]{3,32}$'
       ) THEN
        RAISE EXCEPTION 'Expected 40 users with valid usernames';
    END IF;
    IF (SELECT count(*) FROM incoming_semantic_posts) <> 1000 THEN
        RAISE EXCEPTION 'Expected 1000 CSV posts';
    END IF;
END;
$$;

-- Keep the author mapping stable even when the CSV rows are reordered.
CREATE TEMP TABLE post_assignment ON COMMIT DROP AS
SELECT md5('soulspace.csv.data-semantic-1000.' || p.source_id)::uuid AS blog_id,
       u.id AS author_id, p.content
FROM (
    SELECT source_id, content, row_number() OVER (ORDER BY source_id COLLATE "C") AS position
    FROM incoming_semantic_posts
) p
JOIN (
    SELECT id, row_number() OVER (ORDER BY id) AS position
    FROM incoming_demo_users
) u ON u.position = ((p.position - 1) % 40) + 1;

-- Stop application writes while checking and changing authors.
LOCK TABLE public.users, public.blogs IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM incoming_demo_users i
        JOIN public.users u ON u.username = i.username
        WHERE u.id <> i.id
    ) THEN
        RAISE EXCEPTION 'Demo username/ID conflict; existing users preserved';
    END IF;
    IF (
        SELECT count(*) FROM post_assignment a
        JOIN public.blogs b ON b.id = a.blog_id AND b.content = a.content
    ) <> 1000 THEN
        RAISE EXCEPTION 'CSV posts missing or content changed; assignment cancelled';
    END IF;
END;
$$;

INSERT INTO public.users (id, username, fullname, dob, created_at, updated_at)
SELECT id, username, fullname, dob, created_at, updated_at
FROM incoming_demo_users
ON CONFLICT (id) DO UPDATE
SET username = EXCLUDED.username, updated_at = clock_timestamp()
WHERE public.users.username IS DISTINCT FROM EXCLUDED.username;

UPDATE public.blogs b
SET author_id = a.author_id
FROM post_assignment a
WHERE b.id = a.blog_id AND b.author_id IS DISTINCT FROM a.author_id;

-- Count only the selected CSV posts, excluding other posts owned by these users.
SELECT u.username, u.fullname, count(*) AS assigned_posts,
       count(e.blog_id) AS stored_vectors,
       count(e.blog_id) FILTER (
           WHERE e.content_sha256 = encode(sha256(convert_to(b.content, 'UTF8')), 'hex')
       ) AS matching_vectors
FROM post_assignment a
JOIN public.blogs b ON b.id = a.blog_id
JOIN public.users u ON u.id = b.author_id
LEFT JOIN public.blog_embeddings e ON e.blog_id = b.id
GROUP BY u.username, u.fullname
ORDER BY u.username;
COMMIT;
