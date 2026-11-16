package ctu.soulspace.service;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.dto.response.ProfileResponse;
import ctu.soulspace.dto.response.UserResponse;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@Service
public class ProfileService {
    private static final int MAX_CONFIRMATIONS = 10;
    private static final int MAX_TRACKED_USERS = 4_096;
    private static final long CONFIRMATION_WINDOW_MS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_PASSWORD_CHARACTERS = 128;
    private static final int MAX_PASSWORD_BYTES = 512;
    private static final Set<String> PROFILE_FIELDS =
            Set.of("username", "fullname", "dob", "id", "userAvatarUrl");
    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    @PersistenceContext private EntityManager em;
    private final UserRepository users;
    private final UserService userService;
    private final MediaService media;
    private final PasswordEncoder encoder;
    private final SessionRegistry sessions;
    private final TransactionTemplate transactions;
    private final ObjectProvider<GoogleService> google;
    private final Map<UUID, ConfirmationWindow> confirmations = new HashMap<>();

    public ProfileService(
            UserRepository users,
            UserService userService,
            MediaService media,
            PasswordEncoder encoder,
            SessionRegistry sessions,
            PlatformTransactionManager manager,
            ObjectProvider<GoogleService> google) {
        this.users = users;
        this.userService = userService;
        this.media = media;
        this.encoder = encoder;
        this.sessions = sessions;
        this.google = google;
        transactions = new WriteTransaction(manager);
    }

    public ProfileResponse readOwn(UUID actor, UUID id) {
        requireOwner(actor, id);
        return response(snapshot(id));
    }

    public UpdateResult update(UUID actor, UUID id, JsonNode input, boolean put) {
        requireOwner(actor, id);
        var fields = new java.util.HashSet<>(PROFILE_FIELDS);
        if (put) fields.addAll(Set.of("password", "currentPassword"));
        requireFields(input, fields);
        User before = snapshot(id);
        String verifiedHash = before.getPasswordHash();
        String password = input.hasNonNull("password") ? text(input, "password") : null;
        boolean passwordChange = password != null && !password.isEmpty();
        String hash = null;
        if (passwordChange) {
            if (verifiedHash == null) throw new AppException(ErrorCode.PASSWORD_NOT_ENABLED);
            confirm(actor, input.get("currentPassword"), verifiedHash);
            userService.validatePassword(password);
            hash = encoder.encode(password);
        }
        String newHash = hash;
        UserResponse result;
        try {
            result =
                    transactions.execute(
                            status -> {
                                User user = lockUser(id);
                                if (passwordChange
                                        && !Objects.equals(user.getPasswordHash(), verifiedHash)) {
                                    throw new AppException(ErrorCode.CONCURRENT_MODIFICATION);
                                }
                                validateReadFields(input, user);
                                boolean requested = passwordChange;
                                if (input.has("username")
                                        && (!put || !input.get("username").isNull())) {
                                    user.setUsername(
                                            userService.normalizeUsername(text(input, "username")));
                                    requested = true;
                                }
                                if (input.has("fullname")
                                        && (!put || !input.get("fullname").isNull())) {
                                    user.setFullname(
                                            userService.normalizeFullname(text(input, "fullname")));
                                    requested = true;
                                }
                                if (input.has("dob") && (!put || !input.get("dob").isNull())) {
                                    user.setDob(
                                            input.get("dob").isNull()
                                                    ? null
                                                    : date(text(input, "dob")));
                                    requested = true;
                                }
                                if (!requested)
                                    throw invalid(
                                            "profile",
                                            "Provide at least one profile or password field");
                                if (passwordChange) user.setPasswordHash(newHash);
                                users.saveAndFlush(user);
                                return new UserResponse(
                                        user.getId(),
                                        user.getUsername(),
                                        user.getFullname(),
                                        user.getDob(),
                                        userService.avatarUrl(user));
                            });
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql
                        && "23505".equals(sql.getSQLState())
                        && String.valueOf(sql.getMessage()).contains("users_username_key"))
                    throw new AppException(ErrorCode.USERNAME_EXISTS);
            }
            throw failure;
        }
        if (passwordChange) expireSessions(actor);
        log.info("event=profile_updated user_id={} password_changed={}", actor, passwordChange);
        return new UpdateResult(result, passwordChange);
    }

    public ProfileResponse avatar(UUID actor, UUID id, MultipartFile file) {
        requireOwner(actor, id);
        snapshot(id);
        List<String> failures = new ArrayList<>();
        List<MediaService.UploadedImage> uploaded =
                media.upload(actor, List.of(file), failures, "AVATAR");
        if (uploaded.isEmpty()) throw new AppException(ErrorCode.MEDIA_PROVIDER_ERROR);
        try {
            ProfileResponse result =
                    transactions.execute(
                            status -> {
                                User user = lockUser(id);
                                UUID previous = user.getAvatarMediaId();
                                List<UUID> assets =
                                        new ArrayList<>(List.of(uploaded.getFirst().id()));
                                if (previous != null) assets.add(previous);
                                media.lockAssets(actor, assets);
                                user.setAvatarMediaId(
                                        media.attachAvatar(actor, uploaded.getFirst()));
                                em.flush();
                                if (previous != null) media.enqueue(previous);
                                em.flush();
                                return response(user);
                            });
            log.info("event=avatar_updated user_id={}", actor);
            return result;
        } catch (RuntimeException failure) {
            media.discard(uploaded);
            throw failure;
        }
    }

    public void removeAvatar(UUID actor, UUID id) {
        requireOwner(actor, id);
        transactions.executeWithoutResult(
                status -> {
                    User user = lockUser(id);
                    UUID asset = user.getAvatarMediaId();
                    if (asset == null) return;
                    media.lockAssets(actor, List.of(asset));
                    user.setAvatarMediaId(null);
                    em.flush();
                    media.enqueue(asset);
                    em.flush();
                });
        log.info("event=avatar_removed user_id={}", actor);
    }

    public void delete(UUID actor, UUID id, JsonNode input, HttpSession session) {
        requireOwner(actor, id);
        requireFields(input, Set.of("currentPassword", "reauthProof"));
        if (input.hasNonNull("currentPassword") && input.hasNonNull("reauthProof"))
            throw invalid("currentPassword", "Choose one reauthentication method");
        User before = snapshot(id);
        String verifiedHash = before.getPasswordHash();
        if (verifiedHash != null) {
            if (input.hasNonNull("reauthProof")) throw new AppException(ErrorCode.REAUTH_REQUIRED);
            confirm(actor, input.get("currentPassword"), verifiedHash);
        } else if (input.hasNonNull("currentPassword"))
            throw new AppException(ErrorCode.REAUTH_REQUIRED);
        transactions.executeWithoutResult(
                status -> {
                    User user = lockUser(id);
                    if (!Objects.equals(user.getPasswordHash(), verifiedHash))
                        throw new AppException(ErrorCode.CONCURRENT_MODIFICATION);
                    if (verifiedHash == null) {
                        GoogleService service = google.getIfAvailable();
                        if (service == null) throw new AppException(ErrorCode.REAUTH_REQUIRED);
                        service.consumeProof(
                                session,
                                actor,
                                "DELETE_ACCOUNT",
                                input.hasNonNull("reauthProof") ? text(input, "reauthProof") : null,
                                null);
                    }
                    // Freeze every affected blog before locking comments or touching
                    // trigger-maintained counters.
                    em.createNativeQuery(
                                    """
                SELECT b.id FROM blogs b WHERE b.author_id = :actor
                    OR b.id IN (SELECT blog_id FROM comments WHERE author_id = :actor)
                    OR b.id IN (SELECT blog_id FROM blog_likes WHERE user_id = :actor)
                    OR b.id IN (SELECT blog_id FROM blog_shares WHERE user_id = :actor)
                    OR b.id IN (SELECT c.blog_id FROM comment_likes l JOIN comments c ON c.id = l.comment_id WHERE l.user_id = :actor)
                ORDER BY b.id FOR UPDATE
                """)
                            .setParameter("actor", actor)
                            .getResultList();
                    em.createNativeQuery(
                                    """
                SELECT id FROM comments WHERE author_id = :actor
                    OR blog_id IN (SELECT id FROM blogs WHERE author_id = :actor)
                    OR id IN (SELECT comment_id FROM comment_likes WHERE user_id = :actor)
                ORDER BY id FOR UPDATE
                """)
                            .setParameter("actor", actor)
                            .getResultList();
                    List<UUID> assets =
                            em.createQuery(
                                            "select m.id from MediaAsset m where m.ownerId = :actor order by m.id",
                                            UUID.class)
                                    .setParameter("actor", actor)
                                    .getResultList();
                    media.lockAssets(actor, assets);
                    em.createNativeQuery(
                                    """
                DELETE FROM comment_likes WHERE comment_id IN (
                    SELECT c.id FROM comments c JOIN blogs b ON b.id = c.blog_id
                    WHERE c.author_id = :actor AND b.author_id <> :actor
                )
                """)
                            .setParameter("actor", actor)
                            .executeUpdate();
                    em.createNativeQuery(
                                    """
                UPDATE comments SET content = NULL, deleted_at = COALESCE(deleted_at, clock_timestamp()), author_id = NULL
                WHERE author_id = :actor AND blog_id NOT IN (SELECT id FROM blogs WHERE author_id = :actor)
                """)
                            .setParameter("actor", actor)
                            .executeUpdate();
                    user.setAvatarMediaId(null);
                    em.flush();
                    em.createNativeQuery("DELETE FROM blogs WHERE author_id = :actor")
                            .setParameter("actor", actor)
                            .executeUpdate();
                    for (UUID asset : assets) media.enqueue(asset);
                    em.flush();
                    em.createNativeQuery("DELETE FROM users WHERE id = :actor")
                            .setParameter("actor", actor)
                            .executeUpdate();
                });
        expireSessions(actor);
        log.info("event=account_deleted user_id={}", actor);
    }

    private User snapshot(UUID id) {
        return users.findById(id).orElseThrow(() -> new AppException(ErrorCode.AUTH_REQUIRED));
    }

    private User lockUser(UUID id) {
        if (em.createNativeQuery("SELECT id FROM users WHERE id = :id FOR UPDATE")
                .setParameter("id", id)
                .getResultList()
                .isEmpty()) {
            throw new AppException(ErrorCode.AUTH_REQUIRED);
        }
        User user = em.find(User.class, id);
        em.refresh(user);
        return user;
    }

    private void requireOwner(UUID actor, UUID id) {
        if (actor == null) throw new AppException(ErrorCode.AUTH_REQUIRED);
        if (!actor.equals(id)) throw new AppException(ErrorCode.FORBIDDEN);
    }

    private void requireFields(JsonNode input, Set<String> allowed) {
        if (input == null || !input.isObject() || !allowed.containsAll(input.propertyNames()))
            throw new AppException(ErrorCode.VALIDATION_FAILED);
    }

    private void validateReadFields(JsonNode input, User user) {
        if (input.has("id") && !user.getId().toString().equals(text(input, "id")))
            throw invalid("id", "User id cannot be changed");
        if (input.has("userAvatarUrl")
                && !userService.avatarUrl(user).equals(text(input, "userAvatarUrl"))) {
            throw invalid("userAvatarUrl", "Use the avatar upload endpoint");
        }
    }

    private String text(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual()) throw invalid(field, "Use a text value");
        return value.asText();
    }

    private LocalDate date(String value) {
        try {
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
            LocalDate result = LocalDate.parse(value);
            if (result.getYear() < 1) throw new IllegalArgumentException();
            return userService.resolveDob(result, null);
        } catch (java.time.DateTimeException | IllegalArgumentException failure) {
            throw invalid("dob", "Use a valid past date in YYYY-MM-DD format");
        }
    }

    private void confirm(UUID actor, JsonNode input, String hash) {
        if (input == null || !input.isTextual())
            throw invalid("currentPassword", "Current password is required");
        confirmationAttempt(actor);
        String value = input.asText();
        if (value.codePointCount(0, value.length()) > MAX_PASSWORD_CHARACTERS
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES
                || !encoder.matches(value, hash)) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }
    }

    private synchronized void confirmationAttempt(UUID actor) {
        long now = System.currentTimeMillis();
        confirmations
                .entrySet()
                .removeIf(entry -> now - entry.getValue().start >= CONFIRMATION_WINDOW_MS);
        ConfirmationWindow window = confirmations.get(actor);
        if (window == null) {
            if (confirmations.size() >= MAX_TRACKED_USERS)
                throw new AppException(ErrorCode.RATE_LIMITED);
            window = new ConfirmationWindow(now);
            confirmations.put(actor, window);
        }
        if (window.count >= MAX_CONFIRMATIONS) throw new AppException(ErrorCode.RATE_LIMITED);
        window.count++;
    }

    private void expireSessions(UUID actor) {
        for (Object principal : sessions.getAllPrincipals()) {
            String name =
                    principal instanceof UserDetails details
                            ? details.getUsername()
                            : String.valueOf(principal);
            if (actor.toString().equals(name))
                sessions.getAllSessions(principal, false).forEach(session -> session.expireNow());
        }
    }

    private ProfileResponse response(User user) {
        return new ProfileResponse(
                user.getUsername(), user.getFullname(), user.getDob(), userService.avatarUrl(user));
    }

    private AppException invalid(String field, String message) {
        return new AppException(ErrorCode.VALIDATION_FAILED, Map.of(field, message));
    }

    private static class ConfirmationWindow {
        final long start;
        int count;

        ConfirmationWindow(long start) {
            this.start = start;
        }
    }

    public record UpdateResult(UserResponse user, boolean passwordChanged) {}
}
