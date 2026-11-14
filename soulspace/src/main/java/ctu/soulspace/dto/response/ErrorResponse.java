package ctu.soulspace.dto.response;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import ctu.soulspace.constants.ErrorCode;

public record ErrorResponse(int status, String code, String message, String path,
                            Instant timestamp,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY)
                            Map<String, String> fieldErrors) {

    public static ErrorResponse of(ErrorCode error, String path, Map<String, String> fields) {
        return new ErrorResponse(error.getStatus().value(), error.name(),
            error.getMessage(), path, Instant.now(), fields);
    }
}
