package com.notify.delivery.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounded per-host breaker registry (/ , decision ; , ).
 *
 * <p>Destinations are tenant-controlled, so the set of hosts is unbounded input: the registry must stay inside its
 * configured bound under churn, and - the subtler rule - must never evict a breaker that could still be open, since
 * an evicted breaker starts closed and would readmit traffic to a host already found to be failing.
 */
class HostBreakerRegistryTest {

    /** A clock the test moves by hand, so idle expiry is exercised without sleeping. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-15T12:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private static CircuitBreakerConfig config() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
    }

    @Test
    @DisplayName("two hosts are isolated: opening one leaves the other closed and deliverable")
    void hostsAreIsolated() {
        HostBreakerRegistry registry = new HostBreakerRegistry(
                config(), new ResilienceProperties.HostRegistry(16, Duration.ofMinutes(10)), new MovableClock());

        CircuitBreaker a = registry.forHost("a.example.com");
        CircuitBreaker b = registry.forHost("b.example.com");
        a.transitionToOpenState();

        assertThat(a.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.tryAcquirePermission()).as("the other host is unaffected").isTrue();
        assertThat(registry.forHost("a.example.com")).as("the same host keeps the same breaker").isSameAs(a);
    }

    @Test
    @DisplayName("the registry never exceeds max-hosts under churn, and an evicted host starts closed")
    void theRegistryStaysBounded() {
        int maxHosts = 16;
        HostBreakerRegistry registry = new HostBreakerRegistry(
                config(), new ResilienceProperties.HostRegistry(maxHosts, Duration.ofMinutes(10)), new MovableClock());

        for (int i = 0; i < maxHosts * 10; i++) {
            registry.forHost("host-" + i + ".example.com");
            assertThat(registry.size())
                    .as("never beyond the configured bound, at iteration %d", i)
                    .isLessThanOrEqualTo(maxHosts);
        }

        assertThat(registry.size()).isEqualTo(maxHosts);
        assertThat(registry.forHost("host-0.example.com").getState())
                .as("the first host was evicted long ago and comes back closed")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("eviction is least-recently-used, so a host still in use is kept")
    void evictionIsLeastRecentlyUsed() {
        HostBreakerRegistry registry = new HostBreakerRegistry(
                config(), new ResilienceProperties.HostRegistry(16, Duration.ofMinutes(10)), new MovableClock());
        CircuitBreaker kept = registry.forHost("kept.example.com");

        for (int i = 0; i < 15; i++) {
            registry.forHost("filler-" + i + ".example.com");
            registry.forHost("kept.example.com");            // keep touching it, so it is never the LRU entry
        }
        for (int i = 15; i < 40; i++) {
            registry.forHost("filler-" + i + ".example.com");
            registry.forHost("kept.example.com");
        }

        assertThat(registry.size()).isLessThanOrEqualTo(16);
        assertThat(registry.forHost("kept.example.com"))
                .as("the host in continuous use kept its breaker, and therefore its state")
                .isSameAs(kept);
    }

    @Test
    @DisplayName("an open breaker is never evicted to make room, because an evicted one starts closed")
    void anOpenBreakerIsNotEvicted() {
        int maxHosts = 16;
        HostBreakerRegistry registry = new HostBreakerRegistry(
                config(), new ResilienceProperties.HostRegistry(maxHosts, Duration.ofMinutes(10)), new MovableClock());

        CircuitBreaker failing = registry.forHost("failing.example.com");
        failing.transitionToOpenState();
        for (int i = 0; i < maxHosts * 3; i++) {
            registry.forHost("other-" + i + ".example.com");
        }

        assertThat(registry.hosts()).contains("failing.example.com");
        assertThat(registry.forHost("failing.example.com"))
                .as("still the same breaker, so the host stays deferred rather than being quietly readmitted")
                .isSameAs(failing);
        assertThat(registry.forHost("failing.example.com").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(registry.size()).isLessThanOrEqualTo(maxHosts);
    }

    @Test
    @DisplayName("an idle closed host is expired, and an idle OPEN one is not")
    void idleExpiryKeepsOpenBreakers() {
        MovableClock clock = new MovableClock();
        HostBreakerRegistry registry = new HostBreakerRegistry(
                config(), new ResilienceProperties.HostRegistry(16, Duration.ofMinutes(10)), clock);

        CircuitBreaker idleClosed = registry.forHost("quiet.example.com");
        CircuitBreaker idleOpen = registry.forHost("broken.example.com");
        idleOpen.transitionToOpenState();
        assertThat(idleClosed.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        clock.advance(Duration.ofMinutes(11));
        registry.forHost("something-else.example.com");       // any access runs the expiry sweep

        assertThat(registry.hosts()).doesNotContain("quiet.example.com");
        assertThat(registry.hosts())
                .as("an open breaker outlives idle expiry; otherwise it would come back closed and readmit traffic")
                .contains("broken.example.com");
    }

    /**
     * The registry under concurrent lookups (; repair of review finding F11).
     *
     * <p>Every thread is released from one latch, so the lookups genuinely overlap, and the assertions are about
     * invariants rather than about who won: the size never exceeds the bound, concurrent lookups of a <b>retained</b>
     * host hand back the <em>same</em> breaker instance (otherwise two threads would be recording failures into
     * different windows for one host), and a host whose breaker is open is still there afterwards.
     *
     * <p>No assertion depends on timing or on thread order. The registry is fully synchronised, so this is a
     * regression guard for anyone tempted to make it lock-free.
     */
    @Test
    @DisplayName("concurrent lookups stay inside the bound and share one breaker per retained host")
    void concurrentLookupsStayBoundedAndShared() throws Exception {
        int maxHosts = 16;
        MovableClock clock = new MovableClock();
        HostBreakerRegistry registry = new HostBreakerRegistry(
                config(), new ResilienceProperties.HostRegistry(maxHosts, Duration.ofMinutes(30)), clock);

        // One host that must be retained throughout: its breaker is open, so it may never be evicted.
        CircuitBreaker protectedBreaker = registry.forHost("protected.example.com");
        protectedBreaker.transitionToOpenState();

        // And one ordinary host every thread looks up repeatedly, to check they all get the same instance.
        String shared = "shared.example.com";
        int threads = 8;
        int roundsPerThread = 40;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<java.util.Set<CircuitBreaker>>> futures = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger sizeHighWater = new java.util.concurrent.atomic.AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                futures.add(pool.submit(() -> {
                    java.util.Set<CircuitBreaker> sharedSeen =
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    start.await();
                    for (int round = 0; round < roundsPerThread; round++) {
                        sharedSeen.add(registry.forHost(shared));
                        registry.forHost("protected.example.com");
                        // Distinct, tenant-controlled churn: far more hosts than the bound allows.
                        registry.forHost("churn-" + threadIndex + "-" + round + ".example.com");
                        sizeHighWater.accumulateAndGet(registry.size(), Math::max);
                    }
                    return sharedSeen;
                }));
            }
            start.countDown();

            java.util.Set<CircuitBreaker> allShared =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (java.util.concurrent.Future<java.util.Set<CircuitBreaker>> future : futures) {
                allShared.addAll(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }

            assertThat(sizeHighWater.get())
                    .as("%s threads churning distinct hosts never pushed the registry past its bound", threads)
                    .isLessThanOrEqualTo(maxHosts);
            assertThat(registry.size()).isLessThanOrEqualTo(maxHosts);
            assertThat(allShared)
                    .as("one breaker instance per retained host, or two threads would record into different windows")
                    .hasSize(1);
            assertThat(registry.forHost("protected.example.com"))
                    .as("the open breaker is the same object, never evicted and re-created closed")
                    .isSameAs(protectedBreaker);
            assertThat(protectedBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        } finally {
            pool.shutdownNow();
        }
    }
}
