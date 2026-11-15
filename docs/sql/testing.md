## Kiểm tra đk tài khoản

```
docker compose --env-file .env.docker exec postgres psql -U postgres -d soulspace

SELECT
    id,
    username,
    fullname,
    created_at,
    password_hash IS NOT NULL AS password_enabled,
    password_hash LIKE '$argon2id$%' AS uses_argon2id,
    substring(id::text FROM 15 FOR 1) AS uuid_version
FROM public.users
WHERE username = 'testuser01';

SELECT id, username, fullname
FROM public.users

docker compose --env-file .env.docker exec -T postgres psql -U postgres -d soulspace -v username=testuser01 < infra/postgres/check-auth.sql

```