package com.notify.webhook.config;

import com.notify.demo.config.DemoProperties;
import com.notify.demo.config.DemoWebhookProperties;
import com.notify.webhook.service.WebhookSecretCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guards that keep a weaker webhook configuration from ever running (, , , ).
 *
 * <p>These four refusals are the only thing standing between a production deployment and a plain-http destination, a
 * demo receiver as every tenant's default, a signing-secret cipher that cannot work, or failure-injection routes. The
 * original build shipped them untested, so a refactor could have removed any of them silently.
 */
class WebhookStartupGuardTest {

    private static final String VALID_KEY = "dGVzdC1vbmx5LXdlYmhvb2sta2V5LTMyLWJ5dGVzISE=";

    private static WebhookProperties webhook(String secretKey, List<String> demoAllowlist) {
        return new WebhookProperties(secretKey, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(2),
                8192, 4096, "Notify-Webhook/1", demoAllowlist);
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static void guard(WebhookProperties webhook, DemoProperties demo,
                              DemoWebhookProperties receiver, MockEnvironment environment) {
        new WebhookStartupGuard(webhook, demo, receiver, environment);
    }

    @Test
    @DisplayName("the webhook worker cannot start without a signing-secret encryption key")
    void workerWithoutAKeyIsRefused() {
        MockEnvironment environment = env();
        environment.setProperty("notify.worker.webhook.enabled", "true");

        assertThatThrownBy(() -> guard(webhook("", List.of()), new DemoProperties(""),
                new DemoWebhookProperties(false), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notify.webhook.secret-key")
                .hasMessageContaining("NOTIFY_WEBHOOK_SECRET_KEY");
    }

    @Test
    @DisplayName("the worker starts when the key is present")
    void workerWithAKeyIsAllowed() {
        MockEnvironment environment = env();
        environment.setProperty("notify.worker.webhook.enabled", "true");

        assertThatCode(() -> guard(webhook(VALID_KEY, List.of()), new DemoProperties(""),
                new DemoWebhookProperties(false), environment)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with the worker disabled a missing key is allowed: the API answers 503 instead (stub policy)")
    void noKeyIsAllowedWhileTheWorkerIsOff() {
        assertThatCode(() -> guard(webhook("", List.of()), new DemoProperties(""),
                new DemoWebhookProperties(false), env())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the prod profile refuses a demo allowlist, the only path to plain http or loopback")
    void prodRefusesTheDemoAllowlist() {
        assertThatThrownBy(() -> guard(webhook(VALID_KEY, List.of("localhost")), new DemoProperties(""),
                new DemoWebhookProperties(false), env("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notify.webhook.demo-allowlist");
    }

    @Test
    @DisplayName("the prod profile refuses a configured demo webhook URL")
    void prodRefusesTheDemoProvisioningUrl() {
        assertThatThrownBy(() -> guard(webhook(VALID_KEY, List.of()),
                new DemoProperties("http://localhost:9099/demo/webhook-receiver"),
                new DemoWebhookProperties(false), env("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notify.demo.webhook-url");
    }

    @Test
    @DisplayName("the prod profile refuses the receiver's failure-injection routes")
    void prodRefusesChaos() {
        assertThatThrownBy(() -> guard(webhook(VALID_KEY, List.of()), new DemoProperties(""),
                new DemoWebhookProperties(true), env("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notify.demo.receiver.chaos-enabled");
    }

    @Test
    @DisplayName("a clean prod configuration starts, and the same settings are allowed outside prod")
    void cleanProdStartsAndNonProdIsPermissive() {
        assertThatCode(() -> guard(webhook(VALID_KEY, List.of()), new DemoProperties(""),
                new DemoWebhookProperties(false), env("prod"))).doesNotThrowAnyException();

        assertThatCode(() -> guard(webhook(VALID_KEY, List.of("localhost")),
                new DemoProperties("http://localhost:9099/demo/webhook-receiver"),
                new DemoWebhookProperties(true), env("local"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a malformed key fails fast at startup and never echoes the value")
    void malformedKeysAreRefused() {
        assertThatThrownBy(() -> new WebhookSecretCipher(webhook("not-base64-$$$", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid base64");

        String shortKey = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new WebhookSecretCipher(webhook(shortKey, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageNotContaining(shortKey);
    }

    @Test
    @DisplayName("the properties never print the key, configured or not")
    void propertiesNeverPrintTheKey() {
        assertThat(webhook(VALID_KEY, List.of()).toString())
                .contains("<redacted>").doesNotContain(VALID_KEY);
        assertThat(webhook("", List.of()).toString()).contains("<unset>");
    }
}
