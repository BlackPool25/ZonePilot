package com.zonepilot.backend.auth;

import com.zonepilot.backend.exception.RateLimitExceededException;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Slice 1 auth: register / login / refresh rotation / me.
 * First-ever user may bootstrap as OWNER; afterwards only OWNER can create users.
 */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final LoginThrottleService throttle;

    public AuthService(AppUserRepository users,
                       PasswordEncoder passwords, JwtService jwt) {
        this(users, passwords, jwt, new LoginThrottleService(Optional.empty()));
    }

    @Autowired
    public AuthService(AppUserRepository users,
                       PasswordEncoder passwords, JwtService jwt,
                       LoginThrottleService throttle) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.throttle = throttle;
    }

    @Transactional
    public TokenPair register(String email, String rawPassword, Role role) {
        return registerInternal(email, rawPassword, role, false);
    }

    /**
     * Driver-login channel: the ONLY way to create a DRIVER login is the
     * driver roster endpoint (POST /api/drivers → DriverService). Direct
     * /api/auth/register calls with DRIVER are rejected.
     */
    @Transactional
    public TokenPair registerDriver(String email, String rawPassword) {
        return registerInternal(email, rawPassword, Role.DRIVER, true);
    }

    private TokenPair registerInternal(String email, String rawPassword, Role role,
                                       boolean viaDriverEndpoint) {
        if (role == null) {
            throw new ValidationException("Role is required");
        }
        if (users.count() == 0 && role != Role.OWNER) {
            // Bootstrap: the first-ever user must be the OWNER; a first
            // DISPATCHER/DRIVER would leave the fleet ownerless.
            throw new ValidationException("First user must bootstrap as OWNER");
        }
        if (role == Role.DRIVER && !viaDriverEndpoint) {
            throw new ValidationException("driver logins only via POST /api/drivers");
        }
        DepotPrincipal caller = DepotScope.current();
        if (users.count() > 0) {
            // OWNER manages everyone; DISPATCHER manages DRIVER logins only.
            boolean ownerCall = caller != null && caller.isOwner();
            boolean dispatcherDriverCall = caller != null && !caller.isOwner()
                    && role == Role.DRIVER;
            if (!ownerCall && !dispatcherDriverCall) {
                throw new AccessDeniedException("Only OWNER can create users");
            }
        }
        String normalized = email == null ? null : email.toLowerCase();
        if (normalized == null || normalized.isBlank()) {
            throw new ValidationException("Email is required");
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new ValidationException("Password must be at least 8 characters");
        }
        if (users.existsByEmail(normalized)) {
            throw new ValidationException("Email already registered");
        }
        AppUser user = new AppUser();
        user.setEmail(normalized);
        user.setPasswordHash(passwords.encode(rawPassword));
        user.setRole(role);
        users.save(user);
        return issueTokens(user);
    }

    @Transactional
    public TokenPair login(String email, String rawPassword) {
        return login(email, rawPassword, null);
    }

    @Transactional
    public TokenPair login(String email, String rawPassword, String clientIp) {
        String normalized = email == null ? null : email.toLowerCase();
        // Shared bucket (Redis, memory fallback): throws 429 when full.
        throttle.requireAllowed(normalized, clientIp);
        AppUser user = users.findByEmail(normalized)
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .orElse(null);
        if (user == null || !passwords.matches(rawPassword, user.getPasswordHash())) {
            throttle.recordFailure(normalized, clientIp);
            throw new BadCredentialsException("Invalid credentials");
        }
        throttle.clearFailures(normalized);
        return issueTokens(user);
    }

    @Transactional
    public TokenPair refresh(String refreshToken) {
        if (jwt.isExpired(refreshToken) || !jwt.isRefresh(refreshToken)) {
            throw new AccessDeniedException("Invalid refresh token");
        }
        AppUser user = users.findByEmail(jwt.getUsername(refreshToken))
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .orElseThrow(() -> new AccessDeniedException("Invalid refresh token"));
        String presentedHash = sha256(refreshToken);
        if (!presentedHash.equals(user.getRefreshTokenHash())
                || user.getRefreshExpiresAt() == null
                || user.getRefreshExpiresAt().isBefore(Instant.now())) {
            throw new AccessDeniedException("Invalid refresh token");
        }
        return issueTokens(user);
    }

    private TokenPair issueTokens(AppUser user) {
        String access = jwt.generateAccessToken(user.getEmail(), null, user.getRole().name());
        String refresh = jwt.generateRefreshToken(user.getEmail(), null);
        user.setRefreshTokenHash(sha256(refresh));
        user.setRefreshExpiresAt(jwt.expiryOf(refresh));
        users.save(user);
        return new TokenPair(access, refresh);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record TokenPair(String accessToken, String refreshToken) {}
}
