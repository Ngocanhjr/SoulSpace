package ctu.soulspace.workers;

import ctu.soulspace.repositories.EmbeddingJobRepository;
import ctu.soulspace.repositories.EmbeddingJobRepository.Job;
import ctu.soulspace.repositories.SearchRepository;
import ctu.soulspace.repositories.SearchRepository.PostContent;
import ctu.soulspace.service.SearchEmbeddingService;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BlogEmbeddingWorkerTests {
    private final EmbeddingJobRepository jobs = mock(EmbeddingJobRepository.class);
    private final SearchRepository posts = mock(SearchRepository.class);
    private final SearchEmbeddingService embeddings = mock(SearchEmbeddingService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final BlogEmbeddingWorker worker = new BlogEmbeddingWorker(jobs, posts, embeddings, transactions);
    private final Job job = new Job(UUID.randomUUID(), 1, 1);
    private final PostContent original = new PostContent("Nội dung bài", true);

    @BeforeEach
    void setUp() {
        when(jobs.claim()).thenReturn(Optional.of(job));
        when(posts.findPost(job.blogId())).thenReturn(Optional.of(original));
        when(posts.lockPost(job.blogId())).thenReturn(Optional.of(original));
        when(jobs.lockOwned(job)).thenReturn(true);
        when(embeddings.embedPost(original.content())).thenReturn("[1]");
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    @Test
    void storesVectorBeforeCompletingJob() {
        worker.runNext();
        InOrder order = inOrder(posts, jobs);
        order.verify(posts).lockPost(job.blogId());
        order.verify(jobs).lockOwned(job);
        order.verify(posts).saveEmbedding(job.blogId(), "[1]", original.content());
        order.verify(jobs).complete(job);
    }

    @Test
    void discardsResultWhenAnotherWorkerOrGenerationOwnsJob() {
        when(jobs.lockOwned(job)).thenReturn(false);
        worker.runNext();
        verify(posts, never()).saveEmbedding(any(), any(), any());
        verify(jobs, never()).complete(any());
    }

    @Test
    void removesVectorWhenPostBecomesPrivateDuringRequest() {
        when(posts.lockPost(job.blogId())).thenReturn(Optional.of(new PostContent(original.content(), false)));
        worker.runNext();
        verify(posts).deleteEmbedding(job.blogId());
        verify(posts, never()).saveEmbedding(any(), any(), any());
        verify(jobs).complete(job);
    }

    @Test
    void requeuesChangedContentInsteadOfSavingOldVector() {
        when(posts.lockPost(job.blogId())).thenReturn(Optional.of(new PostContent("Đã sửa", true)));
        worker.runNext();
        verify(jobs).enqueue(job.blogId());
        verify(posts, never()).saveEmbedding(any(), any(), any());
        verify(jobs, never()).complete(any());
    }

    @Test
    void privatePostSkipsJinaAndRemovesVector() {
        PostContent privatePost = new PostContent(original.content(), false);
        when(posts.findPost(job.blogId())).thenReturn(Optional.of(privatePost));
        when(posts.lockPost(job.blogId())).thenReturn(Optional.of(privatePost));
        worker.runNext();
        verifyNoInteractions(embeddings);
        verify(posts).deleteEmbedding(job.blogId());
        verify(jobs).complete(job);
    }

    @Test
    void failureRecordsOnlyErrorTypeAndDoesNotComplete() {
        when(embeddings.embedPost(original.content())).thenThrow(new IllegalStateException("private message"));
        worker.runNext();
        verify(jobs).fail(job, "IllegalStateException");
        verify(jobs, never()).complete(any());
    }

    @Test
    void emptyQueueDoesNotCallJina() {
        when(jobs.claim()).thenReturn(Optional.empty());
        worker.runNext();
        verifyNoInteractions(posts, embeddings, transactions);
    }
}
