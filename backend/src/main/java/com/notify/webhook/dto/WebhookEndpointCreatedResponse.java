package com.notify.webhook.dto;

import com.notify.webhook.model.WebhookEndpointView;

import java.time.Instant;
import java.util.UUID;

/**
 * One-time create/rotate response carrying the plaintext signing secret. Shown ONCE and never retrievable again
 * (), the same contract as an API key's one-time reveal.
 */
public record WebhookEndpointCreatedResponse(
        UUID id,
        String url,
        boolean isDemo,
        boolean enabled,
        boolean statusCallbacks,
        Instant createdAt,
        Instant updatedAt,
        /** The full plaintext signing secret — shown ONCE at creation or rotation only. */
        String signingSecret
) {
    public static WebhookEndpointCreatedResponse from(WebhookEndpointView view, String signingSecret) {
        return new WebhookEndpointCreatedResponse(view.id(), view.url(), view.demo(), view.enabled(),
                view.statusCallbacks(), view.createdAt(), view.updatedAt(), signingSecret);
    }

    @Override
    public String toString() {
        return "WebhookEndpointCreatedResponse[id=" + id + ", signingSecret=<redacted>]";
    }
}
