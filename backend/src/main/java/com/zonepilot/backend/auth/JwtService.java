package com.zonepilot.backend.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import io.jsonwebtoken.security.Keys;

/**
 * Slice 1 auth: HS256 access (15m) + refresh (7d) tokens, jjwt 0.12.6 API.
 * Depot id and role travel inside the token — client-sent depot ids are never trusted.
 */
@Service
public class JwtService {

    private static final String CLAIM_DEPOT = "depotId";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(
            @Value("${app.jwt.secret:zonepilot-dev-secret-change-me-32bytes!!}") String secret,
            @Value("${app.jwt.access-ttl:PT15M}") Duration accessTtl,
            @Value("${app.jwt.refresh-ttl:P7D}") Duration refreshTtl) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.jwt.secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public String generateAccessToken(String username, Long depotId, String role) {
        return Jwts.builder()
                .subject(username)
                .id(UUID.randomUUID().toString()) // uniqueness: iat/exp are second-precision
                .claim(CLAIM_DEPOT, depotId)
                .claim(CLAIM_ROLE, role)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(String username, Long depotId) {
        return Jwts.builder()
                .subject(username)
                .id(UUID.randomUUID().toString()) // uniqueness: iat/exp are second-precision
                .claim(CLAIM_DEPOT, depotId)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(refreshTtl)))
                .signWith(key)
                .compact();
    }

    private Claims payload(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException e) {
            throw new BadTokenException("Invalid or expired token", e);
        }
    }

    public String getUsername(String token) { return payload(token).getSubject(); }

    public Long getDepotId(String token) { return payload(token).get(CLAIM_DEPOT, Long.class); }

    public String getRole(String token) { return payload(token).get(CLAIM_ROLE, String.class); }

    public Instant expiryOf(String token) {
        Date exp = payload(token).getExpiration();
        return exp == null ? null : exp.toInstant();
    }

    public boolean isRefresh(String token) {
        return TYPE_REFRESH.equals(payload(token).get(CLAIM_TYPE, String.class));
    }

    public boolean isExpired(String token) {
        try {
            Date exp = payload(token).getExpiration();
            return exp != null && exp.before(new Date());
        } catch (BadTokenException e) {
            return true;
        }
    }
}
