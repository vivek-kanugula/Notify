package com.notify.apikey.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One-time create/rotate response that includes the plaintext key.
 * The key is shown ONCE and cannot be retrieved again.
 */
public record ApiKeyCreatedResponse(
    UUID id,
    String name,
    String keyPrefix,
    /** The full plaintext API key — shown ONCE at creation/rotation only. */
    String apiKey,
    List<String> scopes,
    String status,
    Instant expiresAt,
    Instant createdAt
) {}
