package ctu.soulspace.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ctu.soulspace.dto.request.SignUpRequest;
import ctu.soulspace.dto.response.UserResponse;
import ctu.soulspace.service.UserService;

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
}
