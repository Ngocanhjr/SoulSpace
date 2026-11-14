package ctu.soulspace.security;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.dto.response.ErrorResponse;

@Component
public class ApiErrorWriter {

    private final JsonMapper jsonMapper;

    public ApiErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      ErrorCode error) throws IOException {
        response.setStatus(error.getStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        jsonMapper.writeValue(response.getOutputStream(),
            ErrorResponse.of(error, request.getRequestURI(), Map.of()));
    }
}
