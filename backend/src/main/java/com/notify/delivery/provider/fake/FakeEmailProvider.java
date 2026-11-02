package com.notify.delivery.provider.fake;

import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.EmailProvider;
import com.notify.delivery.provider.EmailSendRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Deterministic development/test provider. Never contacts the network. Selected by default
 * ({@code notify.email.provider=fake}) and impossible under the {@code prod} profile
 * ({@link com.notify.delivery.provider.guard.ProductionProviderGuard}).
 *
 * <p>Outcome rules, first match wins:
 * <ol>
 *   <li>Reserved test domain {@value #TEST_DOMAIN}: a local part starting with {@code fail-perm} gives a permanent
 *       failure, {@code fail-retry} a retryable failure, {@code defer} a deferral. No other domain has deterministic
 *       hooks, so a real recipient address can never trigger a simulated failure.</li>
 *   <li>Otherwise optional seeded random failures ({@code perm-failure-rate}, then {@code temp-failure-rate}).</li>
 *   <li>Otherwise success with id {@code fake_<uuid>}.</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(prefix = "notify.email", name = "provider", havingValue = "fake", matchIfMissing = true)
public class FakeEmailProvider implements EmailProvider {

    public static final String NAME = "fake";
    public static final String TEST_DOMAIN = "@notify.local";

    private final FakeEmailProperties properties;
    private final RandomGenerator random;

    @Autowired
    public FakeEmailProvider(FakeEmailProperties properties) {
        this(properties, properties.seed() != null ? new Random(properties.seed()) : new Random());
    }

    public FakeEmailProvider(FakeEmailProperties properties, RandomGenerator random) {
        this.properties = properties;
        this.random = random;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DeliveryResult send(EmailSendRequest request) {
        simulateLatency();

        String to = request.to() == null ? "" : request.to().trim().toLowerCase(Locale.ROOT);
        if (to.endsWith(TEST_DOMAIN)) {
            String local = to.substring(0, to.length() - TEST_DOMAIN.length());
            if (local.startsWith("fail-perm")) {
                return new DeliveryResult.PermanentFailure("FAKE_PERMANENT_FAILURE", "Simulated permanent failure", 422, null);
            }
            if (local.startsWith("fail-retry")) {
                return new DeliveryResult.RetryableFailure("FAKE_TEMPORARY_FAILURE", "Simulated temporary failure", 503, null);
            }
            if (local.startsWith("defer")) {
                return new DeliveryResult.Deferred("FAKE_DEFERRED", Duration.ofSeconds(properties.deferSeconds()));
            }
        }

        if (properties.permFailureRate() > 0 && random.nextDouble() < properties.permFailureRate()) {
            return new DeliveryResult.PermanentFailure("FAKE_PERMANENT_FAILURE", "Simulated permanent failure", 422, null);
        }
        if (properties.tempFailureRate() > 0 && random.nextDouble() < properties.tempFailureRate()) {
            return new DeliveryResult.RetryableFailure("FAKE_TEMPORARY_FAILURE", "Simulated temporary failure", 503, null);
        }
        return new DeliveryResult.Success("fake_" + UUID.randomUUID());
    }

    private void simulateLatency() {
        long latency = properties.latencyMs();
        if (latency <= 0) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
