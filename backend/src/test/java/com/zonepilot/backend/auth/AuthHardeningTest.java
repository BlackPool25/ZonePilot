package com.zonepilot.backend.auth;

import com.zonepilot.backend.exception.ValidationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RED: audit feedback — bootstrap, role parsing, driver channel, token checks, DEMO.
 */
@ExtendWith(MockitoExtension.class)
class AuthHardeningTest {

    static final String SECRET = "test-secret-32-bytes-long-xxxxxxxx";

    @Mock AppUserRepository users;
    @Mock LoginThrottleService throttle;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private AuthService service(long count) {
        lenient().when(users.count()).thenReturn(count);
        return new AuthService(users, new BCryptPasswordEncoder(),
                new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7)), throttle);
    }

    @Test
    void firstUser_mustBeOwner() {
        AuthService s = service(0);
        assertThrows(ValidationException.class,
                () -> s.register("a@zp.test", "password-1", Role.DISPATCHER));
    }

    @Test
    void register_nullRole_is400() {
        AuthService s = service(1);
        assertThrows(ValidationException.class,
                () -> s.register("a@zp.test", "password-1", null));
    }

    @Test
    void register_driverViaPublicPath_rejected() {
        AuthService s = service(1);
        assertThrows(ValidationException.class,
                () -> s.register("d@zp.test", "password-1", Role.DRIVER));
    }

    @Test
    void registerDriver_internalPath_ok() {
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new DepotPrincipal("owner@zp.test", null, Role.OWNER), null, java.util.List.of()));
        AuthService s = service(1);
        when(users.existsByEmail("d@zp.test")).thenReturn(false);
        AuthService.TokenPair pair = s.registerDriver("d@zp.test", "password-1");
        assertNotNull(pair.accessToken());
        verify(users, times(2)).save(argThat(u -> u.getRole() == Role.DRIVER));
    }

    @Test
    void role_from_unknown_is400() {
        assertThrows(ValidationException.class, () -> Role.from("BOSS"));
        assertThrows(ValidationException.class, () -> Role.from(null));
        assertEquals(Role.OWNER, Role.from("OWNER"));
    }

    @Test
    void demo_accessTtl_isShort() {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
        Instant demoExp = jwt.expiryOf(jwt.generateAccessToken("d@zp.test", null, "DEMO"));
        Instant ownerExp = jwt.expiryOf(jwt.generateAccessToken("o@zp.test", null, "OWNER"));
        assertTrue(demoExp.isBefore(Instant.now().plus(Duration.ofMinutes(6))),
                "DEMO token must be short-lived, was " + demoExp);
        assertTrue(ownerExp.isAfter(Instant.now().plus(Duration.ofMinutes(10))));
    }

    @Test
    void filter_inactiveUser_noAuth() throws Exception {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
        String token = jwt.generateAccessToken("off@zp.test", null, "OWNER");
        AppUser u = new AppUser();
        u.setEmail("off@zp.test");
        u.setRole(Role.OWNER);
        u.setIsActive(false);
        when(users.findByEmail("off@zp.test")).thenReturn(Optional.of(u));
        JwtAuthFilter filter = new JwtAuthFilter(jwt, users);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + token);
        lenient().when(req.getMethod()).thenReturn("GET");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(req, res);
    }

    @Test
    void filter_roleMismatch_noAuth() throws Exception {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
        // Token says OWNER but the account is now a DISPATCHER (demoted).
        String token = jwt.generateAccessToken("dem@zp.test", null, "OWNER");
        AppUser u = new AppUser();
        u.setEmail("dem@zp.test");
        u.setRole(Role.DISPATCHER);
        u.setIsActive(true);
        when(users.findByEmail("dem@zp.test")).thenReturn(Optional.of(u));
        JwtAuthFilter filter = new JwtAuthFilter(jwt, users);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + token);
        lenient().when(req.getMethod()).thenReturn("GET");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void filter_demoWrite_is403() throws Exception {
        JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
        String token = jwt.generateAccessToken("demo@zp.test", null, "DEMO");
        AppUser u = new AppUser();
        u.setEmail("demo@zp.test");
        u.setRole(Role.DEMO);
        u.setIsActive(true);
        when(users.findByEmail("demo@zp.test")).thenReturn(Optional.of(u));
        JwtAuthFilter filter = new JwtAuthFilter(jwt, users);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(req.getMethod()).thenReturn("POST");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);

        verify(res).sendError(eq(403), anyString());
        verify(chain, never()).doFilter(req, res);
    }
}
