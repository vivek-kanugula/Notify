package com.notify.auth.service;

import com.notify.auth.entity.RefreshTokenEntity;
import com.notify.auth.repository.RefreshTokenRepository;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.PrincipalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Refresh token service: SHA-256 hashed storage, 7-day TTL, rotation on use,
 * family-based reuse detection and revocation per CONTRACTS.md.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final com.notify.tenant.repository.TenantUserRepository tenantUsers;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               com.notify.tenant.repository.TenantUserRepository tenantUsers,
                               Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.tenantUsers = tenantUsers;
        this.clock = clock;
    }

    /**
     * Create a new refresh token for a principal.
     */
    @Transactional
    public RefreshResult createRefreshToken(PrincipalType principalType, UUID principalId,
                                             String userAgent, String ipAddress) {
        UUID familyId = UUID.randomUUID();
        return createTokenInFamily(principalType, principalId, familyId, userAgent, ipAddress);
    }

    /**
     * Rotate a refresh token: validate the old one, revoke it, issue new one in the same family.
     * Reuse of an already-revoked token triggers family revocation (REFRESH_REUSE_DETECTED).
     */
    @Transactional(noRollbackFor = NotifyException.class)
    public RotationResult rotateRefreshToken(String rawToken, String userAgent, String ipAddress) {
        return rotateRefreshToken(rawToken, null, userAgent, ipAddress);
    }

    /**
     * Rotate a refresh token with principal-type enforcement before any rotation writes.
     * Validates that the token's principal type matches expectedPrincipalType before revoking the token,
     * preventing cross-endpoint token destruction and session family wipeout.
     */
    @Transactional(noRollbackFor = NotifyException.class)
    public RotationResult rotateRefreshToken(String rawToken, PrincipalType expectedPrincipalType,
                                             String userAgent, String ipAddress) {
        String tokenHash = sha256(rawToken);
        RefreshTokenEntity existing = refreshTokenRepository.findByTokenHashWithLock(tokenHash)
                .orElseThrow(() -> new NotifyException(ErrorCode.UNAUTHENTICATED, "Invalid refresh token"));

        if (expectedPrincipalType != null && existing.getPrincipalType() != expectedPrincipalType) {
            String target = expectedPrincipalType == PrincipalType.ADMIN ? "Use /auth/admin/refresh for admin tokens"
                    : "Use /auth/refresh for tenant tokens";
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, target);
        }

        validateLocked(existing);

        return rotateLocked(existing, userAgent, ipAddress);
    }




    /**
     * The reuse and expiry rules, on a row that is already locked.
     *
     * <p>**Shared by both rotation modes on purpose.** An earlier version of the callback-bootstrap path checked
     * only the assertion and then rotated, so an expired, logged-out or already-used refresh token with an
     * otherwise valid assertion would have rotated anyway and skipped reuse-triggered family revocation. Both
     * modes now run exactly these checks under the same lock.
     */
    private void validateLocked(RefreshTokenEntity existing) {
        Instant now = clock.instant();
        if (existing.getRevokedAt() != null) {
            log.warn("Refresh token reuse detected for family {}, revoking entire family", existing.getFamilyId());
            refreshTokenRepository.revokeFamily(existing.getFamilyId(), now);
            throw new NotifyException(ErrorCode.REFRESH_REUSE_DETECTED,
                    "Refresh token reuse detected. All sessions in this family have been revoked.");
        }
        if (existing.getExpiresAt().isBefore(now)) {
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "Refresh token expired");
        }
    }
    /** The rotation writes, on a row that is already locked and already validated. */
    private RotationResult rotateLocked(RefreshTokenEntity existing, String userAgent, String ipAddress) {
        existing.setRevokedAt(clock.instant());
        RefreshResult newToken = createTokenInFamily(
                existing.getPrincipalType(), existing.getPrincipalId(),
                existing.getFamilyId(), userAgent, ipAddress);
        existing.setReplacedBy(newToken.tokenId());
        refreshTokenRepository.save(existing);
        return new RotationResult(newToken, existing.getPrincipalType(), existing.getPrincipalId());
    }

    /**
     * Revoke a specific refresh token by raw value (for logout).
     * Idempotent: returns null/existing without throwing if token is absent, expired, or already revoked.
     */
    @Transactional
    public RefreshTokenEntity revokeByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String tokenHash = sha256(rawToken);
        RefreshTokenEntity existing = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElse(null);

        if (existing != null && existing.getRevokedAt() == null) {
            existing.setRevokedAt(clock.instant());
            refreshTokenRepository.save(existing);
        }
        return existing;
    }

    /**
     * Revoke all refresh tokens for a principal (for logout-all or account revocation).
     */
    @Transactional
    public void revokeAllForPrincipal(PrincipalType principalType, UUID principalId) {
        Instant now = clock.instant();
        refreshTokenRepository.revokeAllForPrincipal(principalType, principalId, now);
    }

    private RefreshResult createTokenInFamily(PrincipalType principalType, UUID principalId,
                                               UUID familyId, String userAgent, String ipAddress) {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String tokenHash = sha256(rawToken);

        Instant now = clock.instant();

        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setPrincipalType(principalType);
        entity.setPrincipalId(principalId);
        entity.setTokenHash(tokenHash);
        entity.setFamilyId(familyId);
        entity.setExpiresAt(now.plus(REFRESH_TOKEN_TTL));
        entity.setUserAgent(userAgent != null ? userAgent.substring(0, Math.min(userAgent.length(), 512)) : null);
        entity.setIpAddress(ipAddress);

        RefreshTokenEntity saved = refreshTokenRepository.save(entity);
        return new RefreshResult(saved.getId(), rawToken, saved.getExpiresAt(), saved.getFamilyId());
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public record RefreshResult(UUID tokenId, String rawToken, Instant expiresAt, UUID familyId) {}

    public record RotationResult(RefreshResult refreshResult, PrincipalType principalType, UUID principalId) {}
}
