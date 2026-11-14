package ctu.soulspace.constants;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username or password"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "Invalid CSRF token"),
    USERNAME_EXISTS(HttpStatus.CONFLICT, "Username already exists"),
    ALREADY_AUTHENTICATED(HttpStatus.CONFLICT, "Log out before logging in again"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
