package com.notify.delivery.provider.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * The {@code X-Notify-Signature} header ().
 *
 * <p>Format {@code t=<unix seconds>,v1=<lowercase hex HMAC-SHA256>} over {@code UTF-8(t) || '.' || rawBody}, where
 * {@code rawBody} is exactly the bytes sent. The HMAC key is the UTF-8 bytes of the presented secret string including
 * its {@code whsec_} prefix, so a receiver can use the value it copied without decoding anything.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-Notify-Signature";
    private static final String ALGORITHM = "HmacSHA256";
    private static final byte SEPARATOR = (byte) '.';

    private WebhookSignature() {
    }

    public static String header(String secret, Instant signedAt, byte[] body) {
        long timestamp = signedAt.getEpochSecond();
        return "t=" + timestamp + ",v1=" + hex(secret, timestamp, body);
    }

    /** Lowercase hex HMAC-SHA256 of {@code <t>.<body>}; exposed so a verifier can compare without parsing a header. */
    public static String hex(String secret, long timestamp, byte[] body) {
        byte[] prefix = Long.toString(timestamp).getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + 1 + (body == null ? 0 : body.length)];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        signed[prefix.length] = SEPARATOR;
        if (body != null) {
            System.arraycopy(body, 0, signed, prefix.length + 1, body.length);
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signed));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Webhook signing failed: " + e.getClass().getName());
        }
    }

    /** Parsed {@code t} and {@code v1} values of a received header, or {@code null} when the header is malformed. */
    public static Parsed parse(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        Long timestamp = null;
        String v1 = null;
        for (String part : headerValue.split(",")) {
            String token = part.trim();
            if (token.startsWith("t=")) {
                try {
                    timestamp = Long.parseLong(token.substring(2).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (token.startsWith("v1=")) {
                v1 = token.substring(3).trim();
            }
        }
        if (timestamp == null || v1 == null || v1.isEmpty()) {
            return null;
        }
        return new Parsed(timestamp, v1);
    }

    /**
     * Constant-time comparison of an expected and a received signature, so a receiver cannot be used as a timing
     * oracle. Both values are lowercase hex of the same length when they match.
     */
    public static boolean matches(String expectedHex, String receivedHex) {
        if (expectedHex == null || receivedHex == null || expectedHex.length() != receivedHex.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expectedHex.length(); i++) {
            diff |= expectedHex.charAt(i) ^ receivedHex.charAt(i);
        }
        return diff == 0;
    }

    public record Parsed(long timestamp, String v1) {
    }
}
