package ctu.soulspace.controller;

import ctu.soulspace.constants.AuthMessages;
import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.dto.response.AuthResponse;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.security.GoogleLogin;
import ctu.soulspace.service.GoogleService;
import ctu.soulspace.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class GoogleController {
    private final GoogleService google;
    private final ObjectProvider<GoogleLogin> login;
    private final UserService users;

    public GoogleController(
            GoogleService google, ObjectProvider<GoogleLogin> login, UserService users) {
        this.google = google;
        this.login = login;
        this.users = users;
    }

    @PostMapping("/google/start")
    public ResponseEntity<?> start(
            @Valid @RequestBody Start input,
            Authentication authentication,
            HttpServletRequest request) {
        return ok(
                login().start(
                                request,
                                authentication,
                                input.intent(),
                                input.action(),
                                input.reauthProof()));
    }

    @GetMapping("/google/onboarding")
    public ResponseEntity<?> onboarding(HttpServletRequest request) {
        return ok(google.onboarding(request.getSession()));
    }

    @PostMapping("/google/onboarding")
    public ResponseEntity<AuthResponse> complete(
            @Valid @RequestBody Onboarding input,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (GoogleService.actor(authentication) != null)
            throw new AppException(ErrorCode.ALREADY_AUTHENTICATED);
        GoogleLogin handler = login();
        google.rate(request, "onboarding");
        UUID actor =
                google.complete(
                        request.getSession(), input.username(), input.fullname(), input.dob());
        handler.establish(actor, request, response);
        return ok(new AuthResponse(true, users.findLoginUser(actor), AuthMessages.LOGIN_SUCCESS));
    }

    @GetMapping("/methods")
    public ResponseEntity<?> methods(Authentication authentication) {
        return ok(google.methods(actor(authentication)));
    }

    @PostMapping("/reauthenticate")
    public ResponseEntity<?> reauthenticate(
            @Valid @RequestBody Reauthentication input,
            Authentication authentication,
            HttpServletRequest request) {
        google.rate(request, "proof");
        return ok(
                google.reauthenticate(
                        request.getSession(),
                        actor(authentication),
                        input.intent(),
                        input.currentPassword()));
    }

    @PostMapping("/password")
    public ResponseEntity<?> password(
            @Valid @RequestBody Password input,
            Authentication authentication,
            HttpServletRequest request) {
        google.rate(request, "proof");
        google.setPassword(
                request.getSession(), actor(authentication), input.password(), input.reauthProof());
        request.getSession().invalidate();
        SecurityContextHolder.clearContext();
        return ok(Map.of("message", "Password has been set. Please log in again."));
    }

    private GoogleLogin login() {
        GoogleLogin handler = login.getIfAvailable();
        if (handler == null) throw new AppException(ErrorCode.IDENTITY_PROVIDER_UNAVAILABLE);
        return handler;
    }

    private UUID actor(Authentication authentication) {
        UUID actor = GoogleService.actor(authentication);
        if (actor == null) throw new AppException(ErrorCode.AUTH_REQUIRED);
        return actor;
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    public record Start(@NotBlank String intent, String action, String reauthProof) {}

    public record Onboarding(@NotBlank String username, @NotBlank String fullname, LocalDate dob) {}

    public record Reauthentication(@NotBlank String intent, String currentPassword) {}

    public record Password(@NotNull String password, @NotBlank String reauthProof) {}
}
