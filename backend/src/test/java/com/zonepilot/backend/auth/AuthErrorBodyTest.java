package com.zonepilot.backend.auth;

import com.zonepilot.backend.dto.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Auth 401/403 bodies must be complete standard-envelope JSON. */
class AuthErrorBodyTest {

    @Test
    void write_rendersFullEnvelope() throws Exception {
        // Boot's auto-configured mapper (JavaTimeModule); a `new ObjectMapper()`
        // cannot serialize ApiResponse.timestamp (Instant) and truncates the body.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuthErrorBody(mapper).write(response, 401, "UNAUTHORIZED", "Authentication required");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString())
                .contains("\"success\":false")
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("Authentication required")
                .endsWith("}");
    }
}
