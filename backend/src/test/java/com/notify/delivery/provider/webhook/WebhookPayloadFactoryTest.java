package com.notify.delivery.provider.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.common.model.Channel;
import com.notify.notification.dto.ClaimSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * . The payload bytes are the signed bytes, so their stability is a contract: a receiver verifies the signature
 * over exactly what arrives, and a retry must present the same body.
 */
class WebhookPayloadFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID NOTIFICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant QUEUED_AT = Instant.parse("2026-10-13T09:30:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-10-13T09:00:00Z");

    private final WebhookPayloadFactory factory = new WebhookPayloadFactory();

    private static ClaimSnapshot claim(int attemptCount, int deliveryCycle, String subject, String metadata) {
        return claim(attemptCount, deliveryCycle, subject, metadata, QUEUED_AT);
    }

    /** {@code queuedAt} is rewritten by every retry release, so each attempt may claim a different one. */
    private static ClaimSnapshot claim(
            int attemptCount, int deliveryCycle, String subject, String metadata, Instant queuedAt) {
        return new ClaimSnapshot(NOTIFICATION_ID, TENANT_ID, 0, 1, deliveryCycle, attemptCount, 5, 0,
                Channel.WEBHOOK, "https://receiver.example.com/hook", subject, "order shipped",
                metadata, null, null, queuedAt, CREATED_AT);
    }

    @Test
    @DisplayName("the payload carries exactly the fields, in a fixed order")
    void shapeAndOrder() throws Exception {
        byte[] body = factory.build(claim(1, 0, "Shipped", "{\"orderId\":\"A-1\"}"), CREATED_AT);

        JsonNode json = MAPPER.readTree(body);
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly(
                "schemaVersion", "deliveryId", "notificationId", "channel", "recipient",
                "subject", "body", "metadata", "createdAt");

        assertThat(json.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(json.get("deliveryId").textValue()).isEqualTo(NOTIFICATION_ID + "-0");
        assertThat(json.get("notificationId").textValue()).isEqualTo(NOTIFICATION_ID.toString());
        assertThat(json.get("channel").textValue()).isEqualTo("WEBHOOK");
        assertThat(json.get("recipient").textValue()).isEqualTo("https://receiver.example.com/hook");
        assertThat(json.get("subject").textValue()).isEqualTo("Shipped");
        assertThat(json.get("body").textValue()).isEqualTo("order shipped");
        assertThat(json.get("metadata").get("orderId").textValue()).isEqualTo("A-1");
        assertThat(json.get("createdAt").textValue()).isEqualTo("2026-10-13T09:00:00Z");
    }

    @Test
    @DisplayName("the bytes do not change between attempts of one cycle, so a retry presents the same signed body")
    void stableAcrossAttempts() {
        byte[] first = factory.build(claim(1, 0, "Shipped", null), CREATED_AT);
        byte[] second = factory.build(claim(4, 0, "Shipped", null), CREATED_AT);

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("a retry release that rewrites queuedAt does not change the body: createdAt is the immutable source")
    void queuedAtChangeDoesNotChangeTheBody() {
        // What the pipeline really does: attempt 1 claims the original queued_at, the release rewrites it, attempt 2
        // claims the new one. Only createdAt reaches the payload, so the signed bytes are identical (F2 / ).
        ClaimSnapshot attemptOne = claim(1, 0, "Shipped", null, QUEUED_AT);
        ClaimSnapshot attemptTwo = claim(2, 0, "Shipped", null, QUEUED_AT.plusSeconds(90));

        assertThat(attemptTwo.queuedAt()).isNotEqualTo(attemptOne.queuedAt());
        assertThat(attemptTwo.createdAt()).isEqualTo(attemptOne.createdAt());
        assertThat(factory.build(attemptTwo, attemptTwo.createdAt()))
                .isEqualTo(factory.build(attemptOne, attemptOne.createdAt()));
    }

    @Test
    @DisplayName("a new delivery cycle produces a new deliveryId, so a replay is not deduplicated as the old delivery")
    void cycleChangesDeliveryId() {
        ClaimSnapshot replayed = claim(1, 1, "Shipped", null);

        assertThat(WebhookPayloadFactory.deliveryId(replayed)).isEqualTo(NOTIFICATION_ID + "-1");
        assertThat(factory.build(replayed, CREATED_AT))
                .isNotEqualTo(factory.build(claim(1, 0, "Shipped", null), CREATED_AT));
    }

    @Test
    @DisplayName("a null subject is an explicit JSON null, so the field never disappears from the signed body")
    void nullSubjectIsExplicit() throws Exception {
        JsonNode json = MAPPER.readTree(factory.build(claim(1, 0, null, null), CREATED_AT));

        assertThat(json.has("subject")).isTrue();
        assertThat(json.get("subject").isNull()).isTrue();
    }

    @Test
    @DisplayName("tenant metadata is echoed as the JSON it was accepted as, not as a quoted string")
    void metadataStaysJson() throws Exception {
        JsonNode nested = MAPPER.readTree(
                factory.build(claim(1, 0, null, "{\"a\":{\"b\":[1,2]}}"), CREATED_AT)).get("metadata");

        assertThat(nested.isObject()).isTrue();
        assertThat(nested.get("a").get("b").get(1).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("absent, blank or unparsable metadata becomes JSON null and never changes the payload's shape")
    void metadataFallsBackToNull() throws Exception {
        for (String raw : new String[]{null, "", "   ", "{not json", "}"}) {
            JsonNode json = MAPPER.readTree(factory.build(claim(1, 0, null, raw), CREATED_AT));
            assertThat(json.has("metadata")).as("metadata present for %s", raw).isTrue();
            assertThat(json.get("metadata").isNull()).as("metadata null for %s", raw).isTrue();
        }
    }

    @Test
    @DisplayName("a null queuedAt still yields a createdAt field, as JSON null")
    void nullCreatedAt() throws Exception {
        JsonNode json = MAPPER.readTree(factory.build(claim(1, 0, null, null), null));

        assertThat(json.has("createdAt")).isTrue();
        assertThat(json.get("createdAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("a body containing JSON or HTML is carried as text, never spliced into the payload structure")
    void bodyIsAlwaysText() throws Exception {
        ClaimSnapshot hostile = new ClaimSnapshot(NOTIFICATION_ID, TENANT_ID, 0, 1, 0, 1, 5, 0,
                Channel.WEBHOOK, "https://receiver.example.com/hook", "\"},\"injected\":true,\"x\":\"",
                "<script>alert(1)</script>", null, null, null, QUEUED_AT, CREATED_AT);

        JsonNode json = MAPPER.readTree(factory.build(hostile, CREATED_AT));

        assertThat(json.has("injected")).isFalse();
        assertThat(json.get("subject").textValue()).isEqualTo("\"},\"injected\":true,\"x\":\"");
        assertThat(json.get("body").textValue()).isEqualTo("<script>alert(1)</script>");
    }

    @Test
    @DisplayName("the payload is UTF-8 and holds no tenant id, trace context or sender")
    void carriesNoInternalFields() {
        String text = WebhookPayloadFactory.asText(
                factory.build(claim(1, 0, "Grüße", "{\"k\":\"ü\"}"), CREATED_AT));

        assertThat(text).contains("Grüße").contains("\"ü\"");
        assertThat(text).doesNotContain(TENANT_ID.toString())
                .doesNotContain("tenantId").doesNotContain("traceContext").doesNotContain("sender");
    }
}
