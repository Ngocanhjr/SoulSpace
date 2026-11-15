package ctu.soulspace.security;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.repositories.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionGuardFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_AT = "SOULSPACE_AUTHENTICATED_AT";

    private static final long MAX_SESSION_AGE = Duration.ofHours(8).toMillis();
    private static final long RATE_WINDOW = Duration.ofMinutes(5).toMillis();
    private static final long WRITE_WINDOW_MS = Duration.ofMinutes(1).toMillis();
    private static final int MAX_WRITES_PER_WINDOW = 60;
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_RATE_ENTRIES = 4096;

    private final SessionRegistry sessionRegistry;
    private final UserRepository userRepository;
    private final ApiErrorWriter errors;
    private final Map<String, AttemptWindow> attempts = new HashMap<>();
    private final Map<String, AttemptWindow> writes = new HashMap<>();

    public SessionGuardFilter(
            SessionRegistry sessionRegistry, UserRepository userRepository, ApiErrorWriter errors) {
        this.sessionRegistry = sessionRegistry;
        this.userRepository = userRepository;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated =
                authentication != null
                        && authentication.isAuthenticated()
                        && !(authentication instanceof AnonymousAuthenticationToken);

        boolean valid = true;
        if (authenticated) {
            try {
                valid = sessionIsValid(request, authentication);
            } catch (DataAccessException exception) {
                errors.write(request, response, ErrorCode.SERVICE_UNAVAILABLE);
                return;
            }
        }
        if (!valid) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                sessionRegistry.removeSessionInformation(session.getId());
                session.invalidate();
            }
            SecurityContextHolder.clearContext();

            boolean csrfRequest =
                    "GET".equals(request.getMethod())
                            && "/api/v1/auth/csrf".equals(request.getServletPath());
            boolean logoutRequest =
                    "POST".equals(request.getMethod())
                            && "/api/v1/auth/log-out".equals(request.getServletPath());
            if (!csrfRequest && !logoutRequest) {
                errors.write(request, response, ErrorCode.AUTH_REQUIRED);
                return;
            }
        }

        if (isAuthenticationAttempt(request)
                && !allowAttempt(attempts, request.getRemoteAddr(), RATE_WINDOW, MAX_ATTEMPTS)) {
            response.setHeader(
                    "Retry-After", Long.toString(Duration.ofMillis(RATE_WINDOW).toSeconds()));
            errors.write(request, response, ErrorCode.RATE_LIMITED);
            return;
        }
        if (authenticated
                && valid
                && isBusinessWrite(request)
                && !allowAttempt(
                        writes, authentication.getName(), WRITE_WINDOW_MS, MAX_WRITES_PER_WINDOW)) {
            response.setHeader(
                    "Retry-After", Long.toString(Duration.ofMillis(WRITE_WINDOW_MS).toSeconds()));
            errors.write(request, response, ErrorCode.RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean sessionIsValid(HttpServletRequest request, Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object value = session.getAttribute(AUTHENTICATED_AT);
        if (!(value instanceof Long authenticatedAt)
                || System.currentTimeMillis() - authenticatedAt >= MAX_SESSION_AGE) {
            return false;
        }
        SessionInformation info = sessionRegistry.getSessionInformation(session.getId());
        if (info == null || info.isExpired()) {
            return false;
        }
        UUID userId;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return userRepository.existsById(userId);
    }

    private boolean isAuthenticationAttempt(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String path = request.getServletPath();
        return "/api/v1/auth/log-in".equals(path) || "/api/v1/users/sign-up".equals(path);
    }

    private boolean isBusinessWrite(HttpServletRequest request) {
        if (!java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod()))
            return false;
        String path = request.getServletPath();
        return path.equals("/api/v1/blogs")
                || path.startsWith("/api/v1/blogs/")
                || path.equals("/api/v1/blogs-details")
                || (path.startsWith("/api/v1/users/") && path.contains("/shared-blogs/"));
    }

    private synchronized boolean allowAttempt(
            Map<String, AttemptWindow> windows, String key, long windowMs, int limit) {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= windowMs);
        AttemptWindow window = windows.get(key);
        if (window == null) {
            if (windows.size() >= MAX_RATE_ENTRIES) {
                return false;
            }
            window = new AttemptWindow(now);
            windows.put(key, window);
        }
        if (window.count >= limit) {
            return false;
        }
        window.count++;
        return true;
    }

    private static final class AttemptWindow {
        private final long startedAt;
        private int count;

        private AttemptWindow(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
