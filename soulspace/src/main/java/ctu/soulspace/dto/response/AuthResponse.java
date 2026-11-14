package ctu.soulspace.dto.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

public record AuthResponse(boolean authentication,
                           @JsonInclude(JsonInclude.Include.ALWAYS) LoginUserResponse user,
                           String message,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Integer status,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String code,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String path,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Instant timestamp) {

    public AuthResponse(boolean authentication, LoginUserResponse user, String message) {
        this(authentication, user, message, null, null, null, null);
    }

    public static AuthResponse failure(ErrorResponse error) {
        return new AuthResponse(false, null, error.message(), error.status(),
            error.code(), error.path(), error.timestamp());
    }
}
