package ctu.soulspace.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import ctu.soulspace.constants.ErrorCode;

@Component
public class AuthenticationEntryPoint implements org.springframework.security.web.AuthenticationEntryPoint {

    private final ApiErrorWriter errors;

    public AuthenticationEntryPoint(ApiErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        errors.write(request, response, ErrorCode.AUTH_REQUIRED);
    }
}
