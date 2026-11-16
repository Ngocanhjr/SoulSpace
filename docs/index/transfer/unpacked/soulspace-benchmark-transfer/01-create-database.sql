-- Run as administrator connected to postgres, with Auto-commit enabled.
-- This fails if the database exists; it never drops or overwrites a database.
CREATE DATABASE soulspace_benchmark_with_index
    OWNER soulspace_migrator TEMPLATE template0 ENCODING 'UTF8';
GRANT CONNECT ON DATABASE soulspace_benchmark_with_index TO soulspace_runtime;
