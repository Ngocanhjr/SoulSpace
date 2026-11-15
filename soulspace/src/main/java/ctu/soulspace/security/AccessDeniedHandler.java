package ctu.soulspace.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import ctu.soulspace.constants.ErrorCode;

@Component
public class AccessDeniedHandler implements org.springframework.security.web.access.AccessDeniedHandler {

    private final ApiErrorWriter errors;

    public AccessDeniedHandler(ApiErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        errors.write(request, response, exception instanceof CsrfException
            ? ErrorCode.CSRF_INVALID : ErrorCode.FORBIDDEN);
    }
}
