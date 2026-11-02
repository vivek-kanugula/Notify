package com.notify.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * HS256 JWT access token service.
 * Contract: 15-min access token, claims: sub, tid, roles, aud, jti.
 * Kept in memory by SPA only (never in cookies).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    private final SecretKey signingKey;
    private final Clock clock;

    public JwtService(
            @Value("${notify.jwt.secret}") String secret,
            Clock clock
    ) {
        if (secret.length() < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 characters");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /**
     * Generate an access token for a tenant user.
     */
    public TokenResult generateTenantToken(UUID userId, UUID tenantId, List<String> roles) {
        return generateToken(userId.toString(), tenantId, roles, "notify-tenant");
    }

    /**
     * Generate an access token for an admin user.
     */
    public TokenResult generateAdminToken(UUID adminId, List<String> roles) {
        return generateToken(adminId.toString(), null, roles, "notify-admin");
    }

    private TokenResult generateToken(String subject, UUID tenantId, List<String> roles, String audience) {
        Instant now = clock.instant();
        Instant expiry = now.plus(ACCESS_TOKEN_TTL);
        String jti = UUID.randomUUID().toString();

        var builder = Jwts.builder()
                .subject(subject)
                .id(jti)
                .audience().add(audience).and()
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey);

        if (tenantId != null) {
            builder.claim("tid", tenantId.toString());
        }

        String token = builder.compact();
        return new TokenResult(token, jti, expiry);
    }

    /**
     * Parse and validate a JWT, returning its claims. Returns null if invalid.
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return null;
        }
    }

    public Duration getAccessTokenTtl() {
        return ACCESS_TOKEN_TTL;
    }

    public record TokenResult(String token, String jti, Instant expiresAt) {}
}
