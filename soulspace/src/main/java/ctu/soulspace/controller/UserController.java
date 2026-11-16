package ctu.soulspace.controller;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.dto.request.SignUpRequest;
import ctu.soulspace.dto.response.ProfileResponse;
import ctu.soulspace.dto.response.UserResponse;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.service.ProfileService;
import ctu.soulspace.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final ProfileService profiles;
    private final String cookieName;

    public UserController(
            UserService userService,
            ProfileService profiles,
            @Value("${server.servlet.session.cookie.name:SOULSPACESESSION}") String cookieName) {
        this.userService = userService;
        this.profiles = profiles;
        this.cookieName = cookieName;
    }

    @GetMapping("/patch/{id}")
    public ResponseEntity<ProfileResponse> own(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(profiles.readOwn(actor(authentication), id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProfileResponse> update(
            @PathVariable UUID id, @RequestBody JsonNode input, Authentication authentication) {
        var user = profiles.update(actor(authentication), id, input, false).user();
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        new ProfileResponse(
                                user.username(),
                                user.fullname(),
                                user.dob(),
                                user.userAvatarUrl()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> replace(
            @PathVariable UUID id,
            @RequestBody JsonNode input,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        var result = profiles.update(actor(authentication), id, input, true);
        if (result.passwordChanged()) endSession(request, response);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(result.user());
    }

    @PatchMapping(value = "/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProfileResponse> avatar(
            @PathVariable UUID id,
            MultipartHttpServletRequest request,
            Authentication authentication) {
        if (!request.getParameterMap().isEmpty()
                || !request.getMultiFileMap().keySet().equals(java.util.Set.of("file"))
                || request.getFiles("file").size() != 1)
            throw new AppException(ErrorCode.VALIDATION_FAILED);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(profiles.avatar(actor(authentication), id, request.getFile("file")));
    }

    @DeleteMapping("/{id}/avatar")
    public ResponseEntity<String> removeAvatar(
            @PathVariable UUID id, Authentication authentication) {
        profiles.removeAvatar(actor(authentication), id);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body("Avatar has been deleted.");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(
            @PathVariable UUID id,
            @RequestBody JsonNode input,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        profiles.delete(actor(authentication), id, input, request.getSession(false));
        endSession(request, response);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body("User has been deleted.");
    }

    private UUID actor(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken)
            throw new AppException(ErrorCode.AUTH_REQUIRED);
        return UUID.fromString(authentication.getName());
    }

    private void endSession(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        response.addHeader(
                "Set-Cookie",
                ResponseCookie.from(cookieName, "")
                        .path("/")
                        .httpOnly(true)
                        .secure(request.isSecure())
                        .sameSite("Lax")
                        .maxAge(0)
                        .build()
                        .toString());
    }

    @PostMapping("/sign-up")
    public UserResponse signUp(@Valid @RequestBody SignUpRequest request) {
        return userService.signUp(request);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> profile(
            @PathVariable UUID id, Authentication authentication) {
        UUID viewer =
                authentication == null || authentication instanceof AnonymousAuthenticationToken
                        ? null
                        : UUID.fromString(authentication.getName());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(userService.profile(id, viewer));
    }
}
