package com.zonepilot.backend.auth;

import com.zonepilot.backend.entity.Depot;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.DepotRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Slice 1 auth: register / login / refresh rotation / me.
 * First-ever user may bootstrap as OWNER; afterwards only OWNER can create users.
 */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final DepotRepository depots;
    private final PasswordEncoder passwords;
    private final JwtService jwt;

    public AuthService(AppUserRepository users, DepotRepository depots,
                       PasswordEncoder passwords, JwtService jwt) {
        this.users = users;
        this.depots = depots;
        this.passwords = passwords;
        this.jwt = jwt;
    }

    @Transactional
    public TokenPair register(String email, String rawPassword, Role role, Long depotId) {
        DepotPrincipal caller = DepotScope.current();
        if (users.count() > 0 && (caller == null || !caller.isOwner())) {
            throw new AccessDeniedException("Only OWNER can create users");
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
        Depot depot = null;
        if (role != Role.OWNER) {
            depot = depots.findById(depotId)
                    .orElseThrow(() -> new ResourceNotFoundException("Depot", "id", depotId));
        }
        AppUser user = new AppUser();
        user.setEmail(normalized);
        user.setPasswordHash(passwords.encode(rawPassword));
        user.setRole(role);
        user.setDepot(depot);
        users.save(user);
        return issueTokens(user);
    }

    @Transactional
    public TokenPair login(String email, String rawPassword) {
        AppUser user = users.findByEmail(email == null ? null : email.toLowerCase())
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!passwords.matches(rawPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
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
        Long depotId = user.getDepot() == null ? null : user.getDepot().getId();
        String access = jwt.generateAccessToken(user.getEmail(), depotId, user.getRole().name());
        String refresh = jwt.generateRefreshToken(user.getEmail(), depotId);
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
