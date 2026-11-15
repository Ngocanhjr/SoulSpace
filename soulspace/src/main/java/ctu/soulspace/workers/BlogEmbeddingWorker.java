// Xử lý job embedding ở nền, tự lấy lại việc dở dang và bỏ kết quả cũ khi bài thay đổi.
package ctu.soulspace.workers;

import ctu.soulspace.repositories.EmbeddingJobRepository;
import ctu.soulspace.repositories.EmbeddingJobRepository.Job;
import ctu.soulspace.repositories.SearchRepository;
import ctu.soulspace.repositories.SearchRepository.PostContent;
import ctu.soulspace.services.SearchEmbedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "embedding.worker.enabled", havingValue = "true")
public class BlogEmbeddingWorker {
    private static final Logger log = LoggerFactory.getLogger(BlogEmbeddingWorker.class);
    private final EmbeddingJobRepository jobs;
    private final SearchRepository posts;
    private final SearchEmbedding embeddings;
    private final TransactionTemplate transactions;

    public BlogEmbeddingWorker(EmbeddingJobRepository jobs, SearchRepository posts,
            SearchEmbedding embeddings, TransactionTemplate transactions) {
        this.jobs = jobs;
        this.posts = posts;
        this.embeddings = embeddings;
        this.transactions = transactions;
    }

    // Sau mỗi lượt xử lý, chờ 5 giây rồi kiểm tra job tiếp theo; chạy khi backend đang mở.
    @Scheduled(fixedDelayString = "${embedding.worker.delay-ms:5000}")
    public void runNext() {
        try {
            jobs.claim().ifPresent(this::process);
        } catch (RuntimeException error) {
            log.warn("Không xử lý được hàng đợi embedding: {}", error.getClass().getSimpleName());
        }
    }

    // Lấy nội dung rồi gọi Jina ngoài transaction để không giữ khóa DB trong lúc chờ mạng.
    private void process(Job job) {
        try {
            var snapshot = posts.findPost(job.blogId());
            if (snapshot.isEmpty()) return; // Bài đã xóa thì FK cũng xóa job.
            PostContent post = snapshot.get();
            String vector = post.searchable() ? embeddings.embedPost(post.content()) : null;
            transactions.executeWithoutResult(status -> finish(job, post, vector));
        } catch (RuntimeException error) {
            jobs.fail(job, error.getClass().getSimpleName());
            log.warn("Job embedding {} gặp lỗi: {}", job.blogId(), error.getClass().getSimpleName());
        }
    }

    // Luôn khóa bài trước, job sau: cùng thứ tự với BlogService để tránh deadlock.
    // Kiểm tra lại nội dung và quyền ngay trước khi lưu vector trong cùng transaction.
    private void finish(Job job, PostContent snapshot, String vector) {
        var current = posts.lockPost(job.blogId());
        if (current.isEmpty() || !jobs.lockOwned(job)) return;
        PostContent post = current.get();
        if (!post.searchable()) {
            posts.deleteEmbedding(job.blogId());
        } else if (!post.equals(snapshot)) {
            jobs.enqueue(job.blogId());
            return;
        } else {
            posts.saveEmbedding(job.blogId(), vector, post.content());
        }
        jobs.complete(job);
        log.info("Đã đồng bộ embedding cho bài {}", job.blogId());
    }
}
