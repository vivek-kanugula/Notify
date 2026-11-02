package com.notify.auth.dto;

import java.util.List;
import java.util.UUID;

/**
 * Current user information returned by /me endpoint.
 * Never exposes password hashes, tokens, or internal IDs beyond what's needed.
 */
public record CurrentUserResponse(
        UUID userId,
        String email,
        UUID tenantId,
        String tenantName,
        String tenantSlug,
        String tenantStatus,
        List<String> roles
) {}
