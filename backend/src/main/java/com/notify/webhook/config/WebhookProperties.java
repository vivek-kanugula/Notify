package com.notify.webhook.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Webhook delivery settings ({@code notify.webhook.*}, / ).
 *
 * <p>{@code secretKey} is the AES-256 key that protects every tenant signing secret at rest (). It has no
 * default: without it the webhook configuration API answers {@code 503 FEATURE_NOT_YET_AVAILABLE} and the webhook
 * worker refuses to start ({@link WebhookStartupGuard}). {@link #toString()} never prints it.
 *
 * <p>{@code demoAllowlist} is the only way a destination may use {@code http} or resolve to a loopback address, and
 * only for an endpoint flagged {@code is_demo} outside the {@code prod} profile ().
 *
 * <p>{@code totalTimeout} is the whole attempt's wall-clock budget — name resolution, pool lease, connection, TLS,
 * response headers, the bounded body read and cleanup together ({@code WebhookDeadline}). {@code connectTimeout} is a
 * ceiling <em>inside</em> that budget, not an addition to it. {@code storedSnippetBytes} is the UTF-8 byte budget of
 * the stored response snippet, truncation marker included.
 */
@ConfigurationProperties(prefix = "notify.webhook")
public record WebhookProperties(
        @DefaultValue("") String secretKey,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration totalTimeout,
        /** Ceiling for one name resolution, inside {@code totalTimeout} and never added to it (F7). */
        @DefaultValue("2s") Duration dnsTimeout,
        @DefaultValue("8192") int maxResponseBytes,
        @DefaultValue("4096") int storedSnippetBytes,
        @DefaultValue("Notify-Webhook/1") String userAgent,
        @DefaultValue("") List<String> demoAllowlist
) {

    public WebhookProperties {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("notify.webhook.connect-timeout must be positive");
        }
        if (totalTimeout == null || totalTimeout.compareTo(connectTimeout) < 0) {
            throw new IllegalArgumentException("notify.webhook.total-timeout must be at least connect-timeout");
        }
        if (dnsTimeout == null || dnsTimeout.isNegative() || dnsTimeout.isZero()) {
            throw new IllegalArgumentException("notify.webhook.dns-timeout must be positive");
        }
        if (dnsTimeout.compareTo(totalTimeout) > 0) {
            // It is a ceiling inside the attempt budget; a larger value could never be honoured.
            throw new IllegalArgumentException("notify.webhook.dns-timeout must not exceed total-timeout");
        }
        if (maxResponseBytes < 1024) {
            throw new IllegalArgumentException("notify.webhook.max-response-bytes must be at least 1024");
        }
        if (storedSnippetBytes < 64 || storedSnippetBytes > 4096) {
            // UTF-8 bytes of the final stored value, truncation marker included. The ceiling is the width of
            // delivery_attempts.response_snippet (VARCHAR(4096)): at most 4096 UTF-8 bytes can never be more than
            // 4096 characters, so a value inside this budget survives the write intact, marker and all. The floor
            // keeps room for the marker plus some text.
            //
            // A previous corrective pass narrowed this to 1024 because the shared result service truncated every
            // channel's snippet at 1024 characters. That was a defect in the persistence path, not a limit on the
            // approved webhook budget, and it was repaired there (per-channel limit) instead of here.
            throw new IllegalArgumentException("notify.webhook.stored-snippet-bytes must be in [64, 4096]");
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("notify.webhook.user-agent must not be blank");
        }
        demoAllowlist = demoAllowlist == null ? List.of() : demoAllowlist.stream()
                .filter(host -> host != null && !host.isBlank())
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .toList();
        secretKey = secretKey == null ? "" : secretKey.trim();
    }

    /** True when a signing-secret encryption key is configured; false blocks the webhook runtime paths. */
    public boolean secretKeyConfigured() {
        return !secretKey.isEmpty();
    }

    public boolean demoHostAllowed(String host) {
        return host != null && demoAllowlist.contains(host.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return "WebhookProperties[secretKey=" + (secretKeyConfigured() ? "<redacted>" : "<unset>")
                + ", connectTimeout=" + connectTimeout + ", totalTimeout=" + totalTimeout
                + ", dnsTimeout=" + dnsTimeout
                + ", maxResponseBytes=" + maxResponseBytes + ", storedSnippetBytes=" + storedSnippetBytes
                + ", userAgent=" + userAgent + ", demoAllowlist=" + demoAllowlist + "]";
    }
}
