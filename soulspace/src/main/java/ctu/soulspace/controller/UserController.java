package ctu.soulspace.controller;

import ctu.soulspace.dto.request.SignUpRequest;
import ctu.soulspace.dto.response.UserResponse;
import ctu.soulspace.service.UserService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
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
