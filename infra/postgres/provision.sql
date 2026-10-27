-- Run with psql as the database owner on a dedicated, clean database.
-- Docker entrypoint supplies the environment; Neon operators supply it locally.
\set ON_ERROR_STOP on
\getenv runtime_password DB_PASSWORD
\getenv migrator_password FLYWAY_PASSWORD
SELECT length(:'runtime_password') > 0 AND length(:'migrator_password') > 0
       AND :'runtime_password' <> :'migrator_password' AS passwords_valid \gset
\if :passwords_valid
\else
  \echo 'DB_PASSWORD and FLYWAY_PASSWORD must be nonempty and different.'
  \quit 1
\endif

BEGIN;
SELECT 'CREATE ROLE soulspace_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'soulspace_migrator') \gexec
SELECT 'CREATE ROLE soulspace_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'soulspace_runtime') \gexec
ALTER ROLE soulspace_migrator PASSWORD :'migrator_password';
ALTER ROLE soulspace_runtime PASSWORD :'runtime_password';
ALTER ROLE soulspace_runtime SET lock_timeout = '2s';
ALTER ROLE soulspace_runtime SET statement_timeout = '5s';
ALTER ROLE soulspace_runtime SET idle_in_transaction_session_timeout = '30s';
-- Non-superuser Neon owners need SET membership and the new schema owner's CREATE privilege.
GRANT soulspace_migrator TO CURRENT_USER WITH SET TRUE;
SELECT format('GRANT CONNECT, CREATE ON DATABASE %I TO soulspace_migrator', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO soulspace_runtime', current_database()) \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO soulspace_migrator;
GRANT USAGE ON SCHEMA public TO soulspace_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE soulspace_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO soulspace_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE soulspace_migrator IN SCHEMA public
  REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
COMMIT;
