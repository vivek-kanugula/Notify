package com.notify.webhook.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F7: the caller's DNS wait is bounded, and a stalled resolver cannot grow resources without limit.
 *
 * <p>The stalls here are deterministic — a latch, not a sleep — so the assertions are about the bound itself rather
 * than about timing luck. The lookup is injected, because the point is the pool's behaviour, not the platform
 * resolver's.
 */
class BoundedDnsResolverTest {

    private BoundedDnsResolver resolver;
    private final List<CountDownLatch> releases = new ArrayList<>();
    private ExecutorService callers;

    @AfterEach
    void tearDown() {
        releases.forEach(CountDownLatch::countDown);
        if (resolver != null) {
            resolver.destroy();
        }
        if (callers != null) {
            callers.shutdownNow();
        }
    }

    @Test
    @DisplayName("a lookup that answers is returned as-is")
    void successfulLookup() throws Exception {
        resolver = new BoundedDnsResolver(2, 2);

        InetAddress[] answers = resolver.resolve("localhost", 2_000);

        assertThat(answers).isNotEmpty();
    }

    @Test
    @DisplayName("an unknown host is an UnknownHostException, not a wait")
    void unknownHost() {
        resolver = new BoundedDnsResolver(2, 2);

        assertThatThrownBy(() -> resolver.resolve("no-such-host-" + java.util.UUID.randomUUID() + ".invalid", 3_000))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    @DisplayName("the caller stops waiting at its timeout even though the lookup is still blocked")
    void callerWaitIsBounded() {
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        resolver = stallingPool(1, 1, release);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> resolver.resolve("stalled.example.com", 200))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("exceeded 200 ms");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        // Generous upper bound for scheduling noise, far below the unbounded wait this replaces.
        assertThat(elapsedMs).isLessThan(2_000);
    }

    @Test
    @DisplayName("repeated stalled lookups stay inside the pool: concurrency and queue are both capped, and the rest are refused")
    void repeatedStallsAreBounded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        resolver = stallingPool(2, 2, release);
        callers = Executors.newFixedThreadPool(12);

        // Twelve callers, a pool of 2 + 2: four lookups can exist, the other eight must be refused immediately.
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            results.add(callers.submit(() -> {
                try {
                    resolver.resolve("stalled.example.com", 400);
                    return "resolved";
                } catch (UnknownHostException e) {
                    return e.getMessage().contains("saturated") ? "saturated" : "timeout";
                }
            }));
        }

        int saturated = 0;
        int timedOut = 0;
        for (Future<String> result : results) {
            String outcome = result.get(30, TimeUnit.SECONDS);
            if ("saturated".equals(outcome)) {
                saturated++;
            } else if ("timeout".equals(outcome)) {
                timedOut++;
            }
        }

        assertThat(saturated).as("beyond the bound, work is refused rather than queued").isPositive();
        assertThat(timedOut).as("the lookups that did get a slot time out for their callers").isPositive();
        assertThat(saturated + timedOut).isEqualTo(12);
        assertThat(resolver.activeLookups())
                .as("in-flight plus queued never exceeds the configured bound")
                .isLessThanOrEqualTo(resolver.maxConcurrent() + resolver.queueCapacity());
    }

    @Test
    @DisplayName("saturation is reported as a resolution failure the delivery path can retry, not as a hang")
    void saturationIsExplicit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        resolver = stallingPool(1, 1, release);
        callers = Executors.newFixedThreadPool(3);

        // Fill the one thread and the one queue slot, then prove the next caller is refused at once.
        callers.submit(() -> resolver.resolve("stalled.example.com", 5_000));
        callers.submit(() -> resolver.resolve("stalled.example.com", 5_000));
        Thread.sleep(150);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> resolver.resolve("stalled.example.com", 5_000))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("saturated");
        assertThat((System.nanoTime() - startedAt) / 1_000_000)
                .as("refusal is immediate, not after the timeout").isLessThan(1_000);
    }

    @Test
    @DisplayName("a zero or negative timeout is treated as the smallest real wait, never as no timeout")
    void timeoutFloor() {
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        resolver = stallingPool(1, 1, release);

        assertThatThrownBy(() -> resolver.resolve("stalled.example.com", 0))
                .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> resolver.resolve("stalled.example.com", -5))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    @DisplayName("the pool rejects an impossible configuration instead of silently running unbounded")
    void configurationIsValidated() {
        assertThatThrownBy(() -> new BoundedDnsResolver(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedDnsResolver(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** A pool whose lookups block on {@code release} until released; nothing here touches real DNS. */
    private static BoundedDnsResolver stallingPool(int maxConcurrent, int queueCapacity, CountDownLatch release) {
        return new BoundedDnsResolver(maxConcurrent, queueCapacity) {
            @Override
            protected InetAddress[] lookup(String host) throws UnknownHostException {
                try {
                    // Deterministic: the lookup is blocked until the test releases it, exactly like a black-holed
                    // resolver, with no reliance on sleep timing.
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UnknownHostException("interrupted");
                }
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            }
        };
    }
}