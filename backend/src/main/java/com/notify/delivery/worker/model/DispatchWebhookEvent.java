package com.notify.delivery.worker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Set;
import java.util.UUID;

/**
 * The {@code notify.dispatch.webhook.v1} envelope (CONTRACTS.md): identical in shape to the email envelope, because
 * the outbox writes one envelope format for every channel. It never carries bodies, PII or mutable budgets.
 * Parsing and validation are done by {@code WebhookDeliveryWorker}, which turns any violation into a poison message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchWebhookEvent(
        Integer schemaVersion,
        String eventId,
        String eventType,
        UUID notificationId,
        UUID tenantId,
        String channel,
        Integer generation,
        Integer attempt,
        String occurredAt
) implements DispatchEnvelope {

    public static final Set<String> EVENT_TYPES =
            Set.of("NOTIFICATION_QUEUED", "NOTIFICATION_RETRY_DUE", "NOTIFICATION_REPLAYED");
}
