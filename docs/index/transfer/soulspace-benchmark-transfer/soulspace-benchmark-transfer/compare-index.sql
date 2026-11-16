-- Đo cùng ba query trên MỘT database mỗi lần chạy, bằng soulspace_runtime.
-- Chuẩn bị cặp database giống dữ liệu theo README.md; ANALYZE bằng migrator trước.
-- Trong IntelliJ/pgAdmin: chọn database có V2, chạy toàn bộ file và lưu kết quả;
-- chuyển sang bản không V2, chạy lại. Đợt sau đảo thứ tự hai database.
-- Script chỉ SELECT dữ liệu nghiệp vụ và ghi bảng tạm trong session hiện tại.
-- Không tạo database, không bỏ/tạo index, không sửa lịch sử Flyway.

BEGIN;

DO $$
BEGIN
    IF current_database() NOT IN (
        'soulspace_with_index',
        'soulspace_without_index',
        'soulspace_benchmark_with_index',
        'soulspace_benchmark_without_index'
    ) THEN
        RAISE EXCEPTION 'Chọn database benchmark trước khi đo; hiện tại: %',
            current_database();
    END IF;
END;
$$;

-- Cho phép chạy lại file trong cùng console; chỉ xóa bảng TẠM của script.
DROP TABLE IF EXISTS pg_temp.index_benchmark_queries;
DROP TABLE IF EXISTS pg_temp.index_benchmark_results;

CREATE TEMP TABLE index_benchmark_queries (
    query_name TEXT PRIMARY KEY,
    parameter_id UUID NOT NULL,
    query_sql TEXT NOT NULL
);

-- Tự chọn tham số theo đúng quy tắc của measure_sql.py.
-- Hai database phải có dữ liệu giống nhau: kiểm ba UUID hiển thị bên dưới.
-- Nếu muốn chọn UUID khác, sửa ba subquery ở đây và dùng cùng UUID trên hai bản.
INSERT INTO pg_temp.index_benchmark_queries
VALUES
(
    'comments',
    (SELECT c.blog_id
     FROM public.comments c
     JOIN public.blogs b ON b.id = c.blog_id
     WHERE b.published
     GROUP BY c.blog_id
     ORDER BY count(*) DESC, c.blog_id
     LIMIT 1),
    $query$
    SELECT id, blog_id, author_id, parent_id, content,
           likes_count, created_at, updated_at, deleted_at, version
    FROM public.comments
    WHERE blog_id = %L::uuid
    ORDER BY created_at DESC, id DESC
    $query$
),
(
    'author',
    (SELECT author_id
     FROM public.blogs
     WHERE published
     GROUP BY author_id
     ORDER BY count(*) DESC, author_id
     LIMIT 1),
    $query$
    SELECT *
    FROM public.blogs
    WHERE author_id = %L::uuid AND published = true
    ORDER BY created_at DESC, id DESC
    $query$
),
(
    'shares',
    (SELECT user_id
     FROM public.blog_shares
     GROUP BY user_id
     ORDER BY count(*) DESC, user_id
     LIMIT 1),
    $query$
    SELECT b.*, s.shared_at
    FROM public.blog_shares s
    JOIN public.blogs b ON b.id = s.blog_id
    WHERE s.user_id = %L::uuid AND b.published = true
    ORDER BY s.shared_at DESC, s.blog_id DESC
    $query$
);

CREATE TEMP TABLE index_benchmark_results (
    database_name TEXT NOT NULL DEFAULT current_database(),
    measured_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    query_name TEXT NOT NULL,
    parameter_id UUID NOT NULL,
    sample_no INTEGER NOT NULL,
    actual_rows BIGINT NOT NULL,
    planning_ms DOUBLE PRECISION NOT NULL,
    execution_ms DOUBLE PRECISION NOT NULL,
    shared_hit_blocks BIGINT NOT NULL,
    shared_read_blocks BIGINT NOT NULL,
    temp_read_blocks BIGINT NOT NULL,
    temp_written_blocks BIGINT NOT NULL,
    explain_plan JSONB NOT NULL,
    PRIMARY KEY (query_name, sample_no)
);

DO $$
DECLARE
    warmup_count CONSTANT INTEGER := 5;
    measured_count CONSTANT INTEGER := 60;
    query_spec RECORD;
    explain_sql TEXT;
    plan JSONB;
    i INTEGER;
BEGIN
    FOR query_spec IN
        SELECT * FROM pg_temp.index_benchmark_queries ORDER BY query_name
    LOOP
        explain_sql := 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '
            || format(query_spec.query_sql, query_spec.parameter_id);

        FOR i IN 1..(warmup_count + measured_count) LOOP
            EXECUTE explain_sql INTO plan;

            IF i > warmup_count THEN
                INSERT INTO pg_temp.index_benchmark_results (
                    query_name, parameter_id, sample_no, actual_rows,
                    planning_ms, execution_ms, shared_hit_blocks,
                    shared_read_blocks, temp_read_blocks,
                    temp_written_blocks, explain_plan
                ) VALUES (
                    query_spec.query_name,
                    query_spec.parameter_id,
                    i - warmup_count,
                    (plan #>> '{0,Plan,Actual Rows}')::bigint,
                    (plan #>> '{0,Planning Time}')::double precision,
                    (plan #>> '{0,Execution Time}')::double precision,
                    COALESCE((plan #>> '{0,Plan,Shared Hit Blocks}')::bigint, 0),
                    COALESCE((plan #>> '{0,Plan,Shared Read Blocks}')::bigint, 0),
                    COALESCE((plan #>> '{0,Plan,Temp Read Blocks}')::bigint, 0),
                    COALESCE((plan #>> '{0,Plan,Temp Written Blocks}')::bigint, 0),
                    plan
                );
            END IF;
        END LOOP;
    END LOOP;
END;
$$;

COMMIT;

-- KẾT QUẢ 1: môi trường và tham số; phải dùng cùng role, config và UUID hai bản.
SELECT current_database() AS database_name, current_user AS measured_by,
       version() AS postgres_version;

SELECT name, setting, unit
FROM pg_settings
WHERE name IN (
    'shared_buffers', 'work_mem', 'random_page_cost', 'effective_cache_size',
    'jit', 'enable_seqscan', 'enable_bitmapscan', 'enable_indexscan',
    'statement_timeout', 'lock_timeout', 'autovacuum'
)
ORDER BY name;

SELECT query_name, parameter_id, format(query_sql, parameter_id) AS measured_sql
FROM pg_temp.index_benchmark_queries
ORDER BY query_name;

-- KẾT QUẢ 2: các index thực sự còn trên database hiện tại (bao gồm PK/UNIQUE).
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;

-- KẾT QUẢ 3: tóm tắt 60 mẫu/query, đơn vị ms.
-- p50 = median; p95 = nearest rank, cùng cách tính với measure_sql.py.
SELECT database_name, query_name, parameter_id,
       count(*) AS samples,
       min(actual_rows) AS min_rows,
       max(actual_rows) AS max_rows,
       round((percentile_cont(0.50) WITHIN GROUP (ORDER BY execution_ms))::numeric, 4)
           AS execution_p50_ms,
       round((percentile_disc(0.95) WITHIN GROUP (ORDER BY execution_ms))::numeric, 4)
           AS execution_p95_ms,
       round((percentile_cont(0.50) WITHIN GROUP (ORDER BY planning_ms))::numeric, 4)
           AS planning_p50_ms,
       min(shared_hit_blocks) AS min_shared_hit,
       max(shared_hit_blocks) AS max_shared_hit,
       min(shared_read_blocks) AS min_shared_read,
       max(shared_read_blocks) AS max_shared_read
FROM pg_temp.index_benchmark_results
GROUP BY database_name, query_name, parameter_id
ORDER BY query_name;

-- KẾT QUẢ 4: từng mẫu và plan; xuất kết quả ra file trước khi đóng console.
-- Bảng tạm mất khi session kết thúc hoặc khi chạy lại script.
SELECT *
FROM pg_temp.index_benchmark_results
ORDER BY query_name, sample_no;

-- So sánh hai bảng tóm tắt:
--     hệ số nhanh hơn = execution_p50_ms_không_V2 / execution_p50_ms_có_V2.
-- Đây là Execution Time của SELECT, không phải toàn bộ thời gian API/UI.
-- SQL file không tự kết nối sang database còn lại hoặc chứng minh dữ liệu giống nhau.
-- File này đo 60 mẫu liên tiếp/query/database; không cùng lịch 6 block xen kẽ hai DB
-- của công cụ Python/báo cáo trước. Đảo thứ tự chạy cả file giữa các đợt và ghi rõ cách đo.
