-- Run as administrator connected to postgres, with Auto-commit enabled.
-- Disconnect all source sessions first. No existing database is overwritten.
CREATE DATABASE soulspace_benchmark_without_index
    WITH TEMPLATE soulspace_benchmark_with_index OWNER soulspace_migrator;
GRANT CONNECT ON DATABASE soulspace_benchmark_without_index TO soulspace_runtime;
