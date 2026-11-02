package com.notify.delivery.worker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Set;
import java.util.UUID;

/**
 * The {@code notify.dispatch.email.v1} envelope (CONTRACTS.md): identifiers and the generation fence only.
 * It never carries bodies, PII, or mutable budgets; PostgreSQL is authoritative for everything else.
 * Parsing and validation are done by {@code EmailDeliveryWorker}, which turns any violation into a poison message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchEmailEvent(
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
    public static final Set<String> EVENT_TYPES = Set.of("NOTIFICATION_QUEUED", "NOTIFICATION_RETRY_DUE", "NOTIFICATION_REPLAYED");
}
