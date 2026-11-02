package com.notify.dlq.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;

import java.util.UUID;

/**
 * The authoritative {@code notify.dlq.v1} event, parsed ().
 *
 * <p>Exactly the envelope {@code NotificationService.recordDeadLetter} writes, at {@code schemaVersion 1}: it
 * carries identifiers, the channel, the generation, the delivery cycle, the reason, the last error code and the
 * attempt count - and **no recipient, subject or body**, which is why a materialization needs no sanitizing.
 *
 * <p>This is the <b>only</b> record family that may drive a business dead-letter entry (): it is written in
 * the same transaction as the FAILED status, so it is the committed verdict rather than an observation about it.
 */
public record DeadLetterEventEnvelope(
        int schemaVersion,
        UUID eventId,
        String eventType,
        UUID notificationId,
        UUID tenantId,
        Channel channel,
        int generation,
        int deliveryCycle,
        DlqReason reason,
        String lastErrorCode,
        int attemptCount,
        String occurredAt
) {

    public static final String EVENT_TYPE = "NOTIFICATION_DEAD_LETTERED";

    /**
     * Parses one record value.
     *
     * @throws UnparsableDeadLetterEventException when the value is not a version-1 dead-letter envelope with every
     *         identifier present and well formed. The caller quarantines rather than guessing (plan 2.2 outcome 1).
     */
    public static DeadLetterEventEnvelope parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new UnparsableDeadLetterEventException("value is not a JSON object");
        }
        int version = node.path("schemaVersion").asInt(-1);
        if (version != 1) {
            throw new UnparsableDeadLetterEventException("unsupported schemaVersion " + version);
        }
        String type = node.path("eventType").asText(null);
        if (!EVENT_TYPE.equals(type)) {
            throw new UnparsableDeadLetterEventException("unsupported eventType " + type);
        }
        UUID eventId = uuid(node, "eventId");
        UUID notificationId = uuid(node, "notificationId");
        UUID tenantId = uuid(node, "tenantId");
        Channel channel = enumValue(node, "channel", Channel.class);
        DlqReason reason = enumValue(node, "reason", DlqReason.class);
        if (!node.hasNonNull("generation") || !node.hasNonNull("deliveryCycle")
                || !node.hasNonNull("attemptCount")) {
            throw new UnparsableDeadLetterEventException("generation, deliveryCycle and attemptCount are required");
        }
        return new DeadLetterEventEnvelope(
                version,
                eventId,
                type,
                notificationId,
                tenantId,
                channel,
                node.get("generation").asInt(),
                node.get("deliveryCycle").asInt(),
                reason,
                node.path("lastErrorCode").asText(null),
                node.get("attemptCount").asInt(),
                node.path("occurredAt").asText(null));
    }

    private static UUID uuid(JsonNode node, String field) {
        String raw = node.path(field).asText(null);
        if (raw == null || raw.isBlank()) {
            throw new UnparsableDeadLetterEventException(field + " is missing");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new UnparsableDeadLetterEventException(field + " is not a UUID");
        }
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> type) {
        String raw = node.path(field).asText(null);
        if (raw == null || raw.isBlank()) {
            throw new UnparsableDeadLetterEventException(field + " is missing");
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new UnparsableDeadLetterEventException(field + " has an unknown value");
        }
    }

    /**
     * A record that is not a usable dead-letter envelope. Its message names the structural problem only - never a
     * payload fragment, because the payload is untrusted input.
     */
    public static class UnparsableDeadLetterEventException extends RuntimeException {

        public UnparsableDeadLetterEventException(String message) {
            super(message);
        }
    }
}
