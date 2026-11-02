package com.notify.delivery.provider.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notify.notification.dto.ClaimSnapshot;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Builds the signed request body ().
 *
 * <p>The bytes are identical for every attempt of one delivery cycle: nothing attempt-specific and no clock reading
 * goes in, and {@code createdAt} comes from the notification's immutable acceptance time rather than from
 * {@code queued_at}, which a retry release rewrites. A receiver can therefore deduplicate on the delivery id
 * <em>or</em> on a body hash. Per-attempt information travels in headers ({@code X-Notify-Attempt}) and in the
 * signature's timestamp {@code t}, so the signature header itself does change between attempts — the signed
 * <em>body</em> is what stays identical.
 *
 * <p>Field order is fixed by construction, because the signature covers the exact bytes.
 */
@Component
public class WebhookPayloadFactory {

    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code <notificationId>-<deliveryCycle>}: stable per cycle, new after a DLQ replay (). */
    public static String deliveryId(ClaimSnapshot claim) {
        return claim.id() + "-" + claim.deliveryCycle();
    }

    public byte[] build(ClaimSnapshot claim, Instant createdAt) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("deliveryId", deliveryId(claim));
        root.put("notificationId", claim.id().toString());
        root.put("channel", claim.channel().name());
        root.put("recipient", claim.recipient());
        if (claim.subject() == null) {
            root.putNull("subject");
        } else {
            root.put("subject", claim.subject());
        }
        root.put("body", claim.body());
        root.set("metadata", metadata(claim.metadata()));
        root.put("createdAt", createdAt == null ? null : createdAt.toString());
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Webhook payload could not be serialized: " + e.getClass().getName());
        }
    }

    /**
     * Tenant metadata is echoed as the JSON object it was accepted as. Anything unparsable becomes {@code null}
     * rather than a string that would change the payload's shape.
     */
    private static JsonNode metadata(String raw) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.nullNode();
        }
        try {
            JsonNode parsed = MAPPER.readTree(raw);
            return parsed == null || parsed.isMissingNode() ? MAPPER.nullNode() : parsed;
        } catch (JsonProcessingException e) {
            return MAPPER.nullNode();
        }
    }

    /** Convenience for tests and the demo receiver: the body as text. */
    public static String asText(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}
