package com.notify.apikey.service;

import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.apikey.dto.ApiKeyCreatedResponse;
import com.notify.apikey.dto.ApiKeyResponse;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.apikey.dto.RotateApiKeyRequest;
import com.notify.apikey.entity.ApiKeyEntity;
import com.notify.apikey.repository.ApiKeyRepository;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.ApiKeyScope;
import com.notify.common.model.ApiKeyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * API key lifecycle management: generation, hashing, revocation, rotation, and listing.
 *
 * Key format: {@code ntf_<40 URL-safe Base64 chars>} (prefix 4 chars + underscore + 40 chars = 44 total).
 * Key prefix (display): first 9 chars of the key, e.g. {@code ntf_abc1X}.
 * SHA-256 hash stored in DB; plaintext returned ONCE at creation/rotation.
 *
 * Limit: 10 ACTIVE keys per tenant (). Enforced here before insert.
 * Expiry: keys with a past {@code expires_at} are rejected at authentication time (not stored as revoked).
 * Rotation: creates a new key linked to the old via {@code rotated_to_id}, revokes the old atomically.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private static final int MAX_ACTIVE_KEYS = 10;
    /** ntf_ prefix (4 chars) + 40 URL-safe random chars. */
    private static final String KEY_PREFIX_STRING = "ntf_";
    /** Number of random bytes: ceil(40 * 6 / 8) = 30 bytes → 40 Base64 chars. */
    private static final int RANDOM_BYTES = 30;
    /** Display prefix: the first 8 chars of the full key, e.g. ntf_abcd. */
    private static final int DISPLAY_PREFIX_LEN = 8;
    /** Last-used throttle window: 60 seconds. */
    private static final long LAST_USED_THROTTLE_SECONDS = 60L;

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyStatusCacheService cacheService;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, ApiKeyStatusCacheService cacheService, Clock clock) {
        this.apiKeyRepository = apiKeyRepository;
        this.cacheService = cacheService;
        this.clock = clock;
    }

    /**
     * List all API keys for a tenant (ordered newest first).
     * Never returns the plaintext key.
     */
    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listKeys(UUID tenantId) {
        return apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Create a new API key.
     * Validates scopes, enforces the 10-key limit, generates and stores a hashed key.
     * Returns the one-time plaintext key in the response.
     */
    @Transactional
    public ApiKeyCreatedResponse createKey(UUID tenantId, CreateApiKeyRequest request) {
        // Validate scopes
        List<String> validatedScopes = validateScopes(request.scopes());

        Instant now = clock.instant();

        // Enforce active key limit (ignoring expired keys)
        long activeCount = apiKeyRepository.countActiveByTenantId(tenantId, now);
        if (activeCount >= MAX_ACTIVE_KEYS) {
            throw new NotifyException(ErrorCode.API_KEY_LIMIT_REACHED,
                    "Maximum of " + MAX_ACTIVE_KEYS + " active API keys allowed per tenant");
        }

        // Validate expiry (must be in the future if provided)
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "expiresAt must be a future instant");
        }

        // Generate key
        String plaintext = generateKey();
        String hash = sha256Hex(plaintext);
        String displayPrefix = plaintext.substring(0, DISPLAY_PREFIX_LEN);

        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setTenantId(tenantId);
        entity.setName(request.name());
        entity.setKeyPrefix(displayPrefix);
        entity.setKeyHash(hash);
        entity.setScopes(validatedScopes.toArray(new String[0]));
        entity.setStatus(ApiKeyStatus.ACTIVE);
        entity.setExpiresAt(request.expiresAt());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        entity = apiKeyRepository.save(entity);
        log.info("Created API key id={} prefix={} tenant={}", entity.getId(), displayPrefix, tenantId);

        return toCreatedResponse(entity, plaintext);
    }

    /**
     * Revoke an API key. Idempotent if already revoked.
     * Returns silently if key not found (cross-tenant 404 handled at controller level).
     */
    @Transactional
    public void revokeKey(UUID tenantId, UUID keyId) {
        // Verify the key belongs to this tenant (cross-tenant returns 404 at controller)
        ApiKeyEntity entity = apiKeyRepository.findByIdAndTenantId(keyId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "API key not found"));

        if (entity.getStatus() == ApiKeyStatus.REVOKED) {
            // Already revoked — idempotent
            return;
        }

        int updated = apiKeyRepository.revokeKey(keyId, tenantId, ApiKeyStatus.REVOKED, clock.instant());
        if (updated == 0) {
            log.warn("Revoke key id={} tenant={}: already revoked or race condition", keyId, tenantId);
        } else {
            String keyHash = entity.getKeyHash();
            executeAfterCommit(() -> {
                cacheService.evictApiKey(keyHash);
                log.info("Revoked API key id={} tenant={}", keyId, tenantId);
            });
        }
    }

    /**
     * Rotate an API key: creates a new key with same scopes (optionally overriding),
     * links old key to new via rotated_to_id, and sets the old key's expiry to the grace
     * period (default 24h, customizable) without extending an already earlier expiry.
     * The old key remains ACTIVE and usable until grace expiry or explicit revocation.
     * Returns the one-time plaintext of the new key.
     */
    @Transactional
    public ApiKeyCreatedResponse rotateKey(UUID tenantId, UUID keyId, RotateApiKeyRequest request) {
        ApiKeyEntity old = apiKeyRepository.findByIdAndTenantId(keyId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "API key not found"));

        if (old.getStatus() == ApiKeyStatus.REVOKED) {
            throw new NotifyException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot rotate a revoked API key");
        }
        if (old.getRotatedToId() != null) {
            throw new NotifyException(ErrorCode.INVALID_STATE_TRANSITION,
                    "API key has already been rotated");
        }

        // Use provided scopes or inherit from old key
        List<String> validatedScopes = (request != null && request.scopes() != null && !request.scopes().isEmpty())
                ? validateScopes(request.scopes())
                : List.of(old.getScopes());

        Instant now = clock.instant();

        // Validate expiry
        if (request != null && request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "expiresAt must be a future instant");
        }

        // Generate new key
        String plaintext = generateKey();
        String hash = sha256Hex(plaintext);
        String displayPrefix = plaintext.substring(0, DISPLAY_PREFIX_LEN);

        String name = (request != null && request.name() != null && !request.name().isBlank())
                ? request.name()
                : old.getName();

        ApiKeyEntity newKey = new ApiKeyEntity();
        newKey.setTenantId(tenantId);
        newKey.setName(name);
        newKey.setKeyPrefix(displayPrefix);
        newKey.setKeyHash(hash);
        newKey.setScopes(validatedScopes.toArray(new String[0]));
        newKey.setStatus(ApiKeyStatus.ACTIVE);
        newKey.setExpiresAt(request != null ? request.expiresAt() : null);
        newKey.setCreatedAt(now);
        newKey.setUpdatedAt(now);

        newKey = apiKeyRepository.save(newKey);

        // Rotation overlap grace period (default 24 hours, customizable via gracePeriodHours, max 168 hours)
        int graceHours = (request != null && request.gracePeriodHours() != null)
                ? request.gracePeriodHours()
                : 24;

        if (graceHours > 168) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "gracePeriodHours cannot exceed 168 hours (7 days)");
        }

        if (graceHours <= 0) {
            // Immediate revocation
            old.setStatus(ApiKeyStatus.REVOKED);
        } else {
            Instant graceExpiry = now.plus(Duration.ofHours(graceHours));
            // Do not extend an already earlier expiry
            Instant effectiveExpiry = (old.getExpiresAt() != null && old.getExpiresAt().isBefore(graceExpiry))
                    ? old.getExpiresAt()
                    : graceExpiry;
            old.setExpiresAt(effectiveExpiry);
            // Old key remains ACTIVE during overlap
        }

        old.setRotatedToId(newKey.getId());
        old.setUpdatedAt(now);
        apiKeyRepository.save(old);

        String oldHash = old.getKeyHash();
        executeAfterCommit(() -> {
            cacheService.evictApiKey(oldHash);
            log.info("Evicted rotated API key cache old={} tenant={}", keyId, tenantId);
        });

        log.info("Rotated API key old={} new={} tenant={}", keyId, newKey.getId(), tenantId);
        return toCreatedResponse(newKey, plaintext);
    }

    /**
     * Throttled last-used update. Called from authentication filter.
     * Only writes to DB at most once per 60 seconds per key.
     */
    @Transactional
    public void recordLastUsed(UUID keyId, String ipAddress) {
        Instant now = clock.instant();
        Instant threshold = now.minusSeconds(LAST_USED_THROTTLE_SECONDS);
        apiKeyRepository.updateLastUsed(keyId, now, ipAddress, threshold);
    }

    /**
     * Executes an action after current transaction commits, or immediately if no transaction is active.
     */
    private void executeAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private String generateKey() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return KEY_PREFIX_STRING + encoded;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private List<String> validateScopes(List<String> scopes) {
        return scopes.stream()
                .map(s -> ApiKeyScope.fromValue(s)
                        .orElseThrow(() -> new NotifyException(ErrorCode.VALIDATION_FAILED,
                                "Unknown scope: " + s))
                        .getValue())
                .collect(Collectors.toList());
    }

    public ApiKeyResponse toResponse(ApiKeyEntity entity) {
        return new ApiKeyResponse(
                entity.getId(),
                entity.getName(),
                entity.getKeyPrefix(),
                List.of(entity.getScopes()),
                entity.getStatus().name(),
                entity.getExpiresAt(),
                entity.getLastUsedAt(),
                entity.getCreatedAt(),
                entity.getRotatedToId()
        );
    }

    private ApiKeyCreatedResponse toCreatedResponse(ApiKeyEntity entity, String plaintext) {
        return new ApiKeyCreatedResponse(
                entity.getId(),
                entity.getName(),
                entity.getKeyPrefix(),
                plaintext,
                List.of(entity.getScopes()),
                entity.getStatus().name(),
                entity.getExpiresAt(),
                entity.getCreatedAt()
        );
    }
}
