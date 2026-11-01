package com.zonepilot.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

import com.zonepilot.backend.dto.response.ApiResponse;

/**
 * Writes auth failures (401/403) in the standard {@link ApiResponse} envelope.
 * Uses Boot's configured {@link ObjectMapper} — a plain {@code new ObjectMapper()}
 * has no JavaTimeModule, cannot serialize the envelope's {@code Instant} timestamp,
 * and the container then commits a truncated body.
 */
@Component
final class AuthErrorBody {

    private final ObjectMapper mapper;

    AuthErrorBody(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
