package com.okututor.backend.security;

import com.okututor.backend.common.error.ApiError;
import com.okututor.backend.common.error.ErrorCodes;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;

final class AuthErrorWriter {

    private AuthErrorWriter() {
    }

    static void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
                        ApiError.of(status.value(), code, message)));
    }
}
