package ctu.soulspace.exception;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.dto.response.AuthResponse;
import ctu.soulspace.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final int RATE_LIMIT_RETRY_SECONDS = 60;

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<?> application(AppException exception, HttpServletRequest request) {
        return response(exception.getErrorCode(), request, exception.getFieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception
                .getBindingResult()
                .getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), "Invalid or missing value"));
        return response(ErrorCode.VALIDATION_FAILED, request, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> unreadable(HttpServletRequest request) {
        return response(ErrorCode.VALIDATION_FAILED, request, Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> invalidId(HttpServletRequest request) {
        return response(ErrorCode.VALIDATION_FAILED, request, Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> oversized(HttpServletRequest request) {
        return response(ErrorCode.PAYLOAD_TOO_LARGE, request, Map.of());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> invalidMultipart(HttpServletRequest request) {
        return response(ErrorCode.VALIDATION_FAILED, request, Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<?> authentication(HttpServletRequest request) {
        log.info("event=authentication_rejected");
        return response(ErrorCode.INVALID_CREDENTIALS, request, Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> forbidden(HttpServletRequest request) {
        return response(ErrorCode.FORBIDDEN, request, Map.of());
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<?> unavailable(HttpServletRequest request) {
        return response(ErrorCode.SERVICE_UNAVAILABLE, request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception exception, HttpServletRequest request) {
        // Exception messages can contain rejected passwords or SQL parameter values.
        log.error("Unhandled API failure: {}", exception.getClass().getName());
        return response(ErrorCode.INTERNAL_ERROR, request, Map.of());
    }

    private ResponseEntity<?> response(
            ErrorCode code, HttpServletRequest request, Map<String, String> fields) {
        ErrorResponse error = ErrorResponse.of(code, request.getRequestURI(), fields);
        Object body = code == ErrorCode.INVALID_CREDENTIALS ? AuthResponse.failure(error) : error;
        var response = ResponseEntity.status(code.getStatus()).header("Cache-Control", "no-store");
        if (code == ErrorCode.RATE_LIMITED) {
            response.header("Retry-After", Integer.toString(RATE_LIMIT_RETRY_SECONDS));
        }
        return response.body(body);
    }
}
