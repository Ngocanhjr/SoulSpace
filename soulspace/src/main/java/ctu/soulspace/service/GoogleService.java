package ctu.soulspace.service;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.entities.ExternalIdentity;
import ctu.soulspace.entities.ExternalIdentityId;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.ExternalIdentityRepository;
import ctu.soulspace.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class GoogleService {
    private static final String STATE_ATTRIBUTE = GoogleService.class.getName();
    private static final String PROVIDER = "GOOGLE";
    private static final Duration FLOW_TTL = Duration.ofMinutes(10);
    private static final Duration PROOF_TTL = Duration.ofMinutes(5);
    private static final long RATE_WINDOW_MS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_RATE_ENTRIES = 4096;
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_PASSWORD_CHARACTERS = 128;
    private static final int MAX_PASSWORD_BYTES = 512;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, RateWindow> rates = new HashMap<>();
    private final UserRepository users;
    private final ExternalIdentityRepository identities;
    private final UserService userService;
    private final PasswordEncoder encoder;
    private final SessionRegistry sessions;
    private final WriteTransaction transactions;
    @PersistenceContext private EntityManager em;

    public GoogleService(
            UserRepository users,
            ExternalIdentityRepository identities,
            UserService userService,
            PasswordEncoder encoder,
            SessionRegistry sessions,
            PlatformTransactionManager manager) {
        this.users = users;
        this.identities = identities;
        this.userService = userService;
        this.encoder = encoder;
        this.sessions = sessions;
        transactions = new WriteTransaction(manager);
    }

    public static State state(HttpSession session) {
        synchronized (session) {
            State state = (State) session.getAttribute(STATE_ATTRIBUTE);
            if (state == null) {
                state = new State();
                session.setAttribute(STATE_ATTRIBUTE, state);
            }
            return state;
        }
    }

    public void rate(HttpServletRequest request, String group) {
        State state = state(request.getSession());
        synchronized (rates) {
            long now = System.currentTimeMillis();
            rates.entrySet().removeIf(entry -> now - entry.getValue().started >= RATE_WINDOW_MS);
            String key = request.getRemoteAddr() + ":" + group;
            RateWindow ip = rates.get(key);
            if (ip == null) {
                if (rates.size() >= MAX_RATE_ENTRIES)
                    throw new AppException(ErrorCode.RATE_LIMITED);
                ip = new RateWindow(now);
                rates.put(key, ip);
            }
            if (++ip.count > MAX_ATTEMPTS) throw new AppException(ErrorCode.RATE_LIMITED);
        }
        synchronized (state) {
            long now = System.currentTimeMillis();
            RateWindow window = state.rates.get(group);
            if (window == null || now - window.started >= RATE_WINDOW_MS) {
                window = new RateWindow(now);
                state.rates.put(group, window);
            }
            if (++window.count > MAX_ATTEMPTS) throw new AppException(ErrorCode.RATE_LIMITED);
        }
    }

    public Flow begin(
            HttpSession session,
            Authentication authentication,
            String intent,
            String action,
            String proof) {
        State state = state(session);
        synchronized (state) {
            UUID actor = actor(authentication);
            String hash = null;
            if ("LOGIN".equals(intent)) {
                if (actor != null) throw new AppException(ErrorCode.ALREADY_AUTHENTICATED);
                if (action != null || proof != null)
                    throw new AppException(ErrorCode.VALIDATION_FAILED);
            } else if ("LINK".equals(intent)) {
                if (action != null) throw new AppException(ErrorCode.VALIDATION_FAILED);
                User user = validActor(session, actor);
                hash = user.getPasswordHash();
                if (hash == null) throw new AppException(ErrorCode.FORBIDDEN);
                consumeProof(session, actor, "LINK_GOOGLE", proof, hash);
            } else if ("REAUTH".equals(intent)) {
                if (proof != null || !sensitiveAction(action))
                    throw new AppException(ErrorCode.VALIDATION_FAILED);
                User user = validActor(session, actor);
                if (user.getPasswordHash() != null || identities.findByUserId(actor).isEmpty()) {
                    throw new AppException(ErrorCode.FORBIDDEN);
                }
            } else throw new AppException(ErrorCode.VALIDATION_FAILED);
            state.pending = null;
            state.stage = null;
            state.proof = null;
            state.inFlight = null;
            state.completed = false;
            Flow flow =
                    new Flow(
                            intent,
                            action,
                            actor,
                            hash,
                            session.getId(),
                            Instant.now().plus(FLOW_TTL));
            state.flow = flow;
            return flow;
        }
    }

    // Called only with claims verified by Spring's OIDC authentication provider.
    public UUID verified(HttpSession session, Flow flow, String subject, String fullname) {
        State state = state(session);
        synchronized (state) {
            if (flow == null
                    || state.inFlight != flow
                    || !session.getId().equals(flow.sessionId())
                    || expired(flow.expiresAt())
                    || subject == null
                    || subject.isBlank()
                    || subject.length() > 255) throw new AppException(ErrorCode.OIDC_FLOW_INVALID);
            state.inFlight = null;
            if ("LOGIN".equals(flow.intent())) {
                var identity = identities.findById(new ExternalIdentityId(PROVIDER, subject));
                if (identity.isPresent()) {
                    UUID id = identity.get().getUserId();
                    if (!users.existsById(id)) throw new AppException(ErrorCode.AUTH_REQUIRED);
                    return id;
                }
                state.pending = new Pending(subject, fullname, Instant.now().plus(FLOW_TTL));
                return null;
            }
            User before = validActor(session, flow.actor());
            if (!Objects.equals(before.getPasswordHash(), flow.hash())) {
                throw new AppException(ErrorCode.REAUTH_REQUIRED);
            }
            if ("LINK".equals(flow.intent())) {
                try {
                    transactions.executeWithoutResult(
                            status -> {
                                User user = lockUser(flow.actor());
                                if (!Objects.equals(user.getPasswordHash(), flow.hash()))
                                    throw new AppException(ErrorCode.REAUTH_REQUIRED);
                                var key = new ExternalIdentityId(PROVIDER, subject);
                                var existing = identities.findById(key);
                                if (existing.isPresent()) {
                                    if (!existing.get().getUserId().equals(flow.actor()))
                                        throw new AppException(ErrorCode.IDENTITY_CONFLICT);
                                    return;
                                }
                                if (identities.findByUserId(flow.actor()).isPresent())
                                    throw new AppException(ErrorCode.IDENTITY_CONFLICT);
                                identities.saveAndFlush(new ExternalIdentity(key, flow.actor()));
                            });
                } catch (RuntimeException failure) {
                    if (uniqueFailure(failure)) throw new AppException(ErrorCode.IDENTITY_CONFLICT);
                    throw failure;
                }
            } else {
                var identity =
                        identities
                                .findByUserId(flow.actor())
                                .orElseThrow(() -> new AppException(ErrorCode.REAUTH_REQUIRED));
                if (!subject.equals(identity.getId().getSubject()))
                    throw new AppException(ErrorCode.REAUTH_REQUIRED);
                state.stage =
                        new Stage(
                                flow.actor(),
                                flow.action(),
                                subject,
                                Instant.now().plus(PROOF_TTL));
            }
            return flow.actor();
        }
    }

    public Map<String, Object> onboarding(HttpSession session) {
        State state = state(session);
        synchronized (state) {
            Pending pending = pending(state);
            Map<String, Object> response = new HashMap<>();
            response.put("required", true);
            response.put("suggestedFullname", pending.fullname());
            return response;
        }
    }

    public UUID complete(HttpSession session, String username, String fullname, LocalDate dob) {
        State state = state(session);
        synchronized (state) {
            Pending pending = pending(state);
            String canonical = userService.normalizeUsername(username);
            String name = userService.normalizeFullname(fullname);
            LocalDate birthday = userService.resolveDob(dob, null);
            UUID id;
            try {
                id =
                        transactions.execute(
                                status -> {
                                    if (identities.existsById(
                                            new ExternalIdentityId(PROVIDER, pending.subject())))
                                        throw new AppException(ErrorCode.IDENTITY_CONFLICT);
                                    User user = new User();
                                    user.setUsername(canonical);
                                    user.setFullname(name);
                                    user.setDob(birthday);
                                    users.saveAndFlush(user);
                                    identities.saveAndFlush(
                                            new ExternalIdentity(
                                                    new ExternalIdentityId(
                                                            PROVIDER, pending.subject()),
                                                    user.getId()));
                                    return user.getId();
                                });
            } catch (RuntimeException failure) {
                if (uniqueFailure(failure)) {
                    var sql = WriteTransaction.sqlCause(failure);
                    throw new AppException(
                            sql != null
                                            && String.valueOf(sql.getMessage())
                                                    .contains("users_username_key")
                                    ? ErrorCode.USERNAME_EXISTS
                                    : ErrorCode.IDENTITY_CONFLICT);
                }
                throw failure;
            }
            state.pending = null;
            state.completed = true;
            return id;
        }
    }

    public Map<String, Boolean> methods(UUID actor) {
        User user =
                users.findById(actor).orElseThrow(() -> new AppException(ErrorCode.AUTH_REQUIRED));
        return Map.of(
                "passwordEnabled",
                user.getPasswordHash() != null,
                "googleLinked",
                identities.findByUserId(actor).isPresent());
    }

    public ProofResponse reauthenticate(
            HttpSession session, UUID actor, String intent, String password) {
        State state = state(session);
        synchronized (state) {
            User user = validActor(session, actor);
            String credential;
            if ("LINK_GOOGLE".equals(intent)) {
                String hash = user.getPasswordHash();
                if (hash == null
                        || password == null
                        || password.codePointCount(0, password.length()) > MAX_PASSWORD_CHARACTERS
                        || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES
                        || !encoder.matches(password, hash))
                    throw new AppException(ErrorCode.INVALID_CREDENTIALS);
                credential = hash;
            } else {
                if (!sensitiveAction(intent) || password != null || user.getPasswordHash() != null)
                    throw new AppException(ErrorCode.REAUTH_REQUIRED);
                Stage stage = state.stage;
                if (stage == null
                        || expired(stage.expiresAt())
                        || !actor.equals(stage.actor())
                        || !intent.equals(stage.action()))
                    throw new AppException(ErrorCode.REAUTH_REQUIRED);
                credential =
                        identities
                                .findByUserId(actor)
                                .map(identity -> identity.getId().getSubject())
                                .orElse(null);
                if (!Objects.equals(stage.subject(), credential))
                    throw new AppException(ErrorCode.REAUTH_REQUIRED);
                state.stage = null;
            }
            String token = token();
            Instant expiresAt = Instant.now().plus(PROOF_TTL);
            state.proof = new Proof(token, actor, intent, credential, session.getId(), expiresAt);
            return new ProofResponse(token, expiresAt);
        }
    }

    public void consumeProof(
            HttpSession session, UUID actor, String intent, String token, String hash) {
        if (session == null) throw new AppException(ErrorCode.REAUTH_REQUIRED);
        State state = state(session);
        synchronized (state) {
            validActor(session, actor);
            Proof proof = state.proof;
            String credential =
                    hash != null
                            ? hash
                            : identities
                                    .findByUserId(actor)
                                    .map(identity -> identity.getId().getSubject())
                                    .orElse(null);
            if (proof == null
                    || token == null
                    || expired(proof.expiresAt())
                    || !actor.equals(proof.actor())
                    || !session.getId().equals(proof.sessionId())
                    || !intent.equals(proof.intent())
                    || !Objects.equals(credential, proof.credential())
                    || !MessageDigest.isEqual(
                            token.getBytes(StandardCharsets.UTF_8),
                            proof.token().getBytes(StandardCharsets.UTF_8)))
                throw new AppException(ErrorCode.REAUTH_REQUIRED);
            state.proof = null;
        }
    }

    public void setPassword(HttpSession session, UUID actor, String password, String proof) {
        userService.validatePassword(password);
        String hash = encoder.encode(password);
        transactions.executeWithoutResult(
                status -> {
                    User user = lockUser(actor);
                    if (user.getPasswordHash() != null)
                        throw new AppException(ErrorCode.PASSWORD_ALREADY_ENABLED);
                    consumeProof(session, actor, "SET_PASSWORD", proof, null);
                    user.setPasswordHash(hash);
                    users.saveAndFlush(user);
                });
        expireSessions(actor);
    }

    public void expireSessions(UUID actor) {
        for (Object principal : sessions.getAllPrincipals()) {
            String name =
                    principal instanceof UserDetails details
                            ? details.getUsername()
                            : String.valueOf(principal);
            if (actor.toString().equals(name))
                sessions.getAllSessions(principal, false).forEach(info -> info.expireNow());
        }
    }

    private User validActor(HttpSession session, UUID actor) {
        var info = sessions.getSessionInformation(session.getId());
        if (actor == null || info == null || info.isExpired())
            throw new AppException(ErrorCode.AUTH_REQUIRED);
        String name =
                info.getPrincipal() instanceof UserDetails details
                        ? details.getUsername()
                        : String.valueOf(info.getPrincipal());
        if (!actor.toString().equals(name)) throw new AppException(ErrorCode.AUTH_REQUIRED);
        return users.findById(actor).orElseThrow(() -> new AppException(ErrorCode.AUTH_REQUIRED));
    }

    private User lockUser(UUID actor) {
        if (em.createNativeQuery("SELECT id FROM users WHERE id = :id FOR UPDATE")
                .setParameter("id", actor)
                .getResultList()
                .isEmpty()) throw new AppException(ErrorCode.AUTH_REQUIRED);
        User user = em.find(User.class, actor);
        em.refresh(user);
        return user;
    }

    private Pending pending(State state) {
        if (state.completed) throw new AppException(ErrorCode.ONBOARDING_ALREADY_COMPLETED);
        if (state.pending == null || expired(state.pending.expiresAt())) {
            state.pending = null;
            throw new AppException(ErrorCode.ONBOARDING_REQUIRED);
        }
        return state.pending;
    }

    public static UUID actor(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return null;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException failure) {
            throw new AppException(ErrorCode.AUTH_REQUIRED);
        }
    }

    public static boolean expired(Instant expiresAt) {
        return !Instant.now().isBefore(expiresAt);
    }

    private boolean sensitiveAction(String action) {
        return "SET_PASSWORD".equals(action) || "DELETE_ACCOUNT".equals(action);
    }

    private boolean uniqueFailure(Throwable failure) {
        var sql = WriteTransaction.sqlCause(failure);
        return sql != null && "23505".equals(sql.getSQLState());
    }

    private String token() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static final class State {
        public Flow flow;
        public Flow inFlight;
        private Pending pending;
        private Stage stage;
        private Proof proof;
        private boolean completed;
        private final Map<String, RateWindow> rates = new HashMap<>();
    }

    public record Flow(
            String intent,
            String action,
            UUID actor,
            String hash,
            String sessionId,
            Instant expiresAt) {}

    private record Pending(String subject, String fullname, Instant expiresAt) {}

    private record Stage(UUID actor, String action, String subject, Instant expiresAt) {}

    private record Proof(
            String token,
            UUID actor,
            String intent,
            String credential,
            String sessionId,
            Instant expiresAt) {}

    public record ProofResponse(String reauthProof, Instant expiresAt) {}

    private static final class RateWindow {
        final long started;
        int count;

        RateWindow(long started) {
            this.started = started;
        }
    }
}
