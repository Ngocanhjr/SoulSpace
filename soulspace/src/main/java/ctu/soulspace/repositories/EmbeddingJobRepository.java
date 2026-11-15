// Quản lý hàng đợi embedding trên Neon; mỗi bài có một job với phiên bản generation.
package ctu.soulspace.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EmbeddingJobRepository {
    private final JdbcTemplate jdbc;

    public EmbeddingJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Gọi từ BlogService sau khi lưu bài, trong cùng @Transactional với việc lưu bài.
    // Nếu lưu bài bằng JPA, dùng saveAndFlush/flush trước vì JDBC không tự flush EntityManager.
    // MANDATORY báo lỗi nếu người gọi quên mở transaction, tránh lưu bài mà thiếu job.
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID blogId) {
        jdbc.update("""
                INSERT INTO public.blog_embedding_jobs AS j (blog_id)
                VALUES (?)
                ON CONFLICT (blog_id) DO UPDATE
                SET generation = j.generation + 1, status = 'PENDING', attempts = 0,
                    available_at = clock_timestamp(), lease_until = NULL, last_error = NULL
                """, blogId);
    }

    // Nhận một job bằng khóa SKIP LOCKED để nhiều backend không nhận cùng một lượt xử lý.
    // Job RUNNING hết hạn được nhận lại; tối đa 5 lượt, kể cả khi worker bị dừng giữa chừng.
    @Transactional
    public Optional<Job> claim() {
        jdbc.update("""
                UPDATE public.blog_embedding_jobs
                SET status = 'DEAD', lease_until = NULL, last_error = 'Lease expired after final attempt'
                WHERE status = 'RUNNING' AND lease_until <= clock_timestamp() AND attempts >= 5
                """);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT blog_id FROM public.blog_embedding_jobs
                    WHERE attempts < 5 AND (
                        (status = 'PENDING' AND available_at <= clock_timestamp())
                        OR (status = 'RUNNING' AND lease_until <= clock_timestamp())
                    )
                    ORDER BY available_at, blog_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE public.blog_embedding_jobs AS j
                SET status = 'RUNNING', attempts = j.attempts + 1,
                    lease_until = clock_timestamp() + interval '2 minutes'
                FROM candidate AS c WHERE j.blog_id = c.blog_id
                RETURNING j.blog_id, j.generation, j.attempts
                """, (rs, row) -> new Job(rs.getObject("blog_id", UUID.class),
                        rs.getLong("generation"), rs.getInt("attempts"))).stream().findFirst();
    }

    // Generation + attempts nhận diện lượt xử lý. Worker cũ mất quyền khi bài đổi hoặc lease hết hạn.
    public boolean lockOwned(Job job) {
        return !jdbc.queryForList("""
                SELECT blog_id FROM public.blog_embedding_jobs
                WHERE blog_id = ? AND generation = ? AND attempts = ?
                  AND status = 'RUNNING' AND lease_until > clock_timestamp()
                FOR UPDATE
                """, UUID.class, job.blogId(), job.generation(), job.attempts()).isEmpty();
    }

    // Gọi cùng transaction với lưu/xóa vector, sau khi lockOwned đã thành công.
    public void complete(Job job) {
        jdbc.update("""
                UPDATE public.blog_embedding_jobs SET status = 'DONE', lease_until = NULL, last_error = NULL
                WHERE blog_id = ? AND generation = ? AND attempts = ? AND status = 'RUNNING'
                """, job.blogId(), job.generation(), job.attempts());
    }

    // Chỉ sửa đúng lượt job đang giữ. Lưu tên loại lỗi để tránh ghi nội dung bài hoặc secret vào DB.
    public void fail(Job job, String errorType) {
        jdbc.update("""
                UPDATE public.blog_embedding_jobs
                SET status = CASE WHEN attempts >= 5 THEN 'DEAD' ELSE 'PENDING' END,
                    available_at = clock_timestamp() + attempts * interval '10 seconds',
                    lease_until = NULL, last_error = ?
                WHERE blog_id = ? AND generation = ? AND attempts = ?
                  AND status = 'RUNNING' AND lease_until > clock_timestamp()
                """, errorType, job.blogId(), job.generation(), job.attempts());
    }

    public record Job(UUID blogId, long generation, int attempts) {}
}
