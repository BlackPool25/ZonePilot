package com.zonepilot.backend.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Slice 1 — auth + depot RBAC (TDD RED).
 * Covers: access-token expiry, depot claim round-trip, wrong-key rejection.
 */
class JwtServiceTest {

    private static final String SECRET =
            "zonepilot-test-secret-zonepilot-test-secret-32b+";

    @Test
    void generateAccessToken_carriesDepotAndRole_andRespectsExpiry() {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));

        String token = jwt.generateAccessToken("dispatcher@hsr.test", 7L, "DISPATCHER");

        assertThat(jwt.getUsername(token)).isEqualTo("dispatcher@hsr.test");
        assertThat(jwt.getDepotId(token)).isEqualTo(7L);
        assertThat(jwt.getRole(token)).isEqualTo("DISPATCHER");
        assertThat(jwt.isExpired(token)).isFalse();
    }

    @Test
    void parse_withWrongSecret_throws() {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
        JwtService other = new JwtService(
                "completely-different-secret-xyz-1234567890!!", Duration.ofMinutes(15), Duration.ofDays(7));

        String token = jwt.generateAccessToken("owner@test.io", 1L, "OWNER");

        assertThatThrownBy(() -> other.getUsername(token)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void generateRefreshToken_livesLongerThanAccessToken() {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));

        String access = jwt.generateAccessToken("d@t.io", 1L, "DRIVER");
        String refresh = jwt.generateRefreshToken("d@t.io", 1L);

        assertThat(jwt.expiryOf(refresh)).isAfter(jwt.expiryOf(access));
        assertThat(jwt.isRefresh(refresh)).isTrue();
        assertThat(jwt.isRefresh(access)).isFalse();
    }
}
