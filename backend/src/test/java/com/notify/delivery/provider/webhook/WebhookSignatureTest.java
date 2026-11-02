package com.notify.delivery.provider.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * . These are the vectors a receiver's own implementation must reproduce, so the assertions are written the way
 * a receiver would: recompute the HMAC independently and compare.
 */
class WebhookSignatureTest {

    private static final String SECRET = "whsec_" + "A".repeat(43);
    private static final byte[] BODY = "{\"schemaVersion\":1,\"deliveryId\":\"abc-1\"}".getBytes(StandardCharsets.UTF_8);
    private static final Instant SIGNED_AT = Instant.parse("2026-10-13T10:00:00Z");

    /** What a receiver computes: HMAC-SHA256 over "<t>.<rawBody>" keyed with the presented secret string. */
    private static String receiverSide(String secret, long timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        mac.update(body);
        return HexFormat.of().formatHex(mac.doFinal());
    }

    @Test
    @DisplayName("the header is t=<unix>,v1=<lowercase hex> and a receiver recomputing it independently agrees")
    void headerFormatAndKnownAnswer() throws Exception {
        String header = WebhookSignature.header(SECRET, SIGNED_AT, BODY);

        assertThat(header).startsWith("t=1791885600,v1=").matches("t=\\d+,v1=[0-9a-f]{64}");
        WebhookSignature.Parsed parsed = WebhookSignature.parse(header);
        assertThat(parsed).isNotNull();
        assertThat(parsed.timestamp()).isEqualTo(SIGNED_AT.getEpochSecond());
        assertThat(parsed.v1()).isEqualTo(receiverSide(SECRET, SIGNED_AT.getEpochSecond(), BODY));
        assertThat(parsed.v1()).isEqualTo(WebhookSignature.hex(SECRET, SIGNED_AT.getEpochSecond(), BODY));
        assertThat(parsed.v1()).isLowerCase();
    }

    @Test
    @DisplayName("one changed body byte changes the signature")
    void bodyIsCovered() {
        byte[] tampered = BODY.clone();
        tampered[tampered.length - 2] ^= 0x01;

        assertThat(WebhookSignature.hex(SECRET, 1L, tampered)).isNotEqualTo(WebhookSignature.hex(SECRET, 1L, BODY));
        assertThat(WebhookSignature.hex(SECRET, 1L, new byte[0])).isNotEqualTo(WebhookSignature.hex(SECRET, 1L, BODY));
    }

    @Test
    @DisplayName("a changed timestamp changes the signature, so a captured body cannot be replayed under a new t")
    void timestampIsCovered() {
        assertThat(WebhookSignature.hex(SECRET, 1L, BODY)).isNotEqualTo(WebhookSignature.hex(SECRET, 2L, BODY));
    }

    @Test
    @DisplayName("a different secret changes the signature, so a rotated secret invalidates the old one")
    void secretIsTheKey() {
        String rotated = "whsec_" + "B".repeat(43);
        assertThat(WebhookSignature.hex(rotated, 1L, BODY)).isNotEqualTo(WebhookSignature.hex(SECRET, 1L, BODY));
    }

    @Test
    @DisplayName("the whsec_ prefix is part of the key, which is what the documented receiver snippet uses")
    void prefixIsPartOfTheKey() throws Exception {
        String withoutPrefix = SECRET.substring("whsec_".length());
        assertThat(WebhookSignature.hex(SECRET, 1L, BODY))
                .isEqualTo(receiverSide(SECRET, 1L, BODY))
                .isNotEqualTo(receiverSide(withoutPrefix, 1L, BODY));
    }

    @Test
    @DisplayName("the separator is a literal dot, so t and the body cannot be shifted into each other")
    void separatorIsUnambiguous() {
        // Without a separator these two would hash identically: t=1 + body "23..." vs t=12 + body "3...".
        byte[] bodyA = "23".getBytes(StandardCharsets.UTF_8);
        byte[] bodyB = "3".getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookSignature.hex(SECRET, 1L, bodyA)).isNotEqualTo(WebhookSignature.hex(SECRET, 12L, bodyB));
    }

    @Test
    @DisplayName("parsing tolerates spacing and extra schemes but rejects a malformed header")
    void parsing() {
        assertThat(WebhookSignature.parse("t=123, v1=abc").v1()).isEqualTo("abc");
        assertThat(WebhookSignature.parse(" t=123 ,v1=abc ,v2=xyz").timestamp()).isEqualTo(123L);
        assertThat(WebhookSignature.parse(null)).isNull();
        assertThat(WebhookSignature.parse("")).isNull();
        assertThat(WebhookSignature.parse("v1=abc")).isNull();
        assertThat(WebhookSignature.parse("t=abc,v1=abc")).isNull();
        assertThat(WebhookSignature.parse("t=123,v1=")).isNull();
    }

    @Test
    @DisplayName("comparison is length-safe and constant time in shape")
    void comparison() {
        String expected = WebhookSignature.hex(SECRET, 1L, BODY);
        assertThat(WebhookSignature.matches(expected, expected)).isTrue();
        assertThat(WebhookSignature.matches(expected, expected.substring(0, 63))).isFalse();
        assertThat(WebhookSignature.matches(expected, expected.toUpperCase())).isFalse();
        assertThat(WebhookSignature.matches(expected, null)).isFalse();
        assertThat(WebhookSignature.matches(null, expected)).isFalse();
        String flipped = (expected.charAt(0) == 'a' ? "b" : "a") + expected.substring(1);
        assertThat(WebhookSignature.matches(expected, flipped)).isFalse();
    }
}
