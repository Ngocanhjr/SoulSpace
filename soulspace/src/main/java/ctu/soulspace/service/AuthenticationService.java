package ctu.soulspace.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.dto.request.LoginRequest;

@Service
public class AuthenticationService {

    private final AuthenticationManager authenticationManager;

    public AuthenticationService(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    public Authentication authenticate(LoginRequest request) {
        String username = request.getUsername();
        String password = request.getPassword();
        if (username == null || password == null) {
            throw invalidCredentials();
        }
        String canonical = username.trim().toLowerCase(Locale.ROOT);
        if (!canonical.matches("^[a-z0-9]{3,32}$")
            || password.codePointCount(0, password.length()) > 128
            || password.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw invalidCredentials();
        }
        return authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(canonical, password));
    }

    private BadCredentialsException invalidCredentials() {
        return new BadCredentialsException(ErrorCode.INVALID_CREDENTIALS.getMessage());
    }
}
