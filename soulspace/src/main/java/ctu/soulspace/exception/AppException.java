package ctu.soulspace.exception;

import java.util.Map;

import ctu.soulspace.constants.ErrorCode;

public class AppException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, String> fieldErrors;

    public AppException(ErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public AppException(ErrorCode errorCode, Map<String, String> fieldErrors) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
