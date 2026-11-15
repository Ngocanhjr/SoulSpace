\set ON_ERROR_STOP on
\if :{?username}
\else
\set username 'testuser01'
\endif

-- Run on the same database configured in DB_URL. No password/hash is printed.
SELECT current_database() AS database_name, current_user AS database_role;

SELECT id, username, fullname, created_at, updated_at,
       password_hash IS NOT NULL AS password_enabled,
       password_hash LIKE '$argon2id$%' AS uses_argon2id,
       substring(id::text FROM 15 FOR 1) AS uuid_version
FROM public.users
WHERE username = lower(btrim(:'username'));

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public' AND tablename = 'users'
ORDER BY indexname;

-- An empty result means users has no application trigger. FK/internal triggers are excluded.
SELECT tgname, pg_get_triggerdef(oid) AS definition
FROM pg_trigger
WHERE tgrelid = 'public.users'::regclass AND NOT tgisinternal;

-- These routines are available for interactions; signup/login do not call them.
SELECT p.proname, p.prokind
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public'
  AND p.proname IN ('sp_set_blog_like', 'sp_set_comment_like', 'sp_share_blog', 'sp_unshare_blog')
ORDER BY p.proname;
