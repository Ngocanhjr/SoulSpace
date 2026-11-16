-- Chạy bằng soulspace_migrator trên từng database benchmark, sau clone/bỏ V2.
-- Chỉ đọc bảng ứng dụng, ghi kết quả đối soát vào bảng tạm của session hiện tại.
BEGIN;

DO $$
BEGIN
    IF current_database() NOT IN (
        'soulspace_with_index', 'soulspace_without_index',
        'soulspace_benchmark_with_index', 'soulspace_benchmark_without_index'
    ) THEN
        RAISE EXCEPTION 'Wrong benchmark database: %', current_database();
    END IF;
END;
$$;

DROP TABLE IF EXISTS pg_temp.index_evidence_data;
CREATE TEMP TABLE index_evidence_data (
    table_name TEXT PRIMARY KEY,
    row_count BIGINT NOT NULL,
    content_sha256 TEXT NOT NULL
);

DO $$
DECLARE
    table_record RECORD;
    rows_found BIGINT;
    fingerprint TEXT;
BEGIN
    FOR table_record IN
        SELECT tablename FROM pg_tables
        WHERE schemaname = 'public' ORDER BY tablename
    LOOP
        EXECUTE format(
            $query$
            SELECT count(*), encode(sha256(convert_to(
                COALESCE(string_agg(to_jsonb(t)::text, E'\n'
                    ORDER BY to_jsonb(t)::text), ''), 'UTF8')), 'hex')
            FROM public.%I t
            $query$, table_record.tablename
        ) INTO rows_found, fingerprint;
        INSERT INTO pg_temp.index_evidence_data
        VALUES (table_record.tablename, rows_found, fingerprint);
    END LOOP;
END;
$$;

COMMIT;

-- 1. Môi trường và role quản trị schema dùng để đối soát.
SELECT current_database() AS database_name, current_user AS checked_by,
       version() AS postgres_version;

-- 2. Số hàng và dấu vân tay dữ liệu: phải giống nhau giữa hai database clone.
SELECT current_database() AS database_name, *
FROM pg_temp.index_evidence_data
ORDER BY table_name;

-- 3. Cả hai bản vẫn có lịch sử V1–V5 thành công, kể cả bản đã bỏ index V2.
SELECT current_database() AS database_name, version, script, checksum, success
FROM public.flyway_schema_history
ORDER BY installed_rank;

-- 4. Theo source hiện tại: 44/25 index, 82 constraints, 18 trigger ứng dụng.
SELECT current_database() AS database_name,
       (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public') AS indexes,
       (SELECT count(*) FROM pg_constraint
        WHERE connamespace = 'public'::regnamespace) AS constraints,
       (SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal) AS app_triggers;

-- 5. Đối chiếu tên/định nghĩa index và constraints khi cần bằng chứng chi tiết.
SELECT current_database() AS database_name, tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;

SELECT current_database() AS database_name,
       conrelid::regclass AS table_name, conname, contype,
       pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
ORDER BY table_name, conname;
