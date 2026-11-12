package ctu.edu.blogAPI.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
// đầu tiên khai báo class này, để cho spring biết khi có exception thì class
// này sẽ chịu trách nhiệm handling
// tương ứng từng loại exception thì sẽ khai báo từng loại method
//@RestControllerAdvice
public class GobalExceptionHandler {
    @ExceptionHandler(value = RuntimeException.class)
    ResponseEntity<String> handlingRuntimeException(RuntimeException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());// nội dung mà muốn trả lại cho người dùng
    }

//    @ExceptionHandler(value = MethodArgumentNotValidException.class)
//    ResponseEntity<String> handlingValidation(MethodArgumentNotValidException exception) {
//        return ResponseEntity.badRequest().body(exception.getFieldError().getDefaultMessage());
//    }

    //Xử lý lỗi validation @Valid
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, Object> response = new HashMap<>();
        String firstError = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();

        response.put("status", 400);
        response.put("message", firstError);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    //Xử lý lỗi type mismatch (ví dụ: file bị gửi sai kiểu, boolean = null,…)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", 400);
        response.put("message", "Giá trị '" + ex.getValue() + "' không hợp lệ cho trường '" + ex.getName() + "'");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    //Thiếu parameter
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", 400);
        response.put("message", "Thiếu tham số bắt buộc: " + ex.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    //Bắt mọi 500 exception khác (nếu có)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", 500);
        response.put("message", "Lỗi hệ thống: " + ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    // Bắt tất cả các lỗi 500 khác
//    @ExceptionHandler(Exception.class)
//    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, WebRequest request) {
//        ErrorResponse error = new ErrorResponse(
//                HttpStatus.INTERNAL_SERVER_ERROR.value(),
//                "An unexpected error occurred: " + ex.getMessage(),
//                Instant.now()
//        );
//        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
//    }

    //error 404
    @ExceptionHandler(BlogNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBlogNotFound(BlogNotFoundException e, WebRequest req) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                e.getMessage(),
                req.getDescription(false),
                Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    //error 400
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException e, WebRequest req) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Invalid input format: " + e.getMessage(),
                req.getDescription(false),
                Instant.now()
        );

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    private record ErrorResponse(int statusCode, String message, String path, Instant timestamp) {
    }
}
