package com.notify.delivery.worker.control;

import com.notify.delivery.worker.config.EmailWorkerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** K6 (unit half): escalating timed pause, escalation, progress reset, connectivity poll, async stop, rebalance. */
class WorkerContainerControllerTest {

    private static final TopicPartition P0 = new TopicPartition("notify.dispatch.email.v1", 0);
    private static final TopicPartition P1 = new TopicPartition("notify.dispatch.email.v1", 1);

    private static EmailWorkerProperties props(Duration initial, Duration max, int escalateAfter, Duration poll, int successes) {
        return new EmailWorkerProperties(true, "t", "g", true, Duration.ofMillis(1), 3, initial, max, escalateAfter,
                poll, successes, Duration.ofSeconds(1), 1000, Duration.ofSeconds(60));
    }

    private static final class FakeContainer implements WorkerContainerController.ContainerAccess {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile CountDownLatch stopBlocker;
        final AtomicInteger stops = new AtomicInteger();
        volatile String stopThread;

        @Override
        public void pause(TopicPartition partition) {
            calls.add("pause:" + partition.partition());
        }

        @Override
        public void resume(TopicPartition partition) {
            calls.add("resume:" + partition.partition());
        }

        @Override
        public void stop(Runnable onStopped) {
            stops.incrementAndGet();
            stopThread = Thread.currentThread().getName();
            if (stopBlocker != null) {
                try {
                    stopBlocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            onStopped.run();
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public Collection<TopicPartition> assignedPartitions() {
            return List.of(P0, P1);
        }

        @Override
        public double maxLag() {
            return Double.NaN;
        }

        long count(String call) {
            return calls.stream().filter(call::equals).count();
        }
    }

    private FakeContainer container;
    private SimpleMeterRegistry meters;
    private ScheduledExecutorService timers;
    private ScheduledExecutorService stopper;

    @BeforeEach
    void setUp() {
        container = new FakeContainer();
        meters = new SimpleMeterRegistry();
        timers = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "test-timers"));
        stopper = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "test-stopper"));
    }

    @AfterEach
    void tearDown() {
        timers.shutdownNow();
        stopper.shutdownNow();
    }

    private WorkerContainerController controller(EmailWorkerProperties props, java.util.function.BooleanSupplier probe) {
        return new WorkerContainerController(container, props, probe, meters, Clock.systemUTC(), timers, stopper, false);
    }

    @Test
    @DisplayName("K6 the backoff delay doubles from the initial value and is capped (30 s, 60 s, 120 s, 240 s, 300 s, 300 s)")
    void delayProgression() {
        WorkerContainerController c = controller(props(Duration.ofSeconds(30), Duration.ofSeconds(300), 3, Duration.ofSeconds(5), 2), () -> true);
        assertThat(List.of(1, 2, 3, 4, 5, 6, 50).stream().map(n -> c.backoffDelay(n).toSeconds()).toList())
                .containsExactly(30L, 60L, 120L, 240L, 300L, 300L, 300L);
    }

    @Test
    @DisplayName("K6 a timed pause pauses the partition, resumes it after the delay, and doubles the delay on consecutive pauses")
    void timedPauseResumesAfterDelay() {
        WorkerContainerController c = controller(props(Duration.ofMillis(60), Duration.ofMillis(240), 5, Duration.ofSeconds(5), 2), () -> true);

        c.pauseWithBackoff(P0);
        assertThat(container.calls).containsExactly("pause:0");
        assertThat(c.pausedPartitions()).containsExactly(P0);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(container.count("resume:0")).isEqualTo(1));
        assertThat(c.pausedPartitions()).isEmpty();

        long first = System.nanoTime();
        c.pauseWithBackoff(P0);   // consecutive 2 -> 120 ms
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(container.count("resume:0")).isEqualTo(2));
        assertThat(Duration.ofNanos(System.nanoTime() - first).toMillis()).isGreaterThanOrEqualTo(110);
        assertThat(meters.counter("notify.worker.pauses", "channel", "email", "reason", "backoff").count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("K6 the escalation threshold logs and counts once and the snapshot reports the partition; offsets stay retained")
    void escalation() {
        WorkerContainerController c = controller(props(Duration.ofMillis(10), Duration.ofMillis(20), 3, Duration.ofSeconds(5), 2), () -> true);

        c.pauseWithBackoff(P0);
        c.pauseWithBackoff(P0);
        assertThat(c.snapshot().escalatedPartitions()).isEmpty();
        assertThat(meters.find("notify.worker.escalations").counter()).isNull();

        c.pauseWithBackoff(P0);   // third consecutive pause
        assertThat(c.snapshot().escalatedPartitions()).containsExactly(P0.toString());
        assertThat(meters.counter("notify.worker.escalations", "channel", "email").count()).isEqualTo(1.0);

        c.pauseWithBackoff(P0);   // fourth: still one escalation event
        assertThat(meters.counter("notify.worker.escalations", "channel", "email").count()).isEqualTo(1.0);
        assertThat(c.snapshot().stopped()).as("exhausted contention never stops the container").isFalse();
        assertThat(container.stops).hasValue(0);
    }

    @Test
    @DisplayName("K6 a successful acknowledgment resets the consecutive-pause counter and the escalation")
    void progressResets() {
        WorkerContainerController c = controller(props(Duration.ofMillis(10), Duration.ofMillis(20), 2, Duration.ofSeconds(5), 2), () -> true);
        c.pauseWithBackoff(P0);
        c.pauseWithBackoff(P0);
        assertThat(c.snapshot().escalatedPartitions()).isNotEmpty();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(c.pausedPartitions()).isEmpty());

        c.onProgress(P0);
        assertThat(c.snapshot().escalatedPartitions()).isEmpty();

        c.pauseWithBackoff(P0);   // counts as the first consecutive pause again
        assertThat(c.snapshot().escalatedPartitions()).isEmpty();
    }

    @Test
    @DisplayName("K6 a connectivity pause resumes only after N consecutive successful probes; a failed probe restarts the count")
    void connectivityPauseResumesAfterHealthy() {
        AtomicInteger probes = new AtomicInteger();
        // fail, ok, fail, ok, ok -> resume only at the fifth probe
        boolean[] script = {false, true, false, true, true};
        WorkerContainerController c = controller(
                props(Duration.ofSeconds(30), Duration.ofSeconds(300), 3, Duration.ofMillis(20), 2),
                () -> script[Math.min(probes.getAndIncrement(), script.length - 1)]);

        c.pauseUntilHealthy(P0);
        c.pauseUntilHealthy(P1);
        assertThat(container.calls).containsExactly("pause:0", "pause:1");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(container.count("resume:0")).isEqualTo(1);
            assertThat(container.count("resume:1")).isEqualTo(1);
        });
        assertThat(probes.get()).as("resumed no earlier than the fifth probe").isGreaterThanOrEqualTo(5);
        assertThat(c.pausedPartitions()).isEmpty();
        assertThat(meters.counter("notify.worker.pauses", "channel", "email", "reason", "connectivity").count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("K7 stopAsync never blocks the caller, runs the stop on its own thread, and is idempotent")
    void stopIsAsynchronousAndIdempotent() {
        CountDownLatch release = new CountDownLatch(1);
        container.stopBlocker = release;
        WorkerContainerController c = controller(props(Duration.ofSeconds(30), Duration.ofSeconds(300), 3, Duration.ofSeconds(5), 2), () -> true);

        long begin = System.nanoTime();
        c.stopAsync("NON_TRANSIENT: schema");
        c.stopAsync("again");
        assertThat(Duration.ofNanos(System.nanoTime() - begin).toMillis()).as("caller is not blocked by the stop").isLessThan(500);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(container.stops).hasValue(1));
        assertThat(container.stopThread).isEqualTo("test-stopper");
        assertThat(c.snapshot().stopped()).isTrue();
        assertThat(c.snapshot().stopReason()).isEqualTo("NON_TRANSIENT: schema");
        release.countDown();
        assertThat(container.stops).hasValue(1);
    }

    @Test
    @DisplayName("K6 revoked partitions lose their timers and pause state; a late timer cannot resume a partition we no longer own")
    void revokeCancelsTimers() {
        WorkerContainerController c = controller(props(Duration.ofMillis(80), Duration.ofMillis(160), 5, Duration.ofSeconds(5), 2), () -> true);
        c.pauseWithBackoff(P0);
        c.pauseWithBackoff(P1);

        c.onPartitionsRevoked(List.of(P0));
        assertThat(c.pausedPartitions()).containsExactly(P1);
        assertThat(container.count("resume:0")).as("revocation clears the stale pause request immediately").isEqualTo(1);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(container.count("resume:1")).isEqualTo(1));
        assertThat(container.count("resume:0")).as("P0's cancelled timer did not fire a second resume").isEqualTo(1);
    }

    @Test
    @DisplayName("K6 on assignment, partitions this controller did not pause get any stale pause request cleared")
    void assignmentClearsStalePauseRequests() {
        WorkerContainerController c = controller(props(Duration.ofSeconds(30), Duration.ofSeconds(300), 3, Duration.ofSeconds(5), 2), () -> true);
        c.pauseWithBackoff(P1);   // owned and legitimately paused

        c.onPartitionsAssigned(List.of(P0, P1));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(container.count("resume:0")).isEqualTo(1));
        assertThat(container.count("resume:1")).as("a partition paused by us is left alone").isZero();
    }

    @Test
    @DisplayName("K6 snapshot reports running state, assignment and pauses for the health indicator")
    void snapshotContents() {
        WorkerContainerController c = controller(props(Duration.ofSeconds(30), Duration.ofSeconds(300), 3, Duration.ofSeconds(5), 2), () -> true);
        c.pauseWithBackoff(P0);
        WorkerContainerController.Snapshot s = c.snapshot();
        assertThat(s.containerRunning()).isTrue();
        assertThat(s.assignedPartitions()).isEqualTo(2);
        assertThat(s.pausedPartitions()).containsExactly(P0.toString());
        assertThat(s.stopped()).isFalse();
        assertThat(s.databaseHealthy()).isTrue();
        assertThat(s.maxLag()).isNaN();
    }
}
