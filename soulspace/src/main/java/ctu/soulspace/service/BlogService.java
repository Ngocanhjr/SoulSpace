package ctu.soulspace.service;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.constants.MediaConstants;
import ctu.soulspace.dto.response.BlogCreateResponse;
import ctu.soulspace.dto.response.BlogResponse;
import ctu.soulspace.dto.response.BlogUpdateResponse;
import ctu.soulspace.entities.Blog;
import ctu.soulspace.entities.BlogImage;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.BlogRepository;
import ctu.soulspace.repositories.EmbeddingJobRepository;
import ctu.soulspace.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
    private static final int MAX_BLOG_IMAGES = 10;
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

    public BlogUpdateResponse update(
            UUID actor,
            UUID id,
            String content,
            Boolean published,
            List<String> removeUrls,
            List<MultipartFile> files) {
        if (content == null && published == null && removeUrls.isEmpty() && files.isEmpty())
            throw invalid("blog", "Provide a change");
        ownedBlog(actor, id, false);
        String body = content == null ? null : content.strip();
        if (body != null
                && (body.codePointCount(0, body.length()) > MAX_CONTENT_LENGTH
                        || body.indexOf('\0') >= 0)) {
            throw invalid("content", "Use at most 5000 characters without NUL");
        }
        List<BlogImage> before = images(id);
        Set<String> requested = Set.copyOf(removeUrls);
        validateRemoval(before, requested, files.size());
        List<String> failures = new ArrayList<>();
        List<MediaService.UploadedImage> uploaded = media.upload(actor, files, failures);
        try {
            BlogUpdateResponse result =
                    transactions.execute(
                            status -> {
                                lockActor(actor);
                                Blog blog = ownedBlog(actor, id, true);
                                List<BlogImage> current = images(id);
                                validateRemoval(current, requested, uploaded.size());
                                List<BlogImage> removed =
                                        current.stream()
                                                .filter(
                                                        image ->
                                                                requested.contains(
                                                                        mediaUrl(
                                                                                image
                                                                                        .getAssetId())))
                                                .toList();
                                List<BlogImage> remaining =
                                        current.stream()
                                                .filter(
                                                        image ->
                                                                !requested.contains(
                                                                        mediaUrl(
                                                                                image
                                                                                        .getAssetId())))
                                                .toList();
                                String nextContent = body == null ? blog.getContent() : body;
                                if (nextContent.isBlank()
                                        && remaining.isEmpty()
                                        && uploaded.isEmpty())
                                    throw invalid("content", "Keep text or at least one image");
                                List<UUID> assets =
                                        new ArrayList<>(
                                                current.stream()
                                                        .map(BlogImage::getAssetId)
                                                        .toList());
                                assets.addAll(
                                        uploaded.stream()
                                                .map(MediaService.UploadedImage::id)
                                                .toList());
                                media.lockAssets(actor, assets);
                                em.createNativeQuery("SET CONSTRAINTS blog_image_position DEFERRED")
                                        .executeUpdate();
                                for (BlogImage image : removed) em.remove(image);
                                for (int position = 0; position < remaining.size(); position++)
                                    remaining.get(position).setPosition((short) position);
                                boolean changed =
                                        !nextContent.equals(blog.getContent())
                                                || (published != null
                                                        && published != blog.isPublished())
                                                || !removed.isEmpty()
                                                || !uploaded.isEmpty();
                                blog.setContent(nextContent);
                                if (published != null) blog.setPublished(published);
                                em.flush();
                                List<String> urls =
                                        new ArrayList<>(
                                                remaining.stream()
                                                        .map(image -> mediaUrl(image.getAssetId()))
                                                        .toList());
                                urls.addAll(media.attach(actor, id, uploaded, remaining.size()));
                                em.flush();
                                for (BlogImage image : removed) media.enqueue(image.getAssetId());
                                em.flush();
                                if (changed)
                                    em.createNativeQuery(
                                                    "UPDATE blogs SET updated_at = clock_timestamp() WHERE id = :id")
                                            .setParameter("id", id)
                                            .executeUpdate();
                                em.refresh(blog);
                                return new BlogUpdateResponse(
                                        id,
                                        blog.getContent(),
                                        urls,
                                        List.of(),
                                        List.copyOf(failures),
                                        blog.isPublished(),
                                        blog.getUpdatedAt());
                            });
            log.info("event=blog_updated blog_id={} actor_id={}", id, actor);
            return result;
        } catch (RuntimeException failure) {
            media.discard(uploaded);
            throw failure;
        }
    }

    public void delete(UUID actor, UUID id) {
        transactions.executeWithoutResult(
                status -> {
                    lockActor(actor);
                    Blog blog = ownedBlog(actor, id, true);
                    List<UUID> assets = images(id).stream().map(BlogImage::getAssetId).toList();
                    media.lockAssets(actor, assets);
                    em.remove(blog);
                    em.flush();
                    for (UUID asset : assets) media.enqueue(asset);
                    em.flush();
                });
        log.info("event=blog_deleted blog_id={} actor_id={}", id, actor);
    }

    private void lockActor(UUID actor) {
        if (actor == null
                || em.createNativeQuery("SELECT id FROM users WHERE id = :actor FOR KEY SHARE")
                        .setParameter("actor", actor)
                        .getResultList()
                        .isEmpty()) throw new AppException(ErrorCode.AUTH_REQUIRED);
    }

    private Blog ownedBlog(UUID actor, UUID id, boolean lock) {
        Blog blog =
                lock
                        ? em.find(Blog.class, id, LockModeType.PESSIMISTIC_WRITE)
                        : em.find(Blog.class, id);
        if (blog == null || (!blog.isPublished() && !blog.getAuthorId().equals(actor)))
            throw new AppException(ErrorCode.BLOG_NOT_FOUND);
        if (!blog.getAuthorId().equals(actor)) throw new AppException(ErrorCode.FORBIDDEN);
        return blog;
    }

    private List<BlogImage> images(UUID id) {
        return em.createQuery(
                        "select i from BlogImage i where i.blogId = :id order by i.position",
                        BlogImage.class)
                .setParameter("id", id)
                .getResultList();
    }

    private void validateRemoval(List<BlogImage> images, Set<String> requested, int additions) {
        if (!images.stream()
                .map(image -> mediaUrl(image.getAssetId()))
                .collect(Collectors.toSet())
                .containsAll(requested)) {
            throw new AppException(ErrorCode.INVALID_MEDIA_REFERENCE);
        }
        if (images.size() - requested.size() + additions > MAX_BLOG_IMAGES)
            throw invalid("files", "A post can contain at most 10 images");
    }

    private String mediaUrl(UUID id) {
        return "/api/v1/media/" + id;
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
        Set<UUID> shared =
                viewer == null
                        ? Set.of()
                        : Set.copyOf(
                                em.createQuery(
                                                "select s.id.blogId from BlogShare s where s.id.userId = :viewer and s.id.blogId in :ids",
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
                                    shared.contains(blog.getId()),
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
