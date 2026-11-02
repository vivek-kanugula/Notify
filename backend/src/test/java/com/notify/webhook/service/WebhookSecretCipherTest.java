package com.notify.webhook.service;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.webhook.config.WebhookProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** secret generation shape, AES-256-GCM protection at rest, tenant binding, and the no-key stub path. */
class WebhookSecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static WebhookProperties properties(String secretKey) {
        return new WebhookProperties(secretKey, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(2),
                8192, 4096, "Notify-Webhook/1", List.of());
    }

    private final WebhookSecretCipher cipher = new WebhookSecretCipher(properties(KEY));
    private final WebhookSecretGenerator generator = new WebhookSecretGenerator();

    @Test
    @DisplayName("a generated secret is whsec_ + 43 base64url characters and is unique per call")
    void generatedSecretShape() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String secret = generator.generate();
            assertThat(secret).startsWith("whsec_").hasSize(49).matches("whsec_[A-Za-z0-9_-]{43}");
            assertThat(WebhookSecretGenerator.hasExpectedShape(secret)).isTrue();
            assertThat(seen.add(secret)).as("secrets are unique").isTrue();
        }
        assertThat(WebhookSecretGenerator.hasExpectedShape("whsec_short")).isFalse();
        assertThat(WebhookSecretGenerator.hasExpectedShape(null)).isFalse();
    }

    @Test
    @DisplayName("encrypt then decrypt returns the same secret, and the stored form reveals nothing")
    void roundTrip() {
        String secret = generator.generate();
        String stored = cipher.encrypt(TENANT, secret);

        assertThat(cipher.decrypt(TENANT, stored)).isEqualTo(secret);
        assertThat(stored).doesNotContain(secret).doesNotContain("whsec_");
        assertThat(stored.length()).as("fits webhook_endpoints.signing_secret_enc VARCHAR(512)").isLessThanOrEqualTo(512);
        // IV (12) + ciphertext (49) + tag (16) = 77 bytes
        assertThat(Base64.getDecoder().decode(stored)).hasSize(77);
    }

    @Test
    @DisplayName("every encryption uses a fresh IV, so the same secret never produces the same ciphertext")
    void freshIvPerEncryption() {
        String secret = generator.generate();
        Set<String> ciphertexts = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            assertThat(ciphertexts.add(cipher.encrypt(TENANT, secret))).isTrue();
        }
        assertThat(ciphertexts).allSatisfy(stored -> assertThat(cipher.decrypt(TENANT, stored)).isEqualTo(secret));
    }

    @Test
    @DisplayName("a ciphertext copied into another tenant's row fails authentication instead of decrypting")
    void tenantIdIsAuthenticated() {
        String stored = cipher.encrypt(TENANT, generator.generate());

        assertThatThrownBy(() -> cipher.decrypt(OTHER_TENANT, stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed authentication");
    }

    @Test
    @DisplayName("a tampered ciphertext, tag or IV fails authentication")
    void tamperIsDetected() {
        byte[] sealed = Base64.getDecoder().decode(cipher.encrypt(TENANT, generator.generate()));

        for (int index : new int[]{0, 11, 20, sealed.length - 1}) {
            byte[] copy = sealed.clone();
            copy[index] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(copy);
            assertThatThrownBy(() -> cipher.decrypt(TENANT, tampered))
                    .as("byte %d flipped", index)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("a malformed stored value is rejected without a stack trace from the JCE internals")
    void malformedStoredValue() {
        assertThatThrownBy(() -> cipher.decrypt(TENANT, "not base64 at all!"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not valid base64");
        assertThatThrownBy(() -> cipher.decrypt(TENANT, Base64.getEncoder().encodeToString(new byte[8])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too short");
    }

    @Test
    @DisplayName("without a configured key the cipher is unavailable and every call is 503 FEATURE_NOT_YET_AVAILABLE")
    void noKeyConfigured() {
        WebhookSecretCipher none = new WebhookSecretCipher(properties(""));
        assertThat(none.available()).isFalse();

        assertThatThrownBy(() -> none.encrypt(TENANT, "whsec_x"))
                .isInstanceOf(NotifyException.class)
                .extracting(e -> ((NotifyException) e).getErrorCode())
                .isEqualTo(ErrorCode.FEATURE_NOT_YET_AVAILABLE);
        assertThatThrownBy(() -> none.decrypt(TENANT, "x")).isInstanceOf(NotifyException.class);
    }

    @Test
    @DisplayName("a key of the wrong length or encoding fails fast at construction and never echoes the value")
    void rejectsBadKey() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
        assertThatThrownBy(() -> new WebhookSecretCipher(properties(shortKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> new WebhookSecretCipher(properties("not-base64-$$$")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("valid base64");
    }

    @Test
    @DisplayName("the properties record never prints the key")
    void propertiesRedactKey() {
        assertThat(properties(KEY).toString()).contains("<redacted>").doesNotContain(KEY);
        assertThat(properties("").toString()).contains("<unset>");
    }
}
