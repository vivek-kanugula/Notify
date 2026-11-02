package com.notify.apikey.cache;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cache representation of an API key for authentication hot path.
 * Cached in Redis under {@code apikey:{keyHash}} with 60s TTL.
 */
public record CachedApiKey(
        UUID id,
        UUID tenantId,
        String status,
        List<String> scopes,
        Instant expiresAt
) {}
