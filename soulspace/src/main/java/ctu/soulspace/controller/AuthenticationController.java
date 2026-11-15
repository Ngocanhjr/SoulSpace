package ctu.soulspace.controller;

import ctu.soulspace.constants.AuthMessages;
import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.dto.request.LoginRequest;
import ctu.soulspace.dto.response.AuthResponse;
import ctu.soulspace.dto.response.LoginUserResponse;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.service.AuthenticationService;
import ctu.soulspace.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationController.class);
    private final AuthenticationService authenticationService;
    private final UserService userService;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;

    public AuthenticationController(
            AuthenticationService authenticationService,
            UserService userService,
            SessionAuthenticationStrategy sessionStrategy,
            SecurityContextRepository contextRepository) {
        this.authenticationService = authenticationService;
        this.userService = userService;
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        new CsrfResponse(
                                token.getToken(), token.getHeaderName(), token.getParameterName()));
    }

    @PostMapping("/log-in")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null
                && current.isAuthenticated()
                && !(current instanceof AnonymousAuthenticationToken)) {
            throw new AppException(ErrorCode.ALREADY_AUTHENTICATED);
        }
        Authentication authentication = authenticationService.authenticate(body);
        LoginUserResponse user =
                userService.findLoginUser(UUID.fromString(authentication.getName()));

        sessionStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        log.info("event=login_success user_id={}", user.id());

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new AuthResponse(true, user, AuthMessages.LOGIN_SUCCESS));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(Authentication authentication) {
        LoginUserResponse user =
                userService.findLoginUser(UUID.fromString(authentication.getName()));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new AuthResponse(true, user, AuthMessages.AUTHENTICATED));
    }

    public record CsrfResponse(String token, String headerName, String parameterName) {}
}
