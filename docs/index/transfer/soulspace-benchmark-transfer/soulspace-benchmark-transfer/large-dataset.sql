-- Chỉ dùng trên database benchmark riêng, trống và đã chạy Flyway V1–V5.
-- Chạy bằng soulspace_runtime. Không tắt trigger/FK; ANALYZE bằng migrator sau nạp.
-- Main IDs/nội dung/thời gian cố định; notifications.id/created_at do trigger sinh.
-- Clone nguồn sau nạp để hai bản có cả notifications giống hệt nhau.
-- Seed lớn tạm nâng statement_timeout lên 120s cho session nạp; session đo giữ 5s.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'soulspace_benchmark_with_index' THEN
        RAISE EXCEPTION 'Wrong benchmark database: %', current_database();
    END IF;
    IF EXISTS (SELECT FROM users) OR EXISTS (SELECT FROM blogs)
       OR EXISTS (SELECT FROM categories) OR EXISTS (SELECT FROM media_assets)
       OR EXISTS (SELECT FROM notifications) THEN
        RAISE EXCEPTION 'Benchmark database must be empty';
    END IF;
END;
$$;
INSERT INTO users(id,username,fullname,created_at,updated_at)
SELECT md5('indexbench.user.'||i)::uuid, 'bench'||lpad(i::text,4,'0'),
       'Nhân vật mô phỏng '||i, '2026-05-01'::timestamptz, '2026-05-01'::timestamptz
FROM generate_series(1,1000) i;

INSERT INTO blogs(id,author_id,content,published,created_at,updated_at)
SELECT md5('indexbench.blog.'||i)::uuid,
       md5('indexbench.user.'||(((i-1)%1000)+1))::uuid,
       'Bài mô phỏng số '||i||'. '||repeat('Hôm nay tôi dành thời gian học tập, nghỉ ngơi và trò chuyện cùng bạn bè. ',1+(i%12)),
       (((i-1)/1000+(i-1)%1000)%8)<>0,
       '2026-07-01'::timestamptz+(i*101%1000000)*interval '1 second',
       '2026-07-01'::timestamptz+(i*101%1000000)*interval '1 second'
FROM generate_series(1,10000) i;

INSERT INTO comments(id,blog_id,author_id,content,created_at,updated_at)
SELECT md5('indexbench.comment.'||i)::uuid,md5('indexbench.blog.'||(((i-1)%2000)+1))::uuid,
       CASE WHEN b.published THEN md5('indexbench.user.'||(((i+17)%1000)+1))::uuid ELSE b.author_id END,
       'Bình luận mô phỏng '||i||'. '||repeat('Cảm ơn bạn đã chia sẻ câu chuyện này. ',1+i%4),
       '2026-08-01'::timestamptz+i*interval '1 second',
       '2026-08-01'::timestamptz+i*interval '1 second'
FROM generate_series(1,10000) i JOIN blogs b ON b.id=md5('indexbench.blog.'||(((i-1)%2000)+1))::uuid;

INSERT INTO blog_likes(blog_id,user_id,created_at)
SELECT b.id,md5('indexbench.user.'||(((i-1+offset_n)%1000)+1))::uuid,
       '2026-09-01'::timestamptz+(i*6+offset_n)*interval '1 second'
FROM generate_series(1,10000) i JOIN blogs b ON b.id=md5('indexbench.blog.'||i)::uuid
CROSS JOIN generate_series(1,6) offset_n WHERE b.published ORDER BY i,offset_n LIMIT 50000;

INSERT INTO blog_shares(user_id,blog_id,shared_at)
SELECT md5('indexbench.user.'||(((i-1+17)%1000)+1))::uuid,b.id,
       '2026-10-01'::timestamptz+i*interval '1 second'
FROM generate_series(1,10000) i JOIN blogs b ON b.id=md5('indexbench.blog.'||i)::uuid
WHERE b.published ORDER BY i LIMIT 5000;

INSERT INTO comment_likes(comment_id,user_id,created_at)
SELECT c.id,md5('indexbench.user.'||(((i+31)%1000)+1))::uuid,
       '2026-10-15'::timestamptz+i*interval '1 second'
FROM generate_series(1,10000) i JOIN comments c ON c.id=md5('indexbench.comment.'||i)::uuid
JOIN blogs b ON b.id=c.blog_id WHERE b.published ORDER BY i LIMIT 2000;
COMMIT;
