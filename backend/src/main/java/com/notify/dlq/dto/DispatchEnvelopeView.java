package com.notify.dlq.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.UUID;

/**
 * What can be read out of a dead-lettered <b>dispatch</b> record (/ , ).
 *
 * <p>Everything here is a <b>claim</b> from an untrusted payload. The names say so, and nothing in this record is
 * used for authorization, for filtering, or as the tenant of record - only a database lookup decides that
 * ().
 *
 * <p><b>There is no delivery cycle.</b> The dispatch envelope carries {@code generation} and {@code attempt} but no
 * {@code deliveryCycle}, so none is recorded and none is reconstructed from the notification current cycle: a
 * historical fact that was never stored is not invented here.
 */
public record DispatchEnvelopeView(
        Integer schemaVersion,
        String eventType,
        UUID claimedNotificationId,
        UUID claimedTenantId,
        Integer claimedGeneration
) {

    /** Returns empty when the value is not a dispatch envelope with a usable notification id. */
    public static Optional<DispatchEnvelopeView> parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        UUID notificationId = uuidOrNull(node, "notificationId");
        if (notificationId == null) {
            return Optional.empty();
        }
        Integer version = node.hasNonNull("schemaVersion") ? node.get("schemaVersion").asInt() : null;
        Integer generation = node.hasNonNull("generation") ? node.get("generation").asInt() : null;
        return Optional.of(new DispatchEnvelopeView(
                version,
                node.path("eventType").asText(null),
                notificationId,
                uuidOrNull(node, "tenantId"),
                generation));
    }

    private static UUID uuidOrNull(JsonNode node, String field) {
        String raw = node.path(field).asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
