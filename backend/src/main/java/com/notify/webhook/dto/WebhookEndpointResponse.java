package com.notify.webhook.dto;

import com.notify.webhook.model.WebhookEndpointView;

import java.time.Instant;
import java.util.UUID;

/**
 * Configuration read. It has no secret field at all: the signing secret is returned once, by create and rotate, and
 * can never be read back ().
 */
public record WebhookEndpointResponse(
        UUID id,
        String url,
        boolean isDemo,
        boolean enabled,
        boolean statusCallbacks,
        Instant createdAt,
        Instant updatedAt
) {
    public static WebhookEndpointResponse from(WebhookEndpointView view) {
        return new WebhookEndpointResponse(view.id(), view.url(), view.demo(), view.enabled(),
                view.statusCallbacks(), view.createdAt(), view.updatedAt());
    }
}
