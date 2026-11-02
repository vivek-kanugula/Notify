package com.notify.webhook.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Configuration view of a tenant's webhook endpoint. It deliberately has no secret field: the signing secret is
 * returned exactly once, by the create and rotate paths, and never by a read (, ).
 */
public record WebhookEndpointView(
        UUID id,
        String url,
        boolean demo,
        boolean enabled,
        boolean statusCallbacks,
        Instant createdAt,
        Instant updatedAt
) {
}
