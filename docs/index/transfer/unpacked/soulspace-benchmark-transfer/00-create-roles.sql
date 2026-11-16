-- Run as PostgreSQL administrator. No passwords from the source are included.
-- Existing roles/passwords/settings are kept as-is.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'soulspace_migrator') THEN
        CREATE ROLE soulspace_migrator LOGIN NOSUPERUSER NOCREATEDB
            NOCREATEROLE NOREPLICATION;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'soulspace_runtime') THEN
        CREATE ROLE soulspace_runtime LOGIN NOSUPERUSER NOCREATEDB
            NOCREATEROLE NOREPLICATION;
        ALTER ROLE soulspace_runtime SET lock_timeout = '2s';
        ALTER ROLE soulspace_runtime SET statement_timeout = '5s';
        ALTER ROLE soulspace_runtime SET idle_in_transaction_session_timeout = '30s';
    END IF;
END;
$$;
