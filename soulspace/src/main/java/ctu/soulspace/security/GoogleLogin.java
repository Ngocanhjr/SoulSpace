package ctu.soulspace.security;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.service.GoogleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

public class GoogleLogin implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private static final String AUTHORIZATION = GoogleLogin.class.getName() + ".authorization";
    private static final String CALLBACK_FLOW = GoogleLogin.class.getName() + ".callback";
    private static final Logger log = LoggerFactory.getLogger(GoogleLogin.class);
    private final GoogleService google;
    private final DefaultOAuth2AuthorizationRequestResolver resolver;
    private final SessionAuthenticationStrategy sessions;
    private final SecurityContextRepository contexts;
    private final String frontend;

    public GoogleLogin(
            GoogleService google,
            ClientRegistrationRepository registrations,
            SessionAuthenticationStrategy sessions,
            SecurityContextRepository contexts,
            String frontend) {
        this.google = google;
        this.resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(
                builder ->
                        builder.additionalParameters(
                                parameters -> parameters.put("prompt", "select_account")));
        this.sessions = sessions;
        this.contexts = contexts;
        this.frontend = frontend;
    }

    public Map<String, Object> start(
            HttpServletRequest request,
            Authentication authentication,
            String intent,
            String action,
            String proof) {
        google.rate(request, "start");
        HttpSession session = request.getSession();
        var state = GoogleService.state(session);
        synchronized (state) {
            var flow = google.begin(session, authentication, intent, action, proof);
            OAuth2AuthorizationRequest authorization = resolver.resolve(request, "google");
            session.setAttribute(AUTHORIZATION, authorization);
            return Map.of(
                    "authorizationUrl",
                    authorization.getAuthorizationRequestUri(),
                    "expiresAt",
                    flow.expiresAt());
        }
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        var state = GoogleService.state(session);
        synchronized (state) {
            var authorization = (OAuth2AuthorizationRequest) session.getAttribute(AUTHORIZATION);
            return authorization != null
                            && state.flow != null
                            && !GoogleService.expired(state.flow.expiresAt())
                            && authorization.getState().equals(request.getParameter("state"))
                    ? authorization
                    : null;
        }
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorization,
            HttpServletRequest request,
            HttpServletResponse response) {
        // G01 is the only way to start a flow; GET /oauth2/authorization is disabled.
        throw new AppException(ErrorCode.OIDC_FLOW_INVALID);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        try {
            google.rate(request, "callback");
        } catch (AppException failure) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_request"));
        }
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        var state = GoogleService.state(session);
        synchronized (state) {
            var authorization = loadAuthorizationRequest(request);
            if (authorization != null) {
                request.setAttribute(CALLBACK_FLOW, state.flow);
                state.inFlight = state.flow;
                state.flow = null;
                session.removeAttribute(AUTHORIZATION);
            }
            return authorization;
        }
    }

    public void success(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        var flow = (GoogleService.Flow) request.getAttribute(CALLBACK_FLOW);
        try {
            if (flow == null
                    || "LOGIN".equals(flow.intent())
                            && GoogleService.actor(
                                            contexts.loadDeferredContext(request)
                                                    .get()
                                                    .getAuthentication())
                                    != null) throw new AppException(ErrorCode.OIDC_FLOW_INVALID);
            if (!(authentication.getPrincipal() instanceof OidcUser user))
                throw new AppException(ErrorCode.OIDC_FLOW_INVALID);
            UUID actor =
                    google.verified(
                            request.getSession(), flow, user.getSubject(), user.getFullName());
            if ("LOGIN".equals(flow.intent())) {
                if (actor == null) {
                    SecurityContextHolder.clearContext();
                    redirect(response, "complete");
                    return;
                }
                establish(actor, request, response);
            } else {
                // The OIDC context is never persisted; LINK/REAUTH retain the original local
                // session.
                SecurityContextHolder.setContext(contexts.loadDeferredContext(request).get());
            }
            log.info("event=google_flow_completed intent={} user_id={}", flow.intent(), actor);
            redirect(response, "result");
        } catch (RuntimeException failure) {
            log.info(
                    "event=google_callback_failed reason={}",
                    failure instanceof AppException app
                            ? app.getErrorCode().name()
                            : failure.getClass().getSimpleName());
            failure(request, response);
        }
    }

    public void rejected(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException failure)
            throws IOException {
        String code =
                failure instanceof OAuth2AuthenticationException oauth
                        ? oauth.getError().getErrorCode()
                        : "authentication_failed";
        log.info(
                "event=google_provider_rejected reason={}",
                code.matches("[a-z_]{1,64}") ? code : "authentication_failed");
        failure(request, response);
    }

    public void failure(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        SecurityContextHolder.setContext(contexts.loadDeferredContext(request).get());
        log.info("event=google_flow_rejected");
        redirect(response, "error");
    }

    public void establish(UUID actor, HttpServletRequest request, HttpServletResponse response) {
        Authentication local =
                UsernamePasswordAuthenticationToken.authenticated(
                        actor.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        sessions.onAuthentication(local, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(local);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    private void redirect(HttpServletResponse response, String result) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(frontend + "/#/auth/google/" + result);
    }

    public static class TransientClients implements OAuth2AuthorizedClientRepository {
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String id, Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(
                OAuth2AuthorizedClient client,
                Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {}

        @Override
        public void removeAuthorizedClient(
                String id,
                Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {}
    }
}
