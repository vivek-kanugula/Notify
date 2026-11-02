package com.notify.webhook.service;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.webhook.config.WebhookProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * AES-256-GCM protection of the per-tenant webhook signing secret at rest ().
 *
 * <p>Stored form: {@code base64(IV || ciphertext || tag)} with a fresh 12-byte IV per encryption and a 128-bit tag.
 * The tenant id is the additional authenticated data, so a ciphertext copied into another tenant's row fails to
 * decrypt instead of silently signing with a foreign secret.
 *
 * <p>The key comes from {@code notify.webhook.secret-key} and is never logged. When it is absent every call fails with
 * {@code 503 FEATURE_NOT_YET_AVAILABLE} (the project's stub policy) rather than falling back to anything weaker.
 */
@Component
public class WebhookSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** IV + tag + a 49-byte secret, base64-encoded, stays far below the VARCHAR(512) column. */
    private static final int MAX_STORED_CHARS = 512;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public WebhookSecretCipher(WebhookProperties properties) {
        this.key = properties.secretKeyConfigured() ? readKey(properties.secretKey()) : null;
    }

    private static SecretKey readKey(String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("notify.webhook.secret-key is not valid base64");
        }
        if (raw.length != KEY_BYTES) {
            // Length only; the value itself is never echoed.
            throw new IllegalStateException(
                    "notify.webhook.secret-key must decode to exactly " + KEY_BYTES + " bytes (got " + raw.length + ")");
        }
        try {
            return new SecretKeySpec(raw, "AES");
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    public boolean available() {
        return key != null;
    }

    /** @throws NotifyException 503 when no encryption key is configured. */
    public String encrypt(UUID tenantId, String plaintext) {
        requireKey();
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(tenantId));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            String stored = Base64.getEncoder().encodeToString(out);
            if (stored.length() > MAX_STORED_CHARS) {
                throw new IllegalStateException("Encrypted webhook secret exceeds the stored column width");
            }
            return stored;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Webhook secret encryption failed: " + e.getClass().getName());
        }
    }

    /** @throws IllegalStateException when the stored value is malformed or fails authentication. */
    public String decrypt(UUID tenantId, String stored) {
        requireKey();
        byte[] all;
        try {
            all = Base64.getDecoder().decode(stored);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Stored webhook secret is not valid base64");
        }
        if (all.length <= IV_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("Stored webhook secret is too short to be authenticated");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(aad(tenantId));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Includes AEADBadTagException: tampered ciphertext, wrong key, or a foreign tenant's row.
            throw new IllegalStateException("Stored webhook secret failed authentication: " + e.getClass().getSimpleName());
        }
    }

    private static byte[] aad(UUID tenantId) {
        return tenantId.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private void requireKey() {
        if (key == null) {
            throw new NotifyException(ErrorCode.FEATURE_NOT_YET_AVAILABLE,
                    "Webhook signing secrets are unavailable: notify.webhook.secret-key (env NOTIFY_WEBHOOK_SECRET_KEY) is not configured");
        }
    }
}
