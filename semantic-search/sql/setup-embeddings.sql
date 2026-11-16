-- Prepare embedding storage/jobs on a database with the V1-V5 core schema.
-- Run as the database owner/superuser; pgvector must be installed on the server.
-- Existing tables and their data are not replaced.
\set ON_ERROR_STOP on

BEGIN;
CREATE EXTENSION IF NOT EXISTS vector;

-- Keep ownership with the role that owns the application schema.
SET LOCAL ROLE soulspace_migrator;

CREATE TABLE IF NOT EXISTS public.blog_embeddings (
    blog_id UUID PRIMARY KEY REFERENCES public.blogs(id) ON DELETE CASCADE,
    embedding VECTOR(1024) NOT NULL,
    content_sha256 TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS public.blog_embedding_jobs (
    blog_id UUID PRIMARY KEY REFERENCES public.blogs(id) ON DELETE CASCADE,
    generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_until TIMESTAMPTZ,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS blog_embedding_jobs_available_idx
    ON public.blog_embedding_jobs (available_at, blog_id)
    WHERE status IN ('PENDING', 'RUNNING');

GRANT SELECT, INSERT, UPDATE, DELETE
    ON public.blog_embeddings, public.blog_embedding_jobs TO soulspace_runtime;
COMMIT;
