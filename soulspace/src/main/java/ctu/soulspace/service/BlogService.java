package ctu.soulspace.service;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.constants.MediaConstants;
import ctu.soulspace.dto.response.BlogCreateResponse;
import ctu.soulspace.dto.response.BlogResponse;
import ctu.soulspace.entities.Blog;
import ctu.soulspace.entities.BlogImage;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.BlogRepository;
import ctu.soulspace.repositories.EmbeddingJobRepository;
import ctu.soulspace.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BlogService {
    private static final int MAX_CONTENT_LENGTH = 5_000;
    private static final Logger log = LoggerFactory.getLogger(BlogService.class);
    @PersistenceContext private EntityManager em;
    private final BlogRepository blogs;
    private final UserRepository users;
    private final MediaService media;
    private final EmbeddingJobRepository embeddingJobs;
    private final TransactionTemplate transactions;

    public BlogService(
            BlogRepository blogs,
            UserRepository users,
            MediaService media,
            EmbeddingJobRepository embeddingJobs,
            PlatformTransactionManager transactionManager) {
        this.blogs = blogs;
        this.users = users;
        this.media = media;
        this.embeddingJobs = embeddingJobs;
        transactions = new TransactionTemplate(transactionManager);
    }

    public BlogCreateResponse create(
            UUID actor, String content, boolean published, List<MultipartFile> files) {
        String body = content == null ? "" : content.strip();
        if (body.codePointCount(0, body.length()) > MAX_CONTENT_LENGTH || body.indexOf('\0') >= 0) {
            throw invalid("content", "Use at most 5000 characters without NUL");
        }
        if (body.isEmpty() && files.isEmpty())
            throw invalid("content", "Add text or at least one image");
        if (!users.existsById(actor)) throw new AppException(ErrorCode.AUTH_REQUIRED);
        List<String> failedFiles = new ArrayList<>();
        List<MediaService.UploadedImage> uploaded = media.upload(actor, files, failedFiles);
        if (body.isEmpty() && uploaded.isEmpty())
            throw new AppException(ErrorCode.MEDIA_PROVIDER_ERROR);
        // Uploads finish first; only the database writes share this short transaction.
        BlogCreateResponse response;
        try {
            response =
                    transactions.execute(
                            status -> {
                                if (em.createNativeQuery(
                                                "SELECT id FROM users WHERE id = :actor FOR KEY SHARE")
                                        .setParameter("actor", actor)
                                        .getResultList()
                                        .isEmpty()) {
                                    throw new AppException(ErrorCode.AUTH_REQUIRED);
                                }
                                Blog blog = new Blog();
                                blog.setAuthorId(actor);
                                blog.setContent(body);
                                blog.setPublished(published);
                                blogs.saveAndFlush(blog);
                                List<String> urls = media.attach(actor, blog.getId(), uploaded);
                                em.flush();
                                embeddingJobs.enqueue(blog.getId());
                                return new BlogCreateResponse(
                                        blog.getId(), urls, List.copyOf(failedFiles));
                            });
        } catch (RuntimeException exception) {
            media.discard(uploaded);
            throw exception;
        }
        log.info(
                "event=blog_created blog_id={} author_id={} published={} image_count={}",
                response.blogId(),
                actor,
                published,
                response.successUrls().size());
        return response;
    }

    @Transactional(readOnly = true)
    public List<BlogResponse> feed(UUID viewer) {
        return responses(blogs.findByPublishedTrueOrderByCreatedAtDescIdDesc(), viewer);
    }

    @Transactional(readOnly = true)
    public List<BlogResponse> byAuthor(UUID author, UUID viewer, boolean includePrivate) {
        if (includePrivate && !author.equals(viewer)) throw new AppException(ErrorCode.FORBIDDEN);
        if (!users.existsById(author)) throw new AppException(ErrorCode.USER_NOT_FOUND);
        return responses(
                includePrivate
                        ? blogs.findByAuthorIdOrderByCreatedAtDescIdDesc(author)
                        : blogs.findByAuthorIdAndPublishedTrueOrderByCreatedAtDescIdDesc(author),
                viewer);
    }

    @Transactional(readOnly = true)
    public BlogResponse find(UUID id, UUID viewer) {
        Blog blog =
                blogs.findById(id).orElseThrow(() -> new AppException(ErrorCode.BLOG_NOT_FOUND));
        if (!blog.isPublished() && !blog.getAuthorId().equals(viewer)) {
            throw new AppException(ErrorCode.BLOG_NOT_FOUND);
        }
        return responses(List.of(blog), viewer).getFirst();
    }

    List<BlogResponse> responses(List<Blog> rows, UUID viewer) {
        if (rows.isEmpty()) return List.of();
        List<UUID> ids = rows.stream().map(Blog::getId).toList();
        Map<UUID, User> authors =
                users.findAllById(rows.stream().map(Blog::getAuthorId).distinct().toList()).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, List<String>> images = new HashMap<>();
        em.createQuery(
                        "select i from BlogImage i where i.blogId in :ids order by i.position",
                        BlogImage.class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(
                        image ->
                                images.computeIfAbsent(
                                                image.getBlogId(), ignored -> new ArrayList<>())
                                        .add("/api/v1/media/" + image.getAssetId()));
        Set<UUID> liked =
                viewer == null
                        ? Set.of()
                        : Set.copyOf(
                                em.createQuery(
                                                "select l.id.blogId from BlogLike l where l.id.userId = :viewer and l.id.blogId in :ids",
                                                UUID.class)
                                        .setParameter("viewer", viewer)
                                        .setParameter("ids", ids)
                                        .getResultList());
        return rows.stream()
                .filter(blog -> authors.containsKey(blog.getAuthorId()))
                .map(
                        blog -> {
                            User author = authors.get(blog.getAuthorId());
                            String avatar =
                                    author.getAvatarMediaId() == null
                                            ? MediaConstants.DEFAULT_AVATAR_URL
                                            : "/api/v1/media/" + author.getAvatarMediaId();
                            return new BlogResponse(
                                    blog.getId(),
                                    author.getId(),
                                    author.getUsername(),
                                    avatar,
                                    blog.getContent(),
                                    images.getOrDefault(blog.getId(), List.of()),
                                    count(blog.getLikesCount()),
                                    count(blog.getCommentsCount()),
                                    count(blog.getSharesCount()),
                                    liked.contains(blog.getId()) ? List.of(viewer) : List.of(),
                                    blog.isPublished(),
                                    blog.getCreatedAt(),
                                    blog.getUpdatedAt());
                        })
                .toList();
    }

    private long count(Long value) {
        return value == null ? 0 : value;
    }

    private AppException invalid(String field, String message) {
        return new AppException(ErrorCode.VALIDATION_FAILED, Map.of(field, message));
    }
}
