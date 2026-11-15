// Truy vấn Neon qua JDBC để lấy tối đa 10 bài công khai có vector gần câu tìm kiếm nhất.
package ctu.soulspace.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SearchRepository {
    private final JdbcTemplate jdbc;

    public SearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchResult> search(String queryVector) {
        return jdbc.query("""
                SELECT p.blog_id, p.content, p.username,
                       1 - (e.embedding <=> ?::vector) AS similarity
                FROM public.blog_embeddings AS e
                JOIN public.v_public_blog_feed AS p USING (blog_id)
                WHERE e.content_sha256 = encode(sha256(convert_to(p.content, 'UTF8')), 'hex')
                ORDER BY similarity DESC, p.blog_id
                LIMIT 10
                """, (rs, row) -> new SearchResult(rs.getObject("blog_id", UUID.class),
                        rs.getString("content"), rs.getString("username"),
                        rs.getDouble("similarity")), queryVector);
    }

    public record SearchResult(UUID id, String content, String username, double similarity) {}

    // Đọc phiên bản nội dung mà worker chuẩn bị gửi cho Jina.
    public Optional<PostContent> findPost(UUID blogId) {
        return readPost(blogId, false);
    }

    // Khóa bài trong transaction hoàn tất job để tránh sửa bài xen giữa bước kiểm tra và lưu vector.
    public Optional<PostContent> lockPost(UUID blogId) {
        return readPost(blogId, true);
    }

    private Optional<PostContent> readPost(UUID blogId, boolean lock) {
        String sql = "SELECT content, published FROM public.blogs WHERE id = ?";
        if (lock) sql += " FOR UPDATE";
        return jdbc.query(sql, (rs, row) -> new PostContent(rs.getString("content"),
                rs.getBoolean("published")), blogId).stream().findFirst();
    }

    // Vector và SHA-256 phải cùng xuất phát từ nội dung đã kiểm tra ở cuối job.
    public void saveEmbedding(UUID blogId, String vector, String content) {
        jdbc.update("""
                INSERT INTO public.blog_embeddings (blog_id, embedding, content_sha256)
                VALUES (?, ?::vector, encode(sha256(convert_to(?::text, 'UTF8')), 'hex'))
                ON CONFLICT (blog_id) DO UPDATE
                SET embedding = EXCLUDED.embedding, content_sha256 = EXCLUDED.content_sha256
                """, blogId, vector, content);
    }

    // Bài riêng tư hoặc rỗng không cần giữ vector trong tập tìm kiếm công khai.
    public void deleteEmbedding(UUID blogId) {
        jdbc.update("DELETE FROM public.blog_embeddings WHERE blog_id = ?", blogId);
    }

    public record PostContent(String content, boolean published) {
        public boolean searchable() {
            return published && content != null && !content.isBlank();
        }
    }
}
