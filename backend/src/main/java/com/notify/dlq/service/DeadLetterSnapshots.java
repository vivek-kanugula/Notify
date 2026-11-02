package com.notify.dlq.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.dlq.dto.DeadLetterEventEnvelope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds {@code dead_letter_entries.payload_snapshot} (, decision ).
 *
 * <p>It holds the <b>dead-letter envelope</b> and nothing else: identifiers, channel, reason, generation, cycle,
 * attempt count and timestamps - exactly what the authoritative event already carries. **No notification content
 * is ever written here**: no recipient, no subject, no body, no metadata. That is what makes the snapshot safe to
 * show an admin without sanitizing it ().
 */
public final class DeadLetterSnapshots {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeadLetterSnapshots() {
    }

    public static String of(DeadLetterEventEnvelope event) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", event.schemaVersion());
        snapshot.put("eventId", event.eventId().toString());
        snapshot.put("eventType", event.eventType());
        snapshot.put("notificationId", event.notificationId().toString());
        snapshot.put("tenantId", event.tenantId().toString());
        snapshot.put("channel", event.channel().name());
        snapshot.put("generation", event.generation());
        snapshot.put("deliveryCycle", event.deliveryCycle());
        snapshot.put("reason", event.reason().name());
        snapshot.put("lastErrorCode", event.lastErrorCode());
        snapshot.put("attemptCount", event.attemptCount());
        snapshot.put("occurredAt", event.occurredAt());
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            // Identifiers and enums only, so this cannot fail; refusing loudly beats storing a half snapshot.
            throw new IllegalStateException("Failed to serialize the dead-letter snapshot", e);
        }
    }
}
