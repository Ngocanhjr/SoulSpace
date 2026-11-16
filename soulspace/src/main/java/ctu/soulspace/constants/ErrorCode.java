package ctu.soulspace.constants;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username or password"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "Invalid CSRF token"),
    USERNAME_EXISTS(HttpStatus.CONFLICT, "Username already exists"),
    INVALID_MEDIA_REFERENCE(HttpStatus.BAD_REQUEST, "Image does not belong to this resource"),
    PASSWORD_NOT_ENABLED(
            HttpStatus.CONFLICT, "Set a password after verifying your Google identity"),
    REAUTH_REQUIRED(HttpStatus.FORBIDDEN, "Reauthentication required"),
    OIDC_FLOW_INVALID(
            HttpStatus.BAD_REQUEST, "Google sign-in expired or was not valid. Please try again."),
    ONBOARDING_REQUIRED(HttpStatus.UNAUTHORIZED, "Complete Google sign-up first"),
    ONBOARDING_ALREADY_COMPLETED(HttpStatus.CONFLICT, "Google sign-up has already completed"),
    IDENTITY_CONFLICT(HttpStatus.CONFLICT, "This Google account cannot be linked"),
    PASSWORD_ALREADY_ENABLED(HttpStatus.CONFLICT, "A password has already been set"),
    IDENTITY_PROVIDER_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Google sign-in is unavailable. Please try again later."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    BLOG_NOT_FOUND(HttpStatus.NOT_FOUND, "Blog not found"),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Comment not found"),
    COMMENT_DELETED(HttpStatus.CONFLICT, "Comment has been deleted"),
    ALREADY_SHARED(HttpStatus.CONFLICT, "You have already shared this blog"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Data changed. Reload and try again."),
    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "Image not found"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Image or request is too large"),
    UNSUPPORTED_MEDIA_TYPE(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only static JPEG and PNG images are accepted"),
    MEDIA_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "Image provider is unavailable. Try again later."),
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
