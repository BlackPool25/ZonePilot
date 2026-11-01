package com.zonepilot.backend.auth;

import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slice 1 auth: register / login / refresh rotation / me.
 * First-ever user may bootstrap as OWNER; afterwards only OWNER can create users.
 */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;

    // Basic per-email login bucket: 10 failures / 5 min, then 429-style deny.
    // In-memory only (single instance); success clears the bucket.
    private final ConcurrentHashMap<String, Deque<Instant>> loginFailures = new ConcurrentHashMap<>();
    static final int MAX_FAILURES = 10;
    private static final long WINDOW_SEC = 300;

    public AuthService(AppUserRepository users,
                       PasswordEncoder passwords, JwtService jwt) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
    }

    @Transactional
    public TokenPair register(String email, String rawPassword, Role role) {
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
        String normalized = email == null ? null : email.toLowerCase();
        checkLoginBucket(normalized);
        AppUser user = users.findByEmail(normalized)
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .orElse(null);
        if (user == null || !passwords.matches(rawPassword, user.getPasswordHash())) {
            recordLoginFailure(normalized);
            throw new BadCredentialsException("Invalid credentials");
        }
        if (normalized != null) {
            loginFailures.remove(normalized);
        }
        return issueTokens(user);
    }

    private void checkLoginBucket(String email) {
        if (email == null) {
            return;
        }
        Deque<Instant> attempts = loginFailures.get(email);
        if (attempts == null) {
            return;
        }
        synchronized (attempts) {
            Instant cutoff = Instant.now().minusSeconds(WINDOW_SEC);
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
                attempts.pollFirst();
            }
            if (attempts.size() >= MAX_FAILURES) {
                throw new AccessDeniedException("Too many login attempts, try again later");
            }
        }
    }

    private void recordLoginFailure(String email) {
        if (email == null) {
            return;
        }
        Deque<Instant> attempts = loginFailures.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (attempts) {
            attempts.addLast(Instant.now());
        }
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
