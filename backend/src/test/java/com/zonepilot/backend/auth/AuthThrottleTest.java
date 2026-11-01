package com.zonepilot.backend.auth;

import com.zonepilot.backend.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService throttle wiring: blocked logins never touch the DB, the client
 * IP feeds the per-IP bucket, success clears the email bucket, and refresh
 * rotation revokes the presented token (replay rejected).
 */
@ExtendWith(MockitoExtension.class)
class AuthThrottleTest {

    @Mock AppUserRepository users;
    @Mock PasswordEncoder passwords;
    @Mock JwtService jwt;

    private AuthService serviceWith(LoginThrottleService throttle) {
        return new AuthService(users, passwords, jwt, throttle);
    }

    @Test
    void login_blockedByThrottle_neverHitsDb() {
        LoginThrottleService throttle = mock(LoginThrottleService.class);
        doThrow(new RateLimitExceededException("Too many login attempts, try again later"))
                .when(throttle).requireAllowed("flood@zp.test", "8.8.8.8");

        assertThrows(RateLimitExceededException.class,
                () -> serviceWith(throttle).login("flood@zp.test", "whatever", "8.8.8.8"));
        verify(users, never()).findByEmail(anyString());
    }

    @Test
    void login_failure_recordsBothBuckets_successClearsEmail() {
        LoginThrottleService throttle = mock(LoginThrottleService.class);
        when(users.findByEmail("g@zp.test")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class,
                () -> serviceWith(throttle).login("g@zp.test", "wrong", "9.9.9.9"));
        verify(throttle).recordFailure("g@zp.test", "9.9.9.9");

        AppUser user = new AppUser();
        user.setEmail("g@zp.test");
        user.setPasswordHash("h");
        user.setRole(Role.DISPATCHER);
        user.setIsActive(true);
        when(users.findByEmail("g@zp.test")).thenReturn(Optional.of(user));
        when(passwords.matches("right", "h")).thenReturn(true);
        when(jwt.generateAccessToken(eq("g@zp.test"), any(), eq("DISPATCHER"))).thenReturn("a");
        when(jwt.generateRefreshToken(eq("g@zp.test"), any())).thenReturn("r");
        when(jwt.expiryOf("r")).thenReturn(Instant.now().plusSeconds(60));

        serviceWith(throttle).login("g@zp.test", "right", "9.9.9.9");
        verify(throttle).clearFailures("g@zp.test");
    }

    @Test
    void refresh_rotation_revokesPresentedToken() {
        AppUser user = new AppUser();
        user.setEmail("h@zp.test");
        user.setRole(Role.DISPATCHER);
        user.setIsActive(true);
        user.setRefreshTokenHash(sha256("tok1"));
        user.setRefreshExpiresAt(Instant.now().plusSeconds(3600));
        when(users.findByEmail("h@zp.test")).thenReturn(Optional.of(user));
        when(jwt.isExpired("tok1")).thenReturn(false);
        when(jwt.isRefresh("tok1")).thenReturn(true);
        when(jwt.getUsername("tok1")).thenReturn("h@zp.test");
        when(jwt.generateAccessToken(eq("h@zp.test"), any(), eq("DISPATCHER"))).thenReturn("a2");
        when(jwt.generateRefreshToken(eq("h@zp.test"), any())).thenReturn("tok2");
        when(jwt.expiryOf("tok2")).thenReturn(Instant.now().plusSeconds(3600));
        AuthService auth = serviceWith(new LoginThrottleService(Optional.empty()));

        auth.refresh("tok1");
        assertEquals(sha256("tok2"), user.getRefreshTokenHash());

        // Replay of the rotated-out refresh token is rejected.
        assertThrows(AccessDeniedException.class, () -> auth.refresh("tok1"));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
