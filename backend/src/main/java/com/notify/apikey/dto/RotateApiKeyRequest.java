package com.notify.apikey.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;

/**
 * Request to rotate an existing API key.
 * All fields are optional:
 * - name: overrides key name (or retains previous name if null/blank)
 * - scopes: overrides permissions (or retains previous scopes if null/empty)
 * - expiresAt: optional future expiry for the new key
 * - gracePeriodHours: overlap period during which old key remains active (default 24h, max 168h/7 days, 0 for immediate revocation)
 */
public record RotateApiKeyRequest(
    String name,
    List<String> scopes,
    Instant expiresAt,
    @Min(value = 0, message = "gracePeriodHours must be >= 0")
    @Max(value = 168, message = "gracePeriodHours cannot exceed 168 hours (7 days)")
    Integer gracePeriodHours
) {}
