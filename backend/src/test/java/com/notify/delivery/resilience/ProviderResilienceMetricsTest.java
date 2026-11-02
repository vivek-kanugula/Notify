package com.notify.delivery.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resilience meters an operator actually reads (/ , ; repair of review finding F5).
 *
 * <p>The configuration registers a {@link MeterBinder}, which Spring Boot binds to the application's registry.
 * Nothing asserted its contents, so a renamed meter, a dropped tag or a gauge wired to the wrong object would have
 * been silent. This builds the <b>real</b> binder from {@link ProviderResilienceConfig} and reads what it
 * registers.
 *
 * <p>Cardinality is part of the contract here, not a footnote: webhook hosts are tenant-controlled and a meter
 * registry never evicts a series, so the registry is published as a <em>count</em> and no meter carries a host tag.
 */
class ProviderResilienceMetricsTest {

    private static final Clock CLOCK = Clock.fixed(java.time.Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);

    private static ResilienceProperties props() {
        return new ResilienceProperties(
                true, 50,
                new ResilienceProperties.Breaker(50, 5, 2, Duration.ofSeconds(30), 1),
                new ResilienceProperties.Bulkhead(3, 2),
                new ResilienceProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ResilienceProperties.HostRegistry(16, Duration.ofMinutes(31)),
                Duration.ofMinutes(5));
    }

    private record Wiring(SimpleMeterRegistry registry, CircuitBreaker resendBreaker,
                          HostBreakerRegistry hostBreakers, Bulkhead email, Bulkhead webhook) {
    }

    /** Builds the production configuration's own beans and binds its own MeterBinder to a fresh registry. */
    private static Wiring wire() {
        ProviderResilienceConfig config = new ProviderResilienceConfig();
        ResilienceProperties props = props();
        CircuitBreakerConfig breakerConfig = config.providerCircuitBreakerConfig(props);
        CircuitBreaker resendBreaker = config.resendCircuitBreaker(breakerConfig);
        HostBreakerRegistry hostBreakers = config.webhookHostBreakers(breakerConfig, props, CLOCK);
        Bulkhead email = config.emailBulkhead(props);
        Bulkhead webhook = config.webhookBulkhead(props);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        config.providerResilienceMetrics(resendBreaker, hostBreakers, email, webhook).bindTo(registry);
        return new Wiring(registry, resendBreaker, hostBreakers, email, webhook);
    }

    private static double gauge(SimpleMeterRegistry registry, String name, String... tags) {
        Gauge found = registry.find(name).tags(tags).gauge();
        assertThat(found).as("gauge %s %s", name, java.util.Arrays.toString(tags)).isNotNull();
        return found.value();
    }

    @Test
    @DisplayName("the binder registers exactly the four resilience meters, with their expected tags")
    void theBinderRegistersTheFourMeters() {
        Wiring wiring = wire();

        assertThat(gauge(wiring.registry(), "notify.resilience.breaker_state", "target", "resend")).isZero();
        assertThat(gauge(wiring.registry(), "notify.resilience.host_breakers")).isZero();
        assertThat(gauge(wiring.registry(), "notify.resilience.bulkhead_available", "channel", "email"))
                .isEqualTo(3);
        assertThat(gauge(wiring.registry(), "notify.resilience.bulkhead_available", "channel", "webhook"))
                .isEqualTo(2);

        assertThat(wiring.registry().getMeters())
                .as("nothing else is published, so the series count is fixed by construction")
                .hasSize(4);
    }

    @Test
    @DisplayName("breaker state is an ordered scale an alert can read: 0 closed, 1 half-open, 2 open")
    void breakerStateIsAnOrderedScale() {
        Wiring wiring = wire();

        assertThat(gauge(wiring.registry(), "notify.resilience.breaker_state", "target", "resend")).isZero();

        wiring.resendBreaker().transitionToOpenState();
        assertThat(gauge(wiring.registry(), "notify.resilience.breaker_state", "target", "resend")).isEqualTo(2);

        wiring.resendBreaker().transitionToHalfOpenState();
        assertThat(gauge(wiring.registry(), "notify.resilience.breaker_state", "target", "resend")).isEqualTo(1);

        wiring.resendBreaker().transitionToClosedState();
        assertThat(gauge(wiring.registry(), "notify.resilience.breaker_state", "target", "resend")).isZero();
    }

    @Test
    @DisplayName("the host registry is published as a bounded count, never as a series per host")
    void hostBreakersArePublishedAsACount() {
        Wiring wiring = wire();

        for (int i = 0; i < 5; i++) {
            wiring.hostBreakers().forHost("hooks" + i + ".example.com");
        }

        assertThat(gauge(wiring.registry(), "notify.resilience.host_breakers")).isEqualTo(5);
        assertThat(wiring.registry().getMeters())
                .as("five tenant-controlled hosts added no series: a registry never evicts one")
                .hasSize(4);
        assertThat(wiring.registry().getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey)
                .distinct())
                .as("the only tag keys are fixed strings")
                .containsExactlyInAnyOrder("target", "channel");
    }

    @Test
    @DisplayName("bulkhead availability tracks occupancy and returns when a permit is released")
    void bulkheadAvailabilityTracksOccupancy() {
        Wiring wiring = wire();

        assertThat(wiring.webhook().tryAcquirePermission()).isTrue();
        assertThat(gauge(wiring.registry(), "notify.resilience.bulkhead_available", "channel", "webhook"))
                .isEqualTo(1);
        assertThat(gauge(wiring.registry(), "notify.resilience.bulkhead_available", "channel", "email"))
                .as("the channels are independent")
                .isEqualTo(3);

        wiring.webhook().releasePermission();
        assertThat(gauge(wiring.registry(), "notify.resilience.bulkhead_available", "channel", "webhook"))
                .isEqualTo(2);
    }
}
