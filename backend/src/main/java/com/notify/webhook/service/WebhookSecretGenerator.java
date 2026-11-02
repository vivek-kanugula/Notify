package com.notify.webhook.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates a tenant webhook signing secret: {@code whsec_} + unpadded base64url of 32 {@link SecureRandom} bytes
 * (). The presented string, prefix included, is the HMAC key a receiver uses (), so the exact format is
 * part of the contract.
 */
@Component
public class WebhookSecretGenerator {

    public static final String PREFIX = "whsec_";
    private static final int SECRET_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] raw = new byte[SECRET_BYTES];
        random.nextBytes(raw);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Shape check only; it never confirms that a secret belongs to a tenant. */
    public static boolean hasExpectedShape(String secret) {
        return secret != null && secret.startsWith(PREFIX) && secret.length() == PREFIX.length() + 43;
    }
}
