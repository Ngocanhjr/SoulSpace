package ctu.soulspace.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ctu.soulspace.entities.Blog;
import ctu.soulspace.repositories.BlogRepository;
import ctu.soulspace.repositories.EmbeddingJobRepository;
import ctu.soulspace.repositories.UserRepository;
import ctu.soulspace.service.BlogService;
import ctu.soulspace.service.MediaService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class BlogCreationTransactionTests {
    private final UUID actor = UUID.randomUUID();
    private final UUID blogId = UUID.randomUUID();
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();

    @Mock private BlogRepository blogs;
    @Mock private UserRepository users;
    @Mock private MediaService media;
    @Mock private EmbeddingJobRepository jobs;
    @Mock private PlatformTransactionManager manager;
    @Mock private EntityManager em;
    @Mock(answer = RETURNS_SELF) private Query query;
    @InjectMocks private BlogService service;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "em", em);
        when(users.existsById(actor)).thenReturn(true);
        when(media.upload(any(), any(), any())).thenReturn(List.of());
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(actor));
        when(blogs.saveAndFlush(any(Blog.class)))
                .thenAnswer(
                        invocation -> {
                            Blog blog = invocation.getArgument(0);
                            blog.setId(blogId);
                            return blog;
                        });
        when(media.attach(actor, blogId, List.of())).thenReturn(List.of());
    }

    @Test
    void enqueuesSavedBlogBeforeCommitting() {
        var result = service.create(actor, "new post", true, List.of());

        assertThat(result.blogId()).isEqualTo(blogId);
        var order = inOrder(blogs, jobs, manager);
        order.verify(blogs).saveAndFlush(any(Blog.class));
        order.verify(jobs).enqueue(blogId);
        order.verify(manager).commit(transaction);
        verify(manager, never()).rollback(any());
    }

    @Test
    void enqueueFailureRollsBackAndDiscardsUploads() {
        RuntimeException failure = new IllegalStateException("queue unavailable");
        doThrow(failure).when(jobs).enqueue(blogId);

        assertThatThrownBy(() -> service.create(actor, "new post", true, List.of()))
                .isSameAs(failure);
        verify(manager).rollback(transaction);
        verify(manager, never()).commit(any());
        verify(media).discard(List.of());
    }
}
