package com.notify.webhook.model;

import java.util.UUID;

/**
 * A tenant's webhook destination as resolved for one delivery attempt (): the current row, never a snapshot.
 *
 * <p>Carries the decrypted signing secret, so {@link #toString()} prints identifiers only.
 */
public record WebhookDestination(
        UUID endpointId,
        UUID tenantId,
        String url,
        boolean demo,
        boolean enabled,
        String secret
) {
    @Override
    public String toString() {
        return "WebhookDestination[endpointId=" + endpointId + ", tenantId=" + tenantId
                + ", demo=" + demo + ", enabled=" + enabled + ", secret=<redacted>, url=<redacted>]";
    }
}
