package com.notify.delivery.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider-call guard, against the <b>real</b> Resilience4j primitives (/ , ).
 *
 * <p>No primitive is stubbed: these are the same {@code CircuitBreaker}, {@code Bulkhead} and {@code RateLimiter}
 * classes the application wires, from the resolved 2.3.0 jars, so the test exercises their actual semantics -
 * permission acquisition, half-open admission and permit release - rather than a double's imitation of them.
 *
 * <p>The properties are scaled down only in the dimensions that make a test fast (window sizes, wait durations).
 * The relationships the production configuration relies on are asserted separately in
 * {@link ResiliencePropertiesTest}.
 */
class ProviderCallGuardTest {

    private static final String LABEL = "test-provider";

    private static ResilienceProperties enabled(Duration waitInOpenState) {
        return new ResilienceProperties(
                true, 50,
                new ResilienceProperties.Breaker(50, 5, 2, waitInOpenState, 1),
                new ResilienceProperties.Bulkhead(1, 1),
                new ResilienceProperties.RateLimit(2, Duration.ofSeconds(60)),
                new ResilienceProperties.HostRegistry(16, Duration.ofMinutes(10)),
                Duration.ofMinutes(5));
    }

    private static ProviderCallGuard guard(ResilienceProperties props) {
        return new ProviderCallGuard(props, new SimpleMeterRegistry());
    }

    private static CircuitBreaker breaker(ResilienceProperties props) {
        ResilienceProperties.Breaker config = props.breaker();
        return CircuitBreaker.of(LABEL, CircuitBreakerConfig.custom()
                .failureRateThreshold(config.failureRateThresholdPercent())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(config.slidingWindowSize())
                .minimumNumberOfCalls(config.minimumCalls())
                .waitDurationInOpenState(config.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(config.halfOpenPermittedCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
    }

    private static Bulkhead bulkhead(int maxConcurrent) {
        return Bulkhead.of(LABEL, BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    private static RateLimiter limiter(int permits, Duration period) {
        return RateLimiter.of(LABEL, RateLimiterConfig.custom()
                .limitForPeriod(permits)
                .limitRefreshPeriod(period)
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    @Test
    @DisplayName("a disabled guard is transparent: the call runs and nothing is consulted")
    void disabledGuardIsTransparent() {
        ResilienceProperties off = new ResilienceProperties(
                false, 50, new ResilienceProperties.Breaker(50, 5, 2, Duration.ofSeconds(1), 1),
                new ResilienceProperties.Bulkhead(1, 1),
                new ResilienceProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ResilienceProperties.HostRegistry(16, Duration.ofMinutes(10)), Duration.ofMinutes(5));
        AtomicInteger calls = new AtomicInteger();
        // A breaker forced open and a bulkhead with no permits: a disabled guard must consult neither.
        CircuitBreaker open = breaker(enabled(Duration.ofMinutes(5)));
        open.transitionToOpenState();
        Bulkhead full = bulkhead(1);
        assertThat(full.tryAcquirePermission()).isTrue();

        // A limiter with its single permit already spent, so a consulted limiter would refuse.
        RateLimiter exhausted = limiter(1, Duration.ofMinutes(10));
        assertThat(exhausted.acquirePermission()).isTrue();

        String result = guard(off).execute(LABEL, open, full, exhausted,
                r -> false, () -> {
                    calls.incrementAndGet();
                    return "sent";
                });

        assertThat(result).isEqualTo("sent");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an open breaker defers without invoking the provider")
    void anOpenBreakerDefersWithoutCalling() {
        ResilienceProperties props = enabled(Duration.ofMinutes(5));
        CircuitBreaker breaker = breaker(props);
        breaker.transitionToOpenState();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard(props).execute(LABEL, breaker, null, null, r -> false, () -> {
            calls.incrementAndGet();
            return "sent";
        }))
                .isInstanceOf(ProviderDeferredException.class)
                .extracting(e -> ((ProviderDeferredException) e).code())
                .isEqualTo(ProviderCallGuard.CIRCUIT_OPEN);

        assertThat(calls.get()).as("the provider was never contacted, which is what makes the deferral free").isZero();
    }

    @Test
    @DisplayName("the breaker opens on the failure rate the adapter's own classification reports")
    void theBreakerOpensOnClassifiedFailures() {
        ResilienceProperties props = enabled(Duration.ofMinutes(5));
        CircuitBreaker breaker = breaker(props);
        ProviderCallGuard guard = guard(props);

        // minimumCalls is 2 and the threshold 50%: two failures must open it.
        for (int i = 0; i < 2; i++) {
            String outcome = guard.execute(LABEL, breaker, null, null, "failed"::equals, () -> "failed");
            assertThat(outcome).isEqualTo("failed");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> guard.execute(LABEL, breaker, null, null, r -> false, () -> "sent"))
                .isInstanceOf(ProviderDeferredException.class);
    }

    @Test
    @DisplayName("a result the adapter classified as permanent never opens the breaker")
    void aPermanentOutcomeDoesNotOpenTheBreaker() {
        ResilienceProperties props = enabled(Duration.ofMinutes(5));
        CircuitBreaker breaker = breaker(props);
        ProviderCallGuard guard = guard(props);

        for (int i = 0; i < 10; i++) {
            guard.execute(LABEL, breaker, null, null, "transient"::equals, () -> "permanent-4xx");
        }

        assertThat(breaker.getState())
                .as("a tenant's own misconfigured receiver must not defer everyone else's deliveries")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("half-open admits the configured trial call and closes on success")
    void halfOpenRecoversOnSuccess() throws Exception {
        ResilienceProperties props = enabled(Duration.ofMillis(200));
        CircuitBreaker breaker = breaker(props);
        ProviderCallGuard guard = guard(props);
        breaker.transitionToOpenState();

        // automaticTransitionFromOpenToHalfOpenEnabled means no call is needed to leave the open state.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (breaker.getState() != CircuitBreaker.State.HALF_OPEN && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        String result = guard.execute(LABEL, breaker, null, null, r -> false, () -> "sent");

        assertThat(result).isEqualTo("sent");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a full bulkhead defers without invoking the provider, and releases the breaker permission")
    void aFullBulkheadDefersAndReleasesTheBreakerPermission() {
        ResilienceProperties props = enabled(Duration.ofMinutes(5));
        CircuitBreaker breaker = breaker(props);
        Bulkhead full = bulkhead(1);
        assertThat(full.tryAcquirePermission()).as("the only slot is taken").isTrue();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard(props).execute(LABEL, breaker, full, null, r -> false, () -> {
            calls.incrementAndGet();
            return "sent";
        }))
                .isInstanceOf(ProviderDeferredException.class)
                .extracting(e -> ((ProviderDeferredException) e).code())
                .isEqualTo(ProviderCallGuard.BULKHEAD_FULL);

        assertThat(calls.get()).isZero();
        // The breaker permission must not be spent by a call that never happened, or a stream of bulkhead refusals
        // would eventually look like provider traffic.
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    @DisplayName("the bulkhead permit is released on success, on failure and on a thrown exception")
    void theBulkheadPermitIsAlwaysReleased() {
        ResilienceProperties props = enabled(Duration.ofMinutes(5));
        ProviderCallGuard guard = guard(props);
        Bulkhead bulkhead = bulkhead(1);

        guard.execute(LABEL, null, bulkhead, null, r -> false, () -> "sent");
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).as("released after success").isEqualTo(1);

        guard.execute(LABEL, null, bulkhead, null, r -> true, () -> "failed");
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).as("released after a failure").isEqualTo(1);

        assertThatThrownBy(() -> guard.execute(LABEL, null, bulkhead, null, r -> false, () -> {
            throw new IllegalStateException("provider blew up");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls())
                .as("released even when the call threw").isEqualTo(1);
    }

    @Test
    @DisplayName("an exceeded rate limit defers before the call and does not consume a breaker permission")
    void anExceededRateLimitDefers() {
        ResilienceProperties props = enabled(Duration.ofMinutes(5));
        CircuitBreaker breaker = breaker(props);
        RateLimiter limiter = limiter(1, Duration.ofSeconds(60));
        ProviderCallGuard guard = guard(props);
        AtomicInteger calls = new AtomicInteger();

        assertThat(guard.execute(LABEL, breaker, null, limiter, r -> false, () -> {
            calls.incrementAndGet();
            return "sent";
        })).isEqualTo("sent");

        assertThatThrownBy(() -> guard.execute(LABEL, breaker, null, limiter, r -> false, () -> {
            calls.incrementAndGet();
            return "sent";
        }))
                .isInstanceOf(ProviderDeferredException.class)
                .extracting(e -> ((ProviderDeferredException) e).code())
                .isEqualTo(ProviderCallGuard.RATE_LIMITED);

        assertThat(calls.get()).as("the second call never reached the provider").isEqualTo(1);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls())
                .as("only the call that actually happened counts toward the breaker").isEqualTo(1);
    }

    @Test
    @DisplayName("two channels' bulkheads are independent: saturating one leaves the other free")
    void channelBulkheadsAreIndependent() throws Exception {
        ResilienceProperties props = enabled(Duration.ofMinutes(5));
        ProviderCallGuard guard = guard(props);
        Bulkhead email = bulkhead(1);
        Bulkhead webhook = bulkhead(1);

        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService occupier = Executors.newSingleThreadExecutor();
        try {
            Future<?> busy = occupier.submit(() -> guard.execute("email", null, email, null, r -> false, () -> {
                inFlight.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "sent";
            }));
            assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

            // EMAIL is saturated.
            assertThatThrownBy(() -> guard.execute("email", null, email, null, r -> false, () -> "sent"))
                    .isInstanceOf(ProviderDeferredException.class);
            // WEBHOOK is untouched by that.
            assertThat(guard.execute("webhook", null, webhook, null, r -> false, () -> "sent")).isEqualTo("sent");

            release.countDown();
            busy.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            occupier.shutdownNow();
        }
        assertThat(email.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("no second retry layer: one guarded attempt is exactly one provider invocation")
    void oneAttemptIsOneInvocation() {
        ResilienceProperties props = enabled(Duration.ofMinutes(5));
        ProviderCallGuard guard = guard(props);
        AtomicInteger calls = new AtomicInteger();

        guard.execute(LABEL, breaker(props), bulkhead(1), limiter(10, Duration.ofSeconds(1)),
                r -> true,                                  // even classified as a failure
                () -> {
                    calls.incrementAndGet();
                    return "failed";
                });

        assertThat(calls.get())
                .as("retries stay durable in next_retry_at plus a Quartz intent; nothing here retries")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a tenant-controlled host cannot grow metric cardinality")
    void deferralMetricCardinalityIsBounded() {
        ResilienceProperties props = enabled(Duration.ofMinutes(5));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProviderCallGuard guard = new ProviderCallGuard(props, meters);

        // Many distinct hosts, which is exactly what tenant-owned destinations produce.
        for (int i = 0; i < 50; i++) {
            Bulkhead full = bulkhead(1);
            assertThat(full.tryAcquirePermission()).isTrue();
            String host = "hooks" + i + ".example.com";
            assertThatThrownBy(() -> guard.execute("webhook:" + host, null, full, null, r -> false, () -> "sent"))
                    .isInstanceOf(ProviderDeferredException.class);
        }

        List<Meter> deferrals = meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("notify.resilience.deferred"))
                .toList();
        assertThat(deferrals)
                .as("one series per provider integration, not one per destination host: a meter registry never "
                        + "evicts, so a host tag would grow without limit")
                .hasSize(1);
        assertThat(deferrals.getFirst().getId().getTag("target")).isEqualTo("webhook");
        assertThat(((Counter) deferrals.getFirst()).count())
                .as("every refusal is still counted")
                .isEqualTo(50.0);
    }
}
