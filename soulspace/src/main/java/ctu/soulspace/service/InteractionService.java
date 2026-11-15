package ctu.soulspace.service;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.constants.MediaConstants;
import ctu.soulspace.dto.response.BlogResponse;
import ctu.soulspace.dto.response.CommentResponse;
import ctu.soulspace.dto.response.CommentResult;
import ctu.soulspace.dto.response.LikeResponse;
import ctu.soulspace.dto.response.ShareResponse;
import ctu.soulspace.dto.response.SharedBlogResponse;
import ctu.soulspace.entities.Blog;
import ctu.soulspace.entities.Comment;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
public class InteractionService {
    private static final int MAX_COMMENT_LENGTH = 2_000;
    private static final Logger log = LoggerFactory.getLogger(InteractionService.class);
    @PersistenceContext private EntityManager em;
    private final UserRepository users;
    private final BlogService blogs;
    private final TransactionTemplate transactions;

    public InteractionService(
            UserRepository users, BlogService blogs, PlatformTransactionManager manager) {
        this.users = users;
        this.blogs = blogs;
        transactions = new WriteTransaction(manager);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(UUID blogId, UUID viewer) {
        readableBlog(blogId, viewer, false);
        return commentTree(blogId, viewer).roots();
    }

    public CommentResult addComment(UUID actor, UUID blogId, UUID parentId, String content) {
        CommentResult result =
                write(
                        () -> {
                            lockActor(actor);
                            Blog blog = readableBlog(blogId, actor, true);
                            if (parentId != null) {
                                Comment parent =
                                        lockedComment(
                                                blogId, parentId, ErrorCode.VALIDATION_FAILED);
                                if (parent.getDeletedAt() != null)
                                    throw new AppException(ErrorCode.COMMENT_DELETED);
                            }
                            Comment comment = new Comment();
                            comment.setAuthorId(actor);
                            comment.setBlogId(blogId);
                            comment.setParentId(parentId);
                            comment.setContent(commentContent(content));
                            em.persist(comment);
                            em.flush();
                            em.refresh(comment);
                            return commentResult(
                                    blog,
                                    comment,
                                    actor,
                                    parentId != null,
                                    parentId == null
                                            ? "Comment added successfully"
                                            : "Reply added successfully");
                        });
        log.info("event=comment_created blog_id={} actor_id={}", blogId, actor);
        return result;
    }

    public CommentResult changeComment(
            UUID actor,
            UUID blogId,
            UUID commentId,
            String content,
            JsonNode expectedVersion,
            boolean delete) {
        CommentResult result =
                write(
                        () -> {
                            lockActor(actor);
                            Blog blog = readableBlog(blogId, actor, true);
                            Comment comment =
                                    lockedComment(blogId, commentId, ErrorCode.COMMENT_NOT_FOUND);
                            if (!actor.equals(comment.getAuthorId()))
                                throw new AppException(ErrorCode.FORBIDDEN);
                            long version = version(expectedVersion);
                            String body = delete ? null : commentContent(content);
                            if (comment.getDeletedAt() != null) {
                                if (!delete) throw new AppException(ErrorCode.COMMENT_DELETED);
                            } else {
                                if (comment.getVersion() != version)
                                    throw new AppException(ErrorCode.CONCURRENT_MODIFICATION);
                                if (delete) {
                                    em.createNativeQuery(
                                                    "DELETE FROM comment_likes WHERE comment_id = :id")
                                            .setParameter("id", commentId)
                                            .executeUpdate();
                                    comment.setDeletedAt(Instant.now());
                                }
                                comment.setContent(body);
                                em.flush();
                                em.refresh(comment);
                            }
                            return commentResult(
                                    blog,
                                    comment,
                                    actor,
                                    false,
                                    delete
                                            ? "Comment deleted successfully"
                                            : "Comment updated successfully");
                        });
        log.info(
                "event=comment_changed blog_id={} comment_id={} actor_id={} deleted={}",
                blogId,
                commentId,
                actor,
                delete);
        return result;
    }

    public LikeResponse setLike(UUID actor, UUID blogId, UUID commentId, boolean desired) {
        return write(
                () -> {
                    if (commentId == null) {
                        em.createNativeQuery(
                                        "CALL public.sp_set_blog_like(:actor, :blog, :desired)")
                                .setParameter("actor", actor)
                                .setParameter("blog", blogId)
                                .setParameter("desired", desired)
                                .executeUpdate();
                        Blog blog = em.find(Blog.class, blogId);
                        em.refresh(blog);
                        return new LikeResponse(
                                blogId,
                                null,
                                actor,
                                desired,
                                blog.getLikesCount(),
                                desired ? "Blog liked" : "Blog unliked");
                    }
                    em.createNativeQuery(
                                    "CALL public.sp_set_comment_like(:actor, :blog, :comment, :desired)")
                            .setParameter("actor", actor)
                            .setParameter("blog", blogId)
                            .setParameter("comment", commentId)
                            .setParameter("desired", desired)
                            .executeUpdate();
                    Comment comment = em.find(Comment.class, commentId);
                    em.refresh(comment);
                    return new LikeResponse(
                            blogId,
                            commentId,
                            actor,
                            desired,
                            comment.getLikesCount(),
                            desired ? "Comment liked" : "Comment unliked");
                });
    }

    public ShareResponse share(UUID actor, UUID blogId) {
        ShareResponse response =
                write(
                        () -> {
                            em.createNativeQuery("CALL public.sp_share_blog(:actor, :blog)")
                                    .setParameter("actor", actor)
                                    .setParameter("blog", blogId)
                                    .executeUpdate();
                            Blog blog = em.find(Blog.class, blogId);
                            em.refresh(blog);
                            Instant sharedAt =
                                    em.createQuery(
                                                    """
                select s.sharedAt from BlogShare s where s.id.userId = :actor and s.id.blogId = :blog
                """,
                                                    Instant.class)
                                            .setParameter("actor", actor)
                                            .setParameter("blog", blogId)
                                            .getSingleResult();
                            long total =
                                    em.createQuery(
                                                    """
                select count(s) from BlogShare s, Blog b
                where s.id.userId = :actor and s.id.blogId = b.id and b.published = true
                """,
                                                    Long.class)
                                            .setParameter("actor", actor)
                                            .getSingleResult();
                            return new ShareResponse(
                                    blogId,
                                    actor,
                                    blog.getSharesCount(),
                                    total,
                                    sharedAt,
                                    "Blog shared successfully");
                        });
        log.info("event=blog_shared blog_id={} actor_id={}", blogId, actor);
        return response;
    }

    public void unshare(UUID actor, UUID owner, UUID blogId) {
        if (!actor.equals(owner)) throw new AppException(ErrorCode.FORBIDDEN);
        write(
                () -> {
                    em.createNativeQuery("CALL public.sp_unshare_blog(:actor, :blog)")
                            .setParameter("actor", actor)
                            .setParameter("blog", blogId)
                            .executeUpdate();
                    return null;
                });
        log.info("event=blog_unshared blog_id={} actor_id={}", blogId, actor);
    }

    @Transactional(readOnly = true)
    public List<SharedBlogResponse> shares(UUID owner, UUID viewer) {
        if (!users.existsById(owner)) throw new AppException(ErrorCode.USER_NOT_FOUND);
        List<Object[]> rows =
                em.createQuery(
                                """
            select b, s.sharedAt from BlogShare s, Blog b
            where s.id.userId = :owner and s.id.blogId = b.id and b.published = true
            order by s.sharedAt desc, s.id.blogId desc
            """,
                                Object[].class)
                        .setParameter("owner", owner)
                        .getResultList();
        Map<UUID, BlogResponse> details =
                blogs.responses(rows.stream().map(row -> (Blog) row[0]).toList(), viewer).stream()
                        .collect(Collectors.toMap(BlogResponse::id, item -> item));
        List<SharedBlogResponse> result = new ArrayList<>();
        for (Object[] row : rows) {
            BlogResponse blog = details.get(((Blog) row[0]).getId());
            if (blog == null) continue;
            result.add(
                    new SharedBlogResponse(
                            blog.id(),
                            blog.userId(),
                            blog.username(),
                            blog.userAvatarUrl(),
                            blog.content(),
                            blog.imageContentUrls(),
                            blog.published(),
                            blog.createdAt(),
                            blog.likesCount(),
                            blog.commentsCount(),
                            blog.sharesCount(),
                            blog.sharedByMe(),
                            blog.likedUserIds(),
                            (Instant) row[1]));
        }
        return result;
    }

    private CommentResult commentResult(
            Blog blog, Comment comment, UUID viewer, boolean reply, String message) {
        // Refresh values maintained by PostgreSQL triggers before building the response.
        em.refresh(blog);
        CommentResponse response = commentTree(blog.getId(), viewer).nodes().get(comment.getId());
        return new CommentResult(
                blog.getId(),
                reply ? comment.getParentId() : null,
                blog.getCommentsCount(),
                reply ? null : response,
                reply ? response : null,
                message);
    }

    private Comments commentTree(UUID blogId, UUID viewer) {
        List<Comment> rows =
                em.createQuery(
                                """
            select c from Comment c where c.blogId = :blog order by c.createdAt desc, c.id desc
            """,
                                Comment.class)
                        .setParameter("blog", blogId)
                        .getResultList();
        if (rows.isEmpty()) return new Comments(List.of(), Map.of());
        List<UUID> ids = rows.stream().map(Comment::getId).toList();
        Map<UUID, User> authors =
                users
                        .findAllById(
                                rows.stream()
                                        .map(Comment::getAuthorId)
                                        .filter(Objects::nonNull)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(User::getId, user -> user));
        Set<UUID> liked =
                viewer == null
                        ? Set.of()
                        : Set.copyOf(
                                em.createQuery(
                                                """
            select l.id.commentId from CommentLike l where l.id.userId = :viewer and l.id.commentId in :ids
            """,
                                                UUID.class)
                                        .setParameter("viewer", viewer)
                                        .setParameter("ids", ids)
                                        .getResultList());
        Map<UUID, CommentResponse> nodes = new HashMap<>();
        for (Comment row : rows) {
            User author = authors.get(row.getAuthorId());
            boolean deleted = row.getDeletedAt() != null;
            String avatar =
                    author == null
                            ? null
                            : author.getAvatarMediaId() == null
                                    ? MediaConstants.DEFAULT_AVATAR_URL
                                    : "/api/v1/media/" + author.getAvatarMediaId();
            nodes.put(
                    row.getId(),
                    new CommentResponse(
                            row.getId(),
                            row.getAuthorId(),
                            author == null ? null : author.getUsername(),
                            avatar,
                            deleted ? null : row.getContent(),
                            deleted ? 0 : row.getLikesCount(),
                            !deleted && liked.contains(row.getId()) ? List.of(viewer) : List.of(),
                            new ArrayList<>(),
                            row.getCreatedAt(),
                            row.getUpdatedAt(),
                            deleted,
                            row.getVersion()));
        }
        List<CommentResponse> roots = new ArrayList<>();
        for (Comment row : rows) {
            CommentResponse node = nodes.get(row.getId());
            if (row.getParentId() == null) roots.add(node);
            else nodes.get(row.getParentId()).replies().add(node);
        }
        return new Comments(roots, nodes);
    }

    private Blog readableBlog(UUID id, UUID viewer, boolean lock) {
        Blog blog =
                lock
                        ? em.find(Blog.class, id, LockModeType.PESSIMISTIC_WRITE)
                        : em.find(Blog.class, id);
        if (blog == null || (!blog.isPublished() && !blog.getAuthorId().equals(viewer))) {
            throw new AppException(ErrorCode.BLOG_NOT_FOUND);
        }
        return blog;
    }

    private Comment lockedComment(UUID blogId, UUID id, ErrorCode missing) {
        List<Comment> matches =
                em.createQuery(
                                "select c from Comment c where c.id = :id and c.blogId = :blog",
                                Comment.class)
                        .setParameter("id", id)
                        .setParameter("blog", blogId)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                        .getResultList();
        if (matches.isEmpty()) throw new AppException(missing);
        return matches.getFirst();
    }

    private void lockActor(UUID actor) {
        if (em.createNativeQuery("SELECT id FROM users WHERE id = :actor FOR KEY SHARE")
                .setParameter("actor", actor)
                .getResultList()
                .isEmpty()) throw new AppException(ErrorCode.AUTH_REQUIRED);
    }

    private String commentContent(String content) {
        String value = content == null ? "" : content.strip();
        if (value.isEmpty()
                || value.codePointCount(0, value.length()) > MAX_COMMENT_LENGTH
                || value.indexOf('\0') >= 0) {
            throw new AppException(
                    ErrorCode.VALIDATION_FAILED,
                    Map.of("content", "Use 1 to 2000 characters without NUL"));
        }
        return value;
    }

    private long version(JsonNode value) {
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw new AppException(
                    ErrorCode.VALIDATION_FAILED,
                    Map.of("expectedVersion", "Use a nonnegative integer version"));
        }
        return value.longValue();
    }

    private <T> T write(Supplier<T> operation) {
        try {
            return transactions.execute(status -> operation.get());
        } catch (RuntimeException failure) {
            SQLException sql = WriteTransaction.sqlCause(failure);
            if (sql == null) throw failure;
            String marker =
                    String.valueOf(sql.getMessage())
                            .lines()
                            .findFirst()
                            .orElse("")
                            .replaceFirst("^ERROR:\\s*", "");
            ErrorCode error =
                    switch (marker) {
                        case "ACTOR_NOT_FOUND" -> ErrorCode.AUTH_REQUIRED;
                        case "BLOG_NOT_FOUND" -> ErrorCode.BLOG_NOT_FOUND;
                        case "COMMENT_NOT_FOUND" -> ErrorCode.COMMENT_NOT_FOUND;
                        case "COMMENT_DELETED" -> ErrorCode.COMMENT_DELETED;
                        case "ALREADY_SHARED" -> ErrorCode.ALREADY_SHARED;
                        default -> null;
                    };
            if (error != null) throw new AppException(error);
            throw failure;
        }
    }

    private record Comments(List<CommentResponse> roots, Map<UUID, CommentResponse> nodes) {}
}
