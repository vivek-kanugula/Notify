package com.notify.apikey.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response for an existing API key (one-time secret NOT included).
 * The key_prefix field (e.g. "ntf_abc1") allows the owner to identify the key.
 */
public record ApiKeyResponse(
    UUID id,
    String name,
    String keyPrefix,
    List<String> scopes,
    String status,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant createdAt,
    UUID rotatedToId
) {}
